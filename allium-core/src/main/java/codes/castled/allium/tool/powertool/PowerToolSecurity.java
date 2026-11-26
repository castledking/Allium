package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Security validation and placeholder substitution for powertool commands and tool IDs.
 */
public final class PowerToolSecurity {

    private static final Pattern TOOL_ID = Pattern.compile("^[a-z0-9_]{2,48}$");
    private static final Pattern SELECTOR = Pattern.compile("(?<!\\S)@[aeprs](\\[[^\\]]*\\])?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)\\}");

    public static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            "player", "uuid", "world", "x", "y", "z", "yaw", "pitch",
            "hand", "item", "target", "target_uuid", "block_x", "block_y", "block_z", "block_type"
    );

    private PowerToolSecurity() {
    }

    public static boolean isValidToolName(@Nullable String name) {
        return name != null && TOOL_ID.matcher(name.trim()).matches();
    }

    public static String normalizeToolName(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public static String cleanCommand(String raw) {
        String trimmed = raw.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    public static boolean containsSelectors(@Nullable String command) {
        return command != null && SELECTOR.matcher(command).find();
    }

    /**
     * Checks whether a console command is permitted by {@code allowlist}. An empty list allows any command.
     *
     * <p>An allowlist entry may be:
     * <ul>
     *   <li>{@code *} (explicit opt-in allowing any command),</li>
     *   <li>a namespace prefix ending with {@code :} (e.g. {@code allium:}, {@code minecraft:}),</li>
     *   <li>or a specific command label (e.g. {@code minecraft:effect}, {@code heal}, {@code allium:heal}).</li>
     * </ul>
     */
    public static boolean isConsoleCommandAllowed(@Nullable String command, Collection<String> allowlist) {
        if (command == null || command.isBlank() || allowlist == null) {
            return false;
        }
        String cleaned = cleanCommand(command);
        if (cleaned.isEmpty()) {
            return false;
        }
        if (allowlist.isEmpty()) {
            return true;
        }
        int space = cleaned.indexOf(' ');
        String head = (space >= 0 ? cleaned.substring(0, space) : cleaned).toLowerCase(Locale.ROOT);
        for (String rawEntry : allowlist) {
            if (rawEntry == null || rawEntry.isBlank()) {
                continue;
            }
            String entry = cleanCommand(rawEntry).toLowerCase(Locale.ROOT);
            if ("*".equals(entry)) {
                return true;
            }
            if (entry.endsWith(":")) {
                if (head.startsWith(entry)) {
                    return true;
                }
            } else if (head.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the first unknown placeholder name in {@code text}, or {@code null} when all are valid. */
    @Nullable
    public static String firstUnknownPlaceholder(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!ALLOWED_PLACEHOLDERS.contains(key)) {
                return key;
            }
        }
        return null;
    }

    public static String applyPlaceholders(String template, Map<String, String> values) {
        if (template == null || template.isEmpty() || values.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() != null) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }
}
