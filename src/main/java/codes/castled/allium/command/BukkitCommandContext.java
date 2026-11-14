package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Execution context provided to a {@link SpecCommandHandler} by {@link BukkitCommandRenderer}.
 */
public final class BukkitCommandContext {

    private final PluginStart plugin;
    private final CommandSpec spec;
    private final CommandSender sender;
    private final String label;
    private final String[] args;

    public BukkitCommandContext(
            PluginStart plugin,
            CommandSpec spec,
            CommandSender sender,
            String label,
            String[] args
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.label = Objects.requireNonNull(label, "label");
        this.args = Objects.requireNonNull(args, "args");
    }

    public PluginStart plugin() {
        return plugin;
    }

    public CommandSpec spec() {
        return spec;
    }

    public CommandSender sender() {
        return sender;
    }

    public String label() {
        return label;
    }

    public String[] args() {
        return args;
    }

    /**
     * Resolves the target player from {@code args[targetArgIndex]} if supplied (enforcing
     * {@link CommandSpec#othersPermission()}), or falls back to the sender if the sender is a
     * {@link Player} (sending {@link CommandSpec#consoleUsage()} if invoked from console).
     *
     * @return the resolved online {@link Player}, or {@code null} if a diagnostic message was already sent
     */
    @Nullable
    public Player resolveSelfOrTarget(int targetArgIndex) {
        if (args.length > targetArgIndex) {
            String othersPerm = spec.othersPermission();
            if (othersPerm != null && !othersPerm.isEmpty() && !sender.hasPermission(othersPerm)) {
                plugin.getMessageManager().sendByKey(
                        sender,
                        spec.messageKeys().noPermissionKey(),
                        "{cmd}", spec.canonicalName() + " for others"
                );
                return null;
            }
            String targetName = args[targetArgIndex];
            Player target = plugin.getServer().getPlayer(targetName);
            if (target == null || !PlayerNameVisibility.canSeeTarget(sender, target)) {
                plugin.getMessageManager().sendByKey(
                        sender,
                        spec.messageKeys().playerNotFoundKey(),
                        "{player}", targetName
                );
                return null;
            }
            return target;
        }

        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendByKey(
                    sender,
                    spec.messageKeys().invalidArgsKey(),
                    "{usage}", spec.consoleUsage()
            );
            return null;
        }
        return player;
    }

    public void sendInvalidArgs(String usage) {
        plugin.getMessageManager().sendByKey(
                sender,
                spec.messageKeys().invalidArgsKey(),
                "{usage}", usage
        );
    }

    public void sendOutcome(CommandSender recipient, String outcomeName, String... replacements) {
        String key = spec.messageKeys().outcomeKey(outcomeName);
        if (key != null) {
            plugin.getMessageManager().sendByKey(recipient, key, replacements);
        }
    }
}
