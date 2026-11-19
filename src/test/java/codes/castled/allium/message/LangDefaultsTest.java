package codes.castled.allium.message;

import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangDefaultsTest {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-z0-9_]+}");
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("&[0-9a-fA-Fk-oK-OrR]");
    private static final Set<String> SECTION_ALLOWED_FIELDS = Set.of("text", "actionbar", "sound", "volume", "pitch");
    private static final Set<String> EXPECTED_KEYS;

    static {
        Set<String> keys = new LinkedHashSet<>();
        for (Messages message : Messages.values()) {
            keys.add(message.getKey());
        }
        EXPECTED_KEYS = Set.copyOf(keys);
    }

    private static YamlConfiguration loadLang(String resourcePath) throws Exception {
        try (InputStream stream = LangDefaultsTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(stream, "Missing bundled resource: " + resourcePath);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static List<String> collectLeafEntryKeys(ConfigurationSection root) {
        List<String> entries = new ArrayList<>();
        collectRecursive(root, "", entries);
        return entries;
    }

    private static void collectRecursive(ConfigurationSection section, String prefix, List<String> out) {
        for (String key : section.getKeys(false)) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                ConfigurationSection child = section.getConfigurationSection(key);
                assertNotNull(child, fullKey);
                if (child.contains("text") || child.contains("actionbar") || child.contains("sound")) {
                    assertTrue(SECTION_ALLOWED_FIELDS.containsAll(child.getKeys(false)),
                            "Unexpected fields under message section " + fullKey + ": " + child.getKeys(false));
                    out.add(fullKey);
                } else {
                    collectRecursive(child, fullKey, out);
                }
            } else {
                out.add(fullKey);
            }
        }
    }

    private static String readVisibleText(YamlConfiguration yaml, String key) {
        var entry = LanguageEntry.read(yaml.get(key));
        assertNotNull(entry, key);
        if (entry.actionBar() != null && !entry.actionBar().isEmpty()) {
            return entry.text().isEmpty() ? entry.actionBar() : entry.text() + "\n" + entry.actionBar();
        }
        return entry.text();
    }

    private static Set<String> placeholdersOf(String text) {
        Set<String> found = new HashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static Set<String> colorsOf(String text) {
        Set<String> found = new HashSet<>();
        Matcher matcher = LEGACY_COLOR_PATTERN.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group().toLowerCase());
        }
        return found;
    }

    private static final Set<String> VALID_SOUND_IDS;

    static {
        Set<String> sounds = new HashSet<>();
        for (Sound sound : Sound.values()) {
            sounds.add(sound.getKey().toString());
        }
        VALID_SOUND_IDS = Set.copyOf(sounds);
    }

    private static void assertValidVanillaSound(String soundId, String messageKey) {
        assertTrue(soundId.startsWith("minecraft:"), "Sound id should be namespaced at " + messageKey + ": " + soundId);
        NamespacedKey key = NamespacedKey.fromString(soundId);
        assertNotNull(key, "Invalid namespaced sound key at " + messageKey + ": " + soundId);
        assertTrue(VALID_SOUND_IDS.contains(key.toString()), "Unknown Bukkit sound " + soundId + " at " + messageKey);
    }

    @Test void everyVisibleMessageHasColorExceptTheInlineConsoleLabel()throws Exception{
        for(String locale:List.of("en_US","es_ES")){var yaml=loadLang("lang/"+locale+".yml");for(Messages message:Messages.values()){
            if(message.getKey().equals("messaging.console-name"))continue;
            String text=readVisibleText(yaml,message.getKey());assertTrue(LEGACY_COLOR_PATTERN.matcher(text).find()||text.contains("{prefix}"),locale+": "+message.getKey());
        }}
    }
    @Test
    void bothBundledLangFilesContainAllMessagesWithExactColorsValidSoundsAndParity() throws Exception {
        YamlConfiguration en = loadLang("lang/en_US.yml");
        YamlConfiguration es = loadLang("lang/es_ES.yml");

        // No YAML boolean keys (e.g. unquoted on/off under messaging.toggle)
        assertFalse(en.contains("messaging.toggle.true"), "messaging.toggle.on must be quoted in en_US.yml");
        assertFalse(en.contains("messaging.toggle.false"), "messaging.toggle.off must be quoted in en_US.yml");
        assertFalse(es.contains("messaging.toggle.true"), "messaging.toggle.on must be quoted in es_ES.yml");
        assertFalse(es.contains("messaging.toggle.false"), "messaging.toggle.off must be quoted in es_ES.yml");

        List<String> enKeys = collectLeafEntryKeys(en);
        List<String> esKeys = collectLeafEntryKeys(es);
        assertEquals(EXPECTED_KEYS.size(), enKeys.size(), "en_US.yml entry count should match Messages enum count");
        assertEquals(EXPECTED_KEYS.size(), esKeys.size(), "es_ES.yml entry count should match Messages enum count");
        assertEquals(EXPECTED_KEYS, new LinkedHashSet<>(enKeys), "en_US.yml keys must match Messages exactly");
        assertEquals(EXPECTED_KEYS, new LinkedHashSet<>(esKeys), "es_ES.yml keys must match Messages exactly");

        int withSounds = 0;
        for (Messages msg : Messages.values()) {
            String key = msg.getKey();
            LanguageEntry defEntry = LanguageEntry.read(msg.getDefault());
            LanguageEntry enEntry = LanguageEntry.read(en.get(key));
            LanguageEntry esEntry = LanguageEntry.read(es.get(key));
            assertNotNull(defEntry, "Missing default entry for " + key);
            assertNotNull(enEntry, "Missing en_US entry for " + key);
            assertNotNull(esEntry, "Missing es_ES entry for " + key);

            String defVisible = defEntry.actionBar() != null && !defEntry.actionBar().isEmpty()
                    ? (defEntry.text().isEmpty() ? defEntry.actionBar() : defEntry.text() + "\n" + defEntry.actionBar())
                    : defEntry.text();
            String enVisible = readVisibleText(en, key);
            String esVisible = readVisibleText(es, key);

            if (msg == Messages.KIT_PAID_DENIED) {
                // Keep frozen S16 live-gate string in en_US.yml while ensuring placeholders and colors still match.
                assertEquals(placeholdersOf(defVisible), placeholdersOf(enVisible), key);
                assertEquals(colorsOf(defVisible), colorsOf(enVisible), key);
            } else {
                assertEquals(defEntry.text(), enEntry.text(), "en_US text mismatch for " + key);
                assertEquals(defEntry.actionBar(), enEntry.actionBar(), "en_US actionbar mismatch for " + key);
            }

            assertEquals(defEntry.actionBar() != null, esEntry.actionBar() != null,
                    "Actionbar presence mismatch in es_ES for " + key);
            assertEquals(placeholdersOf(defVisible), placeholdersOf(esVisible),
                    "Placeholder mismatch in es_ES for " + key);
            assertEquals(colorsOf(defVisible), colorsOf(esVisible),
                    "Color scheme mismatch in es_ES for " + key);

            assertEquals(en.isList(key),es.isList(key),key);
            if(!key.equals("prefix"))assertTrue(en.isList(key),key);
            var enSound=LanguageEntry.read(en.get(key)).sound();var esSound=LanguageEntry.read(es.get(key)).sound();
            assertEquals(enSound,esSound,key);
            if(enSound!=null){assertValidVanillaSound(enSound.soundId(),key);assertNull(enSound.volume(),"Bundled messages use the server volume: "+key);if(enSound.pitch()!=null)assertTrue(enSound.pitch()>=0.5&&enSound.pitch()<=2.0,key);withSounds++;}
        }

        assertTrue(withSounds >= 300, "Expected curated sound coverage across interactive lang defaults, got " + withSounds);

        for(String key:List.of("messaging.received","mail.unread","mail.new","mail.new-gift")){
            var sound=LanguageEntry.read(en.get(key)).sound();assertEquals("minecraft:entity.experience_orb.pickup",sound.soundId());assertNull(sound.volume());assertNull(sound.pitch());
        }
    }
}
