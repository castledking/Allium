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
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Server-thread-owned teleport pipeline. Only this class commits an Allium teleport. */
public final class TeleportService implements Listener {
    private static final Map<PluginStart,TeleportService> OWNERS=new WeakHashMap<>();
    public static final String FAULT_WRITE_FAIL="teleport.write-fail",FAULT_LOAD_FAIL="teleport.load-fail";
    public static final UUID SERVER=new UUID(0,0);
    private final PluginStart plugin;
    private final TeleportRepository repository;
    private final ThreadPoolExecutor io;
    private final DestinationSafety safety;
    private final RequestTracker requests=new RequestTracker();
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Map<UUID,Operation> operations=new HashMap<>();
    private final Map<UUID,HistoryWrite> pendingWrites=new ConcurrentHashMap<>();
    private final Set<HistoryWrite> queuedWrites=ConcurrentHashMap.newKeySet();
    private final Set<UUID> committing=new HashSet<>();
    private volatile TeleportSettings settings;
    private SavedLocation spawn;
    private boolean spawnReady;
    private boolean closed;
    private TaskHandle timer;

    private static final class Session {
        final Player player;
        SavedLocation back;
        final Map<String,Long> cooldowns=new HashMap<>();
        boolean loaded;
        long historyRevision;
        Session(Player p){player=p;}
    }
    private record Operation(Session session,CommandSender actor,String command,Supplier<Location> destination,
                             @Nullable Player anchor,@Nullable RequestTracker.Request request,boolean force,
                             Location origin,long due,TeleportSettings rules,BooleanSupplier policy) {}
    private record HistoryWrite(UUID owner,Session session,SavedLocation origin,String command,long until,
                                Map<String,Long> cooldowns,Runnable success) {}

    public TeleportService(PluginStart plugin,TeleportRepository repository,ThreadPoolExecutor io) {
        this(plugin,repository,io,new DestinationSafety());
    }
    TeleportService(PluginStart plugin,TeleportRepository repository,ThreadPoolExecutor io,DestinationSafety safety) {
        this.plugin=plugin;this.repository=repository;this.io=io;
        this.safety=safety;
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
        loadSpawn(0);
        for(Player p:plugin.getServer().getOnlinePlayers())joined(p);
        timer=plugin.getScheduler().repeatOnServer(()->{
            long now=System.currentTimeMillis();
            for(var request:requests.expire(now)) tell(request.requester(),Messages.TELEPORT_CANCELLED);
            for(var operation:List.copyOf(operations.values()))if(now>=operation.due())complete(operation);
            return true;
        },50,50);
    }
    public void disable() {
        closed=true;
        if(timer!=null)timer.cancel();
        HandlerList.unregisterAll(this);
        requests.expire(Long.MAX_VALUE);operations.clear();sessions.clear();
        // Retry records that exhausted their live attempts before draining the worker. Retries
        // belong to the worker, so shutdown never depends on another server tick.
        for(HistoryWrite write:pendingWrites.values())queueHistory(write);
        io.shutdown();
        try {
            if(!io.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS))plugin.logger().warning("Teleport shutdown timed out; pending histories: "+pendingWrites.keySet());
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        for(HistoryWrite write:pendingWrites.values())plugin.logger().severe("Teleport history not saved at shutdown: owner="+write.owner()+" command="+write.command()+" origin="+write.origin()+" cooldowns="+write.cooldowns());
    }
    public boolean reloadSettings() {
        try { settings=TeleportSettings.read(plugin.getConfig());return true; }
        catch(IllegalArgumentException e){plugin.logger().warning("Kept previous teleport settings: "+e.getMessage());return false;}
    }
    public boolean ready(UUID id){Session s=sessions.get(id);return s!=null&&s.loaded&&!pendingWrites.containsKey(id);}
    public boolean warming(UUID id){return operations.containsKey(id);}
    public boolean historyPending(UUID id){return pendingWrites.containsKey(id);}
    public int pendingRequests(){return requests.size();}

    public void request(Player sender,Player recipient,RequestTracker.Kind kind) {
        if(!sender.hasPermission(kind==RequestTracker.Kind.TO_TARGET?"allium.tpa":"allium.tpahere")){send(sender,Messages.NO_PERMISSION);return;}
        if(!allowed(sender,recipient,sender,false,true)||!available(sender))return;
        requests.expire(System.currentTimeMillis());
        var request=new RequestTracker.Request(sender.getUniqueId(),recipient.getUniqueId(),kind,System.currentTimeMillis()+settings.requestExpiryMillis());
        try{requests.put(request);}catch(IllegalStateException full){send(sender,Messages.TELEPORT_BUSY);return;}
        send(sender,Messages.TELEPORT_REQUEST_SENT,"{player}",recipient.getName());
        send(recipient,kind==RequestTracker.Kind.HERE?Messages.TELEPORT_HERE_RECEIVED:Messages.TELEPORT_REQUEST_RECEIVED,"{player}",sender.getName());
    }
    public void accept(Player recipient,@Nullable Player requester) {
        if(!recipient.hasPermission("allium.tpaccept")){send(recipient,Messages.NO_PERMISSION);return;}
        var found=requests.incoming(recipient.getUniqueId(),requester==null?null:requester.getUniqueId(),System.currentTimeMillis());
        if(found.isEmpty()){send(recipient,Messages.TELEPORT_NO_PENDING);return;}
        var request=found.get();
        Player sender=plugin.getServer().getPlayer(request.requester());
        if(sender==null||!allowed(sender,recipient,sender,false,true)){requests.remove(request.requester());return;}
        Player mover=request.kind()==RequestTracker.Kind.TO_TARGET?sender:recipient;
        Player anchor=request.kind()==RequestTracker.Kind.TO_TARGET?recipient:sender;
        if(begin(mover,sender,request.kind()==RequestTracker.Kind.TO_TARGET?"tpa":"tpahere",anchor::getLocation,anchor,request,false)) {
            requests.remove(request.requester());send(recipient,Messages.TELEPORT_ACCEPTED);
        }
    }
    public void deny(Player recipient,@Nullable Player requester) {
        if(!recipient.hasPermission("allium.tpdeny")){send(recipient,Messages.NO_PERMISSION);return;}
        var found=requests.incoming(recipient.getUniqueId(),requester==null?null:requester.getUniqueId(),System.currentTimeMillis());
        if(found.isEmpty()){send(recipient,Messages.TELEPORT_NO_PENDING);return;}
        requests.remove(found.get().requester());send(recipient,Messages.TELEPORT_DENIED);
        tell(found.get().requester(),Messages.TELEPORT_DENIED);
    }
    public void cancel(Player sender) {
        if(!sender.hasPermission("allium.tpacancel")){send(sender,Messages.NO_PERMISSION);return;}
        boolean any=requests.remove(sender.getUniqueId()).isPresent();
        for(var operation:List.copyOf(operations.values()))if(operation.session().player==sender
                ||operation.request()!=null&&operation.request().requester().equals(sender.getUniqueId())) {
            interrupt(operation,"cancelled");any=true;
        }
        send(sender,any?Messages.TELEPORT_REQUEST_CANCELLED:Messages.TELEPORT_NO_PENDING);
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
    public void spawn(Player player) {
        if(!spawnReady){send(player,Messages.TELEPORT_LOADING);return;}
        Location destination=spawn==null?plugin.getServer().getWorlds().get(0).getSpawnLocation():location(spawn);
        if(destination==null){send(player,Messages.TELEPORT_UNSAFE);return;}
        begin(player,player,"spawn",()->destination.clone(),null,null,false);
    }
    public void setSpawn(Player player) {
        if(!player.hasPermission("allium.setspawn")){send(player,Messages.NO_PERMISSION);return;}
        SavedLocation point=saved(player.getLocation());
        submit(()->{
            try{checkWrite();repository.save(SERVER,"spawn",point);onServer(()->{spawn=point;spawnReady=true;send(player,Messages.SPAWN_SET);});}
            catch(SQLException e){onServer(()->send(player,Messages.TELEPORT_STORAGE_FAILED));}
        },()->send(player,Messages.TELEPORT_STORAGE_FAILED));
    }
    public void force(CommandSender actor,Player mover,Player target,String command) {
        boolean allowed=actor.hasPermission("allium."+command)&&(!(actor instanceof Player)||command.equals("tphere")||mover==actor||actor.hasPermission("allium.tp.others"))&&visible(actor,mover)&&visible(actor,target)
                &&allowed(mover,target,actor,true,false)&&allowed(target,mover,actor,true,false);
        plugin.logger().info("Teleport audit actor="+actor.getName()+" mover="+mover.getUniqueId()+" target="+target.getUniqueId()
                +" command="+command+" result="+(allowed?"authorized":"denied")+" reason="+(allowed?"explicit-force-permission":"permission-or-recipient-policy")+" authorized="+allowed+" toggleBypass="+actor.hasPermission("allium.teleport.bypass.toggle"));
        if(!allowed){send(actor,Messages.TELEPORT_REFUSED,"{player}",target.getName());return;}
        begin(mover,actor,command,target::getLocation,target,null,true);
    }
    /** Entry for future home/warp/kit consumers: same safety, warmup, cooldown and history pipeline. */
    public boolean teleport(Player mover,Location destination,String command) {
        if(!command.matches("[a-z]+"))throw new IllegalArgumentException("canonical teleport command required");
        Location snapshot=destination.clone();
        return begin(mover,mover,command,()->snapshot.clone(),null,null,false);
    }
    /** Homes/warps supply a server-thread policy and destination rechecked at physical commit. */
    public boolean teleport(Player mover,Supplier<Location> destination,String command,BooleanSupplier policy) {
        if(!command.matches("[a-z]+"))throw new IllegalArgumentException("canonical teleport command required");
        return begin(mover,mover,command,destination,null,null,false,Objects.requireNonNull(policy));
    }
    private boolean begin(Player mover,CommandSender actor,String command,Supplier<Location> destination,@Nullable Player anchor,
                          @Nullable RequestTracker.Request request,boolean force) {
        return begin(mover,actor,command,destination,anchor,request,force,()->true);
    }
    private boolean begin(Player mover,CommandSender actor,String command,Supplier<Location> destination,@Nullable Player anchor,
                          @Nullable RequestTracker.Request request,boolean force,BooleanSupplier policy) {
        if(!actor.hasPermission("allium."+command)){send(actor,Messages.NO_PERMISSION);return false;}
        if(!available(mover))return false;
        if(!policy.getAsBoolean()){send(actor,Messages.TELEPORT_INTERRUPTED,"{reason}","destination is no longer available");return false;}
        if(operations.containsKey(mover.getUniqueId())){send(actor,Messages.TELEPORT_BUSY);return false;}
        Session session=sessions.get(mover.getUniqueId());
        long now=System.currentTimeMillis();
        if(!actor.hasPermission("allium.teleport.bypass.cooldown")&&session.cooldowns.getOrDefault(command,0L)>now){
            send(actor,Messages.COOLDOWN_ACTIVE,"{time}",Long.toString((session.cooldowns.get(command)-now+999)/1000)+"s");return false;
        }
        TeleportSettings rules=settings;
        var checked=safety.resolve(destination.get(),rules);
        if(checked.isEmpty()){send(actor,Messages.TELEPORT_UNSAFE);return false;}
        long warmup=actor.hasPermission("allium.teleport.bypass.warmup")?0:rules.warmupMillis();
        var operation=new Operation(session,actor,command,destination,anchor,request,force,mover.getLocation().clone(),now+warmup,rules,policy);
        operations.put(mover.getUniqueId(),operation);
        if(warmup==0)complete(operation);
        else send(mover,Messages.TELEPORT_WARMUP,"{time}",Double.toString(warmup/1000.0));
        return true;
    }
    private void complete(Operation operation) {
        UUID id=operation.session().player.getUniqueId();
        if(operations.get(id)!=operation)return;
        Player mover=operation.session().player;
        if(!operation.policy().getAsBoolean()){interrupt(operation,"destination is no longer available");return;}
        if(operation.actor() instanceof Player actor&&!samePlayer(actor)){interrupt(operation,"issuing player went offline");return;}
        if(!operation.actor().hasPermission("allium."+operation.command())) { interrupt(operation,"permission changed");return; }
        if(!operation.actor().hasPermission("allium.teleport.bypass.cooldown")
                &&operation.session().cooldowns.getOrDefault(operation.command(),0L)>System.currentTimeMillis()) { interrupt(operation,"cooldown changed");return; }
        if(!same(operation.session())||operation.anchor()!=null&&!samePlayer(operation.anchor())){interrupt(operation,"player went offline");return;}
        if(operation.request()!=null) {
            var request=operation.request();Player sender=plugin.getServer().getPlayer(request.requester());Player receiver=plugin.getServer().getPlayer(request.recipient());
            if(sender==null||receiver==null||!receiver.hasPermission("allium.tpaccept")||!allowed(sender,receiver,sender,false,false)){interrupt(operation,"request policy changed");return;}
        }
        if(operation.force()&&((operation.command().equals("tp")&&operation.actor() instanceof Player&&mover!=operation.actor()&&!operation.actor().hasPermission("allium.tp.others"))||!visible(operation.actor(),mover)||!visible(operation.actor(),operation.anchor())
                ||!allowed(mover,operation.anchor(),operation.actor(),true,false)||!allowed(operation.anchor(),mover,operation.actor(),true,false))) {
            interrupt(operation,"teleport policy changed");return;
        }
        var destination=safety.resolve(operation.destination().get(),operation.rules());
        if(destination.isEmpty()){operations.remove(id);send(operation.actor(),Messages.TELEPORT_UNSAFE);return;}
        Location origin=mover.getLocation().clone();
        operations.remove(id);committing.add(id);
        boolean success;
        try{success=mover.teleport(destination.get(),PlayerTeleportEvent.TeleportCause.PLUGIN);}
        finally{committing.remove(id);}
        if(!success){send(operation.actor(),Messages.TELEPORT_INTERRUPTED,"{reason}","cancelled by another plugin");return;}
        if(operation.force())plugin.logger().info("Teleport audit completed actor="+operation.actor().getName()+" mover="+id
                +" target="+operation.anchor().getUniqueId()+" command="+operation.command()+" result=completed reason=bukkit-teleport-success world="+mover.getWorld().getUID());
        Session session=operation.session();session.back=saved(origin);
        long until=System.currentTimeMillis()+(operation.actor().hasPermission("allium.teleport.bypass.cooldown")?0:operation.rules().cooldown(operation.command()));
        session.cooldowns.put(operation.command(),until);
        saveHistory(session,session.back,operation.command(),until,0,()->{
            send(mover,Messages.TELEPORT_SUCCESS,"{target}",operation.anchor()==null?operation.command():operation.anchor().getName());
        });
    }
    private boolean allowed(Player requester,Player recipient,CommandSender actor,boolean force,boolean notify) {
        Messages refusal=null;
        if(!samePlayer(requester)||!samePlayer(recipient)||!visible(actor,recipient)||!force&&!visible(recipient,requester))refusal=Messages.PLAYER_NOT_FOUND;
        else if(requester==recipient)refusal=Messages.TELEPORT_SELF;
        else {
            PreferencesModule prefs=preferences();
            PreferenceSessions.Acceptance acceptance=prefs==null?PreferenceSessions.Acceptance.UNAVAILABLE:prefs.acceptance(recipient.getUniqueId(),PreferenceKey.ACCEPT_TELEPORT_REQUESTS);
            if(acceptance==PreferenceSessions.Acceptance.UNAVAILABLE)refusal=Messages.PREFERENCES_UNAVAILABLE;
            else if(acceptance==PreferenceSessions.Acceptance.REFUSES&&!(force&&actor.hasPermission("allium.teleport.bypass.toggle")))refusal=Messages.TELEPORT_REFUSED;
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
    private void interrupt(Operation operation,String reason) {
        if(operations.remove(operation.session().player.getUniqueId(),operation))send(operation.session().player,Messages.TELEPORT_INTERRUPTED,"{reason}",reason);
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
                        onServer(()->{if(same(write.session()))write.success().run();});return;
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
        Session session=new Session(player);sessions.put(player.getUniqueId(),session);
        HistoryWrite pending=pendingWrites.get(player.getUniqueId());if(pending!=null)queueHistory(pending);
        load(session,0);
    }
    private void load(Session session,int attempt) {
        if(!same(session))return;
        HistoryWrite pending=pendingWrites.get(session.player.getUniqueId());
        if(pending!=null){
            if(queuedWrites.contains(pending))plugin.getScheduler().runOnServerLater(()->load(session,attempt),250);
            return; // Exhausted writes keep this connection unavailable; never reload stale SQL.
        }
        long revision=session.historyRevision;
        submit(()->{
            try{
                if(FaultInjection.trip(FAULT_LOAD_FAIL))throw new SQLException("injected teleport load failure");
                var back=repository.load(session.player.getUniqueId(),"back");var cooldowns=repository.loadCooldowns(session.player.getUniqueId());
                onServer(()->{if(same(session)){
                    if(session.historyRevision!=revision){load(session,attempt);return;}
                    session.back=back.orElse(null);session.cooldowns.putAll(cooldowns);session.loaded=true;
                }});
            }catch(SQLException|RuntimeException e){onServer(()->retryLoad(session,attempt));}
        },()->retryLoad(session,attempt));
    }
    private void retryLoad(Session session,int attempt) {
        if(closed||!same(session))return;
        if(attempt<3)plugin.getScheduler().runOnServerLater(()->load(session,attempt+1),250L*(attempt+1));
        else plugin.logger().warning("Teleport data unavailable for "+session.player.getUniqueId()+" after bounded retries");
    }
    private void loadSpawn(int attempt) {
        if(closed)return;
        submit(()->{try{var point=repository.load(SERVER,"spawn");onServer(()->{spawn=point.orElse(null);spawnReady=true;});}
            catch(SQLException|RuntimeException e){onServer(()->retrySpawn(attempt));}},()->retrySpawn(attempt));
    }
    private void retrySpawn(int attempt) {
        if(closed)return;
        if(attempt<3)plugin.getScheduler().runOnServerLater(()->loadSpawn(attempt+1),250L*(attempt+1));
        else plugin.logger().warning("Teleport spawn unavailable after bounded retries");
    }
    private void submit(Runnable work,Runnable rejected) {
        try{io.execute(work);}catch(RejectedExecutionException e){rejected.run();}
    }
    private void checkWrite() throws SQLException {if(FaultInjection.trip(FAULT_WRITE_FAIL))throw new SQLException("injected teleport write failure");}
    private void onServer(Runnable task){if(!closed)plugin.getScheduler().runOnServer(()->{if(!closed)task.run();});}
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
    private void send(CommandSender player,Messages key,String... replacements){if(!(player instanceof Player p)||samePlayer(p))plugin.getMessageManager().send(player,key,replacements);}

    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){joined(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){
        Player player=e.getPlayer();
        for(var op:List.copyOf(operations.values()))if(op.actor()==player||op.session().player==player||op.anchor()==player||op.request()!=null
                &&(op.request().requester().equals(player.getUniqueId())||op.request().recipient().equals(player.getUniqueId())))interrupt(op,"player went offline");
        requests.cancelPlayer(player.getUniqueId());Session s=sessions.get(player.getUniqueId());if(s!=null&&s.player==player)sessions.remove(player.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        var op=operations.get(e.getPlayer().getUniqueId());Location to=e.getTo();
        if(op!=null&&to!=null&&(op.origin().getWorld()!=to.getWorld()||op.origin().distanceSquared(to)>op.rules().movementTolerance()*op.rules().movementTolerance()))interrupt(op,"movement");
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent e){
        if(e.getEntity() instanceof Player p&&e.getFinalDamage()>0){var op=operations.get(p.getUniqueId());if(op!=null)interrupt(op,"damage");}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void externalTeleport(PlayerTeleportEvent e){
        if(!committing.contains(e.getPlayer().getUniqueId())){var op=operations.get(e.getPlayer().getUniqueId());if(op!=null)interrupt(op,"another teleport");}
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent e){
        Player p=e.getEntity();var op=operations.get(p.getUniqueId());if(op!=null)interrupt(op,"death");
        if(settings.deathBack()){
            Session s=sessions.get(p.getUniqueId());if(s!=null){
                s.back=saved(p.getLocation());saveHistory(s,s.back,"death",0,0,()->{});
            }
        }
    }
}
