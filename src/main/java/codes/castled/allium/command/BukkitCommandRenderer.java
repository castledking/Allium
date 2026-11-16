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
        if (spec.canonicalName().equals("enchant") && args.length == 1 && sender.hasPermission("allium.enchant")) {
            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
            List<String> enchants = new ArrayList<>();
            for (var ench : org.bukkit.Registry.ENCHANTMENT) {
                String key = ench.getKey().getKey();
                if (key.startsWith(prefix)) {
                    enchants.add(key);
                }
            }
            enchants.sort(String::compareTo);
            return enchants;
        }
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
        return spec.complete(completionContext,fixedMode==null?args:withMode(fixedMode,args));
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
