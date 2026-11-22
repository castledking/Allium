package codes.castled.allium.config;

import codes.castled.allium.chat.ChatSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacyConfigMigrationTest {
    @Test
    void recoversLegacyChatAndMapsRenamedSettingsWithoutReplacingLocalEdits() throws Exception {
        var source = yaml("""
                version: 0.1.5a
                chat:
                  deletion_resend:
                    clear_lines: 369
                    header_enabled: true
                  channels:
                    local-channel:
                      aliases: [nearby]
                    list:
                      default:
                        format: 'Legacy <message>'
                chat-hover:
                  groups:
                    default:
                      name:
                        hover: [Legacy hover]
                teleport:
                  delay: 5
                  expire: 120
                  cooldown: 30
                home:
                  cooldown: 60
                economy:
                  symbol-before-amount: false
                restore:
                  snapshot-retention-days: 30
                old-addon:
                  settings: [keep]
                """);
        var defaults = yaml("""
                chat:
                  deletion_resend:
                    clear_lines: 500
                    header_enabled: false
                  channels:
                    list:
                      default:
                        format: 'Default <message>'
                teleport:
                  warmup-seconds: 3
                  request-expiry-seconds: 60
                """);
        var target = yaml(defaults.saveToString());
        target.set("teleport.warmup-seconds", 7);
        var report = LegacyConfigMigration.merge(source, target, defaults);

        assertTrue(LegacyConfigMigration.legacy(source));
        assertEquals(List.of("teleport.warmup-seconds"), report.kept());
        assertEquals(7, target.getInt("teleport.warmup-seconds"));
        assertEquals(120, target.getInt("teleport.request-expiry-seconds"));
        assertEquals(30, target.getInt("teleport.cooldowns.tpahere"));
        assertEquals(60, target.getInt("teleport.cooldowns.home"));
        assertEquals(30, target.getInt("restore.retention-days"));
        assertTrue(target.getBoolean("economy.currency-symbol-suffix"));
        assertTrue(target.getBoolean("chat.deletion_resend.header_enabled"));
        assertEquals(List.of("Legacy hover"), target.getStringList("chat-hover.groups.default.name.hover"));
        assertEquals(List.of("keep"), target.getStringList("old-addon.settings"));
        assertEquals(369, ChatSettings.read(target).clearEntries());
        assertEquals("Legacy <message>", ChatSettings.read(target).resolve("global").format());
        assertEquals("local", ChatSettings.read(target).resolve("nearby").id());
        assertTrue(LegacyConfigMigration.merge(source, target, defaults).changed().isEmpty());
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }
}
