package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandCompletionContext;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CommandSpec;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
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
            public boolean hasPermission(String permissionNode) {
                return permissionNode == null || permissionNode.isEmpty() || sender.hasPermission(permissionNode);
            }

            @Override
            public List<String> visibleOnlinePlayers() {
                return collectVisibleOnlinePlayers(plugin.getServer(), sender);
            }
        };

        return spec.complete(completionContext, args);
    }

    public static List<String> collectVisibleOnlinePlayers(Server server, CommandSender sender) {
        if (server == null || server.getOnlinePlayers() == null) {
            return List.of();
        }
        Player senderPlayer = sender instanceof Player p ? p : null;
        boolean canSeeVanished = sender == null || !(sender instanceof Player) || sender.hasPermission("allium.vanish.see");

        List<String> names = new ArrayList<>();
        for (Player online : server.getOnlinePlayers()) {
            if (online == null) {
                continue;
            }
            if (senderPlayer != null && !senderPlayer.canSee(online)) {
                continue;
            }
            if (!canSeeVanished && isMetadataVanished(online)) {
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

    private static boolean isMetadataVanished(Player player) {
        try {
            List<MetadataValue> values = player.getMetadata("vanished");
            if (values != null) {
                for (MetadataValue val : values) {
                    if (val != null && val.asBoolean()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
