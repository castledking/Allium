package codes.castled.allium.tool.disarmer;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Objects;

/**
 * Routes Bukkit events to {@link DisarmBehavior}, for every loaded disarm tool.
 *
 * <p>{@link PlayerInteractEntityEvent} is handled at {@link EventPriority#HIGH} and skipped when
 * already cancelled, so protection plugins that cancel the interaction itself are honored. Its
 * {@code PlayerInteractAtEntityEvent} subclass has its own handler list and is not received
 * here, so one click reaches this listener once per hand; only the main hand acts.
 */
public final class DisarmListener implements Listener {

    private final DisarmBehavior service;

    public DisarmListener(DisarmBehavior service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        service.handleEntityInteraction(event.getPlayer(), event.getRightClicked(), event.getHand(), event, event);
    }

    /**
     * Air clicks arrive pre-cancelled (the interacted block is denied), so this handler reads
     * the item-use result instead of skipping cancelled events.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAir(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() == null
                || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        service.handleAirUse(event.getPlayer(), event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player) && service.pickups().isBlocked(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // Recharge visuals refresh on events, never by polling. Opening the player's own inventory sends
    // nothing to the server, so it cannot be hooked; container opens show that inventory too.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        service.refreshSlot(event.getPlayer(), event.getNewSlot());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        service.refreshVisual(event.getMainHandItem()).ifPresent(event::setMainHandItem);
        service.refreshVisual(event.getOffHandItem()).ifPresent(event::setOffHandItem);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.refreshInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            service.refreshInventory(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }
}
