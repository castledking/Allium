package codes.castled.allium.tool.powertool;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * Bukkit listener routing player and combat events into {@link PowerToolBehavior}.
 */
public final class PowerToolListener implements Listener {

    private final PowerToolBehavior behavior;

    public PowerToolListener(PowerToolBehavior behavior) {
        this.behavior = Objects.requireNonNull(behavior, "behavior");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        EquipmentSlot slot = event.getHand();
        if (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND) {
            return;
        }
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = slot == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (item == null || item.getType().isAir()) {
            return;
        }
        String hand = slot == EquipmentSlot.HAND ? PowerToolBinding.HAND_MAIN : PowerToolBinding.HAND_OFF;
        PowerToolTrigger trigger;
        if (left) {
            trigger = player.isSneaking() ? PowerToolTrigger.SHIFT_LEFT : PowerToolTrigger.LEFT;
        } else {
            trigger = player.isSneaking() ? PowerToolTrigger.SHIFT_RIGHT : PowerToolTrigger.RIGHT;
        }
        // Fallback: if sneaking and no shift-specific binding exists, check plain left/right with sneak condition.
        if (!behavior.handleTrigger(player, item, hand, trigger, event.getClickedBlock(), null, event, event)
                && player.isSneaking()) {
            PowerToolTrigger fallback = left ? PowerToolTrigger.LEFT : PowerToolTrigger.RIGHT;
            behavior.handleTrigger(player, item, hand, fallback, event.getClickedBlock(), null, event, event);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        EquipmentSlot slot = event.getHand();
        if (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = slot == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (item == null || item.getType().isAir()) {
            return;
        }
        String hand = slot == EquipmentSlot.HAND ? PowerToolBinding.HAND_MAIN : PowerToolBinding.HAND_OFF;
        behavior.handleTrigger(player, item, hand, PowerToolTrigger.ON_INTERACT_ENTITY,
                null, event.getRightClicked(), event, event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return;
        }
        behavior.handleTrigger(player, item, PowerToolBinding.HAND_MAIN, PowerToolTrigger.ON_BREAK,
                event.getBlock(), null, event, event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (!(damager instanceof Player player)) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return;
        }
        behavior.handleTrigger(player, item, PowerToolBinding.HAND_MAIN, PowerToolTrigger.ON_HIT,
                null, event.getEntity(), event, event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack main = event.getMainHandItem();
        if (main != null && !main.getType().isAir()
                && behavior.handleTrigger(player, main, PowerToolBinding.HAND_MAIN, PowerToolTrigger.SWAP,
                null, null, event, event)) {
            return;
        }
        ItemStack off = event.getOffHandItem();
        if (off != null && !off.getType().isAir()) {
            behavior.handleTrigger(player, off, PowerToolBinding.HAND_OFF, PowerToolTrigger.SWAP,
                    null, null, event, event);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item == null || item.getType().isAir()) {
            return;
        }
        behavior.handleTrigger(event.getPlayer(), item, PowerToolBinding.HAND_MAIN, PowerToolTrigger.DROP,
                null, null, event, event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        String hand = item.equals(event.getPlayer().getInventory().getItemInOffHand())
                ? PowerToolBinding.HAND_OFF : PowerToolBinding.HAND_MAIN;
        behavior.handleTrigger(event.getPlayer(), item, hand, PowerToolTrigger.ON_CONSUME,
                null, null, event, event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent event) {
        Player player = event.getPlayer();
        // Vanilla uses the rod in the main hand first.
        ItemStack main = player.getInventory().getItemInMainHand();
        boolean mainRod = main != null && main.getType() == org.bukkit.Material.FISHING_ROD;
        ItemStack item = mainRod ? main : player.getInventory().getItemInOffHand();
        if (item == null || item.getType().isAir()) {
            return;
        }
        behavior.handleTrigger(player, item, mainRod ? PowerToolBinding.HAND_MAIN : PowerToolBinding.HAND_OFF,
                PowerToolTrigger.ON_FISH, null, event.getCaught(), event, event);
        // A cancelled reel-in leaves the line out; take it in, as reeling in would have.
        if (event.isCancelled() && event.getHook() != null) {
            event.getHook().remove();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (behavior.actions().pickupSuppression().isBlocked(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        behavior.cleanupPlayer(event.getPlayer().getUniqueId());
    }
}
