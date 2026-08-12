package codes.castled.allium.listeners.items;

import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import codes.castled.allium.items.CustomItemRegistry;
import codes.castled.allium.items.impl.MobDisarmerItem;

/**
 * Routes right-clicks on mobs to {@link MobDisarmerItem}.
 */
public class MobDisarmerListener implements Listener {

    private final CustomItemRegistry customItemRegistry;

    public MobDisarmerListener(final CustomItemRegistry customItemRegistry) {
        this.customItemRegistry = customItemRegistry;
    }

    /** Recharges that finished while the player was offline are settled on the way back in. */
    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final MobDisarmerItem disarmer = disarmer();
        if (disarmer != null) {
            disarmer.refreshInventory(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final MobDisarmerItem disarmer = disarmer();
        if (disarmer != null) {
            disarmer.forget(event.getPlayer());
        }
    }

    /** Prevent a recently disarmed mob from immediately picking its own equipment back up. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) return;
        final MobDisarmerItem disarmer = disarmer();
        if (disarmer != null) disarmer.cancelPickupIfSuppressed(event);
    }

    private MobDisarmerItem disarmer() {
        return customItemRegistry.getItem(MobDisarmerItem.ITEM_ID) instanceof MobDisarmerItem item
                ? item : null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(final PlayerInteractEntityEvent event) {
        final Player player = event.getPlayer();
        final EquipmentSlot hand = event.getHand();
        final ItemStack tool = player.getInventory().getItem(hand);
        if (tool == null || tool.getType() == Material.AIR) {
            return;
        }
        if (!(customItemRegistry.getItem(tool) instanceof MobDisarmerItem disarmer)) {
            return;
        }

        final Entity target = event.getRightClicked();
        if (target instanceof Player) {
            event.setCancelled(true);
            player.sendMessage("§cThe Mob Disarmer does not work on players.");
            return;
        }
        // Armour stands are decoration rather than mobs; stripping them would double as a griefing
        // tool and is not what "disarm a mob" means.
        if (!(target instanceof LivingEntity living) || target instanceof ArmorStand) {
            return;
        }

        if (disarmer.disarm(player, living, tool) || target instanceof Villager) {
            event.setCancelled(true);
            player.getInventory().setItem(hand, tool);
        }
    }

    /**
     * Right-clicking nothing reports the charge state instead of silently doing nothing. Block
     * clicks are deliberately excluded: every chest or door opened while holding the tool would
     * otherwise print a status line.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        final EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }

        final ItemStack tool = event.getItem();
        if (tool == null || tool.getType() == Material.AIR) {
            return;
        }
        if (!(customItemRegistry.getItem(tool) instanceof MobDisarmerItem disarmer)) {
            return;
        }

        if (disarmer.onUse(event.getPlayer(), tool)) {
            event.getPlayer().getInventory().setItem(hand, tool);
        }
    }
}
