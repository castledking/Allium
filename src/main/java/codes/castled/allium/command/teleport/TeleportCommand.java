package codes.castled.allium.command.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.*;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.teleport.DestinationSafety;
import codes.castled.allium.teleport.RequestTracker;
import codes.castled.allium.teleport.TeleportSettings;
import codes.castled.allium.teleport.TeleportSight;
import codes.castled.allium.teleport.TeleportTargets;
import codes.castled.allium.teleport.TeleportService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Parsing only; authorization and teleport policy are rechecked by the service. */
public final class TeleportCommand implements SpecCommandHandler {
    private final PluginStart plugin;private final TeleportService service;private final CommandSpec spec;
    public TeleportCommand(PluginStart plugin,TeleportService service,CommandSpec spec){this.plugin=plugin;this.service=service;this.spec=spec;}
    @Override public CommandSpec spec(){return spec;}
    @Override public void execute(BukkitCommandContext context){
        String[] args=context.args();String command=spec.canonicalName();CommandSender sender=context.sender();
        if(command.equals("tppos")||(command.equals("tp")||command.equals("tpo"))&&codes.castled.allium.teleport.CoordinateArgs.startsWithPosition(java.util.Arrays.asList(args))){position(context);return;}
        if((command.equals("tp")||command.equals("tpo"))&&args.length>=4&&codes.castled.allium.teleport.CoordinateArgs.startsWithPosition(java.util.Arrays.asList(args).subList(1,args.length))){positionForTarget(context);return;}
        if(java.util.Set.of("tp","tphere","there","tppet","tpent").contains(command)&&!(command.equals("tp")&&java.util.Arrays.stream(args).noneMatch(a->a.startsWith("@")))){extended(context);return;}
        if(command.equals("tptoggle")||command.equals("tpauto")){
            if(args.length>2||args.length>0&&!args[0].equalsIgnoreCase("on")&&!args[0].equalsIgnoreCase("off")){context.sendInvalidArgs(spec.selfUsage());return;}
            Player target=args.length==2?target(sender,args[1]):sender instanceof Player p?p:null;
            if(target==null){if(args.length<2)context.sendInvalidArgs(spec.selfUsage());return;}
            Boolean state=args.length==0?null:args[0].equalsIgnoreCase("on");
            if(command.equals("tpauto"))service.toggleAuto(sender,target,state);else service.toggle(sender,target,state);
            return;
        }
        if(command.equals("tpall")||command.equals("tpaall")){
            if(args.length>1||args.length==0&&!(sender instanceof Player)){context.sendInvalidArgs(spec.selfUsage());return;}
            Player anchor=args.length==0?(Player)sender:target(sender,args[0]);
            if(anchor!=null){
                if(command.equals("tpaall"))service.requestAll(sender,anchor);
                else service.forceAll(sender,anchor);
            }
            return;
        }
        if(command.equals("tp")||command.equals("tpo")){
            if(args.length<1||args.length>2||!(sender instanceof Player)&&args.length!=2){context.sendInvalidArgs(spec.selfUsage());return;}
            Player mover=args.length==1?(Player)sender:target(sender,args[0]);
            Player to=target(sender,args[args.length-1]);
            if(mover!=null&&to!=null){
                if(command.equals("tp")&&mover!=sender&&!sender.hasPermission("allium.tp.others")){plugin.getMessageManager().send(sender,Messages.NO_PERMISSION);return;}
                service.force(sender,mover,to,command);
            }return;
        }
        if(!(sender instanceof Player player)){plugin.getMessageManager().send(sender,Messages.PLAYER_ONLY);return;}
        switch(command){
            case "setspawn" -> {
                if (args.length > 1) { context.sendInvalidArgs(spec.selfUsage()); return; }
                if (args.length == 0) service.setSpawn(player);
                else service.setSpawn(player, args[0]);
            }
            case "tpoffline" -> {if(args.length!=1){context.sendInvalidArgs(spec.selfUsage());return;}service.offline(player,args[0]);}
            case "tpa","tpahere","tphere","tpohere" -> {
                if(args.length!=1){context.sendInvalidArgs(spec.selfUsage());return;}
                Player to=target(sender,args[0]);if(to==null)return;
                if(command.equals("tphere")||command.equals("tpohere"))service.force(sender,to,player,command);
                else service.request(player,to,command.equals("tpa")?RequestTracker.Kind.TO_TARGET:RequestTracker.Kind.HERE);
            }
            case "tpaccept","tpdeny" -> {
                if(args.length>1){context.sendInvalidArgs(spec.selfUsage());return;}
                Player from=args.length==0?null:target(sender,args[0]);if(args.length>0&&from==null)return;
                if(command.equals("tpaccept"))service.accept(player,from);else service.deny(player,from);
            }
            default -> {
                if(args.length!=0){context.sendInvalidArgs(spec.selfUsage());return;}
                switch(command){case "tpacancel"->service.cancel(player);case "back"->service.back(player);case "spawn"->service.spawn(player);
                    case "jump"->service.jump(player);
                    case "top","bottom"->{
                        var to=command.equals("top")?codes.castled.allium.teleport.VerticalTeleports.top(player):codes.castled.allium.teleport.VerticalTeleports.bottom(player);
                        if(to.isEmpty()){plugin.getMessageManager().send(player,command.equals("top")?Messages.TELEPORT_TOP_NONE:Messages.TELEPORT_BOTTOM_NONE);return;}
                        service.teleport(player,to.get(),command);
                    }
                    default->throw new IllegalStateException(command);}
            }
        }
    }
    private void extended(BukkitCommandContext context) {
        var actor = context.sender();
        String command = spec.canonicalName();
        String[] args = context.args();
        var resolver = new TeleportTargets(plugin);
        try {
            var modules = plugin.getModuleManager();
            var module = modules == null ? null : modules.getModule("teleport");
            var companions = module instanceof codes.castled.allium.module.TeleportModule teleport ? teleport.companions() : null;
            if (command.equals("tppet") || command.equals("tpent")) {
                if (args.length > 1) context.sendInvalidArgs(spec.selfUsage());
                else if (companions == null) plugin.getMessageManager().send(actor, Messages.MODULE_DISABLED);
                else {
                    Player target = args.length == 0 ? actor instanceof Player player ? player : null : target(actor, args[0]);
                    if (target != null) companions.toggle(actor, target, command.equals("tppet"));
                    else plugin.getMessageManager().send(actor, Messages.PLAYER_ONLY);
                }
                return;
            }
            if (command.equals("tp")) {
                if (args.length < 1 || args.length > 2 || (args.length == 1 && !(actor instanceof Player))) {
                    context.sendInvalidArgs(spec.selfUsage());
                    return;
                }
                dispatch(actor, resolver.resolveAsync(actor, args[args.length - 1]), destinations -> {
                    if (destinations.size() != 1 || !(destinations.get(0) instanceof Player anchor)) {
                        throw new IllegalArgumentException("The destination must resolve to one visible player.");
                    }
                    if (args.length == 1) teleportPlayersTo(actor, args, List.of((Player) actor), anchor);
                    else dispatch(actor, resolver.resolveAsync(actor, args[0]), movers -> teleportPlayersTo(actor, args, movers, anchor));
                });
                return;
            }
            if (!(actor instanceof Player player)) {
                plugin.getMessageManager().send(actor, Messages.PLAYER_ONLY);
                return;
            }
            var destination = command.equals("there") ? TeleportSight.destination(plugin, player)
                    : CompletableFuture.completedFuture(player.getLocation());
            dispatch(actor, destination, location -> {
                if (location == null) throw new IllegalArgumentException("No block in sight.");
                if (args.length == 0) {
                    if (companions == null || !companions.move(player, location)) service.forceLocation(actor, player, location, command);
                    return;
                }
                var rules = service.settings() != null ? service.settings() : TeleportSettings.read(plugin.getConfig());
                dispatch(actor, collectSelections(resolver, player, args, rules.tphereMaxEntities()),
                        selections -> teleportSelection(context, player, location, rules, selections));
            });
        } catch (SecurityException | IllegalArgumentException failure) {
            reportError(actor, failure);
        }
    }

    private void teleportPlayersTo(CommandSender actor, String[] args, List<Entity> movers, Player anchor) {
        requireSelectionPermission(actor, args);
        if (movers.stream().anyMatch(entity -> !(entity instanceof Player))) {
            throw new IllegalArgumentException("/tp source selector must resolve to players.");
        }
        if (movers.stream().anyMatch(entity -> entity != actor) && !actor.hasPermission("allium.tp.others")) {
            throw new SecurityException("allium.tp.others");
        }
        for (Entity entity : movers) service.force(actor, (Player) entity, anchor, "tp");
    }

    private CompletableFuture<List<TeleportTargets.Resolution>> collectSelections(TeleportTargets resolver, Player player,
                                                                                 String[] args, int limit) {
        CompletableFuture<List<TeleportTargets.Resolution>> pending = CompletableFuture.completedFuture(new ArrayList<>());
        for (String arg : args) {
            pending = pending.thenCompose(results -> {
                if (!results.isEmpty() && results.get(results.size() - 1).matchedCount() > limit) {
                    return CompletableFuture.completedFuture(results);
                }
                return resolver.resolveDetailedAsync(player, arg, limit, player).thenApply(resolved -> {
                    results.add(resolved);
                    return results;
                });
            });
        }
        return pending;
    }

    private void teleportSelection(BukkitCommandContext context, Player actor, Location destination, TeleportSettings rules,
                                   List<TeleportTargets.Resolution> selections) {
        String[] args = context.args();
        String command = spec.canonicalName();
        int limit = rules.tphereMaxEntities();
        var targets = new LinkedHashMap<UUID, Entity>();
        boolean confirm = false;
        for (var result : selections) {
            if (result.matchedCount() > limit) {
                sendLimit(actor, result.matchedCount(), limit);
                return;
            }
            confirm |= result.entitySelector();
            for (Entity entity : result.targets()) {
                if (entity != actor) {
                    targets.put(entity.getUniqueId(), entity);
                    confirm |= result.selector() && !(entity instanceof Player);
                }
            }
        }
        if (targets.size() > limit) {
            sendLimit(actor, targets.size(), limit);
            return;
        }
        requireSelectionPermission(actor, args);
        if (targets.isEmpty()) throw new IllegalArgumentException("No visible targets found.");
        if (confirm && rules.selectorConfirmMillis() > 0) {
            String signature = String.join(" ", args).toLowerCase(Locale.ROOT);
            if (!service.consumeSelectorConfirmation(actor.getUniqueId(), command, signature)) {
                int count = targets.size();
                long seconds = Math.max(1L, (rules.selectorConfirmMillis() + 999L) / 1000L);
                plugin.getMessageManager().send(actor, Messages.TELEPORT_SELECTOR_CONFIRM,
                        "{count}", Integer.toString(count), "{label}", count == 1 ? "entity" : "entities",
                        "{command}", "/" + context.label() + " " + String.join(" ", args), "{seconds}", Long.toString(seconds));
                return;
            }
        }

        if (BukkitServerThread.isFolia()) {
            List<Entity> mobs = new ArrayList<>();
            for (Entity target : targets.values()) {
                if (target instanceof Player player) movePlayer(actor, player, command, destination);
                else mobs.add(target);
            }
            if (!mobs.isEmpty()) {
                dispatch(actor, service.moveSelectedEntities(actor, mobs, destination, rules, () -> {
                    requireSelectionPermission(actor, args);
                    return true;
                }), moved -> {
                    if (moved.isEmpty()) throw new IllegalArgumentException("No safe destination.");
                    reportMoved(actor, moved.getAsInt());
                });
            }
            return;
        }

        Location safeEntityDestination = null;
        int moved = 0;
        for (Entity target : targets.values()) {
            if (target instanceof Player player) movePlayer(actor, player, command, destination);
            else {
                if (safeEntityDestination == null) {
                    safeEntityDestination = new DestinationSafety().resolve(destination, rules)
                            .orElseThrow(() -> new IllegalArgumentException("No safe destination."));
                }
                if (target.teleport(safeEntityDestination)) moved++;
            }
        }
        reportMoved(actor, moved);
    }

    private void movePlayer(Player actor, Player target, String command, Location destination) {
        if (command.equals("tphere")) service.force(actor, target, actor, command);
        else service.forceLocation(actor, target, destination, command);
    }

    private void requireSelectionPermission(CommandSender actor, String[] tokens) {
        if (BukkitServerThread.isFolia() && spec.basePermission() != null && !actor.hasPermission(spec.basePermission())) {
            throw new SecurityException(spec.basePermission());
        }
        for (String token : tokens) TeleportTargets.requirePermission(actor, token);
    }

    private void sendLimit(Player actor, int count, int limit) {
        plugin.getMessageManager().send(actor, Messages.TELEPORT_ENTITY_LIMIT_EXCEEDED,
                "{count}", Integer.toString(count), "{limit}", Integer.toString(limit));
    }

    private void reportMoved(Player actor, int count) {
        if (count > 0) {
            plugin.getMessageManager().send(actor, Messages.TELEPORT_ENTITIES_MOVED,
                    "{count}", Integer.toString(count), "{label}", count == 1 ? "entity" : "entities");
        }
    }

    private <T> void dispatch(CommandSender sender, CompletableFuture<T> pending, Consumer<T> task) {
        pending.whenComplete((value, error) -> {
            if (error instanceof CancellationException) return;
            Runnable reply = () -> {
                if (BukkitServerThread.isFolia() && sender instanceof Player player && !player.isOnline()) return;
                if (error != null) reportError(sender, error);
                else {
                    try { task.accept(value); }
                    catch (RuntimeException failure) { reportError(sender, failure); }
                }
            };
            if (BukkitServerThread.isFolia()) BukkitServerThread.runFor(plugin, plugin.getScheduler(), sender, reply, () -> {}, 0);
            else reply.run();
        });
    }

    private void reportError(CommandSender sender, Throwable cause) {
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof CancellationException) return;
        if (cause instanceof SecurityException) plugin.getMessageManager().send(sender, Messages.NO_PERMISSION);
        else if (cause instanceof IllegalArgumentException) {
            plugin.getMessageManager().send(sender, Messages.TELEPORT_SELECTION, "{message}", cause.getMessage());
        } else {
            plugin.logger().warning("Teleport selector failed: " + cause);
            plugin.getMessageManager().send(sender, Messages.TELEPORT_SELECTION, "{message}", "Could not resolve the teleport.");
        }
    }

    /** {@code /tp <x> <y> <z> [yaw] [pitch] [world]} (needs allium.tp.position) and {@code /tppos} with the same arguments. */
    private void position(BukkitCommandContext context){
        CommandSender sender=context.sender();String command=spec.canonicalName();String[] args=context.args();
        if(!(sender instanceof Player player)){plugin.getMessageManager().send(sender,Messages.PLAYER_ONLY);return;}
        if(command.equals("tp")&&!sender.hasPermission("allium.tp.position")){plugin.getMessageManager().send(sender,Messages.NO_PERMISSION);return;}
        if(args.length<3||args.length>6){context.sendInvalidArgs(command.equals("tp")||command.equals("tpo")?"/"+command+" <x> <y> <z> [yaw] [pitch] [world]":spec.selfUsage());return;}
        org.bukkit.Location here=player.getLocation();codes.castled.allium.teleport.CoordinateArgs.Position at;
        try{at=codes.castled.allium.teleport.CoordinateArgs.parse(java.util.Arrays.asList(args),here.getX(),here.getY(),here.getZ(),here.getYaw(),here.getPitch());}
        catch(IllegalArgumentException invalid){plugin.getMessageManager().send(sender,Messages.TELEPORT_SELECTION,"{message}",invalid.getMessage());return;}
        org.bukkit.World world=at.world().isPresent()?plugin.getServer().getWorld(at.world().get()):here.getWorld();
        if(world==null){plugin.getMessageManager().send(sender,Messages.TELEPORT_SELECTION,"{message}","No world is named "+codes.castled.allium.messaging.MessagingService.safeName(at.world().get())+".");return;}
        service.forceLocation(sender,player,new org.bukkit.Location(world,at.x(),at.y(),at.z(),at.yaw(),at.pitch()),command);
    }
    /** {@code /tp <target|@s|selector> <x> <y> <z> [yaw] [pitch] [world]} (and {@code /tpo} with the same arguments). */
    private void positionForTarget(BukkitCommandContext context){
        CommandSender sender=context.sender();String command=spec.canonicalName();String[] args=context.args();
        if(args.length<4||args.length>7){context.sendInvalidArgs("/"+command+" <target> <x> <y> <z> [yaw] [pitch] [world]");return;}
        if(command.equals("tp")&&!sender.hasPermission("allium.tp.position")){plugin.getMessageManager().send(sender,Messages.NO_PERMISSION);return;}
        if (args[0].equalsIgnoreCase("@s")) {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().send(sender, Messages.PLAYER_ONLY);
                return;
            }
            positionForMovers(sender, command, args, List.of(player));
        } else if (args[0].startsWith("@")) {
            try {
                dispatch(sender, new TeleportTargets(plugin).resolveAsync(sender, args[0]), resolved -> {
                    requireSelectionPermission(sender, new String[]{args[0]});
                    if (command.equals("tp") && !sender.hasPermission("allium.tp.position")) throw new SecurityException("allium.tp.position");
                    if (resolved.isEmpty()) throw new IllegalArgumentException("No visible targets found.");
                    if (resolved.stream().anyMatch(entity -> !(entity instanceof Player))) {
                        throw new IllegalArgumentException("/tp source selector must resolve to players.");
                    }
                    positionForMovers(sender, command, args, resolved.stream().map(Player.class::cast).toList());
                });
            } catch (SecurityException | IllegalArgumentException failure) {
                reportError(sender, failure);
            }
        } else {
            Player mover = target(sender, args[0]);
            if (mover != null) positionForMovers(sender, command, args, List.of(mover));
        }
    }

    private void positionForMovers(CommandSender sender, String command, String[] args, List<Player> movers) {
        if(command.equals("tp")&&movers.stream().anyMatch(p->p!=sender)&&!sender.hasPermission("allium.tp.others")){plugin.getMessageManager().send(sender,Messages.NO_PERMISSION);return;}
        Player basePlayer=sender instanceof Player p?p:movers.get(0);
        if(BukkitServerThread.isFolia()&&sender!=basePlayer){
            BukkitServerThread.onOwner(plugin,plugin.getScheduler(),basePlayer,()->{
                if(!basePlayer.isOnline())return;
                var base=basePlayer.getLocation();
                BukkitServerThread.onOwner(plugin,plugin.getScheduler(),sender,()->positionForTargets(sender,command,args,movers,base));
            });
        }else positionForTargets(sender,command,args,movers,basePlayer.getLocation());
    }
    private void positionForTargets(CommandSender sender,String command,String[] args,java.util.List<Player> movers,org.bukkit.Location base){
        codes.castled.allium.teleport.CoordinateArgs.Position at;
        try{at=codes.castled.allium.teleport.CoordinateArgs.parse(java.util.Arrays.asList(args).subList(1,args.length),base.getX(),base.getY(),base.getZ(),base.getYaw(),base.getPitch());}
        catch(IllegalArgumentException invalid){plugin.getMessageManager().send(sender,Messages.TELEPORT_SELECTION,"{message}",invalid.getMessage());return;}
        org.bukkit.World world=at.world().isPresent()?plugin.getServer().getWorld(at.world().get()):base.getWorld();
        if(world==null){plugin.getMessageManager().send(sender,Messages.TELEPORT_SELECTION,"{message}","No world is named "+codes.castled.allium.messaging.MessagingService.safeName(at.world().get())+".");return;}
        org.bukkit.Location dest=new org.bukkit.Location(world,at.x(),at.y(),at.z(),at.yaw(),at.pitch());
        for(Player mover:movers)service.forceLocation(sender,mover,dest,command);
    }
    private Player target(CommandSender viewer,String name){Player p=service.find(viewer,name);if(p==null)plugin.getMessageManager().send(viewer,Messages.PLAYER_NOT_FOUND,"{player}",codes.castled.allium.messaging.MessagingService.safeName(name));return p;}
}
