package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.inventory.gui.BaseGUI;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.CardFrame;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.trade.TradeQuote;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The card menu: the card, the band it falls in, and a trade button.
 *
 * <p>Deliberately does NOT show the card's head value. The payout is not
 * committed to at this point — the trade button opens a window where the card
 * is deposited and confirmed, and the heads are only built then. A "worth 7
 * heads" line here would be a quote that the second click could contradict,
 * which is worse than making the player press once to find out.
 */
public final class CardMenuGui extends BaseGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int SLOT_CARD = 13;
    public static final int SLOT_LADDER = 15;
    public static final int SLOT_TRADE = 22;

    private final TradingCardsModule module;
    private final TradingCardData card;
    private final ItemStack cardStack;
    private final TradeQuote.Result quote;
    private final int slot;

    public CardMenuGui(Player player, TradingCardsModule module,
                       TradingCardData card, ItemStack cardStack,
                       TradeQuote.Result quote, int slot) {
        super(player, "Trading Card", 3, codes.castled.allium.PluginStart.getInstance());
        this.module = module;
        this.card = card;
        this.cardStack = cardStack.clone();
        this.quote = quote;
        this.slot = slot;
    }

    @Override
    public void initialize() {
        setItem(SLOT_CARD, displayCard(), null);
        setItem(SLOT_LADDER, ladderIcon(), null);
        setItem(SLOT_TRADE, tradeIcon(), event -> onTrade());
    }

    private ItemStack displayCard() {
        ItemStack shown = cardStack.clone();
        ItemMeta meta = shown.getItemMeta();
        if (meta == null) return shown;
        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize(module.loreSeparator()));
        lore.add(MM.deserialize("<gray>Tier: " + card.tier().name() + " "
            + card.tier().pipsWithPosition()));
        lore.add(MM.deserialize("<gray>Level: <green>" + card.level()
            + "</green><gray>/" + module.config().levelling().maximumLevel()));
        var band = card.band(module.config().quality());
        lore.add(MM.deserialize(band == null
            ? "<red>Quality: unknown (" + card.quality() + "%)"
            : band.colour() + "Quality: " + codes.castled.allium.tradingcards.card.QualityBand
                .displayId(band.id()) + " <gray>(" + card.quality() + "%)"));
        lore.add(MM.deserialize(module.loreSeparator()));
        if (!quote.isQuoted()) {
            // Only a refusal is worth stating here; a payout is not.
            lore.add(MM.deserialize("<red>" + quote.denial().message()));
        }
        meta.lore(CardFrame.rewrap(card.tier(), meta.lore(), lore));
        shown.setItemMeta(meta);
        return shown;
    }

    private ItemStack ladderIcon() {
        ItemStack icon = new ItemStack(Material.PAPER);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        boolean showWorth = module.config().trade().showWorth();
        meta.displayName(MM.deserialize(showWorth
            ? "<gold>Quality Ladder <dark_gray>— what each condition is worth"
            : "<gold>Quality Ladder <dark_gray>— how each condition is graded"));
        List<Component> lore = new ArrayList<>();
        for (var band : module.config().quality()) {
            boolean current = band.equals(card.band(module.config().quality()));
            lore.add(MM.deserialize(
                (current ? "<green>▶ " : "<dark_gray>  ")
                    + band.colour() + codes.castled.allium.tradingcards.card.QualityBand
                        .displayId(band.id())
                    + " <dark_gray>" + band.min() + "-" + band.max() + "%"
                    + (showWorth ? " <white>×" + band.heads() : "")));
        }
        lore.add(MM.deserialize(module.loreSeparator()));
        lore.add(MM.deserialize("<gray>Right-click this card to trade it in."));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack tradeIcon() {
        boolean canTrade = quote.isQuoted();
        ItemStack icon = new ItemStack(canTrade ? Material.HOPPER : Material.BARRIER);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        if (canTrade) {
            meta.displayName(MM.deserialize("<green>Trade In"));
            meta.lore(List.of(
                MM.deserialize("<gray>Opens the trade window."),
                MM.deserialize("<dark_gray>You confirm there before the card is consumed.")
            ));
        } else {
            meta.displayName(MM.deserialize("<red>Cannot be traded"));
            meta.lore(List.of(MM.deserialize("<gray>" + quote.denial().message())));
        }
        icon.setItemMeta(meta);
        return icon;
    }

    private void onTrade() {
        if (!quote.isQuoted()) {
            player.sendMessage(MM.deserialize(
                "<red>That card cannot be traded: " + quote.denial().message()));
            return;
        }
        // Hand over to the confirm window. The card is NOT consumed here: the
        // player still holds it, and the trade window is where it is deposited.
        player.closeInventory();
        codes.castled.allium.scheduler.SchedulerAdapter.runEntity(plugin, player,
            () -> new TradeInGui(player, module).open(), null);
    }

    private EntityType mobType() {
        try {
            return EntityType.valueOf(card.mob());
        } catch (IllegalArgumentException e) {
            return EntityType.PIG;
        }
    }

    /** The mob this card depicts, for callers outside the menu. */
    public String mobName() {
        return card.mob();
    }
}
