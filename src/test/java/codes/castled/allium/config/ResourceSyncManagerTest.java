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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    @Test void startupNormalizesFlowListsEvenWithoutMissingKeys()throws Exception{
        var plugin=mock(PluginStart.class,RETURNS_DEEP_STUBS);org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(dir.toFile());
        org.mockito.Mockito.when(plugin.getResource("sample.yml")).thenAnswer(c->new java.io.ByteArrayInputStream("items: [one, two]\n".getBytes()));
        var file=dir.resolve("sample.yml");java.nio.file.Files.writeString(file,"items: [one, two]\n");
        new ResourceSyncManager(plugin).syncResource("sample.yml");
        String saved=java.nio.file.Files.readString(file);assertTrue(saved.contains("- one\n"));assertTrue(saved.contains("- two\n"));
    }
    @Test void syncingLegacyLocaleInsertsRootKeysAndPreservesCustomText()throws Exception{
        var plugin=mock(PluginStart.class,RETURNS_DEEP_STUBS);org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(dir.toFile());
        org.mockito.Mockito.when(plugin.getResource("en_US.yml")).thenAnswer(c->getClass().getClassLoader().getResourceAsStream("lang/en_US.yml"));
        var file=dir.resolve("en_US.yml");java.nio.file.Files.writeString(file,"no-permission: customized\n");new ResourceSyncManager(plugin).syncResource("en_US.yml");
        var saved=YamlConfiguration.loadConfiguration(file.toFile());assertEquals("customized",saved.getString("no-permission"));assertTrue(saved.isList("unknown-command"));assertTrue(saved.isList("unknown-command-suggestion"));
    }
    @Test void malformedYamlIsNotOverwrittenAndFormattingBackupIsStable()throws Exception{
        var file=dir.resolve("config.yml");java.nio.file.Files.writeString(file,"values: [one, two]\n");var c=yaml("values: [one, two]\n");YamlFiles.normalize(file,c);String original=java.nio.file.Files.readString(dir.resolve("config.yml.before-format"));YamlFiles.normalize(file,c);assertEquals("values: [one, two]\n",original);assertEquals(original,java.nio.file.Files.readString(dir.resolve("config.yml.before-format")));
        var plugin=mock(PluginStart.class,RETURNS_DEEP_STUBS);org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(dir.toFile());org.mockito.Mockito.when(plugin.getResource("config.yml")).thenAnswer(x->new java.io.ByteArrayInputStream("items: []\n".getBytes()));java.nio.file.Files.writeString(file,"broken: [\n");new ResourceSyncManager(plugin).syncResource("config.yml");assertEquals("broken: [\n",java.nio.file.Files.readString(file));
    }
    @Test void bundledUnknownMessageHasLegacyRootKey()throws Exception{try(var in=getClass().getClassLoader().getResourceAsStream("lang/en_US.yml")){var c=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in));assertTrue(c.contains("unknown-command"));assertTrue(c.contains("unknown-command-suggestion"));assertTrue(c.contains("no-permission"));}}


    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void existingPlainMessageRemainsPlainWhenBundledDefaultAddsSound() throws Exception {
        var bundled=yaml("core:\n  reload-success:\n    text: fresh\n    sound: minecraft:ui.button.click\n");
        var installed=yaml("core:\n  reload-success: customized\n");
        new ResourceSyncManager(mock(PluginStart.class,RETURNS_DEEP_STUBS)).mergeDefaults(bundled,installed,"");
        assertEquals("customized",installed.getString("core.reload-success"));assertFalse(installed.isConfigurationSection("core.reload-success"));
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
