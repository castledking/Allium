package codes.castled.allium.command.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.messaging.MessagingService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.Arrays;

/** {@code /msg <player> <message>} (aliases tell, w, whisper, m, t). {@code console} messages the console. */
public final class MsgCommand implements SpecCommandHandler {

    private final MessagingService messaging;

    public MsgCommand(PluginStart plugin, MessagingService messaging) {
        this.messaging = messaging;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.MSG;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length < 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        String name = args[0];
        CommandSender recipient = messaging.find(context.sender(), name);
        if (recipient == null && name.equalsIgnoreCase("console")) {
            recipient = Bukkit.getConsoleSender();
        }
        messaging.send(context.sender(), recipient, name, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }
}
