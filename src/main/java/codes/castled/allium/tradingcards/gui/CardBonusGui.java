package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.inventory.gui.BaseGUI;
import codes.castled.allium.managers.economy.EconomyManager;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.bonus.BonusSlot;
import codes.castled.allium.tradingcards.bonus.BonusSlotService;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The per-card bonus slots: five slots, each rolled on its own.
 *
 * <p>Opened from the workshop's Bonuses tab. One row of slots with the card
 * above, so the player sees the card and the slots together — a boost only means
 * something next to the card it is on.
 *
 * <p>Left-click rolls, right-click locks, and the lore tells the player all three
 * things rather than leaving them to guess. Locking is the important one: a slot
 * costs real money and a card reroll would otherwise roll over it, so a locked
 * slot is one nothing can take a bonus out of.
 *
 * <p>Every price shown is the price that will be charged, from the same
 * {@link BonusSlotService#costFor} the roll uses, so the menu cannot quote a
 * figure the roll then contradicts.
 */
public final class CardBonusGui extends BaseGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** The card, above the row of slots. */
    public static final int SLOT_CARD = 4;

    /** The five bonus slots, left to right. */
    public static final int SLOT_FIRST = 19;

    public static final int SLOT_BACK = 18;

    private final TradingCardsModule module;
    private final CardSlot source;

    /** Where the card lives, so a dropped button can find it. */
    public CardSlot source() {
        return source;
    }
    private TradingCardData card;
    private ItemStack cardStack;

    public CardBonusGui(Player player, TradingCardsModule module,
                        TradingCardData card, ItemStack cardStack, CardSlot source) {
        super(player, "Bonus Slots", 3, PluginStart.getInstance());
        this.module = module;
        this.card = card;
        this.cardStack = cardStack.clone();
        this.source = source;
    }

    @Override
    public void initialize() {
        clearAll();
        setItem(SLOT_CARD, cardStack, event -> { });

        var service = module.bonusSlots();
        if (service == null) {
            setItem(SLOT_BACK, icon(Material.BARRIER,
                "<red>Bonus Slots", "<gray>Unavailable on this server"), event -> back());
            return;
        }

        // Re-read from the stack on every draw. A roll in this menu changes the
        // card, and a menu still showing the pre-roll state would invite a
        // second click on a slot that has already been paid for.
        ItemStack held = source.stack(player);
        if (held != null) {
            TradingCardData.read(held).ifPresent(fresh -> this.card = fresh);
        }
        List<BonusSlot> slots = service.slots(held, card);

        for (int i = 0; i < service.slotCount(); i++) {
            int index = i;
            setItem(SLOT_FIRST + i, slotIcon(service, card, slots, index),
                event -> onSlotClick(event, index));
        }

        setItem(SLOT_BACK, icon(Material.ARROW, "<yellow>Back"), event -> back());
    }

    private void onSlotClick(InventoryClickEvent event, int index) {
        var service = module.bonusSlots();
        if (service == null) {
            return;
        }
        // Right-click locks rather than rolling, because an accidental roll costs
        // real money and an accidental lock costs nothing to undo.
        var result = event.isRightClick()
            ? module.editBonusSlot(player, source, index, false)
            : module.rollBonusSlot(player, source, index);
        if (result != null && result.message() != null) {
            player.sendMessage(MM.deserialize("<!italic>"
                + (result.succeeded() ? "<green>" : "<red>") + result.message()));
        }
        initialize();
    }

    private void back() {
        new CardWorkshopGui(player, module, card, cardStack, source).open();
    }

    private void clearAll() {
        clickHandlers.clear();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }
    }

    /** One slot's button: what it holds, what the next roll costs, what to click. */
    private ItemStack slotIcon(BonusSlotService service, TradingCardData card,
                               List<BonusSlot> slots, int index) {
        BonusSlot slot = index < slots.size() ? slots.get(index) : BonusSlot.EMPTY;

        if (!service.isTierUnlocked(card, index)) {
            return icon(Material.GRAY_DYE,
                "<red>Locked Bonus Slot", "<gray>TIER UP TO UNLOCK");
        }
        if (!service.isEnabled()) {
            return icon(Material.BARRIER,
                "<red>Inactive Bonus Slot", "<gray>DISABLED ON THIS SERVER");
        }

        List<String> lore = new ArrayList<>();
        if (slot.locked()) {
            lore.add("<yellow>RIGHT CLICK TO UNLOCK");
            lore.add("<gray>Locked. Nothing can roll this slot.");
        } else {
            lore.add("<gray>LEFT CLICK TO RE-ROLL");
            lore.add("<gray>RIGHT CLICK TO LOCK");
            lore.add("<gray>DROP TO CLEAR");
        }
        lore.add("");
        lore.add(slot.locked() ? "<yellow>LOCKED" : "<green>ACTIVE BONUS SLOT");

        if (!slot.isEmpty()) {
            lore.add("");
            lore.add("<gray>Holding:");
            lore.add("  " + boostLine(slot.id(), card));
        }
        lore.add("");
        if (service.canRoll(card, index)) {
            lore.add("<gray>COST: <green>" + money(service.costFor(card, index)));
        } else if (!slot.locked()) {
            lore.add("<red>This card holds every bonus its tier can roll.");
        }
        lore.add("<gray>ROLLS: <white>" + slot.rolls()
            + "</white> <gray>| SPENT: <green>" + money(slot.spent()));

        Material material = slot.isEmpty()
            ? (slot.locked() ? Material.IRON_BARS : Material.LIME_DYE)
            : (slot.locked() ? Material.LIME_CONCRETE : Material.LIME_CONCRETE);
        String name = slot.isEmpty() ? "Empty Bonus Slot" : "Active Bonus Slot";
        List<String> lines = new ArrayList<>();
        lines.add((slot.locked() ? "<yellow>" : "<green>") + name);
        lines.addAll(lore);
        return icon(index, material, lines.toArray(new String[0]));
    }

    private String boostLine(String boostId, TradingCardData card) {
        var renderer = TradingCardData.loreRenderer();
        return renderer == null ? "<white>" + boostId : renderer.bonusLineFor(boostId, card.level());
    }

    private static String money(double amount) {
        EconomyManager manager = PluginStart.getInstance().getEconomyManager();
        if (manager == null) {
            return "$" + Math.round(amount);
        }
        return manager.formatBalance(BigDecimal.valueOf(amount));
    }

    private ItemStack icon(Material material, String... lines) {
        return icon(-1, material, lines);
    }

    private ItemStack icon(int buttonSlot, Material material, String... lines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(
            codes.castled.allium.tradingcards.item.TradingCardKeys.SLOT_BUTTON,
            org.bukkit.persistence.PersistentDataType.INTEGER, buttonSlot);
        meta.displayName(MM.deserialize("<!italic>" + lines[0]));
        List<Component> lore = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            lore.add(MM.deserialize("<!italic>" + lines[i]));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}