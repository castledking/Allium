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
