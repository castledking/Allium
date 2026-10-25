package codes.castled.allium.tradingcards.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.inventory.gui.BaseGUI;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.CardSlot;
import codes.castled.allium.tradingcards.item.CardText;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

/** Shared five-row canvas and protected clicks on the player's region thread. */
abstract class CardGui extends BaseGUI {
    static final int SLOT_BACK = 38;
    static final int SLOT_CLOSE = 40;
    static final int SLOT_NEXT = 41;

    protected final TradingCardsModule module;
    protected final CardSlot source;
    protected TradingCardData card;
    protected ItemStack cardStack;
    private final TradingCardData identity;

    CardGui(Player player, TradingCardsModule module, TradingCardData card,
            ItemStack cardStack, CardSlot source, String label, String glyph) {
        super(player, label, 5, PluginStart.getInstance());
        this.module = module;
        this.source = source;
        this.card = card;
        this.identity = card;
        this.cardStack = cardStack.clone();
        Component title = CardText.component("<white>" + label);
        if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            try {
                Component artwork = NexoCardMenuTitle.title(glyph);
                if (artwork != null) {
                    title = artwork;
                }
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().log(Level.WARNING,
                    "Could not render card menu title <shift:-10><glyph:" + glyph + ">", failure);
            }
        }
        inventory = Bukkit.createInventory(this, 45, title);
    }

    protected final void clear() {
        clickHandlers.clear();
        inventory.clear();
    }

    /** Re-read before drawing or spending; never operate on another card. */
    protected final boolean refreshCard() {
        ItemStack current = source.stack(player);
        var fresh = TradingCardData.read(current);
        if (fresh.isEmpty() || !sameIdentity(identity, fresh.get())) {
            message(false, "That card is no longer in its original slot.");
            player.closeInventory();
            return false;
        }
        card = fresh.get();
        cardStack = current.clone();
        TradingCardData.refreshLore(cardStack, card);
        return true;
    }

    private static boolean sameIdentity(TradingCardData a, TradingCardData b) {
        return a.cardId().equals(b.cardId()) && a.mob().equals(b.mob())
            && a.tier() == b.tier() && a.quality() == b.quality() && a.bound() == b.bound();
    }

    @Override
    public final void setItem(int slot, ItemStack item, Consumer<InventoryClickEvent> handler) {
        inventory.setItem(slot, item);
        if (handler == null) clickHandlers.remove(slot);
        else clickHandlers.put(slot, handler);
    }

    @Override
    public final void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!player.getOpenInventory().getTopInventory().equals(inventory)) return;
        int raw = event.getRawSlot();
        if (raw < 0) return;
        if (raw >= size) {
            onPlayerClick(event);
            return;
        }
        ClickType click = event.getClick();
        // Shift-left is a left click to every menu here, and the one players
        // reach for to take a result out of a slot.
        if (click != ClickType.LEFT && click != ClickType.SHIFT_LEFT && click != ClickType.RIGHT
            && click != ClickType.DROP && click != ClickType.CONTROL_DROP) return;
        var handler = clickHandlers.get(raw);
        if (handler != null) handler.accept(event);
    }

    protected void onPlayerClick(InventoryClickEvent event) {}

    protected final ItemStack icon(Material material, String name, String... lines) {
        return decorate(new ItemStack(material), name, "simple", lines);
    }

    protected final ItemStack button(String name, String... lines) {
        ItemStack item = new ItemStack(Material.FEATHER);
        var meta = item.getItemMeta();
        meta.setCustomModelData(9999);
        item.setItemMeta(meta);
        return decorate(item, name, "simple", lines);
    }

    protected final ItemStack decorate(ItemStack item, String name, String frame, String... lines) {
        var meta = item.getItemMeta();
        meta.displayName(CardText.component(name));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("[frame:" + frame + "]"));
        for (String line : lines) lore.add(CardText.component(line));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
            ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    protected final void region(int start, int width, ItemStack item,
                                Consumer<InventoryClickEvent> handler) {
        for (int slot = start; slot < start + width; slot++) setItem(slot, item.clone(), handler);
    }

    protected final void navigation(Runnable back, Runnable next, String nextName) {
        region(SLOT_BACK, 2, button("<yellow>Back", "<dark_gray>Return to the card menu"), e -> back.run());
        setItem(SLOT_CLOSE, button("<red>Close", "<dark_gray>Close this menu"), e -> player.closeInventory());
        region(SLOT_NEXT, 2, button("<aqua>" + nextName,
            "<dark_gray>Click to continue"), e -> next.run());
    }

    protected final void mainMenu() {
        if (refreshCard()) new CardMenuGui(player, module, card, cardStack, source).open();
    }

    protected final void message(boolean success, String text) {
        player.sendMessage(CardText.component((success ? "<green>" : "<red>") + text));
        player.playSound(player.getLocation(), success ? Sound.BLOCK_ENCHANTMENT_TABLE_USE
            : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, success ? 1.3f : 0.7f);
    }
}
