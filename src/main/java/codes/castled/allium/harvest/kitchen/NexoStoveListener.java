package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.scheduler.SchedulerAdapter;
import com.nexomc.nexo.api.NexoFurniture;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureBreakEvent;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent;
import com.nexomc.nexo.mechanics.furniture.FurnitureMechanic;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Nexo furniture that opens a furnace (see {@link StoveService}). Touches
 * Nexo classes directly, so it is only registered when Nexo is enabled.
 */
final class NexoStoveListener implements Listener {

    private final Plugin plugin;
    private final StoveService stoves;
    private final BuildPermission permission;

    NexoStoveListener(Plugin plugin, StoveService stoves, BuildPermission permission) {
        this.plugin = plugin;
        this.stoves = stoves;
        this.permission = permission;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(NexoFurnitureInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getPlayer().isSneaking()) return;
        KitchenConfig.Stove settings = stoves.settings(event.getMechanic().getItemID());
        if (settings == null) return;
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!permission.canBuild(player, event.getBaseEntity().getLocation())) return;
        stoves.open(player, event.getBaseEntity(), settings);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(NexoFurnitureBreakEvent event) {
        if (stoves.settings(event.getMechanic().getItemID()) != null) {
            stoves.broken(event.getBaseEntity());
        }
    }

    // ==================== the menu ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!StoveService.isStove(event.getView().getTopInventory())) return;
        if (event.getRawSlot() == 2 && event.getWhoClicked() instanceof Player player) {
            ItemStack output = event.getCurrentItem();
            if (output != null && !output.getType().isAir()) {
                stoves.collectExperience(player, event.getView().getTopInventory());
            }
        }
        afterClick(event.getWhoClicked(), event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (StoveService.isStove(event.getView().getTopInventory())) {
            afterClick(event.getWhoClicked(), event.getView().getTopInventory());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (StoveService.isStove(event.getInventory())) {
            stoves.changed(event.getInventory());
        }
    }

    /** The click is only applied after the event, so look at the result a tick later. */
    private void afterClick(Entity who, org.bukkit.inventory.Inventory inventory) {
        SchedulerAdapter.runLaterEntity(plugin, who, () -> stoves.changed(inventory), 1L);
    }

    // ==================== chunks ====================

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            resume(entity);
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay) {
                stoves.unload(entity.getUniqueId());
            }
        }
    }

    private void resume(Entity entity) {
        if (!(entity instanceof ItemDisplay display)) return;
        FurnitureMechanic mechanic = NexoFurniture.furnitureMechanic(display);
        if (mechanic == null) return;
        KitchenConfig.Stove settings = stoves.settings(mechanic.getItemID());
        if (settings != null) {
            stoves.resume(display, settings);
        }
    }

    /** Picks up stoves that were already cooking in chunks loaded before the kitchen started. */
    void bootstrapLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                SchedulerAdapter.runAtLocation(plugin, chunk.getBlock(8, 64, 8).getLocation(), () -> {
                    for (Entity entity : chunk.getEntities()) {
                        resume(entity);
                    }
                });
            }
        }
    }
}
