package codes.castled.allium.frames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * frames.yml: the lore frames a {@code [frame:<name>]} marker can ask for.
 *
 * <p>A frame is either one of the built-in trading card frames, named after
 * its tier, or a background and a frame sprite from the resource pack, put
 * together by {@code /frame build}. Entries are listed in priority order:
 * {@code [frame:auto]} gives a player the first one whose permission they have,
 * and the fallback when they have none.
 *
 * @param pack     the resource pack folder the sprites are read from and the
 *                 built pieces are written into, relative to the server root
 * @param fallback the frame a player with no frame permission gets
 * @param frames   every frame by name, in the order {@code auto} checks them
 */
public record FramesConfig(String pack, String fallback, Map<String, Entry> frames) {

    public static final String FILE = "frames.yml";
    public static final String PERMISSION_ROOT = "allium.frame.";
    public static final int DEFAULT_WIDTH = 200;

    public FramesConfig {
        frames = Collections.unmodifiableMap(new LinkedHashMap<>(frames));
    }

    /**
     * One frame.
     *
     * @param card       a built-in trading card frame tier, or null
     * @param background the background sprite's texture path, as
     *                   {@code namespace:path} under {@code textures/}, or null
     * @param frame      the frame sprite's texture path, or null
     * @param width      the frame's width in pixels; text gets 24 less
     * @param permission what {@code auto} checks for this frame
     */
    public record Entry(String name, String card, String background, String frame,
                        int width, String permission) {
        public boolean sprite() {
            return card == null;
        }
    }

    public static FramesConfig empty() {
        return new FramesConfig("plugins/Nexo/pack", "simple", Map.of());
    }

    /** Reads frames.yml, adding a line to {@code problems} for anything skipped. */
    public static FramesConfig load(ConfigurationSection yaml, List<String> problems) {
        String pack = yaml.getString("pack", "plugins/Nexo/pack");
        String fallback = name(yaml.getString("fallback-frame", "simple"));
        Map<String, Entry> frames = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("frames");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection f = section.getConfigurationSection(key);
                String name = name(key);
                if (f == null || name.equals("auto")) {
                    problems.add("frames." + key + ": skipped, "
                        + (f == null ? "not a section" : "'auto' is reserved"));
                    continue;
                }
                String card = f.getString("card");
                String background = f.getString("background");
                String frame = f.getString("frame");
                if (card == null && background == null && frame == null) {
                    problems.add("frames." + key + ": needs card, or background and/or frame");
                    continue;
                }
                int width = Math.max(48, f.getInt("width", DEFAULT_WIDTH));
                String permission = f.getString("permission", PERMISSION_ROOT + name);
                frames.put(name, new Entry(name, card == null ? null : name(card),
                    background, frame, width, permission));
            }
        }
        return new FramesConfig(pack, fallback, frames);
    }

    /** Frame names are matched case-insensitively. */
    public static String name(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** The sprite frames, which are the ones {@code /frame build} has to make. */
    public List<Entry> spriteFrames() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : frames.values()) {
            if (e.sprite()) out.add(e);
        }
        return out;
    }
}
