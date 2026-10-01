package codes.castled.allium.tradingcards.gui;


import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.gui.CardMenuGui;
import codes.castled.allium.tradingcards.item.TradingCardData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Opens the card menu when a player right-clicks a trading card.
 *
 * <p>Listens only on {@link EquipmentSlot#HAND} and only for a right-click with
 * an empty hand. Both filters matter: the event fires once per hand on a normal
 * server and can fire for the offhand as well, and opening a menu off a
 * left-click or a hand holding something else would make the card impossible
 * to place or break.
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
        // Cancel before the menu opens so the right-click does not also place a
        // block or swing at whatever is behind the card.
        event.setCancelled(true);

        var quote = module.quote(card.get());
        int slot = inventory.getHeldItemSlot();
        new CardMenuGui(event.getPlayer(), module, card.get(), held, quote, slot).open();
    }
}
