package codes.castled.allium.tradingcards.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Small capitals for visible text, preserving MiniMessage tags, glyphs and commands. */
public final class CardText {
    private static final String CAPS = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private CardText() {}

    public static String smallCaps(String text) {
        StringBuilder result = new StringBuilder(text.length());
        char quote = 0;
        boolean tag = false;
        boolean command = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!tag && c == '<' && (i == 0 || text.charAt(i - 1) != '\\')) {
                tag = true;
                command = false;
            } else if (tag) {
                if (quote != 0) {
                    if (c == quote && text.charAt(i - 1) != '\\') quote = 0;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '>') {
                    tag = false;
                    result.append(c);
                    continue;
                }
            } else if (c == '/') {
                command = true;
            } else if (Character.isWhitespace(c)) {
                command = false;
            }
            char lower = Character.toLowerCase(c);
            result.append(!tag && !command && lower >= 'a' && lower <= 'z'
                ? CAPS.charAt(lower - 'a') : c);
        }
        return result.toString();
    }

    public static Component component(String text) {
        return MM.deserialize("<!italic>" + smallCaps(text));
    }
}
