package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.TradingCardData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The card's home: bonuses, merging and trading, backed by Nexo's gui_cards artwork. */
public final class CardMenuGui extends CardGui {
    public static final int SLOT_CARD = 13;
    public static final int SLOT_BONUSES = 36;
    public static final int SLOT_MERGE = 39;
    public static final int SLOT_TRADE = 41;
    public static final int SLOT_EXIT = 43;

    public CardMenuGui(Player player, TradingCardsModule module, TradingCardData card,
                       ItemStack cardStack, CardSlot source) {
        super(player, module, card, cardStack, source, "Trading Card", "gui_cards");
    }

    @Override
    public void initialize() {
        clear();
        if (!refreshCard()) return;
        setItem(SLOT_CARD, cardStack.clone(), null);
        int unlocked = Math.min(5, card.tier().ordinal() + 1);
        long filled = card.bonuses().stream().limit(unlocked).filter(id -> !id.isBlank()).count();
        region(SLOT_BONUSES, 3, button("<aqua>Bonus Boosts",
            "<gray>Make this card your own.", "",
            "<gray>Unlocked: <white>" + unlocked + "<dark_gray>/5",
            "<gray>Active: <green>" + filled,
            "<gray>Signatures: <white>" + card.signatures().size() + " <dark_gray>• fixed by tier",
            "", "<yellow>Click to manage bonuses"), e -> {
                if (refreshCard()) new CardBonusGui(player, module, card, cardStack, source).open();
            });
        region(SLOT_MERGE, 2, button("<green>Merge Cards",
            "<gray>Two matching cards. One higher tier.", "",
            "<gray>Requires the same mob and tier",
            "<gray>Both cards must be level <white>" + module.config().levelling().maximumLevel(),
            "<dark_gray>The result starts at level " + module.mergeRules().targetLevel(),
            "", "<yellow>Click to open the forge"), e -> {
                if (refreshCard()) new CardMergeGui(player, module, card, cardStack, source).open();
            });
        region(SLOT_TRADE, 2, button("<gold>Trade In",
            "<gray>Exchange this card for mob heads.", "",
            "<dark_gray>Review the payout before confirming.",
            "", "<yellow>Click to view trade"), e -> {
                if (refreshCard()) new TradeInGui(player, module, card, cardStack, source).open();
            });
        region(SLOT_EXIT, 2, button("<red>Close", "<dark_gray>Back to your adventure"),
            e -> player.closeInventory());
    }
}
