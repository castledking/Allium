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
    @Test void exactSuppliedRuntimeVersionsAreAcceptedWithSurroundingWhitespace() {
        assertTrue(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported("1.30-dev-36f4ce686"));
        assertTrue(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported(" 1.30-dev-36f4ce686\t"));
        assertTrue(ProviderAdapterBootstrap.ORAXEN_SPEC.isVersionSupported("1.220.1"));
        assertTrue(ProviderAdapterBootstrap.ORAXEN_SPEC.isVersionSupported("\t1.220.1 "));
    }

    @Test void compileOnlyPinsDoNotGrantRuntimeVersionLineSupport() {
        for (String version : List.of("1.29", "1.29.0", "1.29.1", "1.29.2", "1.29-dev-b3ebc6e48")) {
            assertFalse(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported(version), version);
        }
        // The historical source-built 1.211.0 pass cannot identify an arbitrary stock jar.
        for (String version : List.of("1.211", "1.211.0", "1.211.1", "1.211.0-SNAPSHOT")) {
            assertFalse(ProviderAdapterBootstrap.ORAXEN_SPEC.isVersionSupported(version), version);
        }
    }

    @Test void suppliedRuntimeAcceptanceDoesNotExtendToOtherBuildsOrVersionDecorations() {
        for (String version : List.of("1.30-dev-unverified", "1.30-dev-36f4ce687",
                "1.30-dev-36f4ce686-SNAPSHOT", "v1.30-dev-36f4ce686", "1.30.0", "1.30.1")) {
            assertFalse(ProviderAdapterBootstrap.NEXO_SPEC.isVersionSupported(version), version);
        }
        for (String version : List.of("1.220.0", "1.220.2", "1.220.1-SNAPSHOT",
                "1.220.1.1", "v1.220.1", "1.221.0")) {
            assertFalse(ProviderAdapterBootstrap.ORAXEN_SPEC.isVersionSupported(version), version);
        }
    }

    @Test void craftEngineRetainsItsExistingNarrowVersionLine() {
        var spec = ProviderAdapterBootstrap.CRAFTENGINE_SPEC;
        for (String version : List.of("26.9.0", "26.9.1", "26.9.2", " 26.9.2 ")) {
            assertTrue(spec.isVersionSupported(version), version);
        }
        for (String version : List.of("26.8.9", "26.10.0", "27.9.0", "0.0.67")) {
            assertFalse(spec.isVersionSupported(version), version);
        }
    }

    @Test void missingAndMalformedVersionsAreRejectedForEveryProvider() {
        for (var spec : ProviderAdapterBootstrap.ALL_SPECS) {
            assertFalse(spec.isVersionSupported(null), spec.providerKey());
            for (String version : List.of("", " \t", "dev", "1.1.0", "2.0.0", "999999999999999999999.1")) {
                assertFalse(spec.isVersionSupported(version), spec.providerKey() + ": " + version);
            }
        }
    }

    @Test void compileOnlyPinsAreRejectedBeforeAdapterLoadingWithRuntimePolicyDiagnostic() {
        assertVersionRejectedWithoutLoadingAdapter(ProviderAdapterBootstrap.NEXO_SPEC, "1.29.1", "1.30-dev-36f4ce686");
        assertVersionRejectedWithoutLoadingAdapter(ProviderAdapterBootstrap.ORAXEN_SPEC, "1.211.0", "1.220.1");
    }

    private void assertVersionRejectedWithoutLoadingAdapter(ProviderAdapterBootstrap.ProviderSpec spec,
                                                           String version, String acceptedRuntime) {
        var owner = mock(Plugin.class);
        var manager = mock(PluginManager.class);
        var dependency = mock(Plugin.class);
        var reloadCallback = mock(Runnable.class);
        when(manager.getPlugin(spec.bukkitPluginName())).thenReturn(dependency);
        when(dependency.isEnabled()).thenReturn(true);
        when(dependency.getDescription()).thenReturn(new PluginDescriptionFile(spec.bukkitPluginName(), version, "invalid.Main"));
        List<String> warnings = new ArrayList<>();
        List<String> infos = new ArrayList<>();

        var adapter = ProviderAdapterBootstrap.loadSingleProvider(spec, owner, manager,
                reloadCallback, infos::add, warnings::add);

        var absent = assertInstanceOf(ProviderAdapterBootstrap.AbsentProviderAdapter.class, adapter);
        assertEquals(ProviderStatus.ABSENT, absent.status());
        assertEquals(ResolutionStatus.PROVIDER_ABSENT, absent.createItem("test").status());
        assertTrue(absent.reason().contains("version '" + version + "' is incompatible"));
        assertTrue(absent.reason().contains(acceptedRuntime + " only"));
        assertTrue(absent.reason().contains("compile-only API pin " + version));
        assertEquals(List.of(absent.reason()), warnings);
        assertTrue(infos.isEmpty());
        verifyNoInteractions(owner, reloadCallback);
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
