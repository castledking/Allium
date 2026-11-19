package codes.castled.allium.command.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One line of a command's usage card: the arguments typed after the label (without the leading
 * {@code /label}), what that form does, and an optional permission that hides the line from
 * senders who could not use it.
 */
public record CommandUsage(String syntax, String description, String permission) {

    public CommandUsage {
        syntax = syntax == null ? "" : syntax.trim();
        description = description == null ? "" : description.trim();
        permission = permission == null || permission.isBlank() ? null : permission.trim();
    }

    public static CommandUsage of(String syntax, String description) {
        return new CommandUsage(syntax, description, null);
    }

    public static CommandUsage of(String syntax, String description, String permission) {
        return new CommandUsage(syntax, description, permission);
    }

    /** The syntax split into words; a bracketed argument such as {@code <1s-1h, e.g. 10m>} stays one word. */
    public List<String> tokens() {
        return tokens(syntax);
    }

    /** Leading literal words (subcommands) before the first argument, lower-cased. */
    public List<String> literalPrefix() {
        List<String> out = new ArrayList<>();
        for (String token : tokens()) {
            if (isArgument(token) || token.contains("|")) {
                break;
            }
            out.add(token.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** {@code <required>} and {@code [optional]} words are arguments; everything else is typed literally. */
    public static boolean isArgument(String token) {
        return !token.isEmpty() && (token.charAt(0) == '<' || token.charAt(0) == '[');
    }

    /** Splits on spaces outside {@code <>}, {@code []} and {@code ()}. */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '[' || c == '(') {
                depth++;
            } else if ((c == '>' || c == ']' || c == ')') && depth > 0) {
                depth--;
            }
            if (c == ' ' && depth == 0) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }
}
