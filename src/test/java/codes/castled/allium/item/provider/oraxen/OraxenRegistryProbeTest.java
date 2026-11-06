package codes.castled.allium.item.provider.oraxen;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OraxenRegistryProbeTest {
    static final class Legacy {
        private static Map<?, ?> map;
    }
    static final class SnapshotOwner {
        private record RegistrySnapshot(Map<?, ?> map, Map<?, ?> itemById, Set<?> items) {}
        private static volatile RegistrySnapshot registry;
    }
    static final class WrongShape {
        private static volatile Map<?, ?> registry;
    }

    @Test void legacyDistinguishesUninitializedFromLoadedEmpty() throws Exception {
        var probe = new OraxenRegistryProbe(Legacy.class, false);
        Legacy.map = null;
        assertFalse(probe.published());
        Legacy.map = Collections.emptyMap();
        assertTrue(probe.published());
    }

    @Test void snapshotSentinelIsNotReadyButPublishedEmptyRegistryIs() throws Exception {
        var probe = new OraxenRegistryProbe(SnapshotOwner.class, true);
        SnapshotOwner.registry = new SnapshotOwner.RegistrySnapshot(Map.of(), Map.of(), Set.of());
        assertFalse(probe.published());
        SnapshotOwner.registry = new SnapshotOwner.RegistrySnapshot(
                Collections.unmodifiableMap(new LinkedHashMap<>()), Map.of(), Set.of());
        assertTrue(probe.published());
    }

    @Test void populatedPublishedSnapshotIsReadyWithoutChangingItsIdentity() throws Exception {
        var snapshot = new SnapshotOwner.RegistrySnapshot(Collections.unmodifiableMap(Map.of("file", "item")), Map.of("id", "item"), Set.of("id"));
        SnapshotOwner.registry = snapshot;
        assertTrue(new OraxenRegistryProbe(SnapshotOwner.class, true).published());
        assertSame(snapshot, SnapshotOwner.registry);
    }

    @Test void unknownRegistrySchemaFailsWithoutTryingLegacyFallback() {
        assertThrows(ReflectiveOperationException.class, () -> new OraxenRegistryProbe(WrongShape.class, true));
        assertThrows(NoSuchFieldException.class, () -> new OraxenRegistryProbe(Legacy.class, true));
        assertThrows(NoSuchFieldException.class, () -> new OraxenRegistryProbe(SnapshotOwner.class, false));
    }
}
