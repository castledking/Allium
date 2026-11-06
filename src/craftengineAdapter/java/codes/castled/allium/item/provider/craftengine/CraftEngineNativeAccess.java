package codes.castled.allium.item.provider.craftengine;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Exact 26.9.1 signatures; deliberately no reflection or legacy return-type fallback. */
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
}
