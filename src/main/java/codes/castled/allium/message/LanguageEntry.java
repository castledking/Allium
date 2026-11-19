package codes.castled.allium.message;

import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

/**
 * A locale entry is inert text, an optional actionbar line, and one optional sound.
 * {@code soundDeclared} says whether the entry has a sound line at all: one without (a translation
 * that only translates the text) uses the default locale's sound, while {@code [sound] none}
 * silences the message.
 */
public record LanguageEntry(String text, String actionBar, MessageSoundSpec sound, boolean soundDeclared) {
    private static final String SOUND_PREFIX = "[sound]";
    private static final String ACTIONBAR_PREFIX = "[actionbar]";

    public LanguageEntry(String text, MessageSoundSpec sound) {
        this(text, null, sound, sound != null);
    }

    public LanguageEntry(String text, String actionBar, MessageSoundSpec sound) {
        this(text, actionBar, sound, sound != null);
    }

    public static LanguageEntry read(Object value) {
        if (value instanceof String raw) {
            if (isActionBarLine(raw)) {
                String bar = extractActionBar(raw);
                return new LanguageEntry("", bar.isEmpty() ? null : bar, null);
            }
            return new LanguageEntry(raw, null, null);
        }
        if (value instanceof ConfigurationSection section) {
            if (!section.contains("text") && !section.contains("actionbar") && !section.contains("sound")) return null;
            String bar = section.contains("actionbar") ? section.getString("actionbar", "").trim() : null;
            return new LanguageEntry(section.getString("text", ""),
                    bar == null || bar.isEmpty() ? null : bar,
                    sound(section.getString("sound"),
                            section.contains("volume") ? section.getDouble("volume") : null,
                            section.contains("pitch") ? section.getDouble("pitch") : null),
                    section.contains("sound"));
        }
        if (!(value instanceof List<?> lines)) return null;
        var text = new ArrayList<String>();
        String actionBar = null;
        boolean actionBarSeen = false;
        MessageSoundSpec selected = null;
        boolean soundSeen = false;
        for (Object raw : lines) {
            if (!(raw instanceof String line)) continue;
            if (line.startsWith(SOUND_PREFIX)) {
                if (soundSeen) continue;
                soundSeen = true;
                String[] parts = line.substring(SOUND_PREFIX.length()).trim().split("\\s+");
                if (parts.length < 1 || parts.length > 3) continue;
                selected = sound(parts[0], parts.length == 3 ? number(parts[2]) : null, parts.length >= 2 ? number(parts[1]) : null);
            } else if (isActionBarLine(line)) {
                if (actionBarSeen) continue;
                actionBarSeen = true;
                String bar = extractActionBar(line);
                if (!bar.isEmpty()) actionBar = bar;
            } else {
                text.add(line);
            }
        }
        return new LanguageEntry(String.join("\n", text), actionBar, selected, soundSeen);
    }

    private static boolean isActionBarLine(String line) {
        return line.length() >= ACTIONBAR_PREFIX.length()
                && line.regionMatches(true, 0, ACTIONBAR_PREFIX, 0, ACTIONBAR_PREFIX.length())
                && (line.length() == ACTIONBAR_PREFIX.length() || Character.isWhitespace(line.charAt(ACTIONBAR_PREFIX.length())));
    }

    private static String extractActionBar(String line) {
        return line.substring(ACTIONBAR_PREFIX.length()).trim();
    }
    private static Double number(String value){try{double n=Double.parseDouble(value);return Double.isFinite(n)?n:null;}catch(NumberFormatException invalid){return null;}}
    private static MessageSoundSpec sound(String id,Double volume,Double pitch){
        if(id==null||id.isBlank()||Set.of("none","off").contains(id.trim().toLowerCase(Locale.ROOT)))return null;
        id=id.trim();if(!id.contains(":")&&id.contains("."))id="minecraft:"+id;
        return new MessageSoundSpec(id,volume,pitch);
    }
}
