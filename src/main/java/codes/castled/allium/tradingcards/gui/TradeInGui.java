package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Review the source card and live payout, then explicitly exchange it. */
public final class TradeInGui extends CardGui {
    public static final int SLOT_CARD = 13;
    public static final int SLOT_CONFIRM = 22;

    private boolean exchanged;
    private ItemStack unclaimed;
    private codes.castled.allium.tradingcards.trade.TradeQuote.Quote displayedQuote;

    public TradeInGui(Player player, TradingCardsModule module, TradingCardData card,
                       ItemStack cardStack, CardSlot source) {
        super(player, module, card, cardStack, source, "Trade In", "gui_trade");
    }

    @Override
    public void initialize() {
        clear();
        if (exchanged) {
            if (unclaimed != null) setItem(SLOT_CARD, unclaimed.clone(), e -> collect());
            setItem(SLOT_CONFIRM, icon(Material.NETHERITE_AXE, "<green>Trade Complete",
                "<gray>Click your heads above to collect",
                "<dark_gray>Unclaimed heads are saved on close"), e -> collect());
            navigation(() -> player.closeInventory(), this::collect, "Collect Heads");
            return;
        }
        if (!refreshCard()) return;
        setItem(SLOT_CARD, cardStack.clone(), null);
        navigation(this::mainMenu, () -> {
            if (refreshCard()) new CardBonusGui(player, module, card, cardStack, source).open();
        }, "Bonus Boosts");
        var quote = module.quote(card);
        displayedQuote = quote.quote();
        if (source.equipped()) {
            setItem(SLOT_CONFIRM, icon(Material.BARRIER, "<red>This card is equipped",
                "<gray>Take it out of /reliques to trade it in"), null);
        } else if (!quote.isQuoted()) {
            setItem(SLOT_CONFIRM, icon(Material.BARRIER, "<red>Cannot Trade In",
                "<gray>" + quote.denial().message()), null);
        } else {
            String mob = card.mob().replace('_', ' ');
            setItem(SLOT_CONFIRM, decorate(new ItemStack(Material.NETHERITE_AXE),
                "<gold>Trade In", "copper", "<dark_gray>↓", "",
                "<yellow>Collect: <white>" + quote.quote().heads() + "x <aqua>" + mob + " Head",
                "<dark_gray>Click to confirm", "",
                "<red>This consumes your card"), e -> confirm());
        }
    }

    private void confirm() {
        if (exchanged || source.equipped() || !refreshCard()) return;
        var quote = module.quote(card);
        if (!quote.isQuoted()) {
            message(false, quote.denial().message());
            initialize();
            return;
        }
        if (!quote.quote().equals(displayedQuote)) {
            message(false, "The payout changed. Review the new offer before confirming.");
            initialize();
            return;
        }
        ItemStack payout = module.buildPayout(card, quote.quote().heads());
        if (payout == null) {
            message(false, "Could not build this card's head reward. Your card has not been consumed.");
            return;
        }
        // Build first. The original only leaves its source after a payout exists.
        ItemStack original = source.stack(player);
        if (original.getAmount() > 1) {
            ItemStack remainder = original.clone();
            remainder.setAmount(original.getAmount() - 1);
            source.save(player, remainder);
        } else {
            source.save(player, null);
        }
        unclaimed = payout;
        exchanged = true;
        message(true, "Traded in! Click the heads to collect your reward.");
        initialize();
    }

    private void collect() {
        if (unclaimed == null) return;
        var overflow = player.getInventory().addItem(unclaimed.clone());
        unclaimed = overflow.isEmpty() ? null : overflow.values().iterator().next();
        if (unclaimed == null) {
            message(true, "Heads collected. Enjoy!");
            player.closeInventory();
        } else {
            message(false, "Make room in your inventory to collect the remaining heads.");
            initialize();
        }
    }

    /** Only actual unpaid heads; display cards and interface buttons are never returned. */
    public List<ItemStack> collectUnclaimed() {
        if (unclaimed == null) return List.of();
        var result = List.of(unclaimed);
        unclaimed = null;
        inventory.clear();
        return result;
    }

    public boolean hasExchanged() {
        return exchanged;
    }
}
