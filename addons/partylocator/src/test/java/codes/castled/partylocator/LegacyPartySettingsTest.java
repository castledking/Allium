package codes.castled.partylocator;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class LegacyPartySettingsTest {
    @Test
    void legacyPartyManagerSettingsMapOntoTheAddonsConfig() throws Exception {
        var legacy = new YamlConfiguration();
        legacy.loadFromString("""
                party-manager:
                  party-locator-bar: false
                  show-non-party-members-radius: 64
                  sync-with-mcmmo: false
                """);
        var target = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("config.yml")),
                StandardCharsets.UTF_8)) {
            target.load(reader);
        }
        assertEquals(LegacyPartySettings.KEYS, LegacyPartySettings.apply(legacy, target));
        assertFalse(target.getBoolean("party-locator-bar"));
        assertEquals(64, target.getInt("show-non-party-members-radius"));
        assertFalse(target.getBoolean("sync-with-mcmmo"));
        assertEquals(10, target.getInt("refresh-ticks"), "settings legacy never had keep their defaults");
        assertEquals(List.of(), LegacyPartySettings.apply(new YamlConfiguration(), new YamlConfiguration()));
    }
}
