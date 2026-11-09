package codes.castled.allium.config;

import codes.castled.allium.PluginStart;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class ResourceSyncManagerTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void aMessageEntryIsOneValueSoAnOmittedVolumeOrPitchIsNeverPutBack() throws Exception {
        ResourceSyncManager sync = new ResourceSyncManager(mock(PluginStart.class, RETURNS_DEEP_STUBS));
        YamlConfiguration bundled = yaml("""
                messaging:
                  received:
                    text: "hi"
                    sound: minecraft:entity.experience_orb.pickup
                    volume: 0.6
                  sent: "sent"
                  new-key: "new"
                  sounds:
                    usage: "use"
                """);
        YamlConfiguration installed = yaml("""
                messaging:
                  received:
                    text: "hi"
                    sound: minecraft:entity.experience_orb.pickup
                  sent: "sent"
                """);
        int added = sync.mergeDefaults(bundled, installed, "");
        assertFalse(installed.contains("messaging.received.volume"), "the operator's omitted volume stays omitted");
        assertEquals("new", installed.getString("messaging.new-key"), "genuinely new messages are still added");
        assertEquals("use", installed.getString("messaging.sounds.usage"), "and so are new message groups");
        assertEquals(3, added, "new-key, the new sounds section and its usage");

        YamlConfiguration fresh = yaml("messaging:\n  sent: \"sent\"\n");
        sync.mergeDefaults(bundled, fresh, "");
        assertEquals(0.6D, fresh.getDouble("messaging.received.volume"), "a missing entry arrives whole");
        assertTrue(ResourceSyncManager.isMessageEntry(bundled.getConfigurationSection("messaging.received")));
        assertFalse(ResourceSyncManager.isMessageEntry(bundled.getConfigurationSection("messaging")));
    }
}
