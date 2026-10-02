package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.inventory.gui.BaseGUI;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.gui.CardMenuGui;
import codes.castled.allium.tradingcards.integration.ReliqueIntegration;
import codes.castled.allium.tradingcards.item.TradingCardData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
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
        new CardWorkshopGui(event.getPlayer(), module, card.get(), held, slot).open();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
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
        // Only the player's own belongings, and only while they are looking at
        // one of our windows, so this cannot fire from inside another plugin's
        // menu or reopen the workshop from within the workshop.
        if (event.getClickedInventory() == null
            || !event.getClickedInventory().equals(player.getInventory())) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof BaseGUI) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (TradingCardData.read(held).isEmpty()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.isSimilar(held)) {
            return;
        }
        // The click would otherwise split the stack, so take it and open instead.
        event.setCancelled(true);
        new CardWorkshopGui(player, module, TradingCardData.read(held).get(), held,
            player.getInventory().getHeldItemSlot()).open();
    }
}
