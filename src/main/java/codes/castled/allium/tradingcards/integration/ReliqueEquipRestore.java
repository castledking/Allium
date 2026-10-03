package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.tradingcards.boost.BoostService;
import codes.castled.allium.tradingcards.boost.EquippedCardTracker;
import codes.castled.allium.tradingcards.item.TradingCardData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Picks the equipped card back up when a player joins.
 *
 * <p>A card's boosts and its xp both run off the tracker, and the tracker is
 * filled by Relique's equip event. Relique does not post that event when a
 * player joins with a card already in the slot — it only re-syncs its own
 * modifiers — and the boosts are non-persistent and dropped on quit. So after
 * a relog or a restart the card sat in the slot granting nothing and earning
 * nothing until it was taken out and put back.
 *
 * <p>Read from the slot itself, after AuraSkills has loaded the player: a stat
 * applied before that is dropped without complaint. No equip message, since
 * nothing was equipped.
 *
 * <p>Touches Relique classes directly, so only registered when the card slot is
 * installed.
 */
public final class ReliqueEquipRestore implements Listener {

    /** Seconds to wait for AuraSkills to load a player before applying anyway. */
    private static final int ATTEMPTS = 15;

    private final Plugin plugin;
    private final BoostService boosts;
    private final EquippedCardTracker tracker;

    public ReliqueEquipRestore(Plugin plugin, BoostService boosts, EquippedCardTracker tracker) {
        this.plugin = plugin;
        this.boosts = boosts;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        schedule(event.getPlayer(), 1);
    }

    /** For players already online when the module comes up, as after a reload. */
    public void restoreOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            schedule(player, 1);
        }
    }

    private void schedule(Player player, int attempt) {
        SchedulerAdapter.runLaterEntity(plugin, player, () -> restore(player, attempt), 20L);
    }

    private void restore(Player player, int attempt) {
        // Already tracked means the player equipped a card in the meantime, and
        // that path applied it.
        if (!player.isOnline() || !tracker.isEmpty(player.getUniqueId())) {
            return;
        }
        if (!boosts.ready(player.getUniqueId()) && attempt < ATTEMPTS) {
            schedule(player, attempt + 1);
            return;
        }
        ReliqueCardSlot slot = ReliqueCardSlot.find(player);
        if (slot == null) {
            return;
        }
        TradingCardData.read(slot.stack(player)).ifPresent(card ->
            tracker.set(player.getUniqueId(), card, boosts.apply(player, card)));
    }
}
