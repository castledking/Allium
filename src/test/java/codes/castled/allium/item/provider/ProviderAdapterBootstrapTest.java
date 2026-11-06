package codes.castled.allium.item.provider;

import codes.castled.allium.item.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderAdapterBootstrapTest {
    @Test void supportedLinesAreConservativeButAcceptSfDevBuild() {
        assertTrue(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported("1.29-dev-b3ebc6e48"));
        assertTrue(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported("1.29.1"));
        assertTrue(ProviderAdapterBootstrap.CRAFTENGINE_SPEC.isVersionSupported("26.9.2"));
        assertTrue(ProviderAdapterBootstrap.ORAXEN_SPEC.isVersionSupported("1.211.0"));
        assertTrue(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported("1.30-dev-36f4ce686"));
        assertTrue(ProviderAdapterBootstrap.ORAXEN_SPEC.isVersionSupported("1.220.1"));
        assertFalse(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported("1.30-dev-unverified"));
        assertFalse(ProviderAdapterBootstrap.ORAXEN_SPEC.isVersionSupported("1.220.2"));
        assertFalse(ProviderAdapterBootstrap.ORAXEN_SPEC.isVersionSupported("1.220.0"));
        for (String version : List.of("", "dev", "1.1.0", "1.30.0", "2.0.0", "999999999999999999999.1")) {
            assertFalse(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported(version), version);
        }
    }

    @Test void absentDisabledAndIncompatibleProvidersStayAbsentWithoutLoadingAdapters() {
        var owner = mock(Plugin.class);
        var manager = mock(PluginManager.class);
        List<String> logs = new ArrayList<>();
        for (var spec : ProviderAdapterBootstrap.ALL_SPECS) {
            var absent = ProviderAdapterBootstrap.loadSingleProvider(spec, owner, manager, () -> {}, logs::add, logs::add);
            assertEquals(ProviderStatus.ABSENT, absent.status());
            assertEquals(ResolutionStatus.PROVIDER_ABSENT, absent.createItem("test").status());
            Plugin dependency = mock(Plugin.class);
            when(manager.getPlugin(spec.bukkitPluginName())).thenReturn(dependency);
            var disabled = ProviderAdapterBootstrap.loadSingleProvider(spec, owner, manager, () -> {}, logs::add, logs::add);
            assertEquals(ProviderStatus.ABSENT, disabled.status());
            when(dependency.isEnabled()).thenReturn(true);
            when(dependency.getDescription()).thenReturn(new PluginDescriptionFile(spec.bukkitPluginName(), "999.0.0", "invalid.Main"));
            var incompatible = ProviderAdapterBootstrap.loadSingleProvider(spec, owner, manager, () -> {}, logs::add, logs::add);
            assertEquals(ProviderStatus.ABSENT, incompatible.status());
            assertTrue(logs.stream().anyMatch(log -> log.contains("999.0.0") && log.contains("incompatible")));
            verifyNoInteractions(owner);
            when(manager.getPlugin(spec.bukkitPluginName())).thenReturn(null);
        }
        assertTrue(logs.stream().anyMatch(log -> log.contains("optional")));
    }
}
