package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.inventory.gui.BaseGUI;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.merge.MergeRules;
import codes.castled.allium.tradingcards.reroll.RerollService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The card workshop: reroll one card, or merge two.
 *
 * <p>Three tabs over one window rather than three windows, because each action
 * is a one-click decision on a card the player is already holding:
 *
 * <pre>
 *   [ Reroll ]  [ Merge ]  [ Trade ]        (tab row)
 *   [ ...the card, or the two merge slots... ]
 * </pre>
 *
 * <p>Nothing is charged or consumed until the button is pressed, and every
 * price shown is the price that will be charged — computed by the same
 * {@code costFor} the service uses, so the menu cannot quote a figure the
 * trade then contradicts.
 */
public final class CardWorkshopGui extends BaseGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Tab row, well clear of the content row beneath it. */
    public static final int SLOT_TAB_REROLL = 10;
    public static final int SLOT_TAB_BONUS = 11;
    public static final int SLOT_TAB_MERGE = 12;
    public static final int SLOT_TAB_TRADE = 14;

    /** The card being worked on, or the centre of the two merge slots. */
    public static final int SLOT_CARD = 13;
    public static final int SLOT_ACTION = 16;

    /**
     * The two merge slots.
     *
     * <p>Deliberately not 12 and 14: those are the Merge and Trade tabs, and a
     * staged card sitting on a tab button would swallow the tab click and
     * strand the player in a tab they cannot leave. The merge row is its own
     * row below the card instead.
     */
    public static final int SLOT_MERGE_A = 29;
    public static final int SLOT_MERGE_B = 33;

    private enum Tab { REROLL, BONUS, MERGE, TRADE }

    private final TradingCardsModule module;
    private TradingCardData card;
    private ItemStack cardStack;
    private final int sourceSlot;
    private Tab tab = Tab.REROLL;

    /** The two cards staged for a merge, null when a slot is empty. */
    private ItemStack mergeA;
    private ItemStack mergeB;

    public CardWorkshopGui(Player player, TradingCardsModule module,
                           TradingCardData card, ItemStack cardStack, int sourceSlot) {
        super(player, "Trading Card", 5, codes.castled.allium.PluginStart.getInstance());
        this.module = module;
        this.card = card;
        this.cardStack = cardStack.clone();
        this.sourceSlot = sourceSlot;
    }

    @Override
    public void initialize() {
        clearAll();
        setItem(SLOT_TAB_REROLL, tabIcon(Material.AMETHYST_SHARD, Tab.REROLL,
            "Reroll"), event -> switchTab(Tab.REROLL));
        setItem(SLOT_TAB_BONUS, tabIcon(Material.LIME_DYE, Tab.BONUS,
            "Bonus Slots"), event -> switchTab(Tab.BONUS));
        setItem(SLOT_TAB_MERGE, tabIcon(Material.SHEARS, Tab.MERGE,
            "Merge"), event -> switchTab(Tab.MERGE));
        setItem(SLOT_TAB_TRADE, tabIcon(Material.HOPPER, Tab.TRADE,
            "Trade In"), event -> switchTab(Tab.TRADE));
        switch (tab) {
            case REROLL -> renderReroll();
            // The slot menu is its own window: it has five buttons and its own
            // click handling, and squeezing that into a tab of this one would
            // mean two different things owning the same slots.
            case BONUS -> {
                // switchTab opens the slot menu instead of rendering here; this
                // only runs if the window is somehow redrawn while on the tab.
                new CardBonusGui(player, module, card, cardStack, sourceSlot).open();
            }
            case MERGE -> renderMerge();
            case TRADE -> renderTrade();
        }
    }

    private void clearAll() {
        for (int slot = 0; slot < 45; slot++) {
            inventory.setItem(slot, null);
        }
    }

    private ItemStack tabIcon(Material material, Tab which, String label) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            boolean active = which == tab;
            meta.displayName(MM.deserialize((active ? "<green>" : "<gray>") + label));
            meta.lore(List.of(MM.deserialize(active
                ? "<dark_gray>Click to stay here"
                : "<dark_gray>Click to switch")));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void switchTab(Tab which) {
        // Bonus slots get their own window rather than a tab of this one: they
        // have five buttons and their own click handling, and sharing the slots
        // would mean two things owning the same clicks.
        if (which == Tab.BONUS) {
            new CardBonusGui(player, module, card, cardStack, sourceSlot).open();
            return;
        }
        this.tab = which;
        initialize();
    }

    // ==================== reroll ====================

    private void renderReroll() {
        setItem(SLOT_CARD, displayCard(), null);
        RerollService rerollService = module.reroll();
        if (rerollService == null) {
            setItem(SLOT_ACTION, notice("Rerolling is unavailable", null), null);
            return;
        }
        double cost = rerollService.costFor(card);
        boolean maxed = rerollService.isRerolledOut(card);
        setItem(SLOT_ACTION, rerollButton(cost, maxed, rerollService), event -> onReroll());
    }

    private ItemStack rerollButton(double cost, boolean maxed, RerollService rerollService) {
        ItemStack icon = new ItemStack(maxed ? Material.BARRIER : Material.AMETHYST_BLOCK);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        if (maxed) {
            meta.displayName(MM.deserialize("<red>Fully upgraded"));
            meta.lore(List.of(
                MM.deserialize("<gray>This card holds every signature it can."),
                MM.deserialize("<dark_gray>" + card.signatures().size() + " signatures")));
            icon.setItemMeta(meta);
            return icon;
        }
        meta.displayName(MM.deserialize("<green>Reroll <white>" + format(cost)));
        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<gray>Either <white>unlocks a new signature</white>…"));
        lore.add(MM.deserialize("<gray>…or re-rolls your bonus boosts."));
        lore.add(MM.deserialize("<dark_gray>─────────────"));
        lore.add(MM.deserialize("<gray>Chance of a new signature: <white>"
            + Math.round(rerollService.catalog().signatureUnlockChance() * 100) + "%"));
        lore.add(MM.deserialize("<gray>Signatures: <white>" + card.signatures().size()
            + "</white><gray>/</gray><white>" + rerollService.catalog().maximumSignatures()));
        lore.add(MM.deserialize("<dark_gray>─────────────"));
        lore.add(MM.deserialize("<yellow>Each reroll costs more than the last."));
        lore.add(MM.deserialize("<dark_gray>Your existing signatures are never replaced."));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private void onReroll() {
        // Re-read from the hand: the menu's copy is a snapshot, and the price
        // and unlock chance may have been reloaded since it opened.
        ItemStack held = player.getInventory().getItem(sourceSlot);
        var current = TradingCardData.read(held);
        if (current.isEmpty()) {
            player.sendMessage(MM.deserialize(
                "<red>That slot no longer holds a trading card.</red>"));
            player.closeInventory();
            return;
        }
        RerollService.Result result = module.reroll(player, current.get(), sourceSlot);
        if (!result.succeeded()) {
            player.sendMessage(MM.deserialize("<red>" + result.message()));
            player.playSound(player.getLocation(),
                org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.7f);
            return;
        }
        player.sendMessage(MM.deserialize(
            "<green>Rerolled for <white>" + format(result.cost()) + "</white>. "
                + result.message()));
        player.playSound(player.getLocation(),
            org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        // Re-render from the card as it now stands, so the signature list and
        // the next price both update without the player reopening the menu.
        var updated = TradingCardData.read(player.getInventory().getItem(sourceSlot));
        if (updated.isPresent()) {
            this.card = updated.get();
            this.cardStack = player.getInventory().getItem(sourceSlot).clone();
            initialize();
        }
    }

    // ==================== merge ====================

    private void renderMerge() {
        MergeRules rules = module.mergeRules();
        setItem(SLOT_CARD, notice("Two matching cards merge into the next tier", null), null);
        setItem(SLOT_MERGE_A, mergeA == null ? emptyFrame() : mergeA.clone(), null);
        setItem(SLOT_MERGE_B, mergeB == null ? emptyFrame() : mergeB.clone(), null);

        var first = mergeA == null ? null : TradingCardData.read(mergeA).orElse(null);
        var second = mergeB == null ? null : TradingCardData.read(mergeB).orElse(null);

        if (first == null && second == null) {
            setItem(SLOT_ACTION, notice("Stage a card from your hand", 
                "Right-click a card with a trading card in your main hand"),
                event -> {
                    stageFromHand(SLOT_MERGE_A);
                });
            setItem(SLOT_MERGE_A, emptyFrame(), event -> stageFromHand(SLOT_MERGE_A));
            setItem(SLOT_MERGE_B, emptyFrame(), event -> stageFromHand(SLOT_MERGE_B));
            return;
        }
        MergeRules.Verdict verdict = rules.check(first, second);
        if (verdict.allowed()) {
            setItem(SLOT_ACTION, mergeButton(verdict), event -> onMerge(rules, verdict));
        } else {
            setItem(SLOT_ACTION, notice("Cannot merge", verdict.message()), null);
        }
    }

    private ItemStack emptyFrame() {
        ItemStack frame = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = frame.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize("<yellow>Place a level-"
                + module.config().levelling().maximumLevel() + " card here"));
            frame.setItemMeta(meta);
        }
        return frame;
    }

    private ItemStack mergeButton(MergeRules.Verdict verdict) {
        ItemStack icon = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.displayName(MM.deserialize("<green>Merge into a <white>"
            + verdict.resultTier().name().toLowerCase(Locale.ROOT) + " card"));
        meta.lore(List.of(
            MM.deserialize("<gray>Both cards are consumed."),
            MM.deserialize("<dark_gray>The result starts at level "
                + module.mergeRules().targetLevel() + ", where a fresh drop would start.")
        ));
        icon.setItemMeta(meta);
        return icon;
    }

    private void onMerge(MergeRules rules, MergeRules.Verdict verdict) {
        ItemStack result = module.mergeCards(player, mergeA, mergeB, rules, verdict);
        if (result == null) {
            return;
        }
        mergeA = null;
        mergeB = null;
        var overflow = player.getInventory().addItem(result);
        overflow.values().forEach(rest ->
            player.getWorld().dropItemNaturally(player.getLocation(), rest));
        player.sendMessage(MM.deserialize(
            "<green>Merged into a <white>"
                + verdict.resultTier().name().toLowerCase(Locale.ROOT) + " card</white>."));
        player.playSound(player.getLocation(),
            org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        initialize();
    }

    /**
     * Stages a card from the hand into a merge slot.
     *
     * <p>Copies rather than moves, so a player who mis-clicks does not lose the
     * card from their inventory — the staged copy is discarded on close and the
     * real card is only consumed when the merge actually happens.
     */
    private void stageFromHand(int slot) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (TradingCardData.read(held).isEmpty()) {
            player.sendMessage(MM.deserialize(
                "<red>Hold a trading card in your main hand first.</red>"));
            return;
        }
        if (slot == SLOT_MERGE_A) {
            mergeA = held.clone();
        } else {
            mergeB = held.clone();
        }
        initialize();
    }

    // ==================== trade ====================

    private void renderTrade() {
        setItem(SLOT_CARD, displayCard(), null);
        var quote = module.quote(card);
        if (quote.isQuoted()) {
            setItem(SLOT_ACTION, tradeButton(), event -> {
                player.closeInventory();
                codes.castled.allium.scheduler.SchedulerAdapter.runEntity(plugin, player,
                    () -> new TradeInGui(player, module).open(), null);
            });
        } else {
            setItem(SLOT_ACTION, notice("Cannot be traded", quote.denial().message()), null);
        }
    }

    private ItemStack tradeButton() {
        ItemStack icon = new ItemStack(Material.HOPPER);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.displayName(MM.deserialize("<green>Trade In"));
        meta.lore(List.of(
            MM.deserialize("<gray>Opens the confirm window."),
            MM.deserialize("<dark_gray>The card is only spent once you confirm.")));
        icon.setItemMeta(meta);
        return icon;
    }

    // ==================== shared ====================

    private ItemStack displayCard() {
        ItemStack shown = cardStack.clone();
        ItemMeta meta = shown.getItemMeta();
        if (meta == null) return shown;
        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<dark_gray>─────────────"));
        lore.add(MM.deserialize("<gray>Tier: " + card.tier().name() + " "
            + card.tier().pipsWithPosition()));
        lore.add(MM.deserialize("<gray>Level: <green>" + card.level()
            + "</green><gray>/" + module.config().levelling().maximumLevel()));
        var band = card.band(module.config().quality());
        lore.add(MM.deserialize(band == null
            ? "<red>Quality: unknown"
            : band.colour() + "Quality: "
                + codes.castled.allium.tradingcards.card.QualityBand.displayId(band.id())
                + " <gray>(" + card.quality() + "%)"));
        if (card.rerolls() > 0) {
            lore.add(MM.deserialize("<gray>Rerolled: <white>" + card.rerolls() + "</white> times"));
        }
        lore.add(MM.deserialize("<dark_gray>─────────────"));
        lore.add(MM.deserialize("<gray>Signatures: <white>"
            + String.join(", ", card.signatures()) + "</white>"));
        meta.lore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private ItemStack notice(String title, String detail) {
        ItemStack icon = new ItemStack(Material.PAPER);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize("<gray>" + title));
            if (detail != null) {
                meta.lore(List.of(MM.deserialize("<dark_gray>" + detail)));
            }
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static String format(double amount) {
        return java.text.NumberFormat.getIntegerInstance(Locale.ROOT).format((long) amount);
    }

    /** The staged merge slots, so a close handler can discard the copies. */
    public List<ItemStack> stagedCopies() {
        List<ItemStack> staged = new ArrayList<>();
        if (mergeA != null) staged.add(mergeA);
        if (mergeB != null) staged.add(mergeB);
        return staged;
    }
}
