package codes.castled.allium.command.mail;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.mail.MailService;
import codes.castled.allium.message.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code /mail read|send|gift|claim|delete|clear}. The console may send text mail; everything
 * else is a player's own mailbox. Bare {@code /mail} reads.
 */
public final class MailCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final MailService mail;

    public MailCommand(PluginStart plugin, MailService mail) {
        this.plugin = plugin;
        this.mail = mail;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.MAIL;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (!allowed(sender, "allium.mail", "mail")) {
            return;
        }
        String action = args.length == 0 ? "read" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "send" -> {
                if (!allowed(sender, "allium.mail.send", "mail send")) {
                    return;
                }
                if (args.length < 3 || args[1].isBlank()
                        || String.join(" ", Arrays.copyOfRange(args, 2, args.length)).isBlank()) {
                    context.sendInvalidArgs("/mail send <player> <message>");
                    return;
                }
                mail.sendText(sender, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            case "gift" -> {
                if (!allowed(sender, "allium.mail.gift", "mail gift")) {
                    return;
                }
                Player player = player(context);
                if (player == null) {
                    return;
                }
                if (args.length < 2 || args[1].isBlank()) {
                    context.sendInvalidArgs("/mail gift <player> [amount] [note]");
                    return;
                }
                int amount = 0;
                int noteFrom = 2;
                if (args.length > 2 && looksLikeAmount(args[2])) {
                    try {
                        amount = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        context.sendInvalidArgs("/mail gift <player> [amount] [note]");
                        return;
                    }
                    if (amount < 1) {
                        context.sendInvalidArgs("/mail gift <player> [amount] [note]");
                        return;
                    }
                    ItemStack held = player.getInventory().getItem(player.getInventory().getHeldItemSlot());
                    if (held != null && amount > held.getAmount()) {
                        context.sendInvalidArgs("/mail gift <player> [amount] [note]");
                        return;
                    }
                    noteFrom = 3;
                }
                String note = args.length > noteFrom ? String.join(" ", Arrays.copyOfRange(args, noteFrom, args.length)) : null;
                mail.sendGift(player, args[1], amount, note);
            }
            case "read" -> {
                Player player = player(context);
                if (player == null) {
                    return;
                }
                if (args.length > 1) {
                    context.sendInvalidArgs("/mail read");
                    return;
                }
                mail.read(player);
            }
            case "claim" -> {
                Player player = player(context);
                if (player == null) {
                    return;
                }
                if (args.length != 2 || args[1].isBlank()) {
                    context.sendInvalidArgs("/mail claim <id|all>");
                    return;
                }
                mail.claim(player, args[1]);
            }
            case "delete" -> {
                Player player = player(context);
                if (player == null) {
                    return;
                }
                if (args.length != 2 || args[1].isBlank()) {
                    context.sendInvalidArgs("/mail delete <id>");
                    return;
                }
                mail.delete(player, args[1]);
            }
            case "clear" -> {
                Player player = player(context);
                if (player == null) {
                    return;
                }
                if (args.length != 1) {
                    context.sendInvalidArgs("/mail clear");
                    return;
                }
                mail.clear(player);
            }
            default -> context.sendInvalidArgs(spec().selfUsage());
        }
    }

    private static boolean looksLikeAmount(String token) {
        // Numeric-looking input is never silently reinterpreted as a note/full-stack send.
        return token.matches("[+-]?(?:[0-9].*|\\.[0-9].*)");
    }

    private Player player(BukkitCommandContext context) {
        if (context.sender() instanceof Player player) {
            return player;
        }
        plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
        return null;
    }

    private boolean allowed(CommandSender sender, String node, String label) {
        if (sender.hasPermission(node)) {
            return true;
        }
        plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", label);
        return false;
    }
}
