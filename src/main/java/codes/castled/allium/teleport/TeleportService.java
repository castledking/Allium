package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagePolicy;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Player teleport pipeline. On Folia, each mover owns their operation and destination reads run by region. */
public final class TeleportService implements Listener {
    private static final Map<PluginStart,TeleportService> OWNERS=new WeakHashMap<>();
    public static final String FAULT_WRITE_FAIL="teleport.write-fail",FAULT_LOAD_FAIL="teleport.load-fail";
    public static final String WORLD_PERMISSION_PREFIX="allium.worlds.";
    public static final UUID SERVER=new UUID(0,0);
    private final PluginStart plugin;
    private final TeleportRepository repository;
    private final ThreadPoolExecutor io;
    private final DestinationSafety safety;
    private final EndGatewayReturn gatewayReturn;
    private final java.util.function.Consumer<Player> regionalArrival;
    private final RequestTracker requests=new RequestTracker();
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    private final Map<UUID,Operation> operations=new ConcurrentHashMap<>();
    /** Per-player cooldowns (milliseconds; negative keeps teleport.cooldowns) for a command, e.g. random teleport tiers. */
    private final Map<String,java.util.function.ToLongFunction<Player>> cooldownProviders=new ConcurrentHashMap<>();
    private final Map<UUID,HistoryWrite> pendingWrites=new ConcurrentHashMap<>();
    private final Set<HistoryWrite> queuedWrites=ConcurrentHashMap.newKeySet();
    private static final class OfflineLookup {
        final Session session;volatile UUID target;String name;volatile boolean loading=true,valid=true;
        OfflineLookup(Session session){this.session=session;}
    }
    private final Map<UUID,OfflineLookup> offlineLookups=new ConcurrentHashMap<>();
    private final Set<UUID> committing=ConcurrentHashMap.newKeySet();
    private volatile TeleportSettings settings;
    private final Map<UUID,SelectorConfirmation> selectorConfirmations=new ConcurrentHashMap<>();
    private final Map<UUID,Long> arrivalPvpGraceUntil=new ConcurrentHashMap<>();
    private volatile SavedLocation spawn;
    private final Map<String, SavedLocation> groupSpawns = new ConcurrentHashMap<>();
    private volatile boolean spawnReady;
    private volatile boolean closed;
    private TaskHandle timer;

    private record SelectorConfirmation(String command,String signature,long expiresAt) {}
    private record ActorAccess(boolean permitted,boolean bypassCooldown,boolean bypassWarmup,boolean unsafe) {}

    public static final String PERMISSION_BACK_ON_DEATH = "allium.back.ondeath";

    private static final class Session {
        final Player player;
        SavedLocation back;
        boolean backIsDeath;
        final Map<String,Long> cooldowns=new ConcurrentHashMap<>();
        volatile boolean loaded;
        long historyRevision;
        Session(Player p){player=p;}
    }
    private static final class Warmup {
        long due;
        long lastSecond;
    }
    private record Operation(Session session,CommandSender actor,String command,Supplier<Location> destination,
                             @Nullable Player anchor,@Nullable RequestTracker.Request request,boolean force,
                             Location origin,long delay,TeleportSettings rules,BooleanSupplier policy,boolean mayMove,
                             Warmup warmup,@Nullable java.util.function.Consumer<Boolean> outcome,
                             boolean backDeath) {}
    private record HistoryWrite(UUID owner,Session session,SavedLocation origin,String command,long until,
                                Map<String,Long> cooldowns,Runnable success) {}

    public TeleportService(PluginStart plugin,TeleportRepository repository,ThreadPoolExecutor io) {
        this(plugin,repository,io,new DestinationSafety());
    }
    public TeleportService(PluginStart plugin,TeleportRepository repository,ThreadPoolExecutor io,
                           java.util.function.Consumer<Player> regionalArrival) {
        this(plugin,repository,io,new DestinationSafety(),regionalArrival);
    }
    public TeleportService(PluginStart plugin,TeleportRepository repository,ThreadPoolExecutor io,DestinationSafety safety) {
        this(plugin,repository,io,safety,player->{});
    }
    private TeleportService(PluginStart plugin,TeleportRepository repository,ThreadPoolExecutor io,DestinationSafety safety,
                            java.util.function.Consumer<Player> regionalArrival) {
        this.plugin=plugin;this.repository=repository;this.io=io;
        this.safety=safety;
        this.regionalArrival=regionalArrival;
        this.gatewayReturn = new EndGatewayReturn(safety);
        settings=TeleportSettings.read(plugin.getConfig());
    }
    public void enable() {
        synchronized(OWNERS) {
            TeleportService previous=OWNERS.get(plugin);
            if(previous!=null&&previous!=this&&(!previous.closed||!previous.io.isTerminated()||!previous.pendingWrites.isEmpty()))
                throw new IllegalStateException("Previous teleport service still owns unfinished storage; replacement refused");
            OWNERS.put(plugin,this);
        }
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        registerWorldPermissions();
        loadSpawn(0);
        for(Player p:plugin.getServer().getOnlinePlayers())onOwner(p,()->joined(p));
        boolean regional=BukkitServerThread.isFolia();
        timer=plugin.getScheduler().repeatOnServer(()->{
            long now=System.currentTimeMillis();
            for(var request:requests.expire(now)) tell(request.requester(),Messages.TELEPORT_CANCELLED);
            if(!regional){
                for(var operation:List.copyOf(operations.values())){
                    if(now>=operation.warmup().due)complete(operation);
                    else tickWarmup(operation,now);
                }
            }
            return true;
        },50,50);
    }
    public void disable() {
        // Only the single-threaded server can read live locations here. The quit handler saves on the player owner.
        if(!BukkitServerThread.isFolia())for(Session s:List.copyOf(sessions.values()))saveLogout(s.player);
        closed=true;
        if(timer!=null)timer.cancel();
        HandlerList.unregisterAll(this);
        for(Operation operation:List.copyOf(operations.values())){
            if(operations.remove(operation.session().player.getUniqueId(),operation))finish(operation,false);
        }
        requests.expire(Long.MAX_VALUE);operations.clear();offlineLookups.clear();selectorConfirmations.clear();arrivalPvpGraceUntil.clear();sessions.clear();
        // Retry records that exhausted their live attempts before draining the worker. Retries
        // belong to the worker, so shutdown never depends on another server tick.
        for(HistoryWrite write:pendingWrites.values())queueHistory(write);
        io.shutdown();
        try {
            if(!io.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS))plugin.logger().warning("Teleport shutdown timed out; pending histories: "+pendingWrites.keySet());
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        for(HistoryWrite write:pendingWrites.values())plugin.logger().severe("Teleport history not saved at shutdown: owner="+write.owner()+" command="+write.command()+" origin="+write.origin()+" cooldowns="+write.cooldowns());
    }
    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(org.bukkit.configuration.file.FileConfiguration candidateRoot) {
        TeleportSettings next = TeleportSettings.read(candidateRoot);
        return codes.castled.allium.config.ReloadCoordinator.Prepared.of(() -> settings = next);
    }
    public boolean reloadSettings() {
        try { settings=TeleportSettings.read(plugin.getConfig());return true; }
        catch(IllegalArgumentException e){plugin.logger().warning("Kept previous teleport settings: "+e.getMessage());return false;}
    }
    public TeleportSettings settings(){return settings;}
    /** Two-step confirmation for mass-entity selector teleports (such as {@code /s @e}). */
    public boolean consumeSelectorConfirmation(UUID actorId,String command,String signature){
        long now=System.currentTimeMillis();
        long window=settings.selectorConfirmMillis();
        if(window<=0){selectorConfirmations.remove(actorId);return true;}
        SelectorConfirmation existing=selectorConfirmations.get(actorId);
        if(existing!=null&&existing.expiresAt()>now&&existing.command().equals(command)&&existing.signature().equals(signature)){
            selectorConfirmations.remove(actorId);
            return true;
        }
        selectorConfirmations.put(actorId,new SelectorConfirmation(command,signature,now+window));
        return false;
    }
    public boolean ready(UUID id){Session s=sessions.get(id);return s!=null&&s.loaded&&!pendingWrites.containsKey(id);}
    public boolean warming(UUID id){return operations.containsKey(id);}
    public boolean historyPending(UUID id){return pendingWrites.containsKey(id);}
    public int pendingRequests(){return requests.size();}
    /** Server thread: unexpired teleport requests the player sent or received. */
    public int requestsInvolving(UUID id){return requests.involving(id,System.currentTimeMillis());}
    /** Server thread: seconds until the player's longest running teleport cooldown ends, 0 when none runs. */
    public long cooldownSeconds(UUID id){
        Session s=sessions.get(id);if(s==null)return 0;long now=System.currentTimeMillis(),until=0;
        for(long end:s.cooldowns.values())until=Math.max(until,end);
        return until<=now?0:(until-now+999)/1000;
    }

    public void request(Player sender,Player recipient,RequestTracker.Kind kind) {
        if(!sender.hasPermission(kind==RequestTracker.Kind.TO_TARGET?"allium.tpa":"allium.tpahere")){send(sender,Messages.NO_PERMISSION);return;}
        if(!allowed(sender,recipient,sender,false,true)||!available(sender))return;
        requests.expire(System.currentTimeMillis());
        if(tpAutoActive(recipient)){
            Player mover=kind==RequestTracker.Kind.TO_TARGET?sender:recipient;
            Player anchor=kind==RequestTracker.Kind.TO_TARGET?recipient:sender;
            if(begin(mover,sender,kind==RequestTracker.Kind.TO_TARGET?"tpa":"tpahere",anchor::getLocation,anchor,null,false)){
                send(sender,Messages.TELEPORT_REQUEST_SENT,"{player}",recipient.getName());
                send(recipient,Messages.TELEPORT_AUTO_ACCEPTED,"{player}",sender.getName());
            }
            return;
        }
        var request=new RequestTracker.Request(sender.getUniqueId(),recipient.getUniqueId(),kind,System.currentTimeMillis()+settings.requestExpiryMillis());
        try{requests.put(request);}catch(IllegalStateException full){send(sender,Messages.TELEPORT_BUSY);return;}
        send(sender,Messages.TELEPORT_REQUEST_SENT,"{player}",recipient.getName());
        send(recipient,kind==RequestTracker.Kind.HERE?Messages.TELEPORT_HERE_RECEIVED:Messages.TELEPORT_REQUEST_RECEIVED,"{player}",sender.getName());
    }
    /** {@code /tpaall [player]}: sends a teleport-here request from {@code anchor} to every visible online player. */
    public void requestAll(CommandSender actor,Player anchor) {
        if(!actor.hasPermission("allium.tpaall")||(actor!=anchor&&!actor.hasPermission("allium.tpaall.others"))){send(actor,Messages.NO_PERMISSION);return;}
        if(!available(anchor))return;
        long now=System.currentTimeMillis();
        requests.expire(now);
        long expires=now+settings.requestExpiryMillis();
        int sent=0;
        for(Player recipient:List.copyOf(plugin.getServer().getOnlinePlayers())){
            if(recipient==null||!recipient.isOnline()||recipient.equals(anchor))continue;
            if(!visible(actor,recipient)||!allowed(anchor,recipient,anchor,false,false))continue;
            if(tpAutoActive(recipient)){
                if(begin(recipient,anchor,"tpahere",anchor::getLocation,anchor,null,false)){
                    send(recipient,Messages.TELEPORT_AUTO_ACCEPTED,"{player}",anchor.getName());
                    sent++;
                }
                continue;
            }
            var request=new RequestTracker.Request(anchor.getUniqueId(),recipient.getUniqueId(),RequestTracker.Kind.HERE,expires);
            try{requests.putAdditional(request);}catch(IllegalStateException full){break;}
            send(recipient,Messages.TELEPORT_HERE_RECEIVED,"{player}",anchor.getName());
            sent++;
        }
        if(sent==0){send(actor,Messages.TELEPORT_TPAALL_NONE);return;}
        send(actor,Messages.TELEPORT_TPAALL_SENT,"{count}",Integer.toString(sent),"{target}",anchor.getName());
    }
    public void accept(Player recipient,@Nullable Player requester) {
        if(!recipient.hasPermission("allium.tpaccept")){send(recipient,Messages.NO_PERMISSION);return;}
        var found=requests.incoming(recipient.getUniqueId(),requester==null?null:requester.getUniqueId(),System.currentTimeMillis());
        if(found.isEmpty()){send(recipient,Messages.TELEPORT_NO_PENDING);return;}
        var request=found.get();
        Player sender=plugin.getServer().getPlayer(request.requester());
        if(sender==null||!visible(recipient,sender)){requests.remove(request.requester(),recipient.getUniqueId());send(recipient,Messages.TELEPORT_NO_PENDING);return;}
        if(!allowed(sender,recipient,sender,false,true)){requests.remove(request.requester(),recipient.getUniqueId());return;}
        Player mover=request.kind()==RequestTracker.Kind.TO_TARGET?sender:recipient;
        Player anchor=request.kind()==RequestTracker.Kind.TO_TARGET?recipient:sender;
        if(begin(mover,sender,request.kind()==RequestTracker.Kind.TO_TARGET?"tpa":"tpahere",anchor::getLocation,anchor,request,false)) {
            requests.remove(request.requester(),recipient.getUniqueId());send(recipient,Messages.TELEPORT_ACCEPTED);
        }
    }
    public void deny(Player recipient,@Nullable Player requester) {
        if(!recipient.hasPermission("allium.tpdeny")){send(recipient,Messages.NO_PERMISSION);return;}
        var found=requests.incoming(recipient.getUniqueId(),requester==null?null:requester.getUniqueId(),System.currentTimeMillis());
        if(found.isEmpty()){send(recipient,Messages.TELEPORT_NO_PENDING);return;}
        var request=found.get();
        Player sender=plugin.getServer().getPlayer(request.requester());
        if(sender==null||!visible(recipient,sender)){requests.remove(request.requester(),recipient.getUniqueId());send(recipient,Messages.TELEPORT_NO_PENDING);return;}
        requests.remove(request.requester(),recipient.getUniqueId());send(recipient,Messages.TELEPORT_DENIED);
        tell(request.requester(),Messages.TELEPORT_DENIED);
    }
    public void cancel(Player sender) {
        cancel(sender, null);
    }
    /** Cancels pending teleport requests and active warmups for {@code sender}, optionally narrowed to {@code target}. */
    public void cancel(Player sender, @Nullable Player target) {
        if(!sender.hasPermission("allium.tpacancel")){send(sender,Messages.NO_PERMISSION);return;}
        UUID senderId=sender.getUniqueId();
        UUID targetId=target==null?null:target.getUniqueId();
        List<RequestTracker.Request> removed=targetId==null
                ?requests.cancelPlayer(senderId)
                :requests.removeIf(r->(r.requester().equals(senderId)&&r.recipient().equals(targetId))
                        ||(r.requester().equals(targetId)&&r.recipient().equals(senderId)));
        boolean any=!removed.isEmpty();
        Set<UUID> notified=new HashSet<>();
        for(var request:removed){
            UUID otherId=request.requester().equals(senderId)?request.recipient():request.requester();
            if(notified.add(otherId)){
                Player other=plugin.getServer().getPlayer(otherId);
                if(other!=null&&visible(other,sender))send(other,Messages.TELEPORT_REQUEST_CANCELLED);
            }
        }
        if(targetId==null)any=offlineLookups.remove(senderId)!=null||any;
        for(var operation:List.copyOf(operations.values())){
            boolean matches;
            if(targetId==null){
                matches=operation.session().player==sender
                        ||(operation.request()!=null&&(operation.request().requester().equals(senderId)
                                ||operation.request().recipient().equals(senderId)));
            }else{
                boolean requestBetween=operation.request()!=null
                        &&((operation.request().requester().equals(senderId)&&operation.request().recipient().equals(targetId))
                        ||(operation.request().requester().equals(targetId)&&operation.request().recipient().equals(senderId)));
                boolean ownAnchored=operation.session().player==sender&&operation.anchor()==target;
                matches=requestBetween||ownAnchored;
            }
            if(matches){interrupt(operation,"cancelled");any=true;}
        }
        send(sender,any?Messages.TELEPORT_REQUEST_CANCELLED:Messages.TELEPORT_NO_PENDING);
    }
    /**
     * Silently drops pending requests involving {@code player} when the other participant cannot see
     * them, matching offline behavior without broadcasting a vanish leak.
     */
    public void cancelInvisibleRequests(Player player) {
        if(player==null)return;
        UUID id=player.getUniqueId();
        requests.removeIf(request->{
            if(request.requester().equals(id)){
                Player recipient=plugin.getServer().getPlayer(request.recipient());
                return recipient==null||!visible(recipient,player);
            }
            if(request.recipient().equals(id)){
                Player requester=plugin.getServer().getPlayer(request.requester());
                return requester==null||!visible(requester,player);
            }
            return false;
        });
    }
    public void toggle(CommandSender actor,Player target,@Nullable Boolean on) {
        if(!actor.hasPermission("allium.tptoggle") || actor!=target&&!actor.hasPermission("allium.tptoggle.others")){send(actor,Messages.NO_PERMISSION);return;}
        PreferencesModule prefs=preferences();
        if(prefs==null||prefs.acceptance(target.getUniqueId(),PreferenceKey.ACCEPT_TELEPORT_REQUESTS)==PreferenceSessions.Acceptance.UNAVAILABLE) {
            send(actor,Messages.PREFERENCES_UNAVAILABLE,"{player}",target.getName());return;
        }
        boolean next=on!=null?on:prefs.acceptance(target.getUniqueId(),PreferenceKey.ACCEPT_TELEPORT_REQUESTS)!=PreferenceSessions.Acceptance.ACCEPTS;
        if(!next)cancelIncoming(target.getUniqueId());
        prefs.sessions().change(target.getUniqueId(),PreferenceChange.set(PreferenceKey.ACCEPT_TELEPORT_REQUESTS,next)).whenComplete((row,error)->onServer(()->{
            if(error!=null)send(actor,Messages.TELEPORT_STORAGE_FAILED);
            else send(actor,next?Messages.TELEPORT_TOGGLE_ON:Messages.TELEPORT_TOGGLE_OFF,"{player}",target.getName());
        }));
    }
    /** {@code /tpauto [on|off] [player]}: automatically accepts incoming teleport requests. */
    public void toggleAuto(CommandSender actor,Player target,@Nullable Boolean on) {
        if(!actor.hasPermission("allium.tpauto") || actor!=target&&!actor.hasPermission("allium.tpauto.others")){send(actor,Messages.NO_PERMISSION);return;}
        PreferencesModule prefs=preferences();
        if(prefs==null||prefs.effective(target.getUniqueId()).isEmpty()) {
            send(actor,Messages.PREFERENCES_UNAVAILABLE,"{player}",target.getName());return;
        }
        boolean next=on!=null?on:!prefs.effective(target.getUniqueId()).orElseThrow().tpAuto();
        prefs.sessions().change(target.getUniqueId(),PreferenceChange.set(PreferenceKey.TP_AUTO,next)).whenComplete((row,error)->onServer(()->{
            if(error!=null)send(actor,Messages.TELEPORT_STORAGE_FAILED);
            else send(actor,next?Messages.TELEPORT_AUTO_ON:Messages.TELEPORT_AUTO_OFF,"{player}",target.getName());
        }));
    }
    private boolean tpAutoActive(Player recipient) {
        if(recipient==null||!recipient.hasPermission("allium.tpauto")||!recipient.hasPermission("allium.tpaccept"))return false;
        PreferencesModule prefs=preferences();
        return prefs!=null&&prefs.effective(recipient.getUniqueId()).map(e->e.tpAuto()).orElse(false);
    }
    /** {@code /jump}: teleports the player onto the block they are looking at. */
    public void jump(Player player) {
        if(!player.hasPermission("allium.jump")){send(player,Messages.NO_PERMISSION);return;}
        var hit=player.rayTraceBlocks(128.0,org.bukkit.FluidCollisionMode.NEVER);
        if(hit==null||hit.getHitBlock()==null){send(player,Messages.TELEPORT_JUMP_NONE);return;}
        Location dest=hit.getHitBlock().getLocation().add(0.5,1.0,0.5);
        dest.setYaw(player.getLocation().getYaw());
        dest.setPitch(player.getLocation().getPitch());
        teleport(player,dest,"jump");
    }
    private void cancelIncoming(UUID target) {
        for(var request:requests.cancelIncoming(target))tell(request.requester(),Messages.TELEPORT_REQUEST_CANCELLED);
        for(var operation:List.copyOf(operations.values()))if(operation.request()!=null&&operation.request().recipient().equals(target))interrupt(operation,"recipient disabled requests");
    }
    public void back(Player player) {
        Session session=sessions.get(player.getUniqueId());
        if(!available(player))return;
        if(session.back==null){send(player,Messages.TELEPORT_NO_BACK);return;}
        Location destination=location(session.back);
        if(destination==null){send(player,Messages.TELEPORT_UNSAFE);return;}
        begin(player,player,"back",()->destination.clone(),null,null,false);
    }
    /** Primary group spawn for {@code player}, or {@code null} when no group spawn matches. */
    @Nullable
    public Location groupSpawn(Player player) {
        if (!spawnReady || player == null || groupSpawns.isEmpty()) return null;
        String group = codes.castled.allium.text.PlayerMeta.primaryGroup(plugin, player).toLowerCase(Locale.ROOT);
        SavedLocation point = groupSpawns.get(group);
        return point == null ? null : location(point);
    }

    /** Global server spawn ({@code /setspawn}), or {@code null} when unset or unloaded. */
    @Nullable
    public Location defaultSpawn() {
        if (!spawnReady || spawn == null) return null;
        return location(spawn);
    }

    private Location configuredSpawn(Player player) {
        Location groupLocation = groupSpawn(player);
        if (groupLocation != null) return groupLocation;
        return defaultSpawn();
    }

    public boolean isSpawnReady() {
        return spawnReady;
    }

    /** Resolves the player's effective spawn (group -> global -> default world spawn), or {@code null} if unavailable. */
    @Nullable
    public Location resolveSpawn(Player player) {
        if (!spawnReady) return null;
        List<World> worlds = plugin.getServer().getWorlds();
        Location configured = configuredSpawn(player);
        return configured != null ? configured
                : spawn == null && !worlds.isEmpty() ? worlds.get(0).getSpawnLocation() : null;
    }

    public void spawn(Player player) {
        if (!spawnReady) { send(player, Messages.TELEPORT_LOADING); return; }
        Location destination = resolveSpawn(player);
        if (destination == null) { send(player, Messages.TELEPORT_UNSAFE); return; }
        begin(player, player, "spawn", destination::clone, null, null, false);
    }

    public void setSpawn(Player player) {
        setSpawn(player, "default");
    }

    public void setSpawn(Player player, String rawGroup) {
        if (!player.hasPermission("allium.setspawn")) { send(player, Messages.NO_PERMISSION); return; }
        String group = rawGroup.toLowerCase(Locale.ROOT);
        if (!group.matches("[a-z0-9_-]{1,58}")) {
            send(player, Messages.SPAWN_INVALID_GROUP);
            return;
        }
        boolean global = group.equals("default");
        SavedLocation point = saved(player.getLocation());
        submit(() -> {
            try {
                checkWrite();
                repository.save(SERVER, global ? "spawn" : "spawn:" + group, point);
                onServer(() -> {
                    if (global) spawn = point;
                    else groupSpawns.put(group, point);
                    send(player, global ? Messages.SPAWN_SET : Messages.SPAWN_GROUP_SET, "{group}", group);
                });
            } catch (SQLException exception) {
                onServer(() -> send(player, Messages.TELEPORT_STORAGE_FAILED));
            }
        }, () -> send(player, Messages.TELEPORT_STORAGE_FAILED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void gatewayReturn(PlayerTeleportEvent event) {
        var cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.END_GATEWAY
                && cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        safeGatewayReturn(event, event.getTo()).ifPresent(event::setTo);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void gatewayEntityReturn(org.bukkit.event.entity.EntityTeleportEvent event) {
        if (!event.getClass().getName().equals(EndGatewayReturn.ENTITY_EVENT)) return;
        safeGatewayReturn(event, event.getTo()).ifPresent(event::setTo);
    }

    private Optional<Location> safeGatewayReturn(Event event, Location destination) {
        var config = plugin.getConfig();
        if (!config.getBoolean("end-gateway.enabled", true)) return Optional.empty();
        return gatewayReturn.resolve(destination, EndGatewayReturn.exit(event),
                config.getInt("end-gateway.max-distance-from-center", 512),
                config.getInt("end-gateway.search-radius", 5));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void respawn(PlayerRespawnEvent event) {
        var resolution = codes.castled.allium.spawn.SpawnRespawnResolver.evaluateRespawn(
                plugin, event, this, null, null);
        Location destination = resolution.targetLocation(resolution.decision().primary());
        if (destination != null) {
            event.setRespawnLocation(destination);
        }
    }

    /**
     * Server thread: seconds left on {@code command}'s cooldown for {@code mover}, 0 when none runs or
     * {@code actor} bypasses it. Lets a slow search (random teleport) be refused before it starts.
     */
    public long cooldownSeconds(CommandSender actor,UUID mover,String command){
        if(cooldownBypass(actor,command))return 0;
        Session s=sessions.get(mover);if(s==null)return 0;
        long until=s.cooldowns.getOrDefault(command,0L),now=System.currentTimeMillis();
        return until<=now?0:(until-now+999)/1000;
    }
    /** The safety rules a teleport of {@code mover} by {@code actor} is checked against now. */
    public TeleportSettings safetyRulesFor(Player mover,CommandSender actor){return safetyRules(mover,actor,settings);}
    /** Builds safety rules on the mover's region from the issuer's previously checked permission. */
    public TeleportSettings safetyRulesFor(Player mover,boolean allowUnsafe){return safetyRules(mover,allowUnsafe,settings);}
    /** Folia validates once at the destination, then moves the selected non-player entities on their owners. */
    public CompletableFuture<OptionalInt> moveSelectedEntities(Player issuer, List<Entity> entities, Location destination,
                                                                TeleportSettings rules, BooleanSupplier authorized) {
        var result = new CompletableFuture<OptionalInt>();
        safety.resolveRegional(plugin, destination, rules).whenComplete((checked, error) -> {
            Runnable work = () -> {
                try {
                    if (closed || !samePlayer(issuer)) {
                        result.cancel(false);
                    } else if (!authorized.getAsBoolean()) {
                        result.completeExceptionally(new SecurityException("teleport permission changed"));
                    } else if (error != null || checked == null || checked.destination().isEmpty()) {
                        result.complete(OptionalInt.empty());
                    } else {
                        Location safe = checked.destination().get();
                        var moves = entities.stream().map(entity -> moveSelectedEntity(issuer, entity, safe)).toList();
                        CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new)).thenRun(() ->
                                result.complete(OptionalInt.of((int) moves.stream().filter(move -> move.getNow(false)).count())));
                    }
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            };
            try {
                if (BukkitServerThread.runFor(plugin, plugin.getScheduler(), issuer, work, () -> result.cancel(false), 0) == TaskHandle.NONE) {
                    result.cancel(false);
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CompletableFuture<Boolean> moveSelectedEntity(Player issuer, Entity entity, Location destination) {
        var result = new CompletableFuture<Boolean>();
        Runnable work = () -> {
            try {
                if (closed || plugin.getServer().getPlayer(issuer.getUniqueId()) != issuer || entity instanceof Player || !entity.isValid()) {
                    result.complete(false);
                } else {
                    BukkitServerThread.teleportRegional(entity, destination.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)
                            .whenComplete((moved, error) -> result.complete(error == null && Boolean.TRUE.equals(moved)));
                }
            } catch (RuntimeException failure) {
                result.complete(false);
            }
        };
        try {
            var owner = BukkitServerThread.entity(plugin, entity);
            if (owner.isServerThread()) work.run();
            else if (plugin.getScheduler().runOn(owner, work, () -> result.complete(false), 0) == TaskHandle.NONE) result.complete(false);
        } catch (RuntimeException failure) {
            result.complete(false);
        }
        return result;
    }
    public void forceLocation(CommandSender actor,Player mover,Location destination,String command){
        forceLocation(actor,mover,destination,command,arrived->{});
    }
    /** As {@link #forceLocation(CommandSender,Player,Location,String)}, reporting once whether the player arrived. */
    public void forceLocation(CommandSender actor,Player mover,Location destination,String command,java.util.function.Consumer<Boolean> outcome){
        if(!actor.hasPermission(permission(command))||!visible(actor,mover)){send(actor,Messages.NO_PERMISSION);outcome.accept(false);return;}
        boolean bypassToggle=overridesToggle(actor,command);
        Location snapshot=destination.clone();
        boolean begun=begin(mover,actor,command,()->snapshot.clone(),null,null,false,()->visible(actor,mover)&&(actor==mover||!(actor instanceof Player p)||allowed(p,mover,actor,true,false,bypassToggle)&&allowed(mover,p,actor,true,false,bypassToggle)),outcome);
        if(!begun)outcome.accept(false);
    }
    public void force(CommandSender actor,Player mover,Player target,String command) {
        boolean bypassToggle=overridesToggle(actor,command);
        boolean multi=command.equals("tphere")||command.equals("tpohere")||mover==actor||actor.hasPermission("allium.tp.others")||command.equals("tpo")&&actor.hasPermission("allium.tpo");
        boolean allowed=actor.hasPermission("allium."+command)&&(!(actor instanceof Player)||multi)&&visible(actor,mover)&&visible(actor,target)
                &&allowed(mover,target,actor,true,false,bypassToggle)&&allowed(target,mover,actor,true,false,bypassToggle);
        plugin.logger().info("Teleport audit actor="+actor.getName()+" mover="+mover.getUniqueId()+" target="+target.getUniqueId()
                +" command="+command+" result="+(allowed?"authorized":"denied")+" reason="+(allowed?"explicit-force-permission":"permission-or-recipient-policy")+" authorized="+allowed+" toggleBypass="+bypassToggle);
        if(!allowed){send(actor,Messages.TELEPORT_REFUSED,"{player}",target.getName());return;}
        begin(mover,actor,command,target::getLocation,target,null,true);
    }
    /** {@code /tpall [player]}: teleports all visible online players to {@code anchor}, bypassing {@code /tptoggle}. */
    public void forceAll(CommandSender actor,Player anchor) {
        if(!actor.hasPermission("allium.tpall")||!visible(actor,anchor)){send(actor,Messages.NO_PERMISSION);return;}
        int moved=0;
        for(Player mover:List.copyOf(plugin.getServer().getOnlinePlayers())){
            if(mover==anchor||!visible(actor,mover))continue;
            if(!allowed(mover,anchor,actor,true,false,true)||!allowed(anchor,mover,actor,true,false,true))continue;
            if(begin(mover,actor,"tpall",anchor::getLocation,anchor,null,true))moved++;
        }
        plugin.logger().info("Teleport audit actor="+actor.getName()+" command=tpall anchor="+anchor.getUniqueId()+" moved="+moved);
        if(moved==0)send(actor,Messages.TELEPORT_ALL_NONE);
        else send(actor,Messages.TELEPORT_ALL,"{count}",Integer.toString(moved),"{player}",anchor.getName());
    }
    private static boolean overridesToggle(CommandSender actor,String command){
        return actor.hasPermission("allium.teleport.bypass.toggle")
                || command.equals("tpo")&&actor.hasPermission("allium.tpo")
                || command.equals("tpohere")&&actor.hasPermission("allium.tpohere")
                || command.equals("tpall")&&actor.hasPermission("allium.tpall");
    }
    public void registerWorldPermission(World world){
        if(world==null||world.getName()==null||plugin.getServer()==null||plugin.getServer().getPluginManager()==null)return;
        String node=WORLD_PERMISSION_PREFIX+world.getName().toLowerCase(Locale.ROOT);
        var pm=plugin.getServer().getPluginManager();
        if(pm.getPermission(node)==null){
            try{pm.addPermission(new Permission(node,"Allows entering world "+world.getName(),PermissionDefault.TRUE));}
            catch(IllegalArgumentException ignored){}
        }
    }
    private void registerWorldPermissions(){
        if(plugin.getServer()==null||plugin.getServer().getWorlds()==null)return;
        for(World world:plugin.getServer().getWorlds())registerWorldPermission(world);
    }
    public boolean canEnterWorld(Player player,@Nullable World world){
        if(player==null||world==null||world.getName()==null)return true;
        String node=WORLD_PERMISSION_PREFIX+world.getName().toLowerCase(Locale.ROOT);
        if(player.hasPermission(node))return true;
        if(player.isPermissionSet(node))return false;
        var pm=plugin.getServer()==null?null:plugin.getServer().getPluginManager();
        Permission registered=pm==null?null:pm.getPermission(node);
        return registered==null||registered.getDefault()!=PermissionDefault.FALSE;
    }
    /** Last successfully saved logout position; name resolution and SQL run on bounded workers. */
    public void offline(Player mover,String name){
        if(!offlineEnabled()){send(mover,Messages.MODULE_DISABLED);return;}
        if(!mover.hasPermission("allium.tp.offline")){send(mover,Messages.NO_PERMISSION);return;}
        if(!available(mover))return;
        if(operations.containsKey(mover.getUniqueId())||offlineLookups.containsKey(mover.getUniqueId())){send(mover,Messages.TELEPORT_BUSY);return;}
        if(!codes.castled.allium.naming.NameCache.isValidName(name)){send(mover,Messages.TELEPORT_OFFLINE_UNAVAILABLE);return;}
        Player live=plugin.getServer().getPlayerExact(name);
        if(live!=null){if(!visible(mover,live)){send(mover,Messages.TELEPORT_OFFLINE_UNAVAILABLE);return;}force(mover,mover,live,"tp");return;}
        var names=plugin.getPlayerNames();if(names==null){send(mover,Messages.TELEPORT_LOADING);return;}
        var query=new OfflineLookup(sessions.get(mover.getUniqueId()));offlineLookups.put(mover.getUniqueId(),query);
        names.resolve(name).whenComplete((found,error)->onPlayerLater(mover,()->{
            if(!offlineCurrent(mover,query))return;
            if(error!=null){offlineFailed(mover,query,Messages.TELEPORT_STORAGE_FAILED);return;}
            if(found.isEmpty()){offlineFailed(mover,query,Messages.TELEPORT_OFFLINE_UNAVAILABLE);return;}
            query.target=found.get().uuid();query.name=found.get().name();
            if(plugin.getServer().getPlayer(query.target)!=null){offlineFailed(mover,query,Messages.TELEPORT_OFFLINE_UNAVAILABLE);return;}
            submit(()->{try{var point=repository.load(query.target,"logout");onPlayerLater(mover,()->{
                if(!offlineCurrent(mover,query))return;
                if(!query.valid||plugin.getServer().getPlayer(query.target)!=null||point.isEmpty()){offlineFailed(mover,query,Messages.TELEPORT_OFFLINE_UNAVAILABLE);return;}
                Location destination=location(point.get());if(destination==null){offlineFailed(mover,query,Messages.TELEPORT_UNSAFE);return;}
                query.loading=false;
                if(!begin(mover,mover,"tpoffline",()->destination.clone(),null,null,false,()->offlineEnabled()&&offlineCurrent(mover,query)&&query.valid&&plugin.getServer().getPlayer(query.target)==null))offlineLookups.remove(mover.getUniqueId(),query);
            },0);}catch(SQLException|RuntimeException failure){onPlayerLater(mover,()->offlineFailed(mover,query,Messages.TELEPORT_STORAGE_FAILED),0);}},()->offlineFailed(mover,query,Messages.TELEPORT_BUSY));
        },0));
    }
    private boolean offlineEnabled(){return plugin.getConfig().getBoolean("modules.teleport",true)&&(plugin.getCommandManager()==null||!plugin.getCommandManager().getFamilyPolicy().isDisabled("tpoffline"));}
    private boolean offlineCurrent(Player p,OfflineLookup query){return !closed&&offlineLookups.get(p.getUniqueId())==query&&same(query.session);}
    private void offlineFailed(Player p,OfflineLookup query,Messages key){if(offlineLookups.remove(p.getUniqueId(),query))send(p,key);}
    private static String permission(String command){return command.equals("tpoffline")?"allium.tp.offline":command.equals("there")?"allium.tpthere":"allium."+command;}
    private void saveLogout(Player player){
        UUID id=player.getUniqueId();SavedLocation point=saved(player.getLocation());
        submit(()->{try{checkWrite();repository.save(id,"logout",point);}catch(SQLException|RuntimeException error){plugin.logger().warning("Logout teleport location could not be saved for "+id+"; the previous last-known position is retained.");}},()->plugin.logger().warning("Logout teleport queue full for "+id+"; the previous last-known position is retained."));
    }
    /** Entry for future home/warp/kit consumers: same safety, warmup, cooldown and history pipeline. */
    public boolean teleport(Player mover,Location destination,String command) {
        if(!command.matches("[a-z]+"))throw new IllegalArgumentException("canonical teleport command required");
        Location snapshot=destination.clone();
        return begin(mover,mover,command,()->snapshot.clone(),null,null,false);
    }
    /**
     * As {@link #teleport(Player,Location,String)}, and reports once whether the player arrived: true when
     * moved, false when refused, interrupted (moving, damage, quitting) or cancelled. Paid teleports
     * capture or release their charge on it.
     */
    public boolean teleport(Player mover,Location destination,String command,java.util.function.Consumer<Boolean> outcome) {
        Location snapshot=destination.clone();
        return teleport(mover, () -> snapshot.clone(), command, () -> true, Objects.requireNonNull(outcome));
    }
    /** Sets (or with null, removes) the per-player cooldown for {@code command}, in milliseconds; negative keeps the configured one. */
    public void cooldownProvider(String command,@Nullable java.util.function.ToLongFunction<Player> provider){
        if(provider==null)cooldownProviders.remove(command);else cooldownProviders.put(command,provider);
    }
    /** Homes/warps supply a server-thread policy and destination rechecked at physical commit. */
    public boolean teleport(Player mover,Supplier<Location> destination,String command,BooleanSupplier policy) {
        return teleport(mover, destination, command, policy, null);
    }
    public boolean teleport(Player mover,Supplier<Location> destination,String command,BooleanSupplier policy,
                            @Nullable java.util.function.Consumer<Boolean> outcome) {
        if(!command.matches("[a-z]+"))throw new IllegalArgumentException("canonical teleport command required");
        boolean begun=begin(mover,mover,command,destination,null,null,false,Objects.requireNonNull(policy),outcome);
        if(!begun&&outcome!=null)outcome.accept(false);
        return begun;
    }
    private boolean begin(Player mover,CommandSender actor,String command,Supplier<Location> destination,@Nullable Player anchor,
                          @Nullable RequestTracker.Request request,boolean force) {
        return begin(mover,actor,command,destination,anchor,request,force,()->true);
    }
    private boolean begin(Player mover,CommandSender actor,String command,Supplier<Location> destination,@Nullable Player anchor,
                          @Nullable RequestTracker.Request request,boolean force,BooleanSupplier policy) {
        return begin(mover,actor,command,destination,anchor,request,force,policy,null);
    }
    private boolean begin(Player mover,CommandSender actor,String command,Supplier<Location> destination,@Nullable Player anchor,
                          @Nullable RequestTracker.Request request,boolean force,BooleanSupplier policy,
                          @Nullable java.util.function.Consumer<Boolean> outcome) {
        boolean regional=BukkitServerThread.isFolia();
        if(regional&&actor instanceof Player player){
            var owner=BukkitServerThread.entity(plugin,player);
            if(!owner.isServerThread()){
                plugin.getScheduler().runOn(owner,()->{
                    if(!begin(mover,actor,command,destination,anchor,request,force,policy,outcome)&&outcome!=null)outcome.accept(false);
                },()->{if(outcome!=null)onServer(()->outcome.accept(false));},0);
                return true;
            }
        }
        ActorAccess access=actorAccess(actor,command);
        if(regional){
            var owner=BukkitServerThread.entity(plugin,mover);
            if(!owner.isServerThread()){
                Runnable declined=()->{if(outcome!=null)outcome.accept(false);};
                plugin.getScheduler().runOn(owner,()->{
                    if(!beginOwned(mover,actor,command,destination,anchor,request,force,policy,outcome,access))declined.run();
                },()->{if(outcome!=null)onServer(declined);},0);
                return true;
            }
        }
        return beginOwned(mover,actor,command,destination,anchor,request,force,policy,outcome,access);
    }
    private boolean beginOwned(Player mover,CommandSender actor,String command,Supplier<Location> destination,@Nullable Player anchor,
                               @Nullable RequestTracker.Request request,boolean force,BooleanSupplier policy,
                               @Nullable java.util.function.Consumer<Boolean> outcome,ActorAccess access) {
        if(closed)return false;
        if(!access.permitted()){send(actor,Messages.NO_PERMISSION);return false;}
        if(!available(mover))return false;
        if(!policy.getAsBoolean()){send(actor,Messages.TELEPORT_INTERRUPTED,"{reason}","destination is no longer available");return false;}
        var lookup=offlineLookups.get(mover.getUniqueId());
        if(operations.containsKey(mover.getUniqueId())||lookup!=null&&lookup.loading){send(actor,Messages.TELEPORT_BUSY);return false;}
        Session session=sessions.get(mover.getUniqueId());
        long now=System.currentTimeMillis();
        if(!access.bypassCooldown()&&session.cooldowns.getOrDefault(command,0L)>now){
            send(actor,Messages.COOLDOWN_ACTIVE,"{time}",Long.toString((session.cooldowns.get(command)-now+999)/1000)+"s");return false;
        }
        TeleportSettings rules=settings;
        boolean regional=BukkitServerThread.isFolia();
        if(!regional){
            Location wanted=destination.get();
            if(!destinationAllowed(mover,actor,wanted))return false;
            if(safety.ungenerated(wanted,rules)){send(actor,Messages.TELEPORT_UNGENERATED);return false;}
            var checked=safety.resolve(wanted,safetyRules(mover,access.unsafe(),rules));
            if(checked.isEmpty()){send(actor,Messages.TELEPORT_UNSAFE);return false;}
        }
        long warmup=access.bypassWarmup()?0:rules.warmup(command);
        var operation=new Operation(session,actor,command,destination,anchor,request,force,mover.getLocation().clone(),warmup,rules,policy,
                typed(mover,"timer.move",command),new Warmup(),outcome,command.equals("back")&&session.backIsDeath);
        operations.put(mover.getUniqueId(),operation);
        if(regional)checkRegionalDestination(operation,true,access);
        else if(warmup==0)complete(operation);
        else startWarmup(operation,now,false);
        return true;
    }
    private void startWarmup(Operation operation,long now,boolean regional) {
        Player mover=operation.session().player;
        operation.warmup().due=now+operation.delay();
        operation.warmup().lastSecond=(operation.delay()+999L)/1000L;
        String seconds=Long.toString(operation.warmup().lastSecond);
        send(mover,Messages.TELEPORT_WARMUP,"{warmup}",seconds,"{time}",seconds);
        if(regional)plugin.getScheduler().repeatOn(BukkitServerThread.entity(plugin,mover),()->{
            if(!active(operation))return false;
            long time=System.currentTimeMillis();
            if(time>=operation.warmup().due){complete(operation);return false;}
            tickWarmup(operation,time);
            return true;
        },50,50);
    }
    private void tickWarmup(Operation operation,long now) {
        long remaining=operation.warmup().due-now;
        if(remaining<=0)return;
        long seconds=Math.max(1L,(remaining+999L)/1000L);
        if(seconds==operation.warmup().lastSecond)return;
        operation.warmup().lastSecond=seconds;
        Player mover=operation.session().player;
        if(samePlayer(mover)){
            String formatted=Long.toString(seconds);
            plugin.getMessageManager().sendActionBarUpdate(mover,Messages.TELEPORT_WARMUP,"{warmup}",formatted,"{time}",formatted);
        }
    }
    private boolean active(Operation operation) {
        return !closed&&operations.get(operation.session().player.getUniqueId())==operation;
    }
    private boolean canComplete(Operation operation,ActorAccess access) {
        if(!active(operation))return false;
        Player mover=operation.session().player;
        if(!operation.policy().getAsBoolean()){interrupt(operation,"destination is no longer available");return false;}
        if(operation.actor() instanceof Player actor&&!samePlayer(actor)){interrupt(operation,"issuing player went offline");return false;}
        if(!access.permitted()) { interrupt(operation,"permission changed");return false; }
        if(!access.bypassCooldown()
                &&operation.session().cooldowns.getOrDefault(operation.command(),0L)>System.currentTimeMillis()) { interrupt(operation,"cooldown changed");return false; }
        if(!same(operation.session())||operation.anchor()!=null&&!samePlayer(operation.anchor())){interrupt(operation,"player went offline");return false;}
        if(operation.request()!=null) {
            var request=operation.request();Player sender=plugin.getServer().getPlayer(request.requester());Player receiver=plugin.getServer().getPlayer(request.recipient());
            if(sender==null||receiver==null||!receiver.hasPermission("allium.tpaccept")||!allowed(sender,receiver,sender,false,false)){interrupt(operation,"request policy changed");return false;}
        }
        if(operation.force()){
            boolean bypassToggle=overridesToggle(operation.actor(),operation.command());
            if((operation.command().equals("tp")&&operation.actor() instanceof Player&&mover!=operation.actor()&&!operation.actor().hasPermission("allium.tp.others"))||!visible(operation.actor(),mover)||!visible(operation.actor(),operation.anchor())
                    ||!allowed(mover,operation.anchor(),operation.actor(),true,false,bypassToggle)||!allowed(operation.anchor(),mover,operation.actor(),true,false,bypassToggle)) {
                interrupt(operation,"teleport policy changed");return false;
            }
        }
        return true;
    }
    private void complete(Operation operation) {
        if(BukkitServerThread.isFolia()){
            withRegionalActor(operation,access->{
                if(canComplete(operation,access))checkRegionalDestination(operation,false,access);
            });
            return;
        }
        ActorAccess access=actorAccess(operation.actor(),operation.command());
        if(!canComplete(operation,access))return;
        var mover=operation.session().player;
        Location targetLoc=operation.destination().get();
        if(!destinationAllowed(mover,operation.actor(),targetLoc)){discard(operation);return;}
        var destination=safety.resolve(targetLoc,safetyRules(mover,access.unsafe(),operation.rules()));
        if(destination.isEmpty()){rejectDestination(operation,Messages.TELEPORT_UNSAFE);return;}
        commit(operation,destination.get(),false,access.bypassCooldown());
    }
    private void withRegionalActor(Operation operation,java.util.function.Consumer<ActorAccess> action) {
        Player mover=operation.session().player;
        Runnable read=()->{
            if(!active(operation))return;
            ActorAccess access=actorAccess(operation.actor(),operation.command());
            onOwner(mover,()->action.accept(access));
        };
        if(operation.actor() instanceof Player player){
            onParticipant(player,read,()->onServer(()->interrupt(operation,"issuing player went offline")));
        }else read.run();
    }
    private void checkRegionalDestination(Operation operation,boolean preflight,ActorAccess access) {
        Player mover=operation.session().player;
        Player locationOwner=operation.anchor()==null?mover:operation.anchor();
        onParticipant(locationOwner,()->{
            if(!active(operation))return;
            Location wanted;
            try { wanted=samePlayer(locationOwner)?operation.destination().get():null; }
            catch(RuntimeException failure){onOwner(mover,()->rejectDestination(operation,Messages.TELEPORT_UNSAFE));return;}
            onOwner(mover,()->{
                if(!active(operation))return;
                if(!destinationAllowed(mover,operation.actor(),wanted)){discard(operation);return;}
                var rules=safetyRules(mover,access.unsafe(),operation.rules());
                try {
                    safety.resolveRegional(plugin,wanted,rules).whenComplete((result,error)->onOwner(mover,()->
                            regionalDestinationReady(operation,preflight,rules,result,error)));
                } catch(RuntimeException failure) {
                    regionalDestinationReady(operation,preflight,rules,null,failure);
                }
            });
        },()->onOwner(mover,()->interrupt(operation,"player went offline")));
    }
    private void regionalDestinationReady(Operation operation,boolean preflight,TeleportSettings checkedRules,
                                          @Nullable DestinationSafety.RegionalResult result,@Nullable Throwable error) {
        if(!active(operation))return;
        if(result==null||result.destination().isEmpty()){
            if(error!=null)plugin.logger().warning("Regional teleport safety check failed: "+error);
            rejectDestination(operation,result!=null&&result.ungenerated()?Messages.TELEPORT_UNGENERATED:Messages.TELEPORT_UNSAFE);
            return;
        }
        withRegionalActor(operation,access->{
            if(!canComplete(operation,access))return;
            Player mover=operation.session().player;
            if(!checkedRules.equals(safetyRules(mover,access.unsafe(),operation.rules()))){
                checkRegionalDestination(operation,preflight,access);
                return;
            }
            if(preflight&&operation.delay()>0){startWarmup(operation,System.currentTimeMillis(),true);return;}
            Location destination=result.destination().get();
            if(!destinationAllowed(mover,operation.actor(),destination)){discard(operation);return;}
            commit(operation,destination,true,access.bypassCooldown());
        });
    }
    private boolean destinationAllowed(Player mover,CommandSender actor,@Nullable Location target) {
        if(target==null||target.getWorld()==null||canEnterWorld(mover,target.getWorld()))return true;
        send(actor,Messages.WORLD_ACCESS_DENIED,"{world}",target.getWorld().getName());
        if(actor!=mover)send(mover,Messages.WORLD_ACCESS_DENIED,"{world}",target.getWorld().getName());
        return false;
    }
    private void rejectDestination(Operation operation,Messages reason) {
        if(discard(operation))send(operation.actor(),reason);
    }
    private boolean discard(Operation operation) {
        Player mover=operation.session().player;
        UUID id=mover.getUniqueId();
        if(!operations.remove(id,operation))return false;
        offlineLookups.remove(id);
        if(operation.warmup().lastSecond>0&&samePlayer(mover))plugin.getMessageManager().clearActionBarIfPresent(mover,Messages.TELEPORT_WARMUP);
        finish(operation,false);
        return true;
    }
    private void commit(Operation operation,Location destination,boolean regional,boolean bypassCooldown) {
        Player mover=operation.session().player;
        UUID id=mover.getUniqueId();
        OfflineLookup offline=operation.command().equals("tpoffline")?offlineLookups.get(id):null;
        Location origin=mover.getLocation().clone();
        if(!regional){operations.remove(id);offlineLookups.remove(id);}
        committing.add(id);
        if(operation.warmup().lastSecond>0&&samePlayer(mover))plugin.getMessageManager().clearActionBarIfPresent(mover,Messages.TELEPORT_WARMUP);
        if(regional){
            try {
                BukkitServerThread.teleportRegional(mover,destination,PlayerTeleportEvent.TeleportCause.PLUGIN)
                        .whenComplete((success,error)->onParticipant(mover,()->{
                            committing.remove(id);
                            if(!operations.remove(id,operation))return;
                            offlineLookups.remove(id);
                            if(error!=null)plugin.logger().warning("Regional teleport failed: "+error);
                            if(error==null&&Boolean.TRUE.equals(success)) {
                                finishCommit(operation,origin,offline,bypassCooldown);
                            } else {
                                failCommit(operation,"the server refused the move");
                            }
                        },()->onServer(()->{
                            committing.remove(id);
                            discard(operation);
                        })));
            }catch(RuntimeException failure){
                committing.remove(id);
                discard(operation);
                plugin.logger().warning("Regional teleport failed: "+failure);
                send(operation.actor(),Messages.TELEPORT_INTERRUPTED,"{reason}","server could not start the teleport");
            }
            return;
        }
        boolean success;
        // Bukkit will not teleport a player who carries riders; Allium's ride action takes them along.
        var rides=codes.castled.allium.ride.Rides.current();
        try{success=rides!=null?rides.teleport(mover,destination,PlayerTeleportEvent.TeleportCause.PLUGIN):mover.teleport(destination,PlayerTeleportEvent.TeleportCause.PLUGIN);}
        catch (RuntimeException failure) { finish(operation, false); throw failure; }
        finally{committing.remove(id);}
        if(success)finishCommit(operation,origin,offline,cooldownBypass(operation.actor(),operation.command()));
        else failCommit(operation,"cancelled by another plugin");
    }
    private void failCommit(Operation operation,String reason) {
        finish(operation,false);
        send(operation.actor(),Messages.TELEPORT_INTERRUPTED,"{reason}",reason);
    }
    private void finishCommit(Operation operation,Location origin,@Nullable OfflineLookup offline,boolean bypassCooldown) {
        Player mover=operation.session().player;
        UUID id=mover.getUniqueId();
        finish(operation,true);
        if(operation.force())plugin.logger().info("Teleport audit completed actor="+operation.actor().getName()+" mover="+id
                +" target="+operation.anchor().getUniqueId()+" command="+operation.command()+" result=completed reason=bukkit-teleport-success world="+mover.getWorld().getUID());
        if(offline!=null)plugin.logger().info("Offline teleport audit actor="+mover.getUniqueId()+" target="+offline.target+" result=completed world="+mover.getWorld().getUID());
        Session session=operation.session();session.back=saved(origin);session.backIsDeath=false;
        long configured=operation.rules().cooldown(operation.command());
        var provider=cooldownProviders.get(operation.command());
        if(provider!=null){long custom=provider.applyAsLong(mover);if(custom>=0)configured=custom;}
        long until=System.currentTimeMillis()+(bypassCooldown?0:configured);
        session.cooldowns.put(operation.command(),until);
        long graceMillis=operation.rules().arrivalPvpGraceMillis();
        if(graceMillis>0)arrivalPvpGraceUntil.put(id,System.currentTimeMillis()+graceMillis);
        Location landed=mover.getLocation();
        if(BukkitServerThread.isFolia()){
            try { regionalArrival.accept(mover); }
            catch(RuntimeException failure) { plugin.logger().warning("Could not start companion follow: "+failure); }
        }
        boolean position=operation.anchor()==null&&java.util.Set.of("tp","tpo","tppos","top","bottom","jump","rtp").contains(operation.command());
        saveHistory(session,session.back,operation.command(),until,0,()->{
            if(position){send(mover,Messages.TELEPORT_POSITION,"{x}",coordinate(landed.getX()),"{y}",coordinate(landed.getY()),"{z}",coordinate(landed.getZ()),"{world}",landed.getWorld()==null?"?":landed.getWorld().getName());return;}
            send(mover,switch(operation.command()){case "back"->operation.backDeath()?Messages.TELEPORT_BACK_DEATH:Messages.TELEPORT_BACK;case "spawn"->Messages.SPAWN_TELEPORT;case "tpoffline"->Messages.TELEPORT_OFFLINE_SUCCESS;default->Messages.TELEPORT_SUCCESS;},"{target}",offline!=null?offline.name:operation.anchor()==null?operation.command():operation.anchor().getName());
        });
    }
    private static String coordinate(double value){return String.format(Locale.ROOT,"%.1f",value);}
    /**
     * The names a teleport type answers to in per-type nodes: its command ({@code home},
     * {@code tpa}, {@code tppos}, ...) and its group ({@code homes}, {@code warps}, {@code requests}).
     */
    static List<String> typeNames(String command){
        String group=switch(command){case "home"->"homes";case "warp"->"warps";case "tpa","tpahere"->"requests";case "tpo"->"tp";case "tpohere"->"tphere";default->null;};
        return group==null?List.of(command):List.of(command,group);
    }
    /** {@code allium.teleport.<kind>.<type>} for this teleport's type or group, e.g. {@code allium.teleport.timer.move.homes}. */
    private static boolean typed(CommandSender who,String kind,String command){
        for(String type:typeNames(command))if(who.hasPermission("allium.teleport."+kind+"."+type))return true;
        return false;
    }
    private static boolean cooldownBypass(CommandSender actor,String command){
        return actor.hasPermission("allium.teleport.bypass.cooldown")||typed(actor,"cooldown.bypass",command);
    }
    private static ActorAccess actorAccess(CommandSender actor,String command) {
        return new ActorAccess(actor.hasPermission(permission(command)),cooldownBypass(actor,command),
                command.equals("tpall")||actor.hasPermission("allium.teleport.bypass.warmup")||typed(actor,"timer.bypass",command),
                actor.hasPermission("allium.teleport.unsafe"));
    }
    private static TeleportSettings safetyRules(Player mover,CommandSender actor,TeleportSettings rules) {
        return safetyRules(mover,actor.hasPermission("allium.teleport.unsafe"),rules);
    }
    private static TeleportSettings safetyRules(Player mover,boolean unsafe,TeleportSettings rules) {
        boolean flight=mover.getAllowFlight()||mover.getGameMode()==org.bukkit.GameMode.CREATIVE||mover.getGameMode()==org.bukkit.GameMode.SPECTATOR;
        return rules.forMover(flight,unsafe);
    }
    private boolean allowed(Player requester,Player recipient,CommandSender actor,boolean force,boolean notify) {
        return allowed(requester,recipient,actor,force,notify,false);
    }
    private boolean allowed(Player requester,Player recipient,CommandSender actor,boolean force,boolean notify,boolean bypassToggle) {
        Messages refusal=null;
        if(!samePlayer(requester)||!samePlayer(recipient)||!visible(actor,recipient)||!force&&!visible(recipient,requester))refusal=Messages.PLAYER_NOT_FOUND;
        else if(requester==recipient)refusal=Messages.TELEPORT_SELF;
        else {
            PreferencesModule prefs=preferences();
            PreferenceSessions.Acceptance acceptance=prefs==null?PreferenceSessions.Acceptance.UNAVAILABLE:prefs.acceptance(recipient.getUniqueId(),PreferenceKey.ACCEPT_TELEPORT_REQUESTS);
            if(acceptance==PreferenceSessions.Acceptance.UNAVAILABLE)refusal=Messages.PREFERENCES_UNAVAILABLE;
            else if(acceptance==PreferenceSessions.Acceptance.REFUSES&&!(force&&(bypassToggle||actor.hasPermission("allium.teleport.bypass.toggle"))))refusal=Messages.TELEPORT_REFUSED;
            if(refusal==null&&messaging()==null)refusal=Messages.PREFERENCES_UNAVAILABLE;
            if(refusal==null) {
                var ignore=messaging().ignores().ignores(recipient.getUniqueId(),requester.getUniqueId());
                if(ignore==MessagePolicy.Ignore.UNKNOWN)refusal=Messages.PREFERENCES_UNAVAILABLE;
                else if(ignore==MessagePolicy.Ignore.YES&&!(force&&actor.hasPermission("allium.teleport.bypass.ignore")))refusal=Messages.TELEPORT_REFUSED;
            }
        }
        if(refusal!=null&&notify)send(actor,refusal,"{player}",recipient.getName());
        return refusal==null;
    }
    private boolean available(Player player) {
        Session s=sessions.get(player.getUniqueId());
        PreferencesModule p=preferences();
        if(!samePlayer(player)||s==null||!s.loaded||pendingWrites.containsKey(player.getUniqueId())||p==null||p.effective(player.getUniqueId()).isEmpty()) {
            send(player,Messages.TELEPORT_LOADING);return false;
        }
        return true;
    }
    /** Tells the operation's caller how it ended; called exactly once per operation that has an outcome. */
    private void finish(Operation operation,boolean arrived){
        if(operation.outcome()==null)return;
        try{operation.outcome().accept(arrived);}
        catch(RuntimeException failure){plugin.logger().warning("Teleport outcome handler failed for "+operation.command()+": "+failure.getMessage());}
    }
    private void interrupt(Operation operation,String reason) {
        Player mover=operation.session().player;
        onOwner(mover,()->{
            if(committing.contains(mover.getUniqueId())||!discard(operation))return;
            send(mover,Messages.TELEPORT_INTERRUPTED,"{reason}",reason);
        });
    }
    private void saveHistory(Session session,SavedLocation origin,String command,long until,int attempt,Runnable success) {
        session.historyRevision++;
        Map<String,Long> cooldowns=new HashMap<>();
        HistoryWrite previous=pendingWrites.get(session.player.getUniqueId());
        if(previous!=null)cooldowns.putAll(previous.cooldowns());
        cooldowns.putAll(session.cooldowns);
        HistoryWrite write=new HistoryWrite(session.player.getUniqueId(),session,origin,command,until,Map.copyOf(cooldowns),success);
        pendingWrites.put(write.owner(),write);queueHistory(write);
    }
    private void queueHistory(HistoryWrite write) {
        if(!queuedWrites.add(write))return;
        try {io.execute(()->{
            try {
                for(int attempt=0;attempt<4;attempt++) {
                    try {
                        checkWrite();repository.saveHistory(write.owner(),write.origin(),write.cooldowns());
                        pendingWrites.remove(write.owner(),write);
                        onPlayerLater(write.session().player,()->{if(same(write.session()))write.success().run();},0);return;
                    }catch(SQLException|RuntimeException failure) {
                        if(attempt==3) {
                            plugin.logger().severe("Teleport history remains unsaved for "+write.owner()+": "+failure.getMessage());
                            onServer(()->send(write.session().player,Messages.TELEPORT_STORAGE_FAILED));return;
                        }
                        plugin.logger().warning("Retrying teleport history "+write.owner()+": "+failure.getMessage());
                        try {Thread.sleep(250L*(attempt+1));}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
                    }
                }
            } finally {queuedWrites.remove(write);}
        });}catch(RejectedExecutionException rejected) {
            queuedWrites.remove(write);
            plugin.logger().severe("Teleport history queue unavailable for "+write.owner()+"; retained for retry");
            onServer(()->send(write.session().player,Messages.TELEPORT_STORAGE_FAILED));
        }
    }
    private void joined(Player player) {
        offlineLookups.remove(player.getUniqueId());
        for(OfflineLookup query:offlineLookups.values())if(player.getUniqueId().equals(query.target))query.valid=false;
        Session session=new Session(player);sessions.put(player.getUniqueId(),session);
        HistoryWrite pending=pendingWrites.get(player.getUniqueId());if(pending!=null)queueHistory(pending);
        load(session,0);
    }
    private void load(Session session,int attempt) {
        if(!same(session))return;
        HistoryWrite pending=pendingWrites.get(session.player.getUniqueId());
        if(pending!=null){
            if(queuedWrites.contains(pending))onPlayerLater(session.player,()->load(session,attempt),250);
            return; // Exhausted writes keep this connection unavailable; never reload stale SQL.
        }
        long revision=session.historyRevision;
        submit(()->{
            try{
                if(FaultInjection.trip(FAULT_LOAD_FAIL))throw new SQLException("injected teleport load failure");
                var back=repository.load(session.player.getUniqueId(),"back");var cooldowns=repository.loadCooldowns(session.player.getUniqueId());
                onPlayerLater(session.player,()->{if(same(session)){
                    if(session.historyRevision!=revision){load(session,attempt);return;}
                    session.back=back.orElse(null);session.cooldowns.putAll(cooldowns);session.loaded=true;
                }},0);
            }catch(SQLException|RuntimeException e){onPlayerLater(session.player,()->retryLoad(session,attempt),0);}
        },()->retryLoad(session,attempt));
    }
    private void retryLoad(Session session,int attempt) {
        if(closed||!same(session))return;
        if(attempt<3)onPlayerLater(session.player,()->load(session,attempt+1),250L*(attempt+1));
        else plugin.logger().warning("Teleport data unavailable for "+session.player.getUniqueId()+" after bounded retries");
    }
    private void loadSpawn(int attempt) {
        if(closed)return;
        submit(()->{try{
                var point=repository.load(SERVER,"spawn");
                var groups=repository.loadGroupSpawns();
                onServer(()->{spawn=point.orElse(null);groupSpawns.clear();groupSpawns.putAll(groups);spawnReady=true;});}
            catch(SQLException|RuntimeException e){onServer(()->retrySpawn(attempt));}},()->retrySpawn(attempt));
    }
    private void retrySpawn(int attempt) {
        if(closed)return;
        if(attempt<3)plugin.getScheduler().runOnServerLater(()->loadSpawn(attempt+1),250L*(attempt+1));
        else plugin.logger().warning("Teleport spawn unavailable after bounded retries");
    }
    /** Publishes refreshed server and group spawns loaded by an importer before the login fence drops. */
    public void publishImportedSpawns(@Nullable SavedLocation loadedSpawn, Map<String, SavedLocation> loadedGroupSpawns) {
        if (closed) return;
        spawn = loadedSpawn;
        groupSpawns.clear();
        if (loadedGroupSpawns != null) groupSpawns.putAll(loadedGroupSpawns);
        spawnReady = true;
    }
    private void submit(Runnable work,Runnable rejected) {
        try{io.execute(work);}catch(RejectedExecutionException e){rejected.run();}
    }
    private void checkWrite() throws SQLException {if(FaultInjection.trip(FAULT_WRITE_FAIL))throw new SQLException("injected teleport write failure");}
    private void onServer(Runnable task){if(!closed)plugin.getScheduler().runOnServer(()->{if(!closed)task.run();});}
    private void onPlayerLater(Player player,Runnable task,long delay) {
        if(!closed)BukkitServerThread.runFor(plugin,plugin.getScheduler(),player,()->{if(!closed)task.run();},()->{},delay);
    }
    private void onOwner(CommandSender sender,Runnable task) {
        if(!closed)onParticipant(sender,()->{if(!closed)task.run();},()->{});
    }
    private void onParticipant(CommandSender sender,Runnable task,Runnable retired) {
        if(!BukkitServerThread.isFolia()){task.run();return;}
        var owner=sender instanceof Player player?BukkitServerThread.entity(plugin,player):BukkitServerThread.global(plugin);
        if(owner.isServerThread())task.run();
        else plugin.getScheduler().runOn(owner,task,retired,0);
    }
    private boolean same(Session s){return sessions.get(s.player.getUniqueId())==s&&samePlayer(s.player);}
    private boolean samePlayer(Player p){return p!=null&&p.isOnline()&&plugin.getServer().getPlayer(p.getUniqueId())==p;}
    private boolean visible(CommandSender viewer,Player target){
        if(target==null)return false;
        if(messaging()!=null)return messaging().messaging().visibleTo(viewer,target);
        if(viewer instanceof Player p) {
            if(!p.canSee(target))return false;
            if(!p.hasPermission("allium.vanish.see")&&target.getMetadata("vanished").stream().anyMatch(org.bukkit.metadata.MetadataValue::asBoolean))return false;
        }
        return true;
    }
    @Nullable private PreferencesModule preferences(){return plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule p&&p.isEnabled()?p:null;}
    @Nullable private MessagingModule messaging(){return plugin.getModuleManager().getModule("messaging") instanceof MessagingModule m&&m.isEnabled()?m:null;}
    public Player find(CommandSender viewer,String name){
        Player exact=plugin.getServer().getPlayerExact(name);if(exact!=null)return visible(viewer,exact)?exact:null;
        Player found=null;
        for(Player p:plugin.getServer().getOnlinePlayers())if(p.getName().toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))&&visible(viewer,p)){
            if(found!=null)return null;found=p;
        }
        return found;
    }
    private SavedLocation saved(Location p){return new SavedLocation(p.getWorld().getUID(),p.getWorld().getName(),p.getX(),p.getY(),p.getZ(),p.getYaw(),p.getPitch());}
    private Location location(SavedLocation p){var w=plugin.getServer().getWorld(p.worldId());return w==null?null:new Location(w,p.x(),p.y(),p.z(),p.yaw(),p.pitch());}
    private void tell(UUID player,Messages key){Player p=plugin.getServer().getPlayer(player);if(p!=null)send(p,key);}
    private void send(CommandSender player,Messages key,String... replacements){
        if(!BukkitServerThread.isFolia()){
            if(!(player instanceof Player p)||samePlayer(p))plugin.getMessageManager().send(player,key,replacements);
            return;
        }
        onOwner(player,()->{if(!(player instanceof Player p)||samePlayer(p))plugin.getMessageManager().send(player,key,replacements);});
    }

    /** Returns {@code true} while {@code playerId} is within the post-teleport PvP grace window. */
    public boolean hasArrivalPvpGrace(UUID playerId) {
        Long until = arrivalPvpGraceUntil.get(playerId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            arrivalPvpGraceUntil.remove(playerId, until);
            return false;
        }
        return true;
    }

    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){joined(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){
        Player player=e.getPlayer();
        saveLogout(player);offlineLookups.remove(player.getUniqueId());selectorConfirmations.remove(player.getUniqueId());arrivalPvpGraceUntil.remove(player.getUniqueId());
        for(var op:List.copyOf(operations.values()))if(op.actor()==player||op.session().player==player||op.anchor()==player||op.request()!=null
                &&(op.request().requester().equals(player.getUniqueId())||op.request().recipient().equals(player.getUniqueId())))interrupt(op,"player went offline");
        requests.cancelPlayer(player.getUniqueId());Session s=sessions.get(player.getUniqueId());if(s!=null&&s.player==player)sessions.remove(player.getUniqueId());
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void guardArrivalPvp(org.bukkit.event.entity.EntityDamageByEntityEvent e){
        if(!(e.getEntity() instanceof Player victim))return;
        Player attacker=resolvePlayerAttacker(e.getDamager());
        if(attacker==null||attacker.getUniqueId().equals(victim.getUniqueId()))return;
        if(hasArrivalPvpGrace(victim.getUniqueId())){
            e.setCancelled(true);
            send(attacker,Messages.TELEPORT_ARRIVAL_PVP_TARGET,"{player}",victim.getName());
        }else if(hasArrivalPvpGrace(attacker.getUniqueId())){
            e.setCancelled(true);
            send(attacker,Messages.TELEPORT_ARRIVAL_PVP_SELF);
        }
    }
    @Nullable private static Player resolvePlayerAttacker(Entity damager){
        if(damager instanceof Player player)return player;
        if(damager instanceof org.bukkit.entity.Projectile projectile&&projectile.getShooter() instanceof Player shooter)return shooter;
        if(damager instanceof org.bukkit.entity.AreaEffectCloud cloud&&cloud.getSource() instanceof Player source)return source;
        if(damager instanceof org.bukkit.entity.TNTPrimed tnt&&tnt.getSource() instanceof Player source)return source;
        return null;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        var op=operations.get(e.getPlayer().getUniqueId());Location to=e.getTo();
        if(op!=null&&!op.mayMove()&&to!=null&&(op.origin().getWorld()!=to.getWorld()||op.origin().distanceSquared(to)>op.rules().movementTolerance()*op.rules().movementTolerance()))interrupt(op,"movement");
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent e){
        if(e.getEntity() instanceof Player p&&e.getFinalDamage()>0){var op=operations.get(p.getUniqueId());if(op!=null)interrupt(op,"damage");}
    }
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true) public void guardWorldAccess(PlayerTeleportEvent e){
        Location to=e.getTo(),from=e.getFrom();
        if(to==null||to.getWorld()==null)return;
        if(from!=null&&to.getWorld().equals(from.getWorld()))return;
        if(!canEnterWorld(e.getPlayer(),to.getWorld())){
            e.setCancelled(true);
            send(e.getPlayer(),Messages.WORLD_ACCESS_DENIED,"{world}",to.getWorld().getName());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR) public void worldLoaded(WorldLoadEvent e){registerWorldPermission(e.getWorld());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void externalTeleport(PlayerTeleportEvent e){
        if(!committing.contains(e.getPlayer().getUniqueId())){var op=operations.get(e.getPlayer().getUniqueId());if(op!=null)interrupt(op,"another teleport");}
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent e){
        Player p=e.getEntity();arrivalPvpGraceUntil.remove(p.getUniqueId());var op=operations.get(p.getUniqueId());if(op!=null)interrupt(op,"death");
        boolean hasDeathPerm=p.hasPermission(PERMISSION_BACK_ON_DEATH);
        if(settings.deathBack()||hasDeathPerm){
            Session s=sessions.get(p.getUniqueId());if(s!=null){
                s.back=saved(p.getLocation());
                s.backIsDeath=true;
                saveHistory(s,s.back,"death",0,0,()->{});
                if(hasDeathPerm&&p.hasPermission("allium.back")){
                    send(p,Messages.TELEPORT_BACK_ON_DEATH);
                }
            }
        }
    }
}
