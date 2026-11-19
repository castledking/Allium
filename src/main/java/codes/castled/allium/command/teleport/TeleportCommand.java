package codes.castled.allium.command.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.*;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.teleport.RequestTracker;
import codes.castled.allium.teleport.TeleportService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Parsing only; authorization and teleport policy are rechecked by the service. */
public final class TeleportCommand implements SpecCommandHandler {
    private final PluginStart plugin;private final TeleportService service;private final CommandSpec spec;
    public TeleportCommand(PluginStart plugin,TeleportService service,CommandSpec spec){this.plugin=plugin;this.service=service;this.spec=spec;}
    @Override public CommandSpec spec(){return spec;}
    @Override public void execute(BukkitCommandContext context){
        String[] args=context.args();String command=spec.canonicalName();CommandSender sender=context.sender();
        if(command.equals("tppos")||command.equals("tp")&&codes.castled.allium.teleport.CoordinateArgs.startsWithPosition(java.util.Arrays.asList(args))){position(context);return;}
        if(java.util.Set.of("tp","tphere","there","tppet","tpent").contains(command)&&!(command.equals("tp")&&java.util.Arrays.stream(args).noneMatch(a->a.startsWith("@")))){extended(context);return;}
        if(command.equals("tptoggle")){
            if(args.length>2||args.length>0&&!args[0].equalsIgnoreCase("on")&&!args[0].equalsIgnoreCase("off")){context.sendInvalidArgs(spec.selfUsage());return;}
            Player target=args.length==2?target(sender,args[1]):sender instanceof Player p?p:null;
            if(target==null){if(args.length<2)context.sendInvalidArgs(spec.selfUsage());return;}
            service.toggle(sender,target,args.length==0?null:args[0].equalsIgnoreCase("on"));return;
        }
        if(command.equals("tpall")){
            if(args.length>1||args.length==0&&!(sender instanceof Player)){context.sendInvalidArgs(spec.selfUsage());return;}
            Player anchor=args.length==0?(Player)sender:target(sender,args[0]);
            if(anchor!=null)service.forceAll(sender,anchor);
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
                switch(command){case "tpacancel"->service.cancel(player);case "back"->service.back(player);case "spawn"->service.spawn(player);case "setspawn"->service.setSpawn(player);
                    case "top","bottom"->{
                        var to=command.equals("top")?codes.castled.allium.teleport.VerticalTeleports.top(player):codes.castled.allium.teleport.VerticalTeleports.bottom(player);
                        if(to.isEmpty()){plugin.getMessageManager().send(player,command.equals("top")?Messages.TELEPORT_TOP_NONE:Messages.TELEPORT_BOTTOM_NONE);return;}
                        service.teleport(player,to.get(),command);
                    }
                    default->throw new IllegalStateException(command);}
            }
        }
    }
    private void extended(BukkitCommandContext context){
        var actor=context.sender();String command=spec.canonicalName();String[] args=context.args();var resolver=new codes.castled.allium.teleport.TeleportTargets(plugin);
        try{
            var module=plugin.getModuleManager()==null?null:plugin.getModuleManager().getModule("teleport");
            var companions=module instanceof codes.castled.allium.module.TeleportModule tm?tm.companions():null;
            if(command.equals("tppet")||command.equals("tpent")){if(args.length>1){context.sendInvalidArgs(spec.selfUsage());return;}if(companions==null){plugin.getMessageManager().send(actor,Messages.MODULE_DISABLED);return;}Player target=args.length==0?actor instanceof Player p?p:null:target(actor,args[0]);if(target!=null)companions.toggle(actor,target,command.equals("tppet"));else plugin.getMessageManager().send(actor,Messages.PLAYER_ONLY);return;}
            if(command.equals("tp")){
                if(args.length<1||args.length>2||args.length==1&&!(actor instanceof Player)){context.sendInvalidArgs(spec.selfUsage());return;}
                var destinations=resolver.resolve(actor,args[args.length-1]);if(destinations.size()!=1||!(destinations.get(0) instanceof Player anchor))throw new IllegalArgumentException("The destination must resolve to one visible player.");
                var movers=args.length==1?java.util.List.<org.bukkit.entity.Entity>of((Player)actor):resolver.resolve(actor,args[0]);
                if(movers.stream().anyMatch(e->!(e instanceof Player)))throw new IllegalArgumentException("/tp source selector must resolve to players.");
                if(movers.stream().anyMatch(e->e!=actor)&&!actor.hasPermission("allium.tp.others"))throw new SecurityException("allium.tp.others");
                for(var e:movers)service.force(actor,(Player)e,anchor,"tp");return;
            }
            if(!(actor instanceof Player player)){plugin.getMessageManager().send(actor,Messages.PLAYER_ONLY);return;}
            org.bukkit.Location destination=player.getLocation();if(command.equals("there")){var hit=player.rayTraceBlocks(128,org.bukkit.FluidCollisionMode.NEVER);if(hit==null||hit.getHitBlock()==null)throw new IllegalArgumentException("No block in sight.");destination=hit.getHitBlock().getRelative(hit.getHitBlockFace()==null?org.bukkit.block.BlockFace.UP:hit.getHitBlockFace()).getLocation().add(.5,0,.5);destination.setYaw(player.getLocation().getYaw());destination.setPitch(player.getLocation().getPitch());}
            if(args.length==0){if(companions==null||!companions.move(player,destination))service.forceLocation(actor,player,destination,command);return;}
            var rules=service.settings()!=null?service.settings():codes.castled.allium.teleport.TeleportSettings.read(plugin.getConfig());
            int maxTargets=rules.tphereMaxEntities();
            var targets=new java.util.LinkedHashMap<java.util.UUID,org.bukkit.entity.Entity>();
            boolean requiresConfirm=false;
            for(String arg:args){
                var res=resolver.resolveDetailed(actor,arg,maxTargets,player);
                if(res.matchedCount()>maxTargets){
                    plugin.getMessageManager().send(actor,Messages.TELEPORT_ENTITY_LIMIT_EXCEEDED,"{count}",Integer.toString(res.matchedCount()),"{limit}",Integer.toString(maxTargets));
                    return;
                }
                if(res.entitySelector())requiresConfirm=true;
                for(var e:res.targets()){
                    if(e!=player){
                        targets.put(e.getUniqueId(),e);
                        if(res.selector()&&!(e instanceof Player))requiresConfirm=true;
                    }
                }
            }
            if(targets.size()>maxTargets){
                plugin.getMessageManager().send(actor,Messages.TELEPORT_ENTITY_LIMIT_EXCEEDED,"{count}",Integer.toString(targets.size()),"{limit}",Integer.toString(maxTargets));
                return;
            }
            if(targets.isEmpty())throw new IllegalArgumentException("No visible targets found.");
            if(requiresConfirm&&rules.selectorConfirmMillis()>0){
                String signature=String.join(" ",args).toLowerCase(java.util.Locale.ROOT);
                if(!service.consumeSelectorConfirmation(player.getUniqueId(),command,signature)){
                    int count=targets.size();
                    String label=count==1?"entity":"entities";
                    String invoked="/"+context.label()+" "+String.join(" ",args);
                    long seconds=Math.max(1L,(rules.selectorConfirmMillis()+999L)/1000L);
                    plugin.getMessageManager().send(actor,Messages.TELEPORT_SELECTOR_CONFIRM,
                            "{count}",Integer.toString(count),
                            "{label}",label,
                            "{command}",invoked,
                            "{seconds}",Long.toString(seconds));
                    return;
                }
            }
            org.bukkit.Location safeEntityDest=null;
            int entitiesMoved=0;
            for(var e:targets.values()){
                if(e instanceof Player p){
                    if(command.equals("tphere"))service.force(actor,p,player,command);
                    else service.forceLocation(actor,p,destination,command);
                }else{
                    if(safeEntityDest==null){
                        var safe=new codes.castled.allium.teleport.DestinationSafety().resolve(destination,rules);
                        if(safe.isEmpty())throw new IllegalArgumentException("No safe destination.");
                        safeEntityDest=safe.get();
                    }
                    if(e.teleport(safeEntityDest))entitiesMoved++;
                }
            }
            if(entitiesMoved>0){
                plugin.getMessageManager().send(actor,Messages.TELEPORT_ENTITIES_MOVED,
                        "{count}",Integer.toString(entitiesMoved),
                        "{label}",entitiesMoved==1?"entity":"entities");
            }
        }catch(SecurityException denied){plugin.getMessageManager().send(actor,Messages.NO_PERMISSION);}catch(IllegalArgumentException invalid){plugin.getMessageManager().send(actor,Messages.TELEPORT_SELECTION,"{message}",invalid.getMessage());}
    }
    /** {@code /tp <x> <y> <z> [yaw] [pitch] [world]} (needs allium.tp.position) and {@code /tppos} with the same arguments. */
    private void position(BukkitCommandContext context){
        CommandSender sender=context.sender();String command=spec.canonicalName();String[] args=context.args();
        if(!(sender instanceof Player player)){plugin.getMessageManager().send(sender,Messages.PLAYER_ONLY);return;}
        if(command.equals("tp")&&!sender.hasPermission("allium.tp.position")){plugin.getMessageManager().send(sender,Messages.NO_PERMISSION);return;}
        if(args.length<3||args.length>6){context.sendInvalidArgs(command.equals("tp")?"/tp <x> <y> <z> [yaw] [pitch] [world]":spec.selfUsage());return;}
        org.bukkit.Location here=player.getLocation();codes.castled.allium.teleport.CoordinateArgs.Position at;
        try{at=codes.castled.allium.teleport.CoordinateArgs.parse(java.util.Arrays.asList(args),here.getX(),here.getY(),here.getZ(),here.getYaw(),here.getPitch());}
        catch(IllegalArgumentException invalid){plugin.getMessageManager().send(sender,Messages.TELEPORT_SELECTION,"{message}",invalid.getMessage());return;}
        org.bukkit.World world=at.world().isPresent()?plugin.getServer().getWorld(at.world().get()):here.getWorld();
        if(world==null){plugin.getMessageManager().send(sender,Messages.TELEPORT_SELECTION,"{message}","No world is named "+codes.castled.allium.messaging.MessagingService.safeName(at.world().get())+".");return;}
        service.forceLocation(sender,player,new org.bukkit.Location(world,at.x(),at.y(),at.z(),at.yaw(),at.pitch()),command);
    }
    private Player target(CommandSender viewer,String name){Player p=service.find(viewer,name);if(p==null)plugin.getMessageManager().send(viewer,Messages.PLAYER_NOT_FOUND,"{player}",codes.castled.allium.messaging.MessagingService.safeName(name));return p;}
}
