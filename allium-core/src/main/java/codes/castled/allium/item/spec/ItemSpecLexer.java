package codes.castled.allium.item.spec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bracket-, brace- and quote-aware splitting for item specs.
 *
 * <p>Raw component ({@code [...]}) and NBT ({@code {...}}) input routinely carries commas,
 * semicolons, colons and spaces. The lexer tracks a stack of open {@code [} and {@code {} and
 * whether it is inside a {@code '...'} or {@code "..."} string (with {@code \} escapes), and only
 * treats a separator as a split when every depth is zero. A quote opens a string only at a value
 * boundary (input start, whitespace, or after {@code [ { , : = |}), so {@code Bob's_Sword} is
 * plain text.
 */
public final class ItemSpecLexer {

    private ItemSpecLexer() {
    }

    /** Splits command arguments on whitespace outside brackets, braces and quotes. */
    public static List<String> splitArguments(String input) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Scanner scanner = new Scanner(input);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            boolean topLevel = scanner.atTopLevel();
            scanner.accept(c, i);
            if (topLevel && Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        scanner.finish();
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /** Splits on {@code separator} only where bracket, brace and quote depths are all zero. */
    public static List<String> splitTopLevel(String input, char separator) {
        List<String> out = new ArrayList<>();
        int start = 0;
        Scanner scanner = new Scanner(input);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            boolean topLevel = scanner.atTopLevel();
            scanner.accept(c, i);
            if (topLevel && c == separator) {
                out.add(input.substring(start, i));
                start = i + 1;
            }
        }
        scanner.finish();
        out.add(input.substring(start));
        return out;
    }

    /** Index of the first {@code target} outside brackets, braces and quotes, or -1. */
    public static int indexOfTopLevel(String input, char target) {
        Scanner scanner = new Scanner(input);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (scanner.atTopLevel() && c == target) {
                return i;
            }
            scanner.accept(c, i);
        }
        return -1;
    }

    /** Index of the first top-level {@code [} or {@code {}, or -1. */
    public static int indexOfOpenGroup(String input) {
        Scanner scanner = new Scanner(input);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (scanner.atTopLevel() && (c == '[' || c == '{')) {
                return i;
            }
            if (scanner.atTopLevel() && (c == ']' || c == '}')) {
                throw new ItemSpecException("unmatched '" + c + "'", i);
            }
            scanner.accept(c, i);
        }
        return -1;
    }

    /**
     * Returns the index just past the group that opens at {@code start} ({@code [} or {@code {}),
     * respecting nested groups and quotes.
     */
    public static int endOfGroup(String input, int start) {
        Scanner scanner = new Scanner(input);
        for (int i = start; i < input.length(); i++) {
            scanner.accept(input.charAt(i), i);
            if (scanner.atTopLevel()) {
                return i + 1;
            }
        }
        throw new ItemSpecException("'" + input.charAt(start) + "' is never closed", start);
    }

    /** Removes one layer of surrounding quotes and unescapes {@code \"}, {@code \'} and {@code \\}. */
    public static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                StringBuilder out = new StringBuilder();
                for (int i = 1; i < value.length() - 1; i++) {
                    char c = value.charAt(i);
                    if (c == '\\' && i + 1 < value.length() - 1) {
                        out.append(value.charAt(++i));
                    } else {
                        out.append(c);
                    }
                }
                return out.toString();
            }
        }
        return value;
    }

    public static boolean isQuoted(String value) {
        return value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0);
    }

    /** Tracks nesting and quoting one character at a time. */
    private static final class Scanner {
        private final String input;
        private final Deque<int[]> groups = new ArrayDeque<>();
        private char quote;
        private int quoteStart = -1;
        private boolean escaped;
        private char previous = ' ';

        Scanner(String input) {
            this.input = input;
        }

        boolean atTopLevel() {
            return groups.isEmpty() && quote == 0;
        }

        void accept(char c, int index) {
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                previous = c;
                return;
            }
            char before = previous;
            previous = c;
            switch (c) {
                case '"', '\'' -> {
                    if (Character.isWhitespace(before) || "[{,:=|".indexOf(before) >= 0) {
                        quote = c;
                        quoteStart = index;
                    }
                }
                case '[', '{' -> groups.push(new int[]{c, index});
                case ']', '}' -> {
                    if (groups.isEmpty()) {
                        throw new ItemSpecException("unmatched '" + c + "'", index);
                    }
                    int[] open = groups.pop();
                    char expected = open[0] == '[' ? ']' : '}';
                    if (c != expected) {
                        throw new ItemSpecException("'" + (char) open[0] + "' at " + (open[1] + 1)
                                + " is closed by '" + c + "'", index);
                    }
                }
                default -> {
                }
            }
        }

        void finish() {
            if (quote != 0) {
                throw new ItemSpecException("quote " + quote + " is never closed", quoteStart);
            }
            if (!groups.isEmpty()) {
                int[] open = groups.peek();
                throw new ItemSpecException("'" + (char) open[0] + "' is never closed", open[1]);
            }
        }
    }
}
