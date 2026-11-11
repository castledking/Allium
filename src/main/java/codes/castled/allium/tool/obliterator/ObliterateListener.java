package codes.castled.allium.tool.obliterator;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/**
 * Routes right-clicks on air to {@link ObliterateBehavior}. Block clicks never fire it, so opening a
 * chest or a door with the tool in hand does nothing. Air clicks arrive pre-cancelled (no block was
 * used), so the handler reads the item-use result: a plugin that denies using the item stops it.
 */
public final class ObliterateListener implements Listener {

    private final ObliterateBehavior behavior;

    public ObliterateListener(ObliterateBehavior behavior) {
        this.behavior = Objects.requireNonNull(behavior, "behavior");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() == null || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        behavior.handleUse(event.getPlayer(), event.getHand());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        behavior.forget(event.getPlayer().getUniqueId());
    }
}
