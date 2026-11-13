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
        if(command.equals("tptoggle")){
            if(args.length>2||args.length>0&&!args[0].equalsIgnoreCase("on")&&!args[0].equalsIgnoreCase("off")){context.sendInvalidArgs(spec.selfUsage());return;}
            Player target=args.length==2?target(sender,args[1]):sender instanceof Player p?p:null;
            if(target==null){if(args.length<2)context.sendInvalidArgs(spec.selfUsage());return;}
            service.toggle(sender,target,args.length==0?null:args[0].equalsIgnoreCase("on"));return;
        }
        if(command.equals("tp")){
            if(args.length<1||args.length>2||!(sender instanceof Player)&&args.length!=2){context.sendInvalidArgs(spec.selfUsage());return;}
            Player mover=args.length==1?(Player)sender:target(sender,args[0]);
            Player to=target(sender,args[args.length-1]);
            if(mover!=null&&to!=null){
                if(mover!=sender&&!sender.hasPermission("allium.tp.others")){plugin.getMessageManager().send(sender,Messages.NO_PERMISSION);return;}
                service.force(sender,mover,to,"tp");
            }return;
        }
        if(!(sender instanceof Player player)){plugin.getMessageManager().send(sender,Messages.PLAYER_ONLY);return;}
        switch(command){
            case "tpa","tpahere","tphere" -> {
                if(args.length!=1){context.sendInvalidArgs(spec.selfUsage());return;}
                Player to=target(sender,args[0]);if(to==null)return;
                if(command.equals("tphere"))service.force(sender,to,player,command);
                else service.request(player,to,command.equals("tpa")?RequestTracker.Kind.TO_TARGET:RequestTracker.Kind.HERE);
            }
            case "tpaccept","tpdeny" -> {
                if(args.length>1){context.sendInvalidArgs(spec.selfUsage());return;}
                Player from=args.length==0?null:target(sender,args[0]);if(args.length>0&&from==null)return;
                if(command.equals("tpaccept"))service.accept(player,from);else service.deny(player,from);
            }
            default -> {
                if(args.length!=0){context.sendInvalidArgs(spec.selfUsage());return;}
                switch(command){case "tpacancel"->service.cancel(player);case "back"->service.back(player);case "spawn"->service.spawn(player);case "setspawn"->service.setSpawn(player);default->throw new IllegalStateException(command);}
            }
        }
    }
    private Player target(CommandSender viewer,String name){Player p=service.find(viewer,name);if(p==null)plugin.getMessageManager().send(viewer,Messages.PLAYER_NOT_FOUND,"{player}",codes.castled.allium.messaging.MessagingService.safeName(name));return p;}
}
