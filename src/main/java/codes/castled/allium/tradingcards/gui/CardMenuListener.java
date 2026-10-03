package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.inventory.gui.BaseGUI;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.gui.CardMenuGui;
import codes.castled.allium.tradingcards.integration.ReliqueIntegration;
import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.TradingCardData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Opens the card menu when a player right-clicks a trading card.
 *
 * <p>Where that right-click lands depends on who else wants it. Relique equips
 * relics from the same {@link PlayerInteractEvent}, and it ignores an already
 * cancelled event, so a card handler that cancels unconditionally makes the
 * card unequippable while looking perfectly installed: the slot renders, the
 * icon shows, and every attempt to equip is silently dropped on the floor. So in
 * the world the click is only taken when Relique is not in a position to use it.
 *
 * <p>Inside a GUI there is no such contest for the world click, and right-clicking
 * the card in the inventory is the unambiguous way to ask for the card's own menu.
 * The card must be the one in the main hand, because the workshop stages and
 * consumes from the hand.
 */
public class CardMenuListener implements Listener {

    private final TradingCardsModule module;

    public CardMenuListener(TradingCardsModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        PlayerInventory inventory = event.getPlayer().getInventory();
        ItemStack held = inventory.getItemInMainHand();
        var card = TradingCardData.read(held);
        if (card.isEmpty()) {
            return;
        }
        if (ReliqueIntegration.canEquipCards()) {
            return;
        }
        // Cancel before the menu opens so the right-click does not also place a
        // block or swing at whatever is behind the card.
        event.setCancelled(true);

        // The workshop, not the read-only card menu: reroll, merge and trade
        // are all one-click decisions on a card the player is already holding,
        // so they live in one window with tabs rather than three windows.
        int slot = inventory.getHeldItemSlot();
        new CardWorkshopGui(event.getPlayer(), module, card.get(), held, CardSlot.inventory(slot)).open();
    }

    /**
     * Runs at LOW so it sees the click before another plugin can cancel it.
     *
     * <p>Relique cancels inventory clicks in its own window, and at NORMAL this
     * handler never got a look at a click inside the /reliques menu — the exact
     * place a player reaches for a card. LOW is still late enough to respect
     * anything cancelled before us, and the handler is narrow enough (a
     * right-click on a card in the player's own inventory) that running early
     * costs nothing.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // ClickType, not InventoryAction: the RIGHT_CLICK_* pseudo-actions were
        // removed from the API, and a right-click on a plain slot now reports
        // action NOTHING, so the click itself is the only signal left.
        if (event.getClick() != ClickType.RIGHT) {
            return;
        }
        // Only the player's own belongings, so this cannot fire from inside
        // another plugin's menu or reopen the workshop from within the workshop.
        if (event.getClickedInventory() == null
            || !event.getClickedInventory().equals(player.getInventory())) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof BaseGUI) {
            return;
        }
        // Any card, not just the held one. The workshop addresses the card by
        // inventory slot, so it works from anywhere in the inventory, and
        // requiring the card in the main hand meant the click did nothing
        // whenever the card was in the rows above the hotbar.
        ItemStack clicked = event.getCurrentItem();
        var card = TradingCardData.read(clicked);
        if (card.isEmpty()) {
            return;
        }
        // The click would otherwise split the stack, so take it and open instead.
        event.setCancelled(true);
        new CardWorkshopGui(player, module, card.get(), clicked, CardSlot.inventory(event.getSlot())).open();
    }

    /**
     * Opens the card's menu instead of dropping it.
     *
     * <p>A card is worth keeping hold of, and Q is close enough to the movement
     * keys that a dropped card is far more often a slip than a decision. Anything
     * a player would drop a card to do — trade it in, merge it — is in the menu.
     *
     * <p>Covers every way a player drops an item, Q in the world, Q over a slot,
     * and a click outside an open window, because all three come through this
     * event. HIGH, so the bonus menu's own drop handler, which reads a dropped
     * menu button, has already seen the event; that one never carries a card.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (!TradingCardData.isCard(dropped)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        // Inside our own menus a drop is a stray click, not a request for another
        // window on top of the one already open.
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof BaseGUI) {
            return;
        }
        // Next tick: the cancelled drop has to land back in the inventory before
        // the card has a slot to open on.
        ItemStack card = dropped.clone();
        SchedulerAdapter.runEntity(PluginStart.getInstance(), player, () -> {
            ItemStack[] contents = player.getInventory().getStorageContents();
            for (int slot = 0; slot < contents.length; slot++) {
                if (contents[slot] != null && contents[slot].isSimilar(card)) {
                    var data = TradingCardData.read(contents[slot]);
                    if (data.isPresent()) {
                        new CardWorkshopGui(player, module, data.get(), contents[slot],
                            CardSlot.inventory(slot)).open();
                    }
                    return;
                }
            }
        }, null);
    }
}
