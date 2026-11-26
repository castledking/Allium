package codes.castled.allium.command.travel;

import codes.castled.allium.api.SettingsPresentation;
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
        String command=spec.canonicalName();String[] args=context.args();
        if(command.equals("warpinfo")){
            if(args.length!=1){context.sendInvalidArgs(spec.selfUsage());return;}
            service.warpInfo(context.sender(),args[0]);return;
        }
        if(command.equals("warp")&&args.length==2){
            service.warpOther(context.sender(),args[0],args[1]);return;
        }
        if(!(context.sender() instanceof Player player)){context.plugin().getMessageManager().send(context.sender(),Messages.PLAYER_ONLY);return;}
        boolean warp=command.contains("warp");
        if (args.length == 0 && (command.equals("home") || command.equals("homes") || command.equals("warp") || command.equals("warps"))) {
            var menus = context.plugin().getServer().getServicesManager().load(SettingsPresentation.class);
            if (menus != null && menus.open(player, warp ? SettingsPresentation.Screen.WARPS : SettingsPresentation.Screen.HOMES)) return;
        }
        if(command.equals("bed")){
            if(args.length!=0){context.sendInvalidArgs(spec.selfUsage());return;}
            service.bed(player);return;
        }
        if(command.equals("renamehome")){
            if(args.length!=2){context.sendInvalidArgs(spec.selfUsage());return;}
            if(args[0].indexOf(':')>0){
                String[] parts=args[0].split(":",2);
                service.renameOther(player,parts[0],parts[1],args[1]);
            }else{
                service.rename(player,args[0],args[1]);
            }
            return;
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
