package codes.castled.allium.command.travel;

import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.travel.TravelService;
import org.bukkit.entity.Player;

public final class TravelCommand implements SpecCommandHandler {
    private final TravelService service;
    private final CommandSpec spec;
    public TravelCommand(TravelService service,CommandSpec spec){this.service=service;this.spec=spec;}
    @Override public CommandSpec spec(){return spec;}
    @Override public void execute(BukkitCommandContext context) {
        if(!(context.sender() instanceof Player player)){context.plugin().getMessageManager().send(context.sender(),Messages.PLAYER_ONLY);return;}
        String command=spec.canonicalName();String[] args=context.args();boolean warp=command.contains("warp");
        if(command.equals("bed")){
            if(args.length!=0){context.sendInvalidArgs(spec.selfUsage());return;}
            service.bed(player);return;
        }
        if(command.equals("homes")||command.equals("warps")){
            if(!warp&&args.length==1){service.listOther(player,args[0]);return;}
            if(args.length!=0){context.sendInvalidArgs(spec.selfUsage());return;}
            service.list(player,warp);return;
        }
        // /home and /delhome <player>:<home>; home names never contain ':'.
        if((command.equals("home")||command.equals("delhome"))&&args.length==1&&args[0].indexOf(':')>0){
            String[] parts=args[0].split(":",2);
            if(command.equals("home"))service.useOther(player,parts[0],parts[1]);
            else service.deleteOther(player,parts[0],parts[1]);
            return;
        }
        boolean set=command.startsWith("set");
        if(args.length>(set?2:1)||warp&&args.length==0||args.length==2&&!args[1].equalsIgnoreCase("confirm")){
            context.sendInvalidArgs(spec.selfUsage());return;
        }
        String name=args.length==0?"home":args[0];
        if(set)service.set(player,name,warp,args.length==2);
        else if(command.startsWith("del"))service.delete(player,name,warp);
        else service.use(player,name,warp);
    }
}
