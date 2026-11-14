package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.moderation.DurationParser;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.naming.NameCache;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@code /mute <player> [duration] [reason]}. Also accepts {@code /mute list} as a convenience
 * alias for {@code /mutes}. A second token starting with a digit must parse as a valid duration;
 * otherwise usage is returned instead of treating a malformed duration as part of a permanent mute reason.
 */
public final class MuteCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final ModerationService moderation;

    public MuteCommand(PluginStart plugin, ModerationService moderation) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.moderation = Objects.requireNonNull(moderation, "moderation");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.MUTE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length == 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            MutesCommand.sendMuteList(plugin, moderation, sender);
            return;
        }

        Optional<NameCache.Entry> resolved = moderation.resolveModerationTarget(sender, args[0], false);
        if (resolved.isEmpty()) {
            plugin.getMessageManager().send(
                    sender,
                    Messages.PLAYER_NOT_FOUND,
                    "{player}", MessagingService.safeName(args[0])
            );
            return;
        }

        Long durationMillis = null;
        String reason = null;
        if (args.length >= 2) {
            OptionalLong parsedDuration = DurationParser.parseMillis(args[1]);
            if (parsedDuration.isPresent()) {
                durationMillis = parsedDuration.getAsLong();
                if (args.length >= 3) {
                    reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                }
            } else if (DurationParser.startsWithDigit(args[1])) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            } else {
                reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }
        }

        NameCache.Entry target = resolved.get();
        moderation.mute(sender, target.uuid(), target.name(), durationMillis, reason);
    }
}
