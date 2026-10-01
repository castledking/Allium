package codes.castled.allium.tradingcards;

import codes.castled.allium.tradingcards.TradingCardsModule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Hands over anything owed from a trade window the player closed early.
 *
 * <p>The trade window puts the heads in a slot rather than granting them, so a
 * player who closed it and logged out still owns them. This delivers on the
 * next join, and {@code /tradingcards heads} covers the case of a full
 * inventory at that moment.
 */
public class PendingPayoutListener implements Listener {

    private final TradingCardsModule module;

    public PendingPayoutListener(TradingCardsModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (module.pending().isEmpty(event.getPlayer().getUniqueId())) {
            return;
        }
        // Delivered a tick later so the player's inventory is populated by the
        // time the items are added; a player joining into a full inventory would
        // otherwise have the payout dropped on them at their feet.
        codes.castled.allium.scheduler.SchedulerAdapter.runLaterEntity(
            codes.castled.allium.PluginStart.getInstance(), event.getPlayer(),
            () -> module.deliverPending(event.getPlayer()), 20L);
    }
}
