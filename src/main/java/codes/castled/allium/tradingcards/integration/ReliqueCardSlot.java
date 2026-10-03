package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.TradingCardData;
import com.github.darksoulq.relique.api.RelicAPI;
import com.github.darksoulq.relique.data.RelicHandler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The card in a player's Relique card slot, for a menu opened on it there.
 *
 * <p>Relique hands out copies from {@code getEquipped}, so a change is written
 * back through {@code updateItem}: the one call that persists the item without
 * firing Relique's equip and unequip events, which would strip and re-grant
 * the card's boosts around every reroll.
 *
 * <p>Touches Relique classes directly, so only constructed when Relique is
 * installed. Mirrors {@link ReliqueCardWriter}.
 *
 * @param index which item in the slot, since a slot with a raised limit can
 *              hold more than one
 */
public record ReliqueCardSlot(int index) implements CardSlot {

    private static final String SLOT = TradingCardsBranding.RELIQUE_SLOT;

    /** The player's equipped card, or null when the slot holds none. */
    public static ReliqueCardSlot find(Player player) {
        var items = RelicHandler.get(player).getEquipped(SLOT);
        for (int i = 0; i < items.size(); i++) {
            if (TradingCardData.isCard(items.get(i))) {
                return new ReliqueCardSlot(i);
            }
        }
        return null;
    }

    @Override
    public ItemStack stack(Player player) {
        return RelicAPI.getEquipped(player, SLOT, index).orElse(null);
    }

    @Override
    public void save(Player player, ItemStack stack) {
        RelicHandler.get(player).updateItem(SLOT, index, stack);
    }

    @Override
    public boolean equipped() {
        return true;
    }
}
