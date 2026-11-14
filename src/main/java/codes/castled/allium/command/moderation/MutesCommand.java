package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.moderation.MuteRecord;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Objects;

/**
 * {@code /mutes} (alias {@code /mutelist}): lists currently active mutes with remaining duration,
 * staff actor, and reason.
 */
public final class MutesCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final ModerationService moderation;

    public MutesCommand(PluginStart plugin, ModerationService moderation) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.moderation = Objects.requireNonNull(moderation, "moderation");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.MUTES;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (context.args().length != 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        sendMuteList(plugin, moderation, context.sender());
    }

    public static void sendMuteList(PluginStart plugin, ModerationService moderation, CommandSender sender) {
        List<MuteRecord> mutes = moderation.activeMutes(sender);
        if (mutes.isEmpty()) {
            plugin.getMessageManager().send(sender, Messages.MUTES_EMPTY);
            return;
        }
        long now = moderation.nowMillis();
        plugin.getMessageManager().send(
                sender,
                Messages.MUTES_HEADER,
                "{count}", Integer.toString(mutes.size())
        );
        for (MuteRecord record : mutes) {
            plugin.getMessageManager().send(
                    sender,
                    Messages.MUTES_ENTRY,
                    "{player}", record.playerName(),
                    "{remaining}", record.formattedRemaining(now),
                    "{actor}", record.actorName(),
                    "{reason}", record.reason()
            );
        }
    }
}
