package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.bonus.BonusSlot;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** Five bonus sockets over the card, matching gui_card_boosts. Signatures are never rolled. */
public final class CardBonusGui extends CardGui {
    public static final int SLOT_CARD = 22;
    public static final int SLOT_FIRST = 2;

    public CardBonusGui(Player player, TradingCardsModule module, TradingCardData card,
                        ItemStack cardStack, CardSlot source) {
        super(player, module, card, cardStack, source, "Bonus Boosts", "gui_card_boosts");
    }

    public CardSlot source() {
        return source;
    }

    @Override
    public void initialize() {
        clear();
        if (!refreshCard()) return;
        setItem(SLOT_CARD, cardStack.clone(), null);
        var service = module.bonusSlots();
        if (service == null) {
            setItem(4, icon(Material.BARRIER, "<red>Bonuses unavailable"), null);
        } else {
            var slots = service.slots(source.stack(player), card);
            for (int i = 0; i < 5; i++) {
                int index = i;
                ItemStack button = slotIcon(slots, index);
                var meta = button.getItemMeta();
                meta.getPersistentDataContainer().set(
                    codes.castled.allium.tradingcards.item.TradingCardKeys.SLOT_BUTTON,
                    org.bukkit.persistence.PersistentDataType.INTEGER, index);
                button.setItemMeta(meta);
                setItem(SLOT_FIRST + i, button, e -> onSlotClick(e, index));
            }
        }
        navigation(this::mainMenu, () -> {
            if (refreshCard()) new CardMergeGui(player, module, card, cardStack, source).open();
        }, "Merge Cards");
    }

    private ItemStack slotIcon(List<BonusSlot> slots, int index) {
        var service = module.bonusSlots();
        if (!service.isTierUnlocked(card, index)) {
            return icon(Material.FIRE_CHARGE, "<red>Locked Bonus Slot",
                "<dark_gray>Tier up to unlock", "",
                "<gray>Unlocks at <white>" + Tier.values()[index].name());
        }
        if (!service.isEnabled()) {
            return icon(Material.FIRE_CHARGE, "<red>Inactive Bonus Slot",
                "<dark_gray>Disabled on this server");
        }
        BonusSlot slot = slots.get(index);
        List<String> lore = new ArrayList<>();
        if (slot.locked()) {
            lore.add("<yellow>Right click to unlock");
            lore.add("<dark_gray>Protected from rolling and clearing");
        } else {
            lore.add(slot.isEmpty() ? "<dark_gray>Left click to roll" : "<dark_gray>Left click to re-roll");
            lore.add("<dark_gray>Right click to lock");
            if (!slot.isEmpty()) lore.add("<dark_gray>Drop to clear");
        }
        if (!slot.isEmpty()) {
            lore.add("");
            var renderer = TradingCardData.loreRenderer();
            lore.add(renderer == null ? "<white>" + slot.id()
                : renderer.bonusLineFor(slot.id(), slot.strength()));
            // Where the roll landed between the weakest and strongest this
            // boost can be, so a re-roll is a decision rather than a guess.
            String bar = renderer == null ? "" : renderer.strengthBarFor(slot.id(), slot.strength());
            if (!bar.isEmpty()) lore.add(bar);
        }
        lore.add("");
        lore.add("<gray>Slot <white>" + (index + 1) + "<dark_gray>/5");
        if (!slot.locked()) {
            lore.add(service.canRoll(card, index, source.stack(player))
                ? "<gray>Cost: <green>" + money(service.costFor(card, index, source.stack(player)))
                : "<red>No different bonus is available");
        }
        lore.add("<gray>Rolls: <white>" + slot.rolls());
        lore.add("<gray>Spent: <green>" + money(slot.spent()));
        return icon(slot.isEmpty() ? Material.ENDER_PEARL : Material.ENDER_EYE,
            slot.locked() ? "<yellow>Protected Bonus Slot"
                : slot.isEmpty() ? "<gray>Empty Bonus Slot" : "<green>Active Bonus Slot",
            lore.toArray(String[]::new));
    }

    private void onSlotClick(InventoryClickEvent event, int index) {
        if (!refreshCard()) return;
        var click = event.getClick();
        var result = click == ClickType.DROP || click == ClickType.CONTROL_DROP
            ? module.editBonusSlot(player, source, index, true)
            : click == ClickType.RIGHT ? module.editBonusSlot(player, source, index, false)
            : module.rollBonusSlot(player, source, index);
        if (result != null) message(result.succeeded(), result.message());
        initialize();
    }

    /** Fallback for servers that report a GUI Q-drop as a player drop event. */
    void clearSlot(int index) {
        if (!refreshCard()) return;
        var result = module.editBonusSlot(player, source, index, true);
        if (result != null) message(result.succeeded(), result.message());
        initialize();
    }

    private static String money(double amount) {
        var economy = PluginStart.getInstance().getEconomyManager();
        return economy == null ? "$" + Math.round(amount)
            : economy.formatBalance(BigDecimal.valueOf(amount));
    }
}
