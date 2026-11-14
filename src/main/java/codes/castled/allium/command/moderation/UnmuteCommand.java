package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.naming.NameCache;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code /unmute <player> [reason]}.
 */
public final class UnmuteCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final ModerationService moderation;

    public UnmuteCommand(PluginStart plugin, ModerationService moderation) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.moderation = Objects.requireNonNull(moderation, "moderation");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.UNMUTE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length == 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        Optional<NameCache.Entry> resolved = moderation.resolveModerationTarget(sender, args[0], true);
        if (resolved.isEmpty()) {
            plugin.getMessageManager().send(
                    sender,
                    Messages.PLAYER_NOT_FOUND,
                    "{player}", MessagingService.safeName(args[0])
            );
            return;
        }

        String reason = args.length >= 2
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : null;
        NameCache.Entry target = resolved.get();
        moderation.unmute(sender, target.uuid(), target.name(), reason);
    }
}
