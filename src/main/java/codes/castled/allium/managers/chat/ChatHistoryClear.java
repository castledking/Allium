package codes.castled.allium.managers.chat;

import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

/** Evicts client message entries, rather than only their rendered/wrapped lines. */
public final class ChatHistoryClear {
    private static final char[] SPACES = {' ', '\u2000', '\u2001', '\u2002', '\u2003',
        '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A'};
    private ChatHistoryClear() {}

    public static int entryCount(int configured) {
        return configured <= 0 ? 0 : Math.max(100, Math.min(configured, 4096));
    }

    public static void send(int configured, Consumer<Component> send) {
        for (int i = 0; i < entryCount(configured); i++) {
            // Distinct whitespace entries discourage duplicate-line folding.
            // Each entry must be a separate packet: embedded newlines do not
            // count toward the client's stored-message limit.
            int digits = i;
            StringBuilder text = new StringBuilder();
            for (int n = 0; n < 5; n++) {
                text.append(SPACES[digits % SPACES.length]);
                digits /= SPACES.length;
            }
            send.accept(Component.text(text.toString()));
        }
    }

    public static boolean isWhitespace(Component message) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message);
        return plain.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
}
