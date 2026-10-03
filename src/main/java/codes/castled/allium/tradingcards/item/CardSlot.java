package codes.castled.allium.tradingcards.item;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Where the card a menu is working on lives: a slot in the player's inventory,
 * or the Relique card slot.
 *
 * <p>The menus re-read the card on every action rather than trusting the copy
 * they opened with, and write the result straight back, so they need to know
 * where "back" is. An equipped card has no inventory slot, and reaching into
 * Relique for it hands back a copy rather than the item itself.
 */
public interface CardSlot {

    /**
     * The card's stack, or null when the slot no longer holds anything.
     *
     * <p>Changes made to it are kept only once {@link #save} is called with it.
     */
    ItemStack stack(Player player);

    /** Writes back a stack {@link #stack} returned, after changing it. */
    void save(Player player, ItemStack stack);

    /**
     * True for the card in the Relique slot.
     *
     * <p>Its boosts are live, so a change to it has to be re-applied, and it
     * cannot be merged or traded in without coming out of the slot first.
     */
    boolean equipped();

    /** A slot in the player's own inventory, as numbered by {@code getItem}. */
    static CardSlot inventory(int slot) {
        return new Inventory(slot);
    }

    /** A card in the player's inventory. */
    record Inventory(int slot) implements CardSlot {

        @Override
        public ItemStack stack(Player player) {
            return player.getInventory().getItem(slot);
        }

        @Override
        public void save(Player player, ItemStack stack) {
            // getItem hands out the inventory's own stack, so the change is
            // already there; set anyway, so this does not depend on that.
            player.getInventory().setItem(slot, stack);
        }

        @Override
        public boolean equipped() {
            return false;
        }
    }
}
