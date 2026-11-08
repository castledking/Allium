package codes.castled.allium.command.messaging;

import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.messaging.MessagingService;

/** {@code /reply <message>} (alias r): to the last person you messaged or who messaged you. */
public final class ReplyCommand implements SpecCommandHandler {

    private final MessagingService messaging;

    public ReplyCommand(MessagingService messaging) {
        this.messaging = messaging;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.REPLY;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (context.args().length < 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        messaging.reply(context.sender(), String.join(" ", context.args()));
    }
}
