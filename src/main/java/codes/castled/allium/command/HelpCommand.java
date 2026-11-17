package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.hide.HideService;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.HideModule;
import codes.castled.allium.text.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Fresh permission/policy view for every page, search and completion. */
public final class HelpCommand implements SpecCommandHandler {
    private final PluginStart plugin;
    public HelpCommand(PluginStart plugin){this.plugin=plugin;}
    public CommandSpec spec(){return CoreCommandSpecs.HELP;}
    public List<HideService.HelpEntry> entries(CommandSender sender){
        if(plugin.getModuleManager().getModule("hide") instanceof HideModule hide&&hide.isEnabled())return hide.hide().helpEntries(sender);
        var access=CommandMapBridge.inspectPlatformAccess(plugin.getServer(),ignored->{});
        if(access==null||access.knownCommands()==null)return List.of();
        return access.knownCommands().entrySet().stream().filter(e->!e.getKey().contains(":" )&&e.getValue().testPermissionSilent(sender)).map(e->new HideService.HelpEntry(e.getKey(),e.getValue() instanceof PluginIdentifiableCommand pc?pc.getPlugin().getName():"Minecraft",e.getValue().getDescription())).sorted(Comparator.comparing(HideService.HelpEntry::path)).toList();
    }
    public List<String> complete(CommandSender sender,String[] args){
        if(!sender.hasPermission("allium.help")||args.length>2)return List.of();
        var values=new TreeSet<String>(List.of("1","2","3"));
        if(args.length<=1)for(var e:entries(sender)){values.add(e.path());if(sender.hasPermission("allium.admin"))values.add(e.owner());}
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);
        return values.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(64).toList();
    }
    public void execute(BukkitCommandContext context){
        var sender=context.sender();if(!sender.hasPermission("allium.help")){plugin.getMessageManager().send(sender,Messages.HIDE_NO_PERMISSION,"{cmd}","help");return;}
        String[] args=context.args();if(args.length>2){context.sendInvalidArgs(spec().selfUsage());return;}
        int page=1;String query="";
        try{if(args.length>0){if(args[0].matches("[0-9]+")){page=Integer.parseInt(args[0]);if(args.length==2)query=args[1];}else{query=args[0];if(args.length==2)page=Integer.parseInt(args[1]);}}}
        catch(NumberFormatException invalid){context.sendInvalidArgs(spec().selfUsage());return;}
        String search=query.toLowerCase(Locale.ROOT);boolean admin=sender.hasPermission("allium.admin");
        var rows=entries(sender).stream().filter(e->search.isEmpty()||e.path().equalsIgnoreCase(search)||e.path().toLowerCase(Locale.ROOT).startsWith(search+" ")||admin&&e.owner().equalsIgnoreCase(search)).toList();
        int pages=Math.max(1,(rows.size()+7)/8);page=Math.max(1,Math.min(page,pages));
        plugin.getMessageManager().send(sender,Messages.HELP_HEADER,"{page}",""+page,"{pages}",""+pages);
        if(rows.isEmpty())plugin.getMessageManager().send(sender,Messages.HELP_EMPTY);
        for(var e:rows.subList(Math.min((page-1)*8,rows.size()),Math.min(page*8,rows.size()))){
            String line=plugin.getMessageManager().resolve(Messages.HELP_ENTRY,"{command}",e.path(),"{description}",e.description());
            interactive(sender,line,"/"+e.path()+" ",false);
        }
        if(page>1)interactive(sender,plugin.getMessageManager().resolve(Messages.HELP_PREVIOUS),"/help "+(page-1)+(query.isEmpty()?"":" "+query),true);
        if(page<pages)interactive(sender,plugin.getMessageManager().resolve(Messages.HELP_NEXT),"/help "+(page+1)+(query.isEmpty()?"":" "+query),true);
    }
    private static void interactive(CommandSender sender,String text,String command,boolean run){
        if(sender instanceof Player player){var parts=SpigotComponents.of(TextFormatter.parse(text));for(var part:parts)part.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(run?net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND:net.md_5.bungee.api.chat.ClickEvent.Action.SUGGEST_COMMAND,command));player.spigot().sendMessage(parts);}
        else TextSender.send(sender,text);
    }
}
