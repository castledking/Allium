package codes.castled.allium.item.provider;

import java.util.List;
import java.util.Set;

/**
 * Optional, read-only adapter capability. Native types stay in the isolated adapter;
 * callers wait for READY and pass only IDs explicitly bound to Allium behaviors.
 * Implementations must not build/update items, reload providers or write configuration.
 */
public interface ProviderConfigurationDiagnostics {
    List<String> configurationWarnings(Set<String> managedItemIds) throws ReflectiveOperationException;
}
