package codes.castled.allium.harvest.kitchen;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Right-clicks on vanilla-block kneading stations (cauldrons by default). */
final class KneadingListener implements Listener {

    private final KneadingService kneading;
    private final BuildPermission permission;

    KneadingListener(KneadingService kneading, BuildPermission permission) {
        this.kneading = kneading;
        this.permission = permission;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || !kneading.isStationBlock(block) || event.getPlayer().isSneaking()) return;
        if (!permission.canBuild(event.getPlayer(), block.getLocation())) return;
        if (kneading.interact(event.getPlayer(), block, EquipmentSlot.HAND)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (kneading.isStationBlock(event.getBlock())) {
            KneadingService.clear(event.getBlock());
        }
    }
}
