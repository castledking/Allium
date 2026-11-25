package codes.castled.curios;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Optional {@code frames.yml} authoring config for custom Curios item and chat hover frames.
 *
 * <p>Each entry is one of:
 * <ul>
 *   <li>A card-tier alias ({@code card: simple|elite|ultimate|legendary|fabled}),</li>
 *   <li>An alias to another bundled/custom frame ({@code parent: <frame-name>}), or</li>
 *   <li>A custom sprite frame ({@code background:} and/or {@code frame:}, built via {@code /frame build}).</li>
 * </ul>
 * Entries are evaluated in declaration order when resolving {@code auto} frames by permission.
 */
final class CustomFramesConfig {

    static final String FILE = "frames.yml";
    static final String DEFAULT_PACK = "plugins/Allium/custom-frames-pack";
    static final String DEFAULT_FALLBACK = "simple";
    static final String PERMISSION_ROOT = "allium.frame.";
    static final int DEFAULT_WIDTH = 200;
    static final int MIN_WIDTH = 48;
    static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_]{1,48}");

    record Entry(
            String name,
            String aliasTarget,
            String background,
            String frame,
            int width,
            String permission
    ) {
        boolean isAlias() {
            return aliasTarget != null && !aliasTarget.isEmpty();
        }

        boolean isSprite() {
            return !isAlias();
        }
    }

    private final String pack;
    private final String fallback;
    private final Map<String, Entry> frames;

    CustomFramesConfig(String pack, String fallback, Map<String, Entry> frames) {
        this.pack = (pack == null || pack.isBlank()) ? DEFAULT_PACK : pack.trim();
        String normalizedFallback = normalizeName(fallback);
        this.fallback = normalizedFallback.isEmpty() ? DEFAULT_FALLBACK : normalizedFallback;
        this.frames = Collections.unmodifiableMap(new LinkedHashMap<>(frames));
    }

    static CustomFramesConfig empty() {
        return new CustomFramesConfig(DEFAULT_PACK, DEFAULT_FALLBACK, Map.of());
    }

    static CustomFramesConfig load(ConfigurationSection yaml, List<String> problems) {
        if (yaml == null) return empty();
        String pack = yaml.getString("pack", DEFAULT_PACK);
        String fallback = normalizeName(yaml.getString("fallback-frame", DEFAULT_FALLBACK));
        if (fallback.isEmpty() || fallback.equals("auto") || fallback.equals("off") || !VALID_NAME.matcher(fallback).matches()) {
            problems.add("frames.yml fallback-frame '" + yaml.getString("fallback-frame") + "' is invalid; using '" + DEFAULT_FALLBACK + "'");
            fallback = DEFAULT_FALLBACK;
        }
        Map<String, Entry> loaded = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("frames");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection f = section.getConfigurationSection(key);
                String name = normalizeName(key);
                if (f == null) {
                    problems.add("frames." + key + ": skipped, expected a section");
                    continue;
                }
                if (name.equals("auto") || name.equals("off") || !VALID_NAME.matcher(name).matches()) {
                    problems.add("frames." + key + ": skipped, invalid or reserved frame name");
                    continue;
                }
                String card = normalizeName(f.getString("card"));
                String parent = normalizeName(f.getString("parent"));
                String background = trimToNull(f.getString("background"));
                String frame = trimToNull(f.getString("frame"));
                String aliasTarget = !card.isEmpty() ? card : (!parent.isEmpty() ? parent : null);
                if (aliasTarget == null && background == null && frame == null) {
                    problems.add("frames." + key + ": needs card, parent, or background/frame");
                    continue;
                }
                if (aliasTarget != null && (!VALID_NAME.matcher(aliasTarget).matches()
                        || aliasTarget.equals("auto") || aliasTarget.equals("off"))) {
                    problems.add("frames." + key + ": invalid alias target '" + aliasTarget + "'");
                    continue;
                }
                int width = Math.max(MIN_WIDTH, f.getInt("width", DEFAULT_WIDTH));
                String permission = f.isSet("permission")
                        ? trimToEmpty(f.getString("permission"))
                        : PERMISSION_ROOT + name;
                loaded.put(name, new Entry(name, aliasTarget, background, frame, width, permission));
            }
        }
        return new CustomFramesConfig(pack, fallback, loaded);
    }

    String pack() {
        return pack;
    }

    String fallback() {
        return fallback;
    }

    Map<String, Entry> frames() {
        return frames;
    }

    List<Entry> spriteFrames() {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : frames.values()) {
            if (entry.isSprite()) out.add(entry);
        }
        return out;
    }

    static String normalizeName(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
