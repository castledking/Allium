package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.gui.CardWorkshopGui;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.TradingCardData;
import com.github.darksoulq.abyssallib.world.gui.GuiManager;
import com.github.darksoulq.abyssallib.world.gui.GuiView;
import com.github.darksoulq.relique.data.RelicHandler;
import com.github.darksoulq.relique.gui.PlayerInventoryElement;
import com.github.darksoulq.relique.gui.RelicSlotElement;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * Card clicks inside the /reliques window.
 *
 * <ul>
 *   <li>Right-click, or Q, on the equipped card opens its menu. Relique would
 *       otherwise lift it onto the cursor, or drop it on the ground.
 *   <li>Right-click on a card in the inventory opens its menu, as it does
 *       anywhere else.
 *   <li>Left-click on a card in the inventory equips it, swapping out the card
 *       already in the slot. Relique only equips on a shift-click or a drag.
 * </ul>
 *
 * <p>Relique's window is an AbyssalLib GUI, and AbyssalLib's click handler runs
 * whether or not the click was cancelled, then sets the cancelled flag from
 * whatever Relique's slot element answers. Cancelling is not enough: Relique
 * would still unequip the card onto the cursor, and a plain inventory click is
 * answered with "pass", which un-cancels it and lets vanilla pick the card up.
 *
 * <p>So for the clicks taken here, the window is taken out of AbyssalLib's
 * open-window map for the length of the event and put back at MONITOR.
 * AbyssalLib finds no window, leaves the click alone, and the cancel stands.
 * Nothing opens or closes during the event — the card menu opens a tick later —
 * so the window is never closed while it is out of the map, which would skip
 * Relique's save on close.
 *
 * <p>Touches Relique and AbyssalLib classes directly, so only registered when
 * the card slot is installed.
 */
public final class ReliqueMenuListener implements Listener {

    private static final String SLOT = TradingCardsBranding.RELIQUE_SLOT;

    private final TradingCardsModule module;

    /** The window taken out of AbyssalLib's map for the current click. */
    private InventoryView detachedView;
    private GuiView detached;

    public ReliqueMenuListener(TradingCardsModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiView view = GuiManager.OPEN_VIEWS.get(event.getView());
        if (view == null || event.getRawSlot() < 0) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (!TradingCardData.isCard(current)) {
            return;
        }
        boolean top = event.getRawSlot() < view.getTop().getSize();
        var element = view.getElementAt(top ? GuiView.Segment.TOP : GuiView.Segment.BOTTOM,
            event.getSlot());
        ClickType click = event.getClick();

        if (top && element instanceof RelicSlotElement) {
            if (click == ClickType.RIGHT || click == ClickType.DROP
                || click == ClickType.CONTROL_DROP) {
                take(event, view);
                openEquipped(player);
            }
            return;
        }
        if (top || !(element instanceof PlayerInventoryElement)) {
            return;
        }
        if (click == ClickType.RIGHT) {
            take(event, view);
            int slot = event.getSlot();
            TradingCardData.read(current).ifPresent(card ->
                new CardWorkshopGui(player, module, card, current, CardSlot.inventory(slot)).open());
        } else if (click == ClickType.LEFT
            && (event.getCursor() == null || event.getCursor().isEmpty())) {
            if (equip(player, event, view)) {
                take(event, view);
                view.render();
            }
        }
    }

    /** Puts the window back once every other handler has seen the click. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClickSettled(InventoryClickEvent event) {
        if (detached != null && event.getView() == detachedView) {
            GuiManager.OPEN_VIEWS.put(detachedView, detached);
        }
        detached = null;
        detachedView = null;
    }

    /** Cancels the click and hides the window from AbyssalLib until MONITOR. */
    private void take(InventoryClickEvent event, GuiView view) {
        event.setCancelled(true);
        detachedView = event.getView();
        detached = GuiManager.OPEN_VIEWS.remove(detachedView);
    }

    /** Opens the workshop on the equipped card. */
    private void openEquipped(Player player) {
        ReliqueCardSlot slot = ReliqueCardSlot.find(player);
        if (slot == null) {
            return;
        }
        ItemStack stack = slot.stack(player);
        TradingCardData.read(stack).ifPresent(card ->
            new CardWorkshopGui(player, module, card, stack, slot).open());
    }

    /**
     * Equips the clicked card, into the first free card slot or in place of the
     * card already there.
     *
     * <p>A swapped-out card goes into the slot the new one came from, so the
     * click reads as an exchange and nothing lands on the ground.
     *
     * @return false when Relique refused it, so the click goes ahead as a
     *         normal pickup rather than doing nothing
     */
    private boolean equip(Player player, InventoryClickEvent event, GuiView view) {
        RelicHandler handler = RelicHandler.get(player);
        int limit = handler.getSlotLimit(SLOT);
        if (limit <= 0) {
            return false;
        }
        List<ItemStack> equipped = handler.getEquipped(SLOT);
        int target = 0;
        ItemStack previous = null;
        for (int i = 0; i < limit; i++) {
            ItemStack at = i < equipped.size() ? equipped.get(i) : null;
            if (at == null || at.isEmpty()) {
                target = i;
                previous = null;
                break;
            }
            if (i == 0) {
                previous = at;
            }
        }
        ItemStack clicked = event.getCurrentItem();
        ItemStack toEquip = clicked.clone();
        toEquip.setAmount(1);
        // Relique's own checks run here: the validator, the slot limit, a
        // cursed card that cannot come out, and its pre-equip event.
        if (!handler.equip(SLOT, target, toEquip)) {
            return false;
        }
        var inventory = event.getClickedInventory();
        if (clicked.getAmount() > 1) {
            clicked.setAmount(clicked.getAmount() - 1);
            inventory.setItem(event.getSlot(), clicked);
            if (previous != null) {
                player.getInventory().addItem(previous).values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
            }
        } else {
            inventory.setItem(event.getSlot(), previous);
        }
        return true;
    }
}
