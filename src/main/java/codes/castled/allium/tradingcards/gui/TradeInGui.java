package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.inventory.gui.BaseGUI;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.trade.TradeQuote;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Trade-in: the player places a card in the slot, confirms, and the heads
 * appear in the same slot.
 *
 * <p>Nothing is granted to the player until they physically take the heads out
 * of the window. A player who closes the menu mid-trade does not lose the
 * payout — the remainder is owed to them and handed over on their next visit
 * (see {@code PendingPayoutStore}) — and the heads never drop on the floor
 * unless the player's inventory is full, where dropping is the only way the
 * items can exist at all.
 *
 * <p>Slot layout:
 * <pre>
 *   [ confirm ]   [ deposit ]   [ heads ]
 * </pre>
 */
public final class TradeInGui extends BaseGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** The slot the card is placed in, and where the heads appear. */
    public static final int SLOT_DEPOSIT = 13;
    /** The confirm button. */
    public static final int SLOT_CONFIRM = 11;
    /** Where the heads are placed for collection. */
    public static final int SLOT_PAYOUT = 15;

    private final TradingCardsModule module;

    /** True once the card has been exchanged and the payout is on display. */
    private boolean exchanged;

    public TradeInGui(Player player, TradingCardsModule module) {
        super(player, "Trade In", 3, codes.castled.allium.PluginStart.getInstance());
        this.module = module;
    }

    @Override
    public void initialize() {
        setItem(SLOT_CONFIRM, confirmButton(), event -> onConfirm());
        setItem(SLOT_DEPOSIT, exchanged ? emptySlot() : depositFrame(), null);
        if (!exchanged) {
            setItem(SLOT_PAYOUT, payoutFrame(), null);
        }
    }

    private ItemStack emptySlot() {
        ItemStack glass = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            glass.setItemMeta(meta);
        }
        return glass;
    }

    private ItemStack depositFrame() {
        ItemStack frame = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = frame.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize("<yellow>Deposit a trading card here"));
            meta.lore(List.of(
                MM.deserialize("<gray>Place a card in this slot, then press confirm."),
                MM.deserialize("<dark_gray>The card is only consumed once you confirm.")
            ));
            frame.setItemMeta(meta);
        }
        return frame;
    }

    private ItemStack payoutFrame() {
        ItemStack frame = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta meta = frame.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize("<green>Your heads appear here"));
            meta.lore(List.of(
                MM.deserialize("<gray>Take them out of the window to keep them."),
                MM.deserialize("<dark_gray>Closing early will not lose them — they are held for you.")
            ));
            frame.setItemMeta(meta);
        }
        return frame;
    }

    private ItemStack confirmButton() {
        ItemStack button = new ItemStack(exchanged ? Material.BARRIER : Material.EMERALD_BLOCK);
        ItemMeta meta = button.getItemMeta();
        if (meta == null) return button;
        if (exchanged) {
            meta.displayName(MM.deserialize("<red>Already traded"));
            meta.lore(List.of(MM.deserialize("<gray>Take the heads from the slot on the right.")));
        } else {
            meta.displayName(MM.deserialize("<green>Confirm trade"));
            meta.lore(List.of(
                MM.deserialize("<gray>Exchange the deposited card for its mob's heads."),
                MM.deserialize("<dark_gray>The card is consumed; the heads must be collected.")
            ));
        }
        button.setItemMeta(meta);
        return button;
    }

    private void onConfirm() {
        if (exchanged) {
            player.sendMessage(MM.deserialize(
                "<gray>Already traded — take the heads from the slot on the right.</gray>"));
            return;
        }
        ItemStack deposited = inventory.getItem(SLOT_DEPOSIT);
        var card = TradingCardData.read(deposited);
        if (card.isEmpty()) {
            player.sendMessage(MM.deserialize(
                "<red>Put a trading card in the middle slot first.</red>"));
            return;
        }

        TradeQuote.Result quote = module.quote(card.get());
        if (!quote.isQuoted()) {
            player.sendMessage(MM.deserialize(
                "<red>That card cannot be traded: " + quote.denial().message()));
            return;
        }

        ItemStack payout = module.buildPayout(card.get(), quote.quote().heads());
        if (payout == null) {
            // The module already explained why; nothing has been consumed.
            return;
        }

        // The card is cleared only once the payout is known to be buildable, so
        // a card can never be eaten by a trade that produced nothing.
        inventory.setItem(SLOT_DEPOSIT, emptySlot());
        inventory.setItem(SLOT_PAYOUT, payout);
        exchanged = true;
        setItem(SLOT_CONFIRM, confirmButton(), null);

        player.playSound(player.getLocation(),
            org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        player.sendMessage(MM.deserialize(
            "<green>Traded in.</green> <gray>Take your heads from the slot on the right — "
                + "closing the window will not lose them.</gray>"));
    }

    /**
     * Hands back anything left in the payout slot when the window closes.
     *
     * <p>Called by the module on close. The card slot is not examined: a card
     * that was deposited but never confirmed was never taken, so it is
     * returned to the player untouched rather than being traded for nothing.
     */
    public List<ItemStack> collectUnclaimed() {
        List<ItemStack> unclaimed = new ArrayList<>();
        for (int slot : new int[] { SLOT_PAYOUT, SLOT_DEPOSIT }) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (TradingCardData.isCard(stack)) {
                unclaimed.add(stack);
            } else {
                unclaimed.add(stack);
            }
            inventory.setItem(slot, null);
        }
        return unclaimed;
    }

    /** True once a card has been exchanged in this window. */
    public boolean hasExchanged() {
        return exchanged;
    }
}
