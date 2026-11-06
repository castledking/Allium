package codes.castled.allium.item.provider.craftengine;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.item.updater.ItemUpdateConfig;
import net.momirealms.craftengine.core.item.updater.ItemUpdater;
import net.momirealms.craftengine.core.item.updater.impl.ResetOperation;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/** Exact 26.9.1 signatures; the diagnostic-only version-list probe is guarded and read-only. */
final class CraftEngineNativeAccess implements CraftEngineAccess {
    @Override
    public Lifecycle lifecycle() {
        CraftEngine engine = CraftEngine.instance();
        if (engine == null) return new Lifecycle(false, false, true, false);
        return new Lifecycle(engine.isDisabled() || engine.isStopping(),
                engine.isReloading() || engine.isReloadingPack() || engine.isReloadingHost(),
                engine.isEnabling(), engine.isFullyLoaded());
    }

    @Override
    public int loadedItemCount() {
        return Objects.requireNonNull(CraftEngineItems.loadedItems(), "CraftEngine loadedItems()").size();
    }

    @Override
    public ItemStack build(String id) {
        BukkitItemDefinition definition = CraftEngineItems.byId(Key.of(id));
        return definition == null ? null : definition.buildBukkitItem();
    }

    @Override
    public String nativeId(ItemStack stack) {
        Key id = CraftEngineItems.getCustomItemId(stack);
        return id == null ? null : id.asString();
    }

    @Override
    public boolean hasResetUpdater(String id) throws ReflectiveOperationException {
        BukkitItemDefinition definition = CraftEngineItems.byId(Key.of(id));
        return definition != null && definition.updater().isPresent()
                && containsReset(definition.updater().orElseThrow());
    }

    static boolean containsReset(ItemUpdateConfig config) throws ReflectiveOperationException {
        // 26.9.1/26.9.2 expose updater() and Version.updaters(), but no versions getter.
        // Inspect every migration in the chain without executing any native updater.
        Field field = ItemUpdateConfig.class.getDeclaredField("versions");
        if (field.getType() != List.class || Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
            throw new IllegalAccessException("CraftEngine ItemUpdateConfig.versions must be an accessible instance List");
        }
        Object value = field.get(config);
        if (!(value instanceof List<?> versions)) {
            throw new IllegalStateException("CraftEngine ItemUpdateConfig.versions is not a List");
        }
        boolean reset = false;
        for (Object entry : versions) {
            if (!(entry instanceof ItemUpdateConfig.Version version) || version.updaters() == null) {
                throw new IllegalStateException("CraftEngine updater version schema changed");
            }
            for (ItemUpdater updater : version.updaters()) {
                if (updater == null) throw new IllegalStateException("CraftEngine updater is null");
                if (updater instanceof ResetOperation) reset = true;
            }
        }
        return reset;
    }
}
