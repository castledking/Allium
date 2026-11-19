package codes.castled.allium.text;

import org.bukkit.command.CommandSender;

/**
 * Platform-aware dispatcher that delivers {@link FormattedText} as Adventure
 * {@code Component}s on Paper (when {@code Audience} is present on the sender)
 * and falls back to legacy {@code §x} strings on Spigot or non-Audience senders.
 *
 * <p>No Paper or Adventure types appear in this class's signatures or fields.
 */
public final class TextSender {

    private static final boolean ADVENTURE_AVAILABLE = detectAdventure();

    private TextSender() {
    }

    public static boolean isAdventureAvailable() {
        return ADVENTURE_AVAILABLE;
    }

    public static void send(CommandSender sender, String rawMessage) {
        if (sender == null || rawMessage == null || rawMessage.isEmpty()) {
            return;
        }
        send(sender, TextFormatter.parse(rawMessage));
    }

    public static void send(CommandSender sender, FormattedText formatted) {
        if (sender == null || formatted == null || formatted.isEmpty()) {
            return;
        }
        if (ADVENTURE_AVAILABLE) {
            try {
                if (AdventureTextBridge.trySend(sender, formatted)) {
                    return;
                }
            } catch (Throwable ignored) {
                // Fall back to legacy string delivery if Adventure bridge is unavailable
            }
        }
        sender.sendMessage(formatted.toLegacy());
    }

    /**
     * Sends text that contains untrusted input, such as a player's private message. On Paper this
     * is the same component path as {@link #send}. On Spigot a player gets explicit chat components
     * instead of a legacy string, because Spigot turns http(s) URLs in legacy chat strings into
     * clickable links; untrusted text must never become interactive. The console gets plain text.
     */
    public static void sendLiteral(CommandSender sender, FormattedText formatted) {
        if (sender == null || formatted == null || formatted.isEmpty()) {
            return;
        }
        if (ADVENTURE_AVAILABLE) {
            try {
                if (AdventureTextBridge.trySend(sender, formatted)) {
                    return;
                }
            } catch (Throwable ignored) {
                // Fall through to the Spigot component path.
            }
        }
        if (sender instanceof org.bukkit.entity.Player player) {
            player.spigot().sendMessage(SpigotComponents.of(formatted));
        } else {
            sender.sendMessage(formatted.toPlainText());
        }
    }

    /**
     * Sends an action bar overlay message without emitting any chat line (never enters chat history
     * or {@code /deletemsg}). Non-player senders (such as console) receive plain text when non-empty.
     */
    public static void sendActionBar(CommandSender sender, FormattedText formatted) {
        if (sender == null || formatted == null || formatted.isEmpty()) {
            return;
        }
        if (sender instanceof org.bukkit.entity.Player player) {
            if (ADVENTURE_AVAILABLE) {
                try {
                    if (AdventureTextBridge.trySendActionBar(player, formatted)) {
                        return;
                    }
                } catch (Throwable ignored) {
                    // Fall through to Spigot ACTION_BAR channel.
                }
            }
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, SpigotComponents.of(formatted));
        } else {
            sender.sendMessage(formatted.toPlainText());
        }
    }

    /** Clears the player's action bar without sending any chat line. */
    public static void clearActionBar(org.bukkit.entity.Player player) {
        if (player == null) {
            return;
        }
        if (ADVENTURE_AVAILABLE) {
            try {
                if (AdventureTextBridge.trySendActionBar(player, FormattedText.EMPTY)) {
                    return;
                }
            } catch (Throwable ignored) {
                // Fall through to Spigot ACTION_BAR channel.
            }
        }
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                new net.md_5.bungee.api.chat.TextComponent(""));
    }

    private static boolean detectAdventure() {
        try {
            Class.forName("net.kyori.adventure.audience.Audience");
            Class.forName("net.kyori.adventure.text.Component");
            return CommandSender.class.getInterfaces().length >= 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
