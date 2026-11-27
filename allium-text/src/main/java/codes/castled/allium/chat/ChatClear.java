package codes.castled.allium.chat;

/** The shared line count and invisible, distinct messages used to clear client chat. */
public final class ChatClear {
    private static final char[] SPACES = {' ', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004',
            '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A'};

    private ChatClear() {}

    public static int entries(int configured) {
        return configured <= 0 ? 0 : Math.max(100, Math.min(4096, configured));
    }

    public static String blankLine(int index) {
        char[] line = new char[5];
        for (int i = 0; i < line.length; i++) {
            line[i] = SPACES[index % SPACES.length];
            index /= SPACES.length;
        }
        return new String(line);
    }
}
