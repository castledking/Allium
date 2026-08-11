package codes.castled.allium.listeners.items;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import codes.castled.allium.items.CustomItemRegistry;
import codes.castled.allium.items.impl.PhantomObliteratorItem;

/** Restricts the Obliterator to air-clicks, so opening blocks never emits its status message. */
public class PhantomObliteratorListener implements Listener {
    private final CustomItemRegistry registry;

    public PhantomObliteratorListener(final CustomItemRegistry registry) { this.registry = registry; }

    private PhantomObliteratorItem item() {
        return registry.getItem(PhantomObliteratorItem.ITEM_ID) instanceof PhantomObliteratorItem tool ? tool : null;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final PhantomObliteratorItem tool = item();
        if (tool != null) tool.refreshInventory(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final PhantomObliteratorItem tool = item();
        if (tool != null) tool.forget(event.getPlayer());
    }

    // Air-clicks have no protected block interaction to preserve. Do not let an unrelated
    // listener's cancellation prevent the tool from firing; block-clicks remain excluded below.
    @EventHandler
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() == null) return;
        final ItemStack stack = event.getPlayer().getInventory().getItem(event.getHand());
        if (stack == null || stack.getType() == Material.AIR) return;
        if (!(registry.getItem(stack) instanceof PhantomObliteratorItem tool)) return;
        if (tool.onUse(event.getPlayer(), stack)) event.getPlayer().getInventory().setItem(event.getHand(), stack);
    }
}
