package codes.castled.allium.listeners.security;

import java.util.List;
import java.util.Locale;

/** Shared command-path semantics for execution, suggestions and hide.yml entries. */
public final class CommandPath {
    private CommandPath() {}

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String path = value.trim();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String root(String path) {
        String normalized = normalize(path);
        int space = normalized.indexOf(' ');
        return space < 0 ? normalized : normalized.substring(0, space);
    }

    private static String withoutNamespace(String path) {
        int colon = root(path).indexOf(':');
        return colon < 0 ? path : path.substring(colon + 1);
    }

    private static boolean isBranch(String path, String branch) {
        return path.equals(branch) || path.startsWith(branch + " ");
    }

    /** A rule covers its descendants; completion whitelists also expose its ancestors. */
    public static boolean matches(List<String> rules, String value, boolean includeAncestors) {
        String path = normalize(value);
        if (path.isEmpty()) {
            return false;
        }
        String plainPath = withoutNamespace(path);
        for (String rule : rules) {
            if (rule.equals("*") || isBranch(path, rule) || isBranch(plainPath, rule)
                    || (includeAncestors && (isBranch(rule, path) || isBranch(rule, plainPath)))) {
                return true;
            }
        }
        return false;
    }

    /** Reconstruct the candidate path using the server's suggestion replacement offset. */
    public static String suggestion(String buffer, String suggestion, int replacementStart) {
        String input = buffer == null ? "" : buffer;
        int start = replacementStart;
        if (start < 0 || start > input.length()) {
            start = completionStart(input);
        }
        if (start <= 1 && suggestion.startsWith("/")) {
            return normalize(suggestion);
        }
        return normalize(input.substring(0, start) + suggestion);
    }

    /** Bukkit completions replace the last token; packet completions supply their own range. */
    public static int completionStart(String buffer) {
        for (int i = buffer.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(buffer.charAt(i))) {
                return i + 1;
            }
        }
        return buffer.startsWith("/") ? 1 : 0;
    }
}
