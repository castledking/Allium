package codes.castled.allium.item.provider;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderStartupDiagnosticsTest {
    @TempDir Path directory;

    private static final String DISABLED = """
            oraxenConverter:
              convertItems: false
              convertResourcePack: false
              convertSettings: false
              convertFurnitureOnLoad: false
            """;

    @Test
    void disabledMigrationStaysQuietAndLeavesExactConfigBytesUnchanged() throws Exception {
        Path file = directory.resolve("converter.yml");
        String content = "# owned by Nexo; retain comments and unrelated keys\n" + DISABLED
                + "itemsadderConverter:\n  convertItems: true\n";
        Files.writeString(file, content);
        assertTrue(ProviderStartupDiagnostics.migrationWarning(file).isEmpty());
        assertEquals(content, Files.readString(file));
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(file), entries.toList());
        }
    }

    @Test
    void nativeDefaultsWarnWithoutCreatingMissingFileOrDirectory() throws Exception {
        Path absent = directory.resolve("Nexo/converter.yml");
        String warning = ProviderStartupDiagnostics.migrationWarning(absent).get(0);
        assertTrue(warning.contains("rewrite Oraxen native identities as Nexo"));
        assertTrue(warning.contains("convertItems, convertResourcePack, convertSettings, convertFurnitureOnLoad"));
        assertTrue(warning.contains("before boot"));
        assertFalse(Files.exists(absent.getParent()));
    }

    @Test
    void eachEnabledConverterSwitchWarnsAndMissingSwitchUsesNativeTrueDefault() throws Exception {
        Path file = directory.resolve("converter.yml");
        for (String key : List.of("convertItems", "convertResourcePack", "convertSettings", "convertFurnitureOnLoad")) {
            String enabled = DISABLED.replace(key + ": false", key + ": true");
            Files.writeString(file, enabled);
            assertTrue(ProviderStartupDiagnostics.migrationWarning(file).get(0).contains("enabled (" + key + ")"));
            assertEquals(enabled, Files.readString(file));
        }
        Files.writeString(file, DISABLED.replace("  convertItems: false\n", ""));
        assertTrue(ProviderStartupDiagnostics.migrationWarning(file).get(0).contains("enabled (convertItems)"));
    }

    @Test
    void migrationInspectionRequiresEnabledNexoAndPresentOraxenAndWarnsOnlyOnce() throws Exception {
        var plugins = mock(PluginManager.class);
        var nexo = mock(Plugin.class);
        when(nexo.isEnabled()).thenReturn(true);
        when(nexo.getDataFolder()).thenReturn(directory.toFile());
        when(plugins.getPlugin("Nexo")).thenReturn(nexo);
        List<String> warnings = new ArrayList<>();
        var diagnostics = new ProviderStartupDiagnostics(new ItemService(), plugins, warnings::add);
        assertTrue(diagnostics.inspect());
        assertTrue(warnings.isEmpty(), "Nexo alone is not a coexistence warning");

        when(plugins.getPlugin("Oraxen")).thenReturn(mock(Plugin.class));
        when(nexo.isEnabled()).thenReturn(false);
        assertTrue(new ProviderStartupDiagnostics(new ItemService(), plugins, warnings::add).inspect());
        assertTrue(warnings.isEmpty());
        when(nexo.isEnabled()).thenReturn(true);
        diagnostics = new ProviderStartupDiagnostics(new ItemService(), plugins, warnings::add);
        assertTrue(diagnostics.inspect());
        assertTrue(diagnostics.inspect());
        assertEquals(1, warnings.size());
        assertFalse(Files.exists(directory.resolve("converter.yml")));
        verify(nexo, never()).saveConfig();
        verify(nexo, never()).reloadConfig();
    }

    @Test
    void invalidConverterConfigWarnsInsteadOfOverwritingOrRepairingIt() throws Exception {
        Path file = directory.resolve("converter.yml");
        String invalid = "oraxenConverter: [\n";
        Files.writeString(file, invalid);
        var plugins = mock(PluginManager.class);
        var nexo = mock(Plugin.class);
        when(nexo.isEnabled()).thenReturn(true);
        when(nexo.getDataFolder()).thenReturn(directory.toFile());
        when(plugins.getPlugin("Nexo")).thenReturn(nexo);
        when(plugins.getPlugin("Oraxen")).thenReturn(mock(Plugin.class));
        List<String> warnings = new ArrayList<>();
        assertTrue(new ProviderStartupDiagnostics(new ItemService(), plugins, warnings::add).inspect());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("could not be inspected"));
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void delayedReadinessUsesBindingsRegisteredAfterInstallationAndOnlyThatProvider() throws Exception {
        var items = new ItemService();
        var adapter = mock(ItemProviderAdapter.class, withSettings().extraInterfaces(ProviderConfigurationDiagnostics.class));
        when(adapter.providerName()).thenReturn(ProviderKey.CRAFTENGINE);
        when(adapter.status()).thenReturn(ProviderStatus.LOADING);
        items.registerProvider(adapter);
        var capability = (ProviderConfigurationDiagnostics) adapter;
        var plugins = mock(PluginManager.class);
        List<String> warnings = new ArrayList<>();
        var diagnostics = new ProviderStartupDiagnostics(items, plugins, warnings::add);
        assertFalse(diagnostics.inspect());
        verify(capability, never()).configurationWarnings(anySet());

        // S06/onEnable can register definitions and both preferred/alias appearances before
        // CE finishes. No native registry scan should nominate unrelated provider items.
        items.registerAlliumItem(new AlliumItemDefinition("tool", Material.STICK, "allium:tool", null, null, Map.of()));
        items.setPreferredAppearanceForBehavior("allium:tool", ProviderKey.parse("craftengine:test:preferred"));
        items.bindAppearanceToBehavior(ProviderKey.parse("craftengine:test:alias"), "allium:tool");
        items.bindAppearanceToBehavior(ProviderKey.parse("nexo:Other"), "allium:tool");
        when(adapter.status()).thenReturn(ProviderStatus.READY);
        when(capability.configurationWarnings(Set.of("test:preferred", "test:alias"))).thenReturn(List.of("reset warning"));
        assertTrue(diagnostics.inspect());
        assertTrue(diagnostics.inspect());
        assertEquals(List.of("reset warning"), warnings);
        verify(capability).configurationWarnings(Set.of("test:preferred", "test:alias"));
        verify(adapter, never()).createItem(anyString());
    }

    @Test
    void absentFailedAndUnavailableInspectionsDoNotLoopOrChangeProviderStatus() throws Exception {
        for (ProviderStatus status : List.of(ProviderStatus.ABSENT, ProviderStatus.FAILED, ProviderStatus.READY)) {
            var items = new ItemService();
            var adapter = mock(ItemProviderAdapter.class, withSettings().extraInterfaces(ProviderConfigurationDiagnostics.class));
            when(adapter.providerName()).thenReturn(ProviderKey.CRAFTENGINE);
            when(adapter.status()).thenReturn(status);
            var capability = (ProviderConfigurationDiagnostics) adapter;
            when(capability.configurationWarnings(anySet())).thenThrow(new IllegalAccessException("shape changed"));
            items.registerProvider(adapter);
            List<String> warnings = new ArrayList<>();
            var diagnostics = new ProviderStartupDiagnostics(items, mock(PluginManager.class), warnings::add);
            assertTrue(diagnostics.inspect());
            assertTrue(diagnostics.inspect());
            assertEquals(status == ProviderStatus.READY ? 1 : 0, warnings.size());
            if (status != ProviderStatus.READY) verify(capability, never()).configurationWarnings(anySet());
            assertEquals(status, adapter.status());
            verify(adapter, never()).createItem(anyString());
        }
    }

    @Test
    void appearanceBindingsSnapshotIsImmutableAndIncludesDefinitionPreferredAndAlias() {
        var items = new ItemService();
        items.registerAlliumItem(new AlliumItemDefinition("tool", Material.STICK, "allium:tool", null, null, Map.of()));
        Map<ProviderKey, String> snapshot = items.appearanceBindings();
        assertEquals(Map.of(ProviderKey.allium("tool"), "allium:tool"), snapshot);
        items.bindAppearanceToBehavior(ProviderKey.parse("craftengine:test:alias"), "allium:tool");
        items.setPreferredAppearanceForBehavior("allium:tool", ProviderKey.parse("craftengine:test:preferred"));
        assertEquals(1, snapshot.size(), "snapshot is not a live mutable view");
        assertEquals(3, items.appearanceBindings().size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
