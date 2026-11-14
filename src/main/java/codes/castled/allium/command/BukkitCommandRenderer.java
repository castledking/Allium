package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandCompletionContext;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CommandSpec;
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

        handler.execute(new BukkitCommandContext(plugin, spec, sender, label, args));
        return true;
    }

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

        return spec.complete(completionContext, args);
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
