package codes.castled.allium.lang;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a pre-recode Allium {@code lang.yml} into entries for the recode's
 * {@code lang/<locale>.yml}. Pure: it reads a parsed document (nested maps, lists and strings)
 * and returns what to write plus a report; the plugin does the file work.
 *
 * <p>A legacy message is a string or a list of lines. List lines may be directives:
 * {@code [sound] <id> [volume] [pitch]} (also {@code [sound <id> ...]}) becomes the entry's
 * sound, with an omitted volume or pitch left out so each falls back separately to
 * {@code sounds.defaults}; {@code [particle]}, {@code [command]} and any other directive are
 * dropped and reported, so a lang file can never run a command. MiniMessage colour and
 * decoration tags become {@code &} codes; gradients, rainbows and hex colours are kept, since
 * Allium's formatter reads them; click, hover and other interactive tags are removed.
 *
 * <p>Only keys with an explicit mapping are imported. A recode default that starts with
 * {@code {prefix}} gets the prefix on the imported text too, because pre-recode Allium added
 * prefixes in code.
 */
public final class LegacyLangImport {

    /** One legacy key, its recode key and placeholder renames ({@code {name}} to {@code {player}}). */
    public record Mapping(String legacyKey, String key, Map<String, String> placeholders) {
        public Mapping {
            placeholders = Map.copyOf(placeholders);
        }
    }

    /** What to write for one recode key. A null sound means a text-only message. */
    public record Entry(String legacyKey, String key, String text, @Nullable String sound, @Nullable Double volume,
                        @Nullable Double pitch, List<String> notes) {
        public Entry {
            notes = List.copyOf(notes);
        }
    }

    /**
     * @param entries  the converted messages
     * @param unmapped legacy message keys with no recode counterpart (features not ported yet)
     * @param notes    per-key changes worth reading (dropped directives, removed tags)
     */
    public record Report(List<Entry> entries, List<String> unmapped, List<String> notes) {
        public Report {
            entries = List.copyOf(entries);
            unmapped = List.copyOf(unmapped);
            notes = List.copyOf(notes);
        }
    }

    private static final Map<String, String> NAME_TO_PLAYER = Map.of("{name}", "{player}");

    /** Every legacy key this importer knows, in report order. */
    public static final List<Mapping> MAPPINGS = List.of(
            new Mapping("core-prefix", "prefix", Map.of()),
            new Mapping("no-permission", "error.no-permission", Map.of()),
            new Mapping("player-only-command", "error.player-only", Map.of()),
            new Mapping("console-only", "error.console-only", Map.of()),
            new Mapping("player-not-found", "error.player-not-found", NAME_TO_PLAYER),
            new Mapping("reload.reload-success", "core.reload-success", Map.of()),
            new Mapping("reload.reload-failure", "core.reload-failed", Map.of()),
            new Mapping("heal.self", "player.heal", Map.of()),
            new Mapping("heal.healed-other", "player.heal-other", NAME_TO_PLAYER),
            new Mapping("feed.self", "player.feed", Map.of()),
            new Mapping("feed.other", "player.feed-other", NAME_TO_PLAYER),
            new Mapping("fly.toggle", "player.fly-toggle", NAME_TO_PLAYER),
            new Mapping("god.toggle", "player.god-toggle", NAME_TO_PLAYER),
            new Mapping("gamemode.switch", "player.gamemode-self", Map.of()),
            new Mapping("gamemode.switch-other", "player.gamemode-other", NAME_TO_PLAYER),
            new Mapping("msg.sent", "messaging.sent", Map.of()),
            new Mapping("msg.received", "messaging.received", Map.of()),
            new Mapping("msg.reply", "messaging.no-reply-target", Map.of())
    );

    /** Keys that are configuration, not messages, and are never reported as unmapped. */
    private static final List<String> NOT_MESSAGES = List.of("version", "error-prefix", "styles", "unknown-locale");

    /** Line directives pre-recode Allium understood; any other bracketed line is text. */
    private static final List<String> DIRECTIVES = List.of("sound", "particle", "command", "actionbar", "title", "subtitle", "json");
    private static final Pattern DIRECTIVE = Pattern.compile("^\\[(\\w+)(?:]\\s*|\\s+)(.*?)]?\\s*$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z_-]+}");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z_#][^<>]*)>");
    private static final Map<String, String> CODES = codes();

    private LegacyLangImport() {
    }

    /**
     * @param document       the parsed legacy file
     * @param recodeDefault  the recode's built-in text for a key, or null when the key is unknown
     */
    public static Report convert(Map<String, ?> document, Function<String, String> recodeDefault) {
        Objects.requireNonNull(document, "document");
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", document, flat);
        List<Entry> entries = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> mappedKeys = new ArrayList<>();
        for (Mapping mapping : MAPPINGS) {
            mappedKeys.add(mapping.legacyKey());
            if (!flat.containsKey(mapping.legacyKey())) {
                continue;
            }
            String fallback = recodeDefault.apply(mapping.key());
            if (fallback == null) {
                notes.add(mapping.legacyKey() + ": the recode has no " + mapping.key() + "; skipped");
                continue;
            }
            Entry entry = convertOne(mapping, flat.get(mapping.legacyKey()), fallback);
            entries.add(entry);
            entry.notes().forEach(note -> notes.add(mapping.legacyKey() + ": " + note));
        }
        List<String> unmapped = new ArrayList<>();
        for (String key : flat.keySet()) {
            if (!mappedKeys.contains(key) && NOT_MESSAGES.stream().noneMatch(n -> key.equals(n) || key.startsWith(n + "."))) {
                unmapped.add(key);
            }
        }
        return new Report(entries, unmapped, notes);
    }

    static Entry convertOne(Mapping mapping, Object value, String recodeDefault) {
        List<String> notes = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                lines.add(String.valueOf(element));
            }
        } else {
            lines.add(String.valueOf(value));
        }
        List<String> text = new ArrayList<>();
        String sound = null;
        Double volume = null;
        Double pitch = null;
        for (String line : lines) {
            Matcher directive = DIRECTIVE.matcher(line.trim());
            String name = directive.matches() ? directive.group(1).toLowerCase(Locale.ROOT) : "";
            if (!DIRECTIVES.contains(name)) {
                text.add(line); // "[Server] hello" is text, not a directive
                continue;
            }
            if (!name.equals("sound")) {
                notes.add("dropped the [" + name + "] line; lang entries can only carry text and one sound");
                continue;
            }
            if (sound != null) {
                notes.add("dropped a second [sound] line");
                continue;
            }
            String[] parts = directive.group(2).trim().split("\\s+");
            if (parts.length == 0 || parts[0].isBlank()) {
                notes.add("dropped an empty [sound] line");
                continue;
            }
            sound = normalizeSound(parts[0]);
            if (parts.length > 1) {
                volume = number(parts[1], "volume", notes);
            }
            if (parts.length > 2) {
                pitch = number(parts[2], "pitch", notes);
            }
            if (parts.length > 3) {
                notes.add("ignored extra [sound] values after the pitch");
            }
        }
        String joined = String.join("\n", text).strip();
        String converted = convertTags(joined, notes);
        for (Map.Entry<String, String> rename : mapping.placeholders().entrySet()) {
            converted = converted.replace(rename.getKey(), rename.getValue());
        }
        if (recodeDefault.startsWith("{prefix}") && !converted.contains("{prefix}")) {
            converted = converted.isEmpty() ? "{prefix}" : "{prefix} " + converted;
        }
        List<String> unfilled = new ArrayList<>();
        Matcher placeholder = PLACEHOLDER.matcher(converted);
        while (placeholder.find()) {
            String name = placeholder.group();
            if (!recodeDefault.contains(name) && !name.equals("{prefix}") && !unfilled.contains(name)) {
                unfilled.add(name);
            }
        }
        if (!unfilled.isEmpty()) {
            notes.add("uses " + String.join(", ", unfilled) + ", which Allium's own text for " + mapping.key()
                    + " does not; where Allium does not fill it, it shows as written");
        }
        return new Entry(mapping.legacyKey(), mapping.key(), converted, sound, volume, pitch, notes);
    }

    /** {@code block.note_block.pling} to {@code minecraft:block.note_block.pling}; enum names stay as they are. */
    static String normalizeSound(String raw) {
        String trimmed = raw.trim();
        if (trimmed.contains(":")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (trimmed.contains(".")) {
            return "minecraft:" + trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed;
    }

    /**
     * MiniMessage to Allium's formatter: named colours and decorations become {@code &} codes,
     * closing tags are dropped, gradients, rainbows and hex colours are kept, and anything else
     * (click, hover, insertion, fonts, translations) is removed.
     */
    static String convertTags(String text, List<String> notes) {
        Matcher matcher = TAG.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean droppedClosing = false;
        List<String> removed = new ArrayList<>();
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String body = matcher.group(2);
            String name = body.toLowerCase(Locale.ROOT);
            String replacement;
            if (name.startsWith("gradient") || name.startsWith("rainbow") || name.startsWith("#")) {
                replacement = matcher.group();
            } else if (closing) {
                replacement = "";
                droppedClosing = true;
            } else if (name.equals("newline") || name.equals("br")) {
                replacement = "\n";
            } else if (name.startsWith("color:") || name.startsWith("colour:") || name.startsWith("c:")) {
                String colour = name.substring(name.indexOf(':') + 1);
                replacement = colour.startsWith("#") ? "<" + colour + ">" : CODES.getOrDefault(colour, "");
                if (replacement.isEmpty()) {
                    removed.add(body);
                }
            } else if (CODES.containsKey(name)) {
                replacement = CODES.get(name);
            } else {
                replacement = "";
                removed.add(body.contains(":") ? body.substring(0, body.indexOf(':')) : body);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        if (droppedClosing) {
            notes.add("closing MiniMessage tags were dropped; & codes do not revert, so check the colours");
        }
        if (!removed.isEmpty()) {
            notes.add("removed unsupported tags " + removed.stream().distinct().toList());
        }
        return out.toString();
    }

    @Nullable
    private static Double number(String raw, String what, List<String> notes) {
        try {
            double value = Double.parseDouble(raw);
            if (Double.isFinite(value)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        notes.add("the [sound] " + what + " '" + raw + "' is not a number; left out, so the default applies");
        return null;
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, Object> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flatten(key, nested, out);
            } else if (entry.getValue() != null) {
                out.put(key, entry.getValue());
            }
        }
    }

    private static Map<String, String> codes() {
        Map<String, String> codes = new LinkedHashMap<>();
        String[] names = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        String digits = "0123456789abcdef";
        for (int i = 0; i < names.length; i++) {
            codes.put(names[i], "&" + digits.charAt(i));
        }
        codes.put("grey", "&7");
        codes.put("dark_grey", "&8");
        for (String bold : List.of("bold", "b")) {
            codes.put(bold, "&l");
        }
        for (String italic : List.of("italic", "i", "em")) {
            codes.put(italic, "&o");
        }
        for (String underline : List.of("underlined", "u")) {
            codes.put(underline, "&n");
        }
        for (String strike : List.of("strikethrough", "st")) {
            codes.put(strike, "&m");
        }
        for (String obfuscated : List.of("obfuscated", "obf")) {
            codes.put(obfuscated, "&k");
        }
        codes.put("reset", "&r");
        return codes;
    }
}
