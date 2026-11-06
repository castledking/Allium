package codes.castled.allium.item.provider.oraxen;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

/** Read-only, version-specific registry publication probes; no speculative field fallback. */
final class OraxenRegistryProbe {
    private final Field registry;
    private final Field snapshotMap;

    OraxenRegistryProbe(Class<?> owner, boolean snapshotRegistry) throws ReflectiveOperationException {
        registry = owner.getDeclaredField(snapshotRegistry ? "registry" : "map");
        if (!Modifier.isStatic(registry.getModifiers()) || !registry.trySetAccessible()) {
            throw new IllegalAccessException("Oraxen registry must be an accessible static field");
        }
        if (!snapshotRegistry) {
            if (registry.getType() != Map.class) throw new IllegalAccessException("Oraxen 1.211 map must have type Map");
            snapshotMap = null;
            return;
        }
        Class<?> type = registry.getType();
        var components = type.getRecordComponents();
        if (!Modifier.isVolatile(registry.getModifiers()) || !type.isRecord()
                || type.getEnclosingClass() != owner || !type.getSimpleName().equals("RegistrySnapshot")
                || components.length != 3
                || !components[0].getName().equals("map") || components[0].getType() != Map.class
                || !components[1].getName().equals("itemById") || components[1].getType() != Map.class
                || !components[2].getName().equals("items") || components[2].getType() != Set.class) {
            throw new IllegalAccessException("Oraxen 1.220.1 registry must match the verified volatile RegistrySnapshot schema");
        }
        snapshotMap = type.getDeclaredField("map");
        if (!snapshotMap.trySetAccessible()) throw new IllegalAccessException("Oraxen snapshot map is inaccessible");
    }

    boolean published() throws ReflectiveOperationException {
        Object snapshot = registry.get(null);
        if (snapshotMap == null) return snapshot != null;
        if (snapshot == null) return false;
        Map<?, ?> map = (Map<?, ?>) snapshotMap.get(snapshot);
        if (map == null) throw new IllegalStateException("Oraxen snapshot map is null");
        // Verified 1.220.1 starts with RegistrySnapshot.empty(): Map.of() for every view.
        // Successful loadItems replaces it with immutableItemMap(): Collections.unmodifiableMap,
        // even when empty. Compare against this JVM's own Map.of() type, never a guessed JDK name.
        // Looking only at registry != null or map size would prematurely accept the initial sentinel.
        return map.getClass() != Map.of().getClass();
    }
}
