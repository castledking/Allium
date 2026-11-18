package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandCompletionContext;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.module.MailModule;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Renders a platform-neutral {@link CommandSpec} and {@link SpecCommandHandler} into a Bukkit
 * {@link CommandExecutor} and {@link TabCompleter}.
 */
public final class BukkitCommandRenderer implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final CommandFamilyPolicy policy;
    private final SpecCommandHandler handler;

    public BukkitCommandRenderer(
            PluginStart plugin,
            CommandFamilyPolicy policy,
            SpecCommandHandler handler
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public CommandSpec spec() {
        return handler.spec();
    }

    public SpecCommandHandler handler() {
        return handler;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        CommandSpec spec = handler.spec();
        if (policy.isDisabled(spec.canonicalName()) || policy.isDisabled(label)) {
            plugin.getMessageManager().sendByKey(sender, spec.messageKeys().moduleDisabledKey());
            return true;
        }

        String basePerm = spec.basePermission();
        if (basePerm != null && !basePerm.isEmpty() && !sender.hasPermission(basePerm)) {
            plugin.getMessageManager().sendByKey(
                    sender,
                    spec.messageKeys().noPermissionKey(),
                    "{cmd}", spec.canonicalName()
            );
            return true;
        }

        String fixedMode=spec.canonicalName().equals("gamemode")?CoreCommandSpecs.gamemodeAlias(label):null;
        if(fixedMode!=null){
            if(args.length>1){plugin.getMessageManager().sendByKey(sender,spec.messageKeys().invalidArgsKey(),"{usage}","/"+label+" [player]");return true;}
            args=withMode(fixedMode,args);
        }
        handler.execute(new BukkitCommandContext(plugin, spec, sender, label, args));
        return true;
    }

    private static String[] withMode(String mode,String[] args){String[] expanded=new String[args.length+1];expanded[0]=mode;System.arraycopy(args,0,expanded,1,args.length);return expanded;}

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        CommandSpec spec = handler.spec();
        if (policy.isDisabled(spec.canonicalName()) || policy.isDisabled(alias)) {
            return List.of();
        }

        if (spec == CoreCommandSpecs.CLEARINVENTORY
                && handler instanceof codes.castled.allium.command.essentials.ClearInventoryCommand clear) {
            return clear.complete(sender, args);
        }
        if((spec.canonicalName().equals("give")&&args.length==2||spec.canonicalName().equals("i")&&args.length==1)&&sender.hasPermission("allium.give")&&plugin.getModuleManager().getModule("items") instanceof codes.castled.allium.module.ItemsModule items&&items.isEnabled())return items.complete(args[args.length-1]);
        if(java.util.Set.of("tp","tphere","there").contains(spec.canonicalName())&&args.length>0&&args[args.length-1].startsWith("@")){
            if(!sender.hasPermission(spec.basePermission())||!codes.castled.allium.teleport.TeleportTargets.selectors(sender))return List.of();String prefix=args[args.length-1];return java.util.stream.Stream.of("@p","@r","@a","@s","@e").filter(v->v.startsWith(prefix)).filter(v->!v.equals("@a")||sender.hasPermission("allium.admin")||sender.hasPermission("allium.command.selector.all")).filter(v->!v.equals("@e")||sender.hasPermission("allium.admin")||sender.hasPermission("allium.command.selector.entities")).toList();
        }
        if(spec.canonicalName().equals("help")&&handler instanceof HelpCommand help)return help.complete(sender,args);
        boolean tpPosition=spec.canonicalName().equals("tp")&&args.length>=2&&codes.castled.allium.teleport.CoordinateArgs.isCoordinate(args[0]);
        if(tpPosition||spec.canonicalName().equals("tppos")){
            if(!sender.hasPermission(tpPosition?"allium.tp.position":"allium.tppos"))return List.of();
            int index=args.length-1;String prefix=args.length==0?"":args[index];
            if(index<5)return "~".startsWith(prefix)?List.of("~"):List.of();
            if(index==5)return plugin.getServer().getWorlds().stream().map(org.bukkit.World::getName).filter(n->codes.castled.allium.teleport.CoordinateArgs.startsWithIgnoreCase(n,prefix)).sorted().limit(50).toList();
            return List.of();
        }
        if(spec.canonicalName().equals("allium")&&args.length>=2&&args[0].equalsIgnoreCase("hide")){
            if(plugin.getModuleManager().getModule("hide") instanceof codes.castled.allium.module.HideModule hide&&hide.isEnabled())
                return hide.hide().complete(sender,java.util.Arrays.copyOfRange(args,1,args.length));
            return List.of();
        }
        if((spec.canonicalName().equals("channel")||spec.canonicalName().equals("nick"))&&plugin.getModuleManager()!=null){
            if(!sender.hasPermission(spec.basePermission()))return List.of();
            if(spec.canonicalName().equals("channel")&&sender instanceof Player p&&alias.substring(alias.lastIndexOf(':')+1).equals("channel")
                &&plugin.getModuleManager().getModule("chat") instanceof codes.castled.allium.module.ChatModule module&&module.isEnabled()){
                if(args.length<=1)return java.util.stream.Stream.of("join","leave").filter(v->args.length==0||v.startsWith(args[0])).toList();
                if(args.length==2)return module.chat().complete(p,args[1]);
                if(args.length==3)return java.util.stream.Stream.of("read","write").filter(v->v.startsWith(args[2])).toList();
                return List.of();
            }
            if(spec.canonicalName().equals("nick")&&args.length==1&&sender.hasPermission("allium.nick.others")){
                var names=plugin.getPlayerNames();return names==null?List.of():names.complete(sender,args[0],64);
            }
        }
        if(spec.canonicalName().equals("enchant")&&sender.hasPermission("allium.enchant")){
            Player target=sender instanceof Player p?p:null;String prefix=args.length==0?"":args[args.length-1];boolean nameArg=args.length<=1;
            if(args.length==2&&sender.hasPermission("allium.enchant.others")){Player named=plugin.getServer().getPlayerExact(args[0]);if(named!=null&&codes.castled.allium.naming.PlayerNameVisibility.canSee(sender,named)){target=named;nameArg=true;}}
            if(nameArg){var item=target==null?null:target.getInventory().getItemInMainHand();var existing=item==null||item.getItemMeta()==null?java.util.Set.<org.bukkit.enchantments.Enchantment>of():item.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta book?book.getStoredEnchants().keySet():item.getItemMeta().getEnchants().keySet();return codes.castled.allium.item.library.EnchantCompletions.names(item,prefix,existing);}
        }
        if(java.util.Set.of("tp","tphere","there").contains(spec.canonicalName())&&sender.hasPermission(spec.basePermission())&&codes.castled.allium.teleport.TeleportTargets.selectors(sender)&&args.length>0&&args[args.length-1].startsWith("@"))return java.util.stream.Stream.of("@p","@r","@s","@a","@e").filter(v->v.startsWith(args[args.length-1])).filter(v->!v.equals("@a")||sender.hasPermission("allium.command.selector.all")||sender.hasPermission("allium.admin")).filter(v->!v.equals("@e")||sender.hasPermission("allium.command.selector.entities")||sender.hasPermission("allium.admin")).toList();
        CommandCompletionContext completionContext = new CommandCompletionContext() {
            @Override
            public List<String> travelNames(boolean warps, String prefix) {
                if (!(sender instanceof Player player) || plugin.getModuleManager()==null
                        || !(plugin.getModuleManager().getModule("travel") instanceof codes.castled.allium.module.TravelModule module)
                        || !module.isEnabled())return List.of();
                return warps?module.travel().completeWarps(player,prefix):module.travel().completeHomes(player,prefix);
            }

            @Override
            public List<String> kitNames(boolean allAdminKits, String prefix) {
                if (plugin.getModuleManager() == null
                        || !(plugin.getModuleManager().getModule("kits") instanceof KitsModule module)
                        || !module.isEnabled() || module.kits() == null) {
                    return List.of();
                }
                return module.kits().completeKits(sender, allAdminKits, prefix);
            }

            @Override
            public List<String> jailNames(String prefix) {
                if (plugin.getModuleManager() == null
                        || !(plugin.getModuleManager().getModule("moderation") instanceof codes.castled.allium.module.ModerationModule module)
                        || !module.isEnabled() || module.discipline() == null) return List.of();
                return module.discipline().completeJails(prefix);
            }

            @Override
            public boolean hasPermission(String permissionNode) {
                return permissionNode == null || permissionNode.isEmpty() || sender.hasPermission(permissionNode);
            }

            @Override
            public List<String> visibleOnlinePlayers() {
                return collectVisibleOnlinePlayers(plugin.getServer(), sender);
            }

            @Override
            public List<String> knownPlayers(String prefix) {
                PlayerNameService names = plugin.getPlayerNames();
                return names == null ? collectVisibleOnlinePlayers(plugin.getServer(), sender).stream()
                        .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT)))
                        .limit(PlayerNameService.COMPLETION_LIMIT).toList()
                        : names.complete(sender, prefix, PlayerNameService.COMPLETION_LIMIT);
            }

            @Override
            public List<String> mailIds(boolean claimableOnly) {
                if (!(sender instanceof Player player) || plugin.getModuleManager() == null
                        || !(plugin.getModuleManager().getModule("mail") instanceof MailModule mail)
                        || !mail.isEnabled() || mail.mail() == null) {
                    return List.of();
                }
                return mail.mail().completeMailbox(player, claimableOnly);
            }
        };

        String fixedMode=spec.canonicalName().equals("gamemode")?CoreCommandSpecs.gamemodeAlias(alias):null;
        List<String> out=spec.complete(completionContext,fixedMode==null?args:withMode(fixedMode,args));
        if(spec.canonicalName().equals("tp")&&args.length==1&&"~".startsWith(args[0])&&sender.hasPermission("allium.tp.position")){
            out=new ArrayList<>(out);out.add("~");
        }
        return out;
    }

    public static List<String> collectVisibleOnlinePlayers(Server server, CommandSender sender) {
        if (server == null || server.getOnlinePlayers() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Player online : server.getOnlinePlayers()) {
            if (online == null) {
                continue;
            }
            if (!PlayerNameVisibility.canSee(sender, online)) {
                continue;
            }
            String name = online.getName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        names.sort(Comparator.comparing(s -> s.toLowerCase( java.util.Locale.ROOT)));
        return List.copyOf(names);
    }
}
