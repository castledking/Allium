package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.inventory.gui.BaseGUI;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.card.QualityBand;
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
 * The right-click menu for a trading card: the card, what it is worth, and the
 * trade button.
 *
 * <p>The card is shown whole rather than as a table of every band, so the
 * player sees the number that applies to *this* card. The full ladder is on the
 * next line of the lore for anyone who wants to know what they would need for a
 * better one.
 */
public final class CardMenuGui extends BaseGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Where the card itself sits. */
    public static final int SLOT_CARD = 13;
    /** Where the trade button sits. */
    public static final int SLOT_TRADE = 22;
    /** Where the band ladder is shown. */
    public static final int SLOT_LADDER = 15;

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
        var meta = shown.getItemMeta();
        if (meta == null) return shown;
        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<dark_gray>─────────────"));
        lore.add(MM.deserialize("<gray>Tier: " + card.tier().name() + " "
            + card.tier().pipsWithPosition()));
        lore.add(MM.deserialize("<gray>Level: <green>" + card.level()
            + "</green><gray>/" + module.config().levelling().maximumLevel()));
        QualityBand band = card.band(module.config().quality());
        if (band == null) {
            lore.add(MM.deserialize("<red>Quality: unknown (" + card.quality() + "%)"));
        } else {
            lore.add(MM.deserialize(band.colour() + "Quality: "
                + QualityBand.displayId(band.id()) + " <gray>(" + card.quality() + "%)"));
        }
        lore.add(MM.deserialize("<dark_gray>─────────────"));
        if (quote.isQuoted()) {
            lore.add(MM.deserialize("<yellow>Worth: <white>" + quote.quote().heads()
                + "</white> " + mobName() + " <gray>head(s)"));
        } else {
            lore.add(MM.deserialize("<red>" + quote.denial().message()));
        }
        meta.lore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private ItemStack ladderIcon() {
        ItemStack icon = new ItemStack(Material.PAPER);
        var meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.displayName(MM.deserialize(
            "<gold>Quality Ladder <dark_gray>— what each condition is worth"));
        List<Component> lore = new ArrayList<>();
        for (QualityBand band : module.config().quality()) {
            boolean current = band.equals(card.band(module.config().quality()));
            lore.add(MM.deserialize(
                (current ? "<green>▶ " : "<dark_gray>  ")
                    + band.colour() + QualityBand.displayId(band.id())
                    + " <dark_gray>" + band.min() + "-" + band.max() + "%"
                    + " <white>×" + band.heads()));
        }
        lore.add(MM.deserialize("<dark_gray>─────────────"));
        lore.add(MM.deserialize("<gray>A card is worth the heads of the band its quality falls in."));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack tradeIcon() {
        boolean canTrade = quote.isQuoted();
        ItemStack icon = canTrade
            ? codes.castled.allium.tradingcards.item.HeadResolver.iconFor(mobType(), quote.quote().heads())
            : new ItemStack(Material.BARRIER);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        if (canTrade) {
            meta.displayName(MM.deserialize(
                "<green>Trade for <white>" + quote.quote().heads()
                + "</white> " + mobName() + " <green>Head(s)"));
            meta.lore(List.of(
                MM.deserialize("<gray>Consumes this card."),
                MM.deserialize("<dark_gray>Hand the card in at the trade button.")
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
        int heads = quote.quote().heads();
        plugin.getLogger().info("card trade: " + player.getName() + " traded a "
            + card.tier() + " " + card.mob() + " card for " + heads + " head(s)");

        // The module re-reads the card out of the player's hand and re-quotes
        // it before touching anything, so a stale menu figure cannot trade at
        // the wrong rate and a moved card cannot be consumed.
        boolean traded = module.tradeCard(player, slot, cardStack);
        if (traded) {
            player.closeInventory();
        }
    }

    private EntityType mobType() {
        try {
            return EntityType.valueOf(card.mob());
        } catch (IllegalArgumentException e) {
            return EntityType.PIG;
        }
    }

    private String mobName() {
        return codes.castled.allium.spawnercraft.SpawnerCoreManager
            .formatEntityName(mobType());
    }
}
