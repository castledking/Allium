package codes.castled.allium.harvest.kitchen;

import com.nexomc.nexo.api.NexoBlocks;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureBreakEvent;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent;
import com.nexomc.nexo.mechanics.custom_block.CustomBlockMechanic;
import com.nexomc.nexo.mechanics.furniture.FurnitureMechanic;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Kneading stations made from Nexo furniture or Nexo custom blocks. Touches
 * Nexo classes directly, so it is only registered when Nexo is enabled.
 */
final class NexoKneadingListener implements Listener {

    private final KneadingService kneading;
    private final BuildPermission permission;

    NexoKneadingListener(KneadingService kneading, BuildPermission permission) {
        this.kneading = kneading;
        this.permission = permission;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnitureInteract(NexoFurnitureInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getPlayer().isSneaking()) return;
        FurnitureMechanic mechanic = event.getMechanic();
        if (!kneading.isNexoStation(mechanic.getItemID())) return;
        Block station = event.getBaseEntity().getLocation().getBlock();
        if (!permission.canBuild(event.getPlayer(), station.getLocation())) return;
        if (kneading.interact(event.getPlayer(), station, EquipmentSlot.HAND)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnitureBreak(NexoFurnitureBreakEvent event) {
        if (kneading.isNexoStation(event.getMechanic().getItemID())) {
            KneadingService.clear(event.getBaseEntity().getLocation().getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCustomBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || event.getPlayer().isSneaking()) return;
        if (kneading.settings() == null || kneading.settings().nexoStations().isEmpty()) return;
        CustomBlockMechanic mechanic = NexoBlocks.customBlockMechanic(block.getLocation());
        if (mechanic == null || !kneading.isNexoStation(mechanic.getItemID())) return;
        if (!permission.canBuild(event.getPlayer(), block.getLocation())) return;
        if (kneading.interact(event.getPlayer(), block, EquipmentSlot.HAND)) {
            event.setCancelled(true);
        }
    }
}
