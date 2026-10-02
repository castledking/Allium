package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.TradingCardKeys;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * "Drop to clear" on a bonus slot button.
 *
 * <p>Dropping the button is the gesture that costs nothing and cannot be
 * misread as a purchase, which is why it clears rather than rolls.
 *
 * <p>Scoped tightly on purpose: the button must carry {@link
 * TradingCardKeys#SLOT_BUTTON}, the player must still have the menu open, and
 * the card must still be in the slot the menu was opened on. Without all three,
 * a stray drop somewhere else in the world would empty a slot.
 */
public final class BonusSlotDropListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TradingCardsModule module;

    public BonusSlotDropListener(TradingCardsModule module) {
        this.module = module;
    }

    // 26.3 has no InventoryDropItemEvent; a Q-drop from a GUI surfaces here
    // instead, so this is the only drop event that can carry a menu button.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        var player = event.getPlayer();
        var top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof CardBonusGui gui)) {
            return;
        }
        var button = event.getItemDrop().getItemStack();
        if (button.getItemMeta() == null) {
            return;
        }
        Integer index = button.getItemMeta().getPersistentDataContainer()
            .get(TradingCardKeys.SLOT_BUTTON, PersistentDataType.INTEGER);
        if (index == null) {
            return;
        }
        var service = module.bonusSlots();
        if (service == null) {
            return;
        }
        // The menu does not hand its slot out, so the card's inventory slot has
        // to be recovered from what the menu is holding rather than trusted.
        var result = module.editBonusSlot(player, gui.sourceSlot(), index, true);
        if (result != null && result.message() != null) {
            player.sendMessage(MM.deserialize("<!italic>"
                + (result.slots() == null ? "<red>" : "<green>") + result.message()));
        }
        gui.initialize();
    }
}
