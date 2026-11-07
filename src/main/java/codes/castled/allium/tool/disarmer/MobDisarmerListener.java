package codes.castled.allium.tool.disarmer;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/**
 * Routes Bukkit events to {@link MobDisarmerService}.
 *
 * <p>{@link PlayerInteractEntityEvent} is handled at {@link EventPriority#HIGH} and skipped when
 * already cancelled, so protection plugins that cancel the interaction itself are honored. Its
 * {@code PlayerInteractAtEntityEvent} subclass has its own handler list and is not received
 * here, so one click reaches this listener once per hand; only the main hand acts.
 */
public final class MobDisarmerListener implements Listener {

    private final MobDisarmerService service;

    public MobDisarmerListener(MobDisarmerService service) {
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }
}
