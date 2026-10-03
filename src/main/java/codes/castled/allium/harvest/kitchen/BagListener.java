package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Storage bags, clicked in any inventory:
 *
 * <ul>
 *   <li>right-click with an empty cursor takes one item out; right-clicking
 *       again while holding that item adds one more to the cursor</li>
 *   <li>shift-left-click takes a full stack (or whatever is left) onto the
 *       cursor</li>
 *   <li>left-click while holding the stored item puts it in</li>
 * </ul>
 *
 * Everything else falls through to vanilla, so a plain left-click picks the
 * bag up and moves it like any other item. Creative mode is left alone:
 * creative inventory clicks are client-authoritative and fight any cursor
 * changes made on the server.
 */
final class BagListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Supplier<KitchenConfig> config;
    private final ItemResolverChain items;

    BagListener(Supplier<KitchenConfig> config, ItemResolverChain items) {
        this.config = config;
        this.items = items;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT) {
            return;
        }
        ItemStack bagStack = event.getCurrentItem();
        KitchenConfig.Bag bag = bagOf(bagStack).orElse(null);
        if (bag == null || bagStack.getAmount() != 1) {
            // A stack of several bags would multiply whatever goes in.
            return;
        }
        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        boolean cursorStored = !cursorEmpty && items.matches(bag.stores(), cursor);
        int stored = amount(bagStack);

        if (click == ClickType.RIGHT) {
            if (cursorEmpty && stored > 0) {
                ItemStack one = items.create(bag.stores(), 1).orElse(null);
                if (one == null) return;
                event.setCancelled(true);
                player.setItemOnCursor(one);
                write(event, bag, bagStack, stored - 1);
            } else if (cursorStored && stored > 0 && cursor.getAmount() < cursor.getMaxStackSize()) {
                event.setCancelled(true);
                ItemStack grown = cursor.clone();
                grown.setAmount(cursor.getAmount() + 1);
                player.setItemOnCursor(grown);
                write(event, bag, bagStack, stored - 1);
            } else if (cursorStored) {
                // Bag empty or cursor full: do nothing rather than letting
                // vanilla swap the flour and the bag.
                event.setCancelled(true);
            }
            return;
        }

        if (click == ClickType.SHIFT_LEFT) {
            if (!cursorEmpty || stored <= 0) {
                return; // an empty bag shift-clicks across inventories as usual
            }
            ItemStack taken = items.create(bag.stores(), 1).orElse(null);
            if (taken == null) return;
            int amount = Math.min(stored, taken.getMaxStackSize());
            taken.setAmount(amount);
            event.setCancelled(true);
            player.setItemOnCursor(taken);
            write(event, bag, bagStack, stored - amount);
            return;
        }

        // LEFT
        if (cursorStored) {
            event.setCancelled(true);
            int moved = Math.min(cursor.getAmount(), bag.capacity() - stored);
            if (moved <= 0) {
                player.sendActionBar(MINI.deserialize("<red>The bag is full.</red>"));
                return;
            }
            ItemStack rest = cursor.clone();
            rest.setAmount(cursor.getAmount() - moved);
            player.setItemOnCursor(rest.getAmount() > 0 ? rest : null);
            write(event, bag, bagStack, stored + moved);
        }
    }

    Optional<KitchenConfig.Bag> bagOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Optional.empty();
        KitchenConfig cfg = config.get();
        if (cfg.bags().isEmpty()) return Optional.empty();
        return items.identify(stack).map(cfg.bags()::get);
    }

    static int amount(ItemStack bag) {
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return 0;
        Integer amount = meta.getPersistentDataContainer().get(KitchenKeys.BAG_AMOUNT, PersistentDataType.INTEGER);
        return amount == null ? 0 : Math.max(0, amount);
    }

    /** Stores a new amount on a bag item in place and refreshes its lore line. */
    void setAmount(KitchenConfig.Bag bag, ItemStack stack, int amount) {
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(KitchenKeys.BAG_AMOUNT, PersistentDataType.INTEGER, amount);
        meta.setMaxStackSize(1);
        // Rebuild the lore from the item's own definition each time so the
        // counter line is replaced rather than appended again.
        List<Component> lore = new ArrayList<>(items.create(bag.item(), 1)
            .map(ItemStack::getItemMeta).map(ItemMeta::lore).orElse(List.of()));
        lore.add(MINI.deserialize(bag.loreLine()
                .replace("<amount>", Integer.toString(amount))
                .replace("<capacity>", Integer.toString(bag.capacity())))
            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    private void write(InventoryClickEvent event, KitchenConfig.Bag bag, ItemStack bagStack, int amount) {
        ItemStack updated = bagStack.clone();
        setAmount(bag, updated, amount);
        event.setCurrentItem(updated);
    }

    /** Takes one stored item out of a held bag, for the kneading space. */
    boolean takeOne(ItemStack held, ItemRef wanted) {
        KitchenConfig.Bag bag = bagOf(held).orElse(null);
        if (bag == null || !bag.stores().equals(wanted) || held.getAmount() != 1) return false;
        int stored = amount(held);
        if (stored <= 0) return false;
        setAmount(bag, held, stored - 1);
        return true;
    }

    /** Whether a held item is a bag of the given item, regardless of how full it is. */
    boolean isBagOf(ItemStack held, ItemRef wanted) {
        return bagOf(held).map(bag -> bag.stores().equals(wanted)).orElse(false);
    }
}
