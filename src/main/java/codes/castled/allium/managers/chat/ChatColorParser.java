package codes.castled.allium.managers.chat;

import codes.castled.allium.managers.core.Text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static codes.castled.allium.managers.core.Text.DebugSeverity.WARN;

/**
 * Permission-aware colour parser for text a player typed.
 *
 * <p>Legacy codes and MiniMessage tags are both accepted inside the same message. The two
 * systems keep their own permission gates - MiniMessage tags are validated on the text as
 * typed ({@code chat.minimessage.*}), legacy codes on {@code chat.color.*} /
 * {@code chat.format.*} - and whatever survives both passes is translated into a single
 * MiniMessage string that is deserialized once. That is what lets
 * {@code &#8C8F9B&lVIP <gradient:red:blue>hi</gradient>} render in full.
 *
 * <p>Recognised legacy forms: {@code &a}, {@code &#RRGGBB}, {@code &x&R&R&G&G&B&B} and the
 * {@code §} equivalents. An ampersand that is not part of a colour code is left alone, so
 * "Tom &amp; Jerry" survives intact.
 *
 * <p>Permissions live under a scope, so surfaces that colour differently typed text - chat
 * ({@link #CHAT}) and signs ({@code allium.sign}) - share one implementation and one set of
 * rules. Every scope answers to the same shape: {@code <scope>.color[.name|.*]},
 * {@code <scope>.format[.name|.*]}, {@code <scope>.color.hex} and
 * {@code <scope>.minimessage[.type]}.
 */
public final class ChatColorParser {

    /** Answers permission questions for the author of the text being parsed. */
    @FunctionalInterface
    public interface PermissionCheck {
        boolean has(String permission);
    }

    public static final PermissionCheck ALLOW_ALL = permission -> true;
    public static final PermissionCheck DENY_ALL = permission -> false;

    /** Permission scope of text typed in chat. */
    public static final String CHAT = "chat";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** {@code &#RRGGBB} / {@code §#RRGGBB}. */
    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)[&§]#([0-9A-F]{6})");
    /** {@code &x&R&R&G&G&B&B} - the form Bungee/Spigot serialize hex colours into. */
    private static final Pattern X_HEX_PATTERN = Pattern.compile("(?i)[&§]x((?:[&§][0-9A-F]){6})");
    /** A single legacy colour or format code. */
    private static final Pattern CODE_PATTERN = Pattern.compile("(?i)[&§]([0-9A-FK-OR])");
    /**
     * A plausible MiniMessage tag. Deliberately requires a letter after the opening angle
     * bracket so ordinary text like "&lt;3" is not mistaken for markup.
     */
    private static final Pattern MINI_TAG_PATTERN = Pattern.compile("</?[a-zA-Z][^<>]*>");
    /**
     * Any MiniMessage tag that ends up colouring text, gradient and rainbow included. Kept apart
     * from the gates in {@link #isMiniMessageAllowed}, which give those two their own permissions.
     */
    private static final Pattern MINI_COLOR_PATTERN = Pattern.compile("(?i).*<("
            + "color:[^>]*|#[0-9A-F]{6}"
            + "|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue"
            + "|green|aqua|red|light_purple|yellow|white"
            + "|gradient(:[^>]*)?|rainbow(:[^>]*)?"
            + ")>.*");

    private static final Map<Character, String> LEGACY_TO_TAG = new HashMap<>();
    /** Permission suffix per code, resolved against the scope at lookup time. */
    private static final Map<Character, String> LEGACY_TO_PERMISSION = new HashMap<>();

    static {
        LEGACY_TO_TAG.put('0', "<black>");
        LEGACY_TO_TAG.put('1', "<dark_blue>");
        LEGACY_TO_TAG.put('2', "<dark_green>");
        LEGACY_TO_TAG.put('3', "<dark_aqua>");
        LEGACY_TO_TAG.put('4', "<dark_red>");
        LEGACY_TO_TAG.put('5', "<dark_purple>");
        LEGACY_TO_TAG.put('6', "<gold>");
        LEGACY_TO_TAG.put('7', "<gray>");
        LEGACY_TO_TAG.put('8', "<dark_gray>");
        LEGACY_TO_TAG.put('9', "<blue>");
        LEGACY_TO_TAG.put('a', "<green>");
        LEGACY_TO_TAG.put('b', "<aqua>");
        LEGACY_TO_TAG.put('c', "<red>");
        LEGACY_TO_TAG.put('d', "<light_purple>");
        LEGACY_TO_TAG.put('e', "<yellow>");
        LEGACY_TO_TAG.put('f', "<white>");
        LEGACY_TO_TAG.put('k', "<obfuscated>");
        LEGACY_TO_TAG.put('l', "<bold>");
        LEGACY_TO_TAG.put('m', "<strikethrough>");
        LEGACY_TO_TAG.put('n', "<underlined>");
        LEGACY_TO_TAG.put('o', "<italic>");
        LEGACY_TO_TAG.put('r', "<reset>");

        LEGACY_TO_PERMISSION.put('0', "color.black");
        LEGACY_TO_PERMISSION.put('1', "color.dark_blue");
        LEGACY_TO_PERMISSION.put('2', "color.dark_green");
        LEGACY_TO_PERMISSION.put('3', "color.dark_aqua");
        LEGACY_TO_PERMISSION.put('4', "color.dark_red");
        LEGACY_TO_PERMISSION.put('5', "color.dark_purple");
        LEGACY_TO_PERMISSION.put('6', "color.gold");
        LEGACY_TO_PERMISSION.put('7', "color.gray");
        LEGACY_TO_PERMISSION.put('8', "color.dark_gray");
        LEGACY_TO_PERMISSION.put('9', "color.blue");
        LEGACY_TO_PERMISSION.put('a', "color.green");
        LEGACY_TO_PERMISSION.put('b', "color.aqua");
        LEGACY_TO_PERMISSION.put('c', "color.red");
        LEGACY_TO_PERMISSION.put('d', "color.light_purple");
        LEGACY_TO_PERMISSION.put('e', "color.yellow");
        LEGACY_TO_PERMISSION.put('f', "color.white");
        LEGACY_TO_PERMISSION.put('k', "format.magic");
        LEGACY_TO_PERMISSION.put('l', "format.bold");
        LEGACY_TO_PERMISSION.put('m', "format.strikethrough");
        LEGACY_TO_PERMISSION.put('n', "format.underline");
        LEGACY_TO_PERMISSION.put('o', "format.italic");
        LEGACY_TO_PERMISSION.put('r', "format.reset");
    }

    private ChatColorParser() {
    }

    /** Parses text typed by {@code player}, honouring their colour/format permissions. */
    public static Component parse(Player player, String raw) {
        return parse(permissionsOf(player), raw, CHAT);
    }

    public static Component parse(PermissionCheck permissions, String raw) {
        return parse(permissions, raw, CHAT);
    }

    /** As {@link #parse(PermissionCheck, String)}, for text whose permissions live under {@code scope}. */
    public static Component parse(PermissionCheck permissions, String raw, String scope) {
        return deserialize(toMiniMessage(permissions, raw, scope));
    }

    /**
     * Deserializes MiniMessage source that {@link #toMiniMessage} produced. Callers that want
     * to inspect the filtered source first - the sign listener logs it - can take the two steps
     * separately instead of filtering twice.
     */
    public static Component deserialize(String miniMessageSource) {
        if (miniMessageSource == null || miniMessageSource.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(miniMessageSource);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to parse colours, falling back to plain text: " + e.getMessage());
            return Component.text(stripFormatting(miniMessageSource));
        }
    }

    /** Returns the permission-filtered text as MiniMessage source. */
    public static String toMiniMessage(Player player, String raw) {
        return toMiniMessage(permissionsOf(player), raw, CHAT);
    }

    public static String toMiniMessage(PermissionCheck permissions, String raw) {
        return toMiniMessage(permissions, raw, CHAT);
    }

    /** As {@link #toMiniMessage(PermissionCheck, String)}, for text under the {@code scope} permission tree. */
    public static String toMiniMessage(PermissionCheck permissions, String raw, String scope) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        PermissionCheck checks = permissions == null ? DENY_ALL : permissions;
        String root = scope == null || scope.isEmpty() ? CHAT : scope;
        return legacyToMiniMessage(filterLegacyCodes(checks, root, filterMiniMessageTags(checks, root, raw)));
    }

    /** Removes every legacy code and MiniMessage tag, leaving the visible text. */
    public static String stripFormatting(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String stripped = X_HEX_PATTERN.matcher(raw).replaceAll("");
        stripped = HEX_PATTERN.matcher(stripped).replaceAll("");
        stripped = CODE_PATTERN.matcher(stripped).replaceAll("");
        try {
            stripped = MINI_MESSAGE.stripTags(stripped);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to strip MiniMessage tags: " + e.getMessage());
        }
        return stripped;
    }

    /**
     * Length of the legacy colour token starting at {@code index}, or 0 when the text at
     * that position is not a colour token. Lets callers walk past leading colours without
     * re-implementing the token shapes.
     */
    public static int formattingTokenLength(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return 0;
        }
        char marker = text.charAt(index);
        if (marker != '&' && marker != '§') {
            return 0;
        }
        int hex = matchLength(X_HEX_PATTERN, text, index);
        if (hex > 0) {
            return hex;
        }
        hex = matchLength(HEX_PATTERN, text, index);
        if (hex > 0) {
            return hex;
        }
        return matchLength(CODE_PATTERN, text, index);
    }

    /** True when the text carries markup either system would act on. */
    public static boolean containsFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return containsMiniMessageTags(text)
                || X_HEX_PATTERN.matcher(text).find()
                || HEX_PATTERN.matcher(text).find()
                || CODE_PATTERN.matcher(text).find();
    }

    public static boolean containsMiniMessageTags(String text) {
        return text != null && MINI_TAG_PATTERN.matcher(text).find();
    }

    /**
     * True when the text asks for a colour: a legacy colour code, either hex form, or a
     * MiniMessage colour tag. Distinct from {@link #containsFormatting}, which also answers true
     * for format codes - callers that need to tell "coloured" from "slanted" want this one.
     */
    public static boolean containsColor(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (X_HEX_PATTERN.matcher(text).find() || HEX_PATTERN.matcher(text).find()) {
            return true;
        }
        Matcher matcher = CODE_PATTERN.matcher(text);
        while (matcher.find()) {
            if (isColorCode(Character.toLowerCase(matcher.group(1).charAt(0)))) {
                return true;
            }
        }
        return MINI_COLOR_PATTERN.matcher(text).matches();
    }

    private static PermissionCheck permissionsOf(Player player) {
        return player == null ? DENY_ALL : player::hasPermission;
    }

    /**
     * Drops MiniMessage tags the author may not use. Runs before the legacy conversion so
     * a player with colour permissions but no MiniMessage permissions still gets their
     * {@code &a} codes - the tags we generate ourselves are never permission checked.
     */
    private static String filterMiniMessageTags(PermissionCheck permissions, String scope, String text) {
        if (!containsMiniMessageTags(text)) {
            return text;
        }
        if (!permissions.has(scope + ".minimessage") && !permissions.has(scope + ".minimessage.*")) {
            return MINI_MESSAGE.stripTags(text);
        }
        return isMiniMessageAllowed(permissions, scope, text) ? text : MINI_MESSAGE.stripTags(text);
    }

    private static boolean hasMiniMessagePermission(PermissionCheck permissions, String scope, String tagType) {
        if (permissions.has(scope + ".minimessage.*")) {
            return true;
        }
        String permission = scope + ".minimessage." + tagType.toLowerCase(Locale.ROOT);
        return permissions.has(permission) || permissions.has(permission + ".*");
    }

    /**
     * Mirrors the per-tag gates the chat formatter has always applied. The gradient and
     * rainbow patterns also cover their argument-less forms, which used to slip through
     * because the old checks required a ':' or '#' right after the tag name.
     */
    private static boolean isMiniMessageAllowed(PermissionCheck permissions, String scope, String text) {
        if (text.matches("(?i).*<(color:[^>]*|#[0-9A-F]{6}|black|dark_blue|dark_green|dark_aqua|dark_red"
                + "|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white)>.*")
                && !hasMiniMessagePermission(permissions, scope, "color")) {
            return false;
        }
        if (text.matches("(?i).*<gradient(:[^>]*)?>.*")) {
            String tagType = text.matches("(?i).*<gradient[^>]*:phase-.*?>.*") ? "gradient.animation" : "gradient";
            if (!hasMiniMessagePermission(permissions, scope, tagType)) {
                return false;
            }
        }
        if (text.matches("(?i).*<rainbow(:[^>]*)?>.*") && !hasMiniMessagePermission(permissions, scope, "rainbow")) {
            return false;
        }
        if (text.matches("(?i).*<click:.*?>.*") && !hasMiniMessagePermission(permissions, scope, "click")) {
            return false;
        }
        if (text.matches("(?i).*<hover:.*?>.*") && !hasMiniMessagePermission(permissions, scope, "hover")) {
            return false;
        }
        return !text.matches("(?i).*<(b|bold|i|italic|u|underlined|st|strikethrough|obf|obfuscated|reset)>.*")
                || hasMiniMessagePermission(permissions, scope, "format");
    }

    /**
     * Drops legacy codes the author may not use. Unlike a blind two-character skip this
     * keeps hex codes whole and leaves a lone ampersand as literal text.
     */
    private static String filterLegacyCodes(PermissionCheck permissions, String scope, String text) {
        boolean allowAnyColor = permissions.has(scope + ".color") || permissions.has(scope + ".color.*");
        boolean allowAnyFormat = permissions.has(scope + ".format") || permissions.has(scope + ".format.*");
        boolean allowHex = allowAnyColor || permissions.has(scope + ".color.hex");

        StringBuilder filtered = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char current = text.charAt(i);
            if (current != '&' && current != '§') {
                filtered.append(current);
                i++;
                continue;
            }

            int hexLength = matchLength(X_HEX_PATTERN, text, i);
            if (hexLength == 0) {
                hexLength = matchLength(HEX_PATTERN, text, i);
            }
            if (hexLength > 0) {
                if (allowHex) {
                    filtered.append(text, i, i + hexLength);
                }
                i += hexLength;
                continue;
            }

            int codeLength = matchLength(CODE_PATTERN, text, i);
            if (codeLength > 0) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (isCodeAllowed(permissions, scope, code, allowAnyColor, allowAnyFormat)) {
                    filtered.append(text, i, i + codeLength);
                }
                i += codeLength;
                continue;
            }

            // Not a colour code - an ampersand the player meant literally.
            filtered.append(current);
            i++;
        }
        return filtered.toString();
    }

    private static boolean isCodeAllowed(PermissionCheck permissions, String scope, char code,
                                         boolean allowAnyColor, boolean allowAnyFormat) {
        String suffix = LEGACY_TO_PERMISSION.get(code);
        if (suffix == null) {
            return false;
        }
        String permission = scope + "." + suffix;
        if (isColorCode(code)) {
            return allowAnyColor || permissions.has(permission);
        }
        // 'k' historically answers to both spellings of the obfuscated permission.
        return allowAnyFormat
                || permissions.has(permission)
                || (code == 'k' && permissions.has(scope + ".format.obfuscated"));
    }

    /**
     * Rewrites the surviving legacy codes as MiniMessage tags. Colour codes clear existing
     * formatting in legacy chat, so they carry a {@code <reset>}; format codes do not.
     */
    private static String legacyToMiniMessage(String text) {
        String converted = replaceAll(X_HEX_PATTERN, text,
                matcher -> "<reset><#" + matcher.group(1).replaceAll("[&§]", "").toUpperCase(Locale.ROOT) + ">");
        converted = replaceAll(HEX_PATTERN, converted,
                matcher -> "<reset><#" + matcher.group(1).toUpperCase(Locale.ROOT) + ">");
        return replaceAll(CODE_PATTERN, converted, matcher -> {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            String tag = LEGACY_TO_TAG.get(code);
            if (tag == null) {
                return matcher.group();
            }
            return isColorCode(code) ? "<reset>" + tag : tag;
        });
    }

    private static String replaceAll(Pattern pattern, String text, Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder buffer = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean isColorCode(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
    }

    /** Length of {@code pattern}'s match anchored at {@code index}, or 0 when it does not match there. */
    private static int matchLength(Pattern pattern, String text, int index) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(index, text.length());
        return matcher.lookingAt() ? matcher.end() - index : 0;
    }
}
