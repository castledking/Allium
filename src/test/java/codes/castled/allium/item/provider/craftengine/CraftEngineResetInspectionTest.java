package codes.castled.allium.item.provider.craftengine;

import net.momirealms.craftengine.core.item.updater.ItemUpdateConfig;
import net.momirealms.craftengine.core.item.updater.ItemUpdater;
import net.momirealms.craftengine.core.item.updater.impl.ResetOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CraftEngineResetInspectionTest {
    @Test
    void inspectsAllNativeMigrationVersionsWithoutExecutingOrChangingUpdaters() throws Exception {
        ItemUpdater nonReset = context -> fail("diagnostic executed an updater");
        // The lazy definition is deliberately absent: inspection must never resolve/build it.
        ItemUpdater reset = new ResetOperation(null, List.of(), List.of());
        ItemUpdater[] first = {nonReset};
        ItemUpdater[] second = {reset, nonReset};
        var config = new ItemUpdateConfig(List.of(
                new ItemUpdateConfig.Version(5, first), new ItemUpdateConfig.Version(2, second)));
        assertTrue(CraftEngineNativeAccess.containsReset(config));
        assertArrayEquals(new ItemUpdater[]{nonReset}, first);
        assertArrayEquals(new ItemUpdater[]{reset, nonReset}, second);
        assertEquals(5, config.maxVersion());
    }

    @Test
    void emptyOrNonResetMigrationsAreQuietAndMalformedSchemaFailsLoudly() throws Exception {
        assertFalse(CraftEngineNativeAccess.containsReset(new ItemUpdateConfig(List.of())));
        ItemUpdater nonReset = context -> fail("diagnostic executed an updater");
        assertFalse(CraftEngineNativeAccess.containsReset(new ItemUpdateConfig(List.of(
                new ItemUpdateConfig.Version(1, new ItemUpdater[]{nonReset})))));
        var malformed = new ItemUpdateConfig(List.of(new ItemUpdateConfig.Version(1, null)));
        assertThrows(IllegalStateException.class, () -> CraftEngineNativeAccess.containsReset(malformed));
    }
}
