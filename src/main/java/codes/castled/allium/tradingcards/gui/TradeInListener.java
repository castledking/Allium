package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.tradingcards.TradingCardsModule;
import java.util.List;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Collects anything left in a trade window when the player closes it.
 *
 * <p>The window holds the heads in a slot rather than granting them, so
 * closing it early must not lose the payout. What is left is handed to the
 * module's pending store, which owes it to the player and pays it on their next
 * visit. Heads are never dropped on close — a full inventory is the only case
 * where dropping happens, and that is handled at delivery time.
 *
 * <p>A card that was deposited but never confirmed is returned too, since
 * confirming is what consumes it.
 */
public class TradeInListener implements Listener {

    private final TradingCardsModule module;

    public TradeInListener(TradingCardsModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof TradeInGui gui)) {
            return;
        }
        List<org.bukkit.inventory.ItemStack> leftovers = gui.collectUnclaimed();
        if (leftovers.isEmpty()) {
            return;
        }
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        // Runs on the player's region thread already, but the delivery touches
        // the inventory, so it is dispatched rather than assumed.
        SchedulerAdapter.runEntity(
            codes.castled.allium.PluginStart.getInstance(), player,
            () -> module.returnLeftovers(player, leftovers), null);
    }

    /**
     * Blocks dragging into the trade window.
     *
     * <p>A shift-click or drag could otherwise move an item into the deposit
     * or payout slot without the player intending to trade, and the payout slot
     * in particular is read by the confirm handler.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TradeInGui) {
            event.setCancelled(true);
        }
    }
}
