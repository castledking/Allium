package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.TradingCardData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Two input previews at 11/15 and the next-tier result at 22. Originals stay in
 * inventory until the result is taken, by clicking it or the confirm button.
 */
public final class CardMergeGui extends CardGui {
    public static final int SLOT_FIRST = 11;
    public static final int SLOT_SECOND = 15;
    public static final int SLOT_RESULT = 22;

    private CardSlot.Inventory secondSource;
    private ItemStack secondSnapshot;

    public CardMergeGui(Player player, TradingCardsModule module, TradingCardData card,
                        ItemStack cardStack, CardSlot source) {
        super(player, module, card, cardStack, source, "Merge Cards", "gui_merge");
    }

    @Override
    public void initialize() {
        clear();
        if (!refreshCard()) return;
        setItem(SLOT_FIRST, cardStack.clone(), null);
        navigation(this::mainMenu, this::confirm, "Confirm Merge");
        if (source.equipped()) {
            setItem(SLOT_RESULT, icon(Material.BARRIER, "<red>This card is equipped",
                "<gray>Take it out of /reliques to merge it"), null);
            region(SLOT_NEXT, 2, button("<red>Cannot Merge",
                "<gray>Take this card out of /reliques first"),
                e -> message(false, "Take this card out of /reliques first."));
            return;
        }
        ItemStack second = secondSource == null ? null : secondSource.stack(player);
        if (second == null || !second.isSimilar(secondSnapshot)) {
            secondSource = null;
            secondSnapshot = null;
            second = null;
        }
        setItem(SLOT_SECOND, second == null ? button("<yellow>Select a second card",
            "<gray>Click another card in your inventory", "",
            "<gray>Same mob • same tier",
            "<gray>Both level <white>" + module.mergeRules().maximumLevel(),
            "<gray>Keeps the higher quality",
            "<dark_gray>Your cards stay with you until confirmation") : second.clone(), e -> {
                secondSource = null;
                secondSnapshot = null;
                initialize();
            });
        var other = TradingCardData.read(second).orElse(null);
        var verdict = module.mergeRules().check(card, other);
        if (verdict.allowed()) {
            int quality = module.mergeRules().mergedQuality(card, other);
            var definition = module.registry().byId(card.cardId());
            var preview = definition.flatMap(def -> module.factory().create(def, verdict.resultTier(),
                module.mergeRules().targetLevel(), quality,
                codes.castled.allium.tradingcards.card.CardFactory.startingSignatures(verdict.resultTier()),
                card.bound() || other.bound(), module.config(), java.util.concurrent.ThreadLocalRandom.current()));
            // The preview is the card the merge gives, so taking it is the merge.
            setItem(SLOT_RESULT, preview.orElseGet(() -> icon(Material.BARRIER,
                "<red>Result item unavailable", "<gray>Your cards have not been consumed")),
                preview.isPresent() ? e -> confirm() : null);
            region(SLOT_NEXT, 2, button("<green>Confirm Merge",
                "<gray>Receive a <white>" + verdict.resultTier().name() + " card",
                "<gray>Starting level: <white>" + module.mergeRules().targetLevel(),
                "<gray>Quality: <white>" + quality,
                "", "<red>Consumes both input cards", "<yellow>Click here or take the card to forge"),
                e -> confirm());
        } else {
            setItem(SLOT_RESULT, icon(Material.PAPER, "<yellow>Merge Preview",
                "<gray>" + verdict.message(), "", "<dark_gray>Two cards become one higher tier"), null);
            region(SLOT_NEXT, 2, button("<red>Cannot Merge",
                "<gray>" + verdict.message()), e -> message(false, verdict.message()));
        }
    }

    @Override
    protected void onPlayerClick(InventoryClickEvent event) {
        if (source.equipped() || !(source instanceof CardSlot.Inventory first)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.SHIFT_LEFT) return;
        if (event.getSlot() == first.slot() || TradingCardData.read(event.getCurrentItem()).isEmpty()) return;
        secondSource = new CardSlot.Inventory(event.getSlot());
        secondSnapshot = event.getCurrentItem().clone();
        initialize();
    }

    private void confirm() {
        if (!refreshCard() || source.equipped() || secondSource == null) return;
        ItemStack second = secondSource.stack(player);
        if (second == null || !second.isSimilar(secondSnapshot)) {
            message(false, "The second card changed. Select it again.");
            initialize();
            return;
        }
        ItemStack result = module.mergeCards(player, source.stack(player), second);
        if (result == null) {
            initialize();
            return;
        }
        secondSource = null;
        secondSnapshot = null;
        player.getInventory().addItem(result).values().forEach(rest ->
            player.getWorld().dropItemNaturally(player.getLocation(), rest));
        message(true, "Forged a " + TradingCardData.read(result).orElseThrow().tier().name() + " card!");
        player.closeInventory();
    }
}
