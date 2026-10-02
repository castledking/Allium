package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.item.TradingCardData;
import com.github.darksoulq.relique.api.RelicAPI;
import com.github.darksoulq.relique.data.RelicHandler;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Writes a levelled card back into the Relique slot it is sitting in.
 *
 * <p>Without this, xp awards are cosmetic: the tracker is refreshed and the
 * player sees the level-up message, but the item in the slot is unchanged, so the
 * level evaporates the moment they relog or unequip.
 *
 * <p>Relique hands out clones from {@code getEquipped} — deliberately, since
 * callers would otherwise mutate its inventory data behind its back — so the item
 * has to be read, mutated, and written back through {@code updateItem}, the only
 * route that both persists and re-syncs modifiers.
 *
 * <p>Touches Relique classes directly, so only constructed when Relique is
 * installed. Mirrors {@link ReliqueIntegration}'s handling of that.
 */
public final class ReliqueCardWriter {

    private final Logger logger;

    public ReliqueCardWriter(Logger logger) {
        this.logger = logger;
    }

    /**
     * Replaces the equipped card with a levelled copy.
     *
     * <p>Silently does nothing when the card cannot be found: the level has
     * already been granted in memory, and a missing write is worth a log line
     * rather than an exception thrown in the middle of an xp award.
     *
     * @return true when the item was written
     */
    public boolean write(Player player, TradingCardData updated) {
        String slot = TradingCardsBranding.RELIQUE_SLOT;
        int index = findIndex(player, slot);
        if (index < 0) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME + "] A card level for "
                + player.getName() + " was awarded, but no card is in the " + slot
                + " slot any more; the level will not persist.");
            return false;
        }
        var current = RelicAPI.getEquipped(player, slot, index);
        if (current.isEmpty()) {
            return false;
        }
        // Mutated in place so the display name, lore and any custom model data
        // already on the clone survive; a fresh item would lose all of it.
        TradingCardData.write(current.get(), updated);
        RelicHandler.get(player).updateItem(slot, index, current.get());
        return true;
    }

    /**
     * The slot index holding the equipped card, or -1.
     *
     * <p>Found by matching rather than assumed to be 0, because a slot with a
     * raised limit can hold more than one item and the card need not be first.
     */
    private int findIndex(Player player, String slot) {
        var items = RelicHandler.get(player).getEquipped(slot);
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (item != null && !item.isEmpty() && TradingCardData.isCard(item)) {
                return i;
            }
        }
        return -1;
    }
}
