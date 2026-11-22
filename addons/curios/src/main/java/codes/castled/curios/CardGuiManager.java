package codes.castled.curios;

import codes.castled.allium.api.ItemIdentity;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Interactive 4-screen SF trading card GUI flow (Home, Bonus Boosts, Merge Forge, Trade In)
 * backed by {@code curios:card_menus} artwork, plus right-click and drop triggers.
 */
final class CardGuiManager implements Listener {

    private static final int SLOT_BACK = 38;
    private static final int SLOT_CLOSE = 40;
    private static final int SLOT_NEXT = 41;

    sealed interface CardSource permits InventorySource, EquippedSource {
        boolean equipped();
        ItemStack read(CuriosPlugin plugin, Player player);
        void write(CuriosPlugin plugin, Player player, ItemStack updated);
    }

    record InventorySource(int slot) implements CardSource {
        @Override public boolean equipped() { return false; }
        @Override public ItemStack read(CuriosPlugin plugin, Player player) {
            if (slot < 0 || slot >= player.getInventory().getSize()) return null;
            return player.getInventory().getItem(slot);
        }
        @Override public void write(CuriosPlugin plugin, Player player, ItemStack updated) {
            if (slot >= 0 && slot < player.getInventory().getSize()) {
                player.getInventory().setItem(slot, updated);
            }
        }
    }

    record EquippedSource(Catalog.Slot slot) implements CardSource {
        @Override public boolean equipped() { return true; }
        @Override public ItemStack read(CuriosPlugin plugin, Player player) {
            return plugin.equipped(player, slot);
        }
        @Override public void write(CuriosPlugin plugin, Player player, ItemStack updated) {
            plugin.setEquipped(player, slot, updated);
        }
    }

    private final CuriosPlugin plugin;

    CardGuiManager(CuriosPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isCardScreen(InventoryHolder holder) {
        return holder instanceof CardScreen;
    }

    void openHome(Player player, CardSource source) {
        ItemStack stack = source.read(plugin, player);
        Catalog.Entry entry = plugin.entry(stack);
        if (entry == null || entry.legacyCard().isEmpty()) {
            plugin.tellCards(player, "Hold or click a trading card to open its menu.");
            return;
        }
        new HomeScreen(player, source, entry, plugin.identity(stack).orElseThrow()).open();
    }

    /**
     * Opens a card's menu in response to an inventory click, on the next tick. The click is
     * cancelled, but the client has already moved the card onto its cursor. Bukkit corrects that
     * only in the menu that is open when the event ends, so opening the card menu inside the event
     * left the player's own inventory with a ghost card on its cursor once the menu closed.
     */
    void openHomeAfterClick(Player player, CardSource source) {
        plugin.core().scheduler().runOnServer(() -> {
            if (!player.isOnline()) return;
            player.updateInventory();
            openHome(player, source);
        });
    }

    void closeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof CardScreen screen) {
                screen.returnUnclaimed();
                player.closeInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onScreenClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CardScreen screen)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !screen.player.getUniqueId().equals(player.getUniqueId())) return;
        if (!plugin.editable(player)) return;
        int raw = event.getRawSlot();
        if (raw < 0) return;
        if (raw >= 45) {
            screen.onPlayerInventoryClick(event);
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.SHIFT_LEFT && click != ClickType.RIGHT
                && click != ClickType.DROP && click != ClickType.CONTROL_DROP) return;
        Consumer<InventoryClickEvent> handler = screen.handlers.get(raw);
        if (handler != null) handler.accept(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onScreenDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CardScreen) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onScreenClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof CardScreen screen) {
            screen.returnUnclaimed();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryRightClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("curios.use")) return;
        if (event.getClick() != ClickType.RIGHT) return;
        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();
        if (topHolder instanceof CardScreen || plugin.isEquipmentMenu(topHolder)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(player.getInventory())) return;
        ItemStack clicked = event.getCurrentItem();
        Catalog.Entry entry = plugin.entry(clicked);
        if (entry == null || entry.legacyCard().isEmpty()) return;
        event.setCancelled(true);
        openHomeAfterClick(player, new InventorySource(event.getSlot()));
    }

    /**
     * Right-clicking a held card opens its menu, in the air or at a block. This is the trigger
     * creative players have: the creative inventory screen handles its own clicks on the client,
     * so a right click there never reaches the server as one.
     *
     * <p>Not {@code ignoreCancelled}: Bukkit reports every air click as cancelled (there is no block
     * to use), which would leave only block clicks. A plugin that denies using the item still wins.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onRightClickHeldCard(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND || event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("curios.use")) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        Catalog.Entry entry = plugin.entry(held);
        if (entry == null || entry.legacyCard().isEmpty()) return;
        event.setCancelled(true);
        openHome(player, new InventorySource(player.getInventory().getHeldItemSlot()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropCard(PlayerDropItemEvent event) {
        if (plugin.cardRules().allowDrop) return;
        ItemStack dropped = event.getItemDrop().getItemStack();
        Catalog.Entry entry = plugin.entry(dropped);
        if (entry == null || entry.legacyCard().isEmpty()) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof CardScreen) return;
        ItemStack copy = dropped.clone();
        plugin.core().scheduler().runOnServer(() -> {
            if (!player.isOnline()) return;
            ItemStack[] storage = player.getInventory().getStorageContents();
            for (int slot = 0; slot < storage.length; slot++) {
                if (storage[slot] != null && storage[slot].isSimilar(copy)) {
                    openHome(player, new InventorySource(slot));
                    return;
                }
            }
        });
    }

    private abstract class CardScreen implements InventoryHolder {
        final Player player;
        final CardSource source;
        final String expectedEntryId;
        final String expectedQuality;
        final String expectedBound;
        final Inventory inventory;
        final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
        Catalog.Entry entry;
        ItemStack stack;
        ItemIdentity identity;

        CardScreen(Player player, CardSource source, Catalog.Entry entry, ItemIdentity identity, String glyph, String plainTitle) {
            this.player = player;
            this.source = source;
            this.entry = entry;
            this.identity = identity;
            this.expectedEntryId = entry.id();
            this.expectedQuality = identity.state().getOrDefault("quality", "100");
            this.expectedBound = identity.state().getOrDefault("bound", "false");
            this.inventory = PackPresentation.cardMenuInventory(this, plugin.texturedMenu(), glyph, plainTitle);
        }

        @Override public Inventory getInventory() { return inventory; }

        void open() {
            render();
            player.openInventory(inventory);
        }

        abstract void render();

        void onPlayerInventoryClick(InventoryClickEvent event) {}

        void returnUnclaimed() {}

        void clear() {
            handlers.clear();
            inventory.clear();
        }

        boolean refresh() {
            ItemStack current = source.read(plugin, player);
            Catalog.Entry freshEntry = plugin.entry(current);
            Optional<ItemIdentity> freshId = plugin.identity(current);
            if (freshEntry == null || freshId.isEmpty()
                    || !freshEntry.id().equals(expectedEntryId)
                    || !freshId.get().state().getOrDefault("quality", "100").equals(expectedQuality)
                    || !freshId.get().state().getOrDefault("bound", "false").equals(expectedBound)) {
                feedback(false, "That card is no longer in its original slot.");
                player.closeInventory();
                return false;
            }
            this.entry = freshEntry;
            this.identity = freshId.get();
            this.stack = current.clone();
            return true;
        }

        void set(int slot, ItemStack item, Consumer<InventoryClickEvent> handler) {
            inventory.setItem(slot, item);
            if (handler == null) handlers.remove(slot);
            else handlers.put(slot, handler);
        }

        void region(int start, int width, ItemStack item, Consumer<InventoryClickEvent> handler) {
            for (int s = start; s < start + width; s++) set(s, item.clone(), handler);
        }

        ItemStack icon(Material material, String title, String... lore) {
            return PackPresentation.decorateButton(new ItemStack(material), title, List.of(lore), "simple", plugin.texturedMenu());
        }

        ItemStack button(Material fallbackMaterial, String title, String... lore) {
            ItemStack base;
            if (plugin.texturedMenu()) {
                base = new ItemStack(Material.PAPER);
                ItemMeta meta = base.getItemMeta();
                meta.setCustomModelData(plugin.iconModel("blank"));
                base.setItemMeta(meta);
            } else {
                base = new ItemStack(fallbackMaterial);
            }
            return PackPresentation.decorateButton(base, title, List.of(lore), "simple", plugin.texturedMenu());
        }

        /** An icon drawn with the card slot's art (SF's card.png), as /relics shows an empty card slot. */
        ItemStack cardIcon(String title, String... lore) {
            ItemStack base = new ItemStack(Material.PAPER);
            ItemMeta meta = base.getItemMeta();
            meta.setCustomModelData(plugin.iconModel("card"));
            base.setItemMeta(meta);
            return PackPresentation.decorateButton(base, title, List.of(lore), "simple", plugin.texturedMenu());
        }

        void navigation(Runnable back, Runnable next, String nextName, Material nextFallback) {
            region(SLOT_BACK, 2, button(Material.ARROW, "<yellow>Back", "<dark_gray>Return to the card menu"), e -> back.run());
            set(SLOT_CLOSE, button(Material.BARRIER, "<red>Close", "<dark_gray>Close this menu"), e -> player.closeInventory());
            region(SLOT_NEXT, 2, button(nextFallback, "<aqua>" + nextName, "<dark_gray>Click to continue"), e -> next.run());
        }

        void openHomeScreen() {
            if (refresh()) new HomeScreen(player, source, entry, identity).open();
        }

        void feedback(boolean success, String text) {
            plugin.tellCards(player, (success ? "&a" : "&c") + text);
            player.playSound(player.getLocation(),
                    success ? Sound.BLOCK_ENCHANTMENT_TABLE_USE : Sound.BLOCK_NOTE_BLOCK_BASS,
                    0.6f, success ? 1.3f : 0.7f);
        }
    }

    private final class HomeScreen extends CardScreen {
        HomeScreen(Player player, CardSource source, Catalog.Entry entry, ItemIdentity identity) {
            super(player, source, entry, identity, PackPresentation.MENU_HOME_GLYPH, "Trading Card");
        }

        @Override
        void render() {
            clear();
            if (!refresh()) return;
            set(13, stack.clone(), null);
            CardRules.Tier tier = CardRules.Tier.parse(entry.legacyTier());
            int unlocked = Math.min(CardRules.BONUS_SLOT_COUNT, tier.ordinal() + 1);
            List<CardRules.BonusSlotData> bonuses = CardRules.parseBonuses(identity.state().get("bonuses"));
            long filled = bonuses.stream().limit(unlocked).filter(b -> !b.isEmpty()).count();
            List<String> sigs = CardRules.parseSignatures(identity.state().get("signatures"));

            region(36, 3, button(Material.NETHER_STAR, "<aqua>Bonus Boosts",
                    "<gray>Make this card your own.", "",
                    "<gray>Unlocked: <white>" + unlocked + "<dark_gray>/5",
                    "<gray>Active: <green>" + filled,
                    "<gray>Signatures: <white>" + sigs.size() + " <dark_gray>• fixed by tier",
                    "", "<yellow>Click to manage bonuses"), e -> {
                if (refresh()) new BonusScreen(player, source, entry, identity).open();
            });

            region(39, 2, button(Material.ANVIL, "<green>Merge Cards",
                    "<gray>Two matching cards. One higher tier.", "",
                    "<gray>Requires the same mob and tier",
                    "<gray>Both cards must be level <white>" + entry.maxLevel(),
                    "<dark_gray>The result starts at level 0",
                    "", "<yellow>Click to open the forge"), e -> {
                if (refresh()) new MergeScreen(player, source, entry, identity).open();
            });

            region(41, 2, button(Material.EMERALD, "<gold>Trade In",
                    "<gray>Exchange this card for rewards.", "",
                    "<dark_gray>Review the payout before confirming.",
                    "", "<yellow>Click to view trade"), e -> {
                if (refresh()) new TradeScreen(player, source, entry, identity).open();
            });

            region(43, 2, button(Material.BARRIER, "<red>Close", "<dark_gray>Back to your adventure"),
                    e -> player.closeInventory());
        }
    }

    private final class BonusScreen extends CardScreen {
        BonusScreen(Player player, CardSource source, Catalog.Entry entry, ItemIdentity identity) {
            super(player, source, entry, identity, PackPresentation.MENU_BOOST_GLYPH, "Bonus Boosts");
        }

        @Override
        void render() {
            clear();
            if (!refresh()) return;
            set(22, stack.clone(), null);
            CardRules.Tier tier = CardRules.Tier.parse(entry.legacyTier());
            int unlocked = Math.min(CardRules.BONUS_SLOT_COUNT, tier.ordinal() + 1);
            List<CardRules.BonusSlotData> slots = CardRules.parseBonuses(identity.state().get("bonuses"));

            for (int i = 0; i < CardRules.BONUS_SLOT_COUNT; i++) {
                final int slotIdx = i;
                set(2 + i, slotItem(tier, unlocked, slots.get(i), i), e -> onSlotClick(e, slotIdx));
            }
            navigation(this::openHomeScreen, () -> {
                if (refresh()) new MergeScreen(player, source, entry, identity).open();
            }, "Merge Cards", Material.ANVIL);
        }

        private ItemStack slotItem(CardRules.Tier tier, int unlocked, CardRules.BonusSlotData slot, int index) {
            if (index >= unlocked) {
                CardRules.Tier reqTier = CardRules.Tier.values()[Math.min(index, CardRules.Tier.values().length - 1)];
                return icon(Material.FIRE_CHARGE, "<red>Locked Bonus Slot",
                        "<dark_gray>Tier up to unlock", "",
                        "<gray>Unlocks at <white>" + CardRules.titleCase(reqTier.name()));
            }
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
                CardRules.BonusDef def = CardRules.BONUSES.get(slot.boostId());
                lore.add(def != null ? def.lineMini(slot.strength()) : "<white>" + CardRules.titleCase(slot.boostId()));
                if (def == null || def.hasMagnitude()) {
                    lore.add(PackPresentation.strengthBarMini(slot.strength(), plugin.cardRules().xpBarWidth));
                }
            }
            lore.add("");
            lore.add("<gray>Slot <white>" + (index + 1) + "<dark_gray>/5");
            CardRules.RollPricing pricing = plugin.cardRules().rollPricing;
            if (!slot.locked()) {
                List<CardRules.BonusSlotData> all = CardRules.parseBonuses(identity.state().get("bonuses"));
                if (!pricing.enabled()) lore.add("<red>Rolling is unavailable");
                else if (plugin.cardRules().rollBonusSlot(tier, all, index, ThreadLocalRandom.current()) == null)
                    lore.add("<red>No different bonus is available");
                else lore.add("<gray>Cost: <green>" + money(pricing.costFor(slot.rolls(), tier)));
            }
            lore.add("<gray>Rolls: <white>" + slot.rolls());
            lore.add("<gray>Spent: <green>" + money(slot.spent()));
            Material mat = slot.isEmpty() ? Material.ENDER_PEARL : Material.ENDER_EYE;
            String title = slot.locked() ? "<yellow>Protected Bonus Slot"
                    : slot.isEmpty() ? "<gray>Empty Bonus Slot" : "<green>Active Bonus Slot";
            return PackPresentation.decorateButton(new ItemStack(mat), title, lore, "simple", plugin.texturedMenu());
        }

        private void onSlotClick(InventoryClickEvent event, int index) {
            if (!refresh()) return;
            CardRules.Tier tier = CardRules.Tier.parse(entry.legacyTier());
            int unlocked = Math.min(CardRules.BONUS_SLOT_COUNT, tier.ordinal() + 1);
            if (index >= unlocked) {
                feedback(false, "Tier up this card to unlock bonus slot " + (index + 1) + ".");
                return;
            }
            List<CardRules.BonusSlotData> slots = new ArrayList<>(CardRules.parseBonuses(identity.state().get("bonuses")));
            CardRules.BonusSlotData current = slots.get(index);
            ClickType click = event.getClick();
            if (click == ClickType.RIGHT) {
                if (current.isEmpty()) {
                    feedback(false, "Roll a bonus in this slot before locking it.");
                    return;
                }
                slots.set(index, current.withLocked(!current.locked()));
                saveBonuses(slots, !current.locked() ? "Locked bonus slot " + (index + 1) + "." : "Unlocked bonus slot " + (index + 1) + ".");
            } else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
                if (current.isEmpty()) return;
                if (current.locked()) {
                    feedback(false, "Unlock slot " + (index + 1) + " before clearing it.");
                    return;
                }
                slots.set(index, current.cleared());
                saveBonuses(slots, "Cleared bonus slot " + (index + 1) + ".");
            } else {
                // Refused before any money moves for every reason the roll would change nothing.
                CardRules.RollPricing pricing = plugin.cardRules().rollPricing;
                if (!pricing.enabled()) {
                    feedback(false, "Rolling a bonus slot is unavailable.");
                    return;
                }
                if (current.locked()) {
                    feedback(false, "Unlock slot " + (index + 1) + " before rolling it.");
                    return;
                }
                CardRules.BonusSlotData rolled = plugin.cardRules().rollBonusSlot(tier, slots, index, ThreadLocalRandom.current());
                if (rolled == null) {
                    feedback(false, "No different bonus is available for this tier.");
                    return;
                }
                double cost = pricing.costFor(current.rolls(), tier);
                var economy = plugin.core().economy();
                if (cost > 0) {
                    if (!economy.available()) {
                        feedback(false, "The economy is unavailable, so a bonus slot cannot be charged for.");
                        return;
                    }
                    if (!economy.has(player.getUniqueId(), cost) || !economy.withdraw(player.getUniqueId(), cost)) {
                        feedback(false, "You need " + money(cost) + " to roll that bonus slot.");
                        return;
                    }
                }
                slots.set(index, rolled.paid(cost));
                CardRules.BonusDef def = CardRules.BONUSES.get(rolled.boostId());
                String label = def != null ? def.plainName() + " " + def.formatValue(rolled.strength()) : rolled.boostId();
                if (!saveBonuses(slots, "Rolled " + label + " (" + rolled.strength() + "%) in slot " + (index + 1)
                        + (cost > 0 ? " for " + money(cost) : "") + "!") && cost > 0) {
                    // Charged but the card could not be written: give the money back.
                    economy.deposit(player.getUniqueId(), cost);
                }
            }
        }

        private String money(double amount) {
            return plugin.core().economy().format(amount);
        }

        private boolean saveBonuses(List<CardRules.BonusSlotData> slots, String successMessage) {
            ItemStack current = source.read(plugin, player);
            ItemStack updated = plugin.changed(current, Map.of("bonuses", CardRules.encodeBonuses(slots)));
            if (updated == null) {
                feedback(false, "Could not update this card.");
                return false;
            }
            source.write(plugin, player, updated);
            if (source.equipped()) plugin.refreshAttributes(player);
            feedback(true, successMessage);
            render();
            return true;
        }
    }

    private final class MergeScreen extends CardScreen {
        private Integer secondSlot;
        private ItemStack secondSnapshot;

        MergeScreen(Player player, CardSource source, Catalog.Entry entry, ItemIdentity identity) {
            super(player, source, entry, identity, PackPresentation.MENU_MERGE_GLYPH, "Merge Cards");
        }

        @Override
        void render() {
            clear();
            if (!refresh()) return;
            set(11, stack.clone(), null);
            navigation(this::openHomeScreen, this::confirmMerge, "Confirm Merge", Material.ANVIL);
            if (source.equipped()) {
                set(22, icon(Material.BARRIER, "<red>This card is equipped",
                        "<gray>Take it out of /relics to merge it"), null);
                region(SLOT_NEXT, 2, button(Material.BARRIER, "<red>Cannot Merge",
                        "<gray>Take this card out of /relics first"),
                        e -> feedback(false, "Take this card out of /relics first."));
                return;
            }

            ItemStack second = secondSlot == null ? null : player.getInventory().getItem(secondSlot);
            if (second == null || secondSnapshot == null || !second.isSimilar(secondSnapshot)) {
                secondSlot = null;
                secondSnapshot = null;
                second = null;
            }
            set(15, second == null
                    ? cardIcon("<yellow>Select a second card",
                    "<gray>Click another card in your inventory", "",
                    "<gray>Same mob • same tier",
                    "<gray>Both level <white>" + entry.maxLevel(),
                    "<gray>Keeps the higher quality",
                    "<dark_gray>Your cards stay with you until confirmation")
                    : second.clone(), e -> {
                secondSlot = null;
                secondSnapshot = null;
                render();
            });

            Catalog.Entry secondEntry = plugin.entry(second);
            Optional<ItemIdentity> secondId = plugin.identity(second);
            int firstLevel = CardRules.parseIntSafe(identity.state().get("level"), 0);
            int firstQuality = CardRules.parseIntSafe(identity.state().get("quality"), 100);
            int secondLevel = secondId.map(id -> CardRules.parseIntSafe(id.state().get("level"), 0)).orElse(0);
            int secondQuality = secondId.map(id -> CardRules.parseIntSafe(id.state().get("quality"), 100)).orElse(100);

            CardRules.MergeVerdict verdict = plugin.cardRules().checkMerge(
                    entry, firstLevel, firstQuality, secondEntry, secondLevel, secondQuality, plugin.catalog().entries);
            if (verdict.allowed()) {
                Catalog.Entry resultEntry = plugin.catalog().entries.get(verdict.resultId());
                boolean bound = Boolean.parseBoolean(identity.state().get("bound"))
                        || secondId.map(id -> Boolean.parseBoolean(id.state().get("bound"))).orElse(false);
                ItemStack preview = plugin.create(resultEntry, Map.of(
                        "level", "0",
                        "xp", "0",
                        "quality", Integer.toString(verdict.mergedQuality()),
                        "bound", Boolean.toString(bound),
                        "frame", resultEntry.frame()
                ));
                set(22, preview != null ? preview : icon(Material.BARRIER, "<red>Result unavailable"),
                        preview != null ? e -> confirmMerge() : null);
                region(SLOT_NEXT, 2, button(Material.EMERALD_BLOCK, "<green>Confirm Merge",
                        "<gray>Receive a <white>" + CardRules.titleCase(verdict.resultTier().name()) + " card",
                        "<gray>Starting level: <white>0",
                        "<gray>Quality: <white>" + verdict.mergedQuality() + "%",
                        "", "<red>Consumes both input cards",
                        "<yellow>Click here or the preview to forge"), e -> confirmMerge());
            } else {
                set(22, cardIcon("<yellow>Merge Preview",
                        "<gray>" + verdict.message(), "", "<dark_gray>Two cards become one higher tier"), null);
                region(SLOT_NEXT, 2, button(Material.BARRIER, "<red>Cannot Merge",
                        "<gray>" + verdict.message()), e -> feedback(false, verdict.message()));
            }
        }

        @Override
        void onPlayerInventoryClick(InventoryClickEvent event) {
            if (source.equipped() || !(source instanceof InventorySource invSource)) return;
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.SHIFT_LEFT) return;
            if (event.getClickedInventory() != player.getInventory()) return;
            if (event.getSlot() == invSource.slot()) return;
            ItemStack clicked = event.getCurrentItem();
            Catalog.Entry clickedEntry = plugin.entry(clicked);
            if (clickedEntry == null || clickedEntry.legacyCard().isEmpty()) return;
            secondSlot = event.getSlot();
            secondSnapshot = clicked.clone();
            render();
        }

        private void confirmMerge() {
            if (!refresh() || source.equipped() || secondSlot == null) return;
            ItemStack second = player.getInventory().getItem(secondSlot);
            if (second == null || secondSnapshot == null || !second.isSimilar(secondSnapshot)) {
                feedback(false, "The second card changed. Select it again.");
                render();
                return;
            }
            Catalog.Entry secondEntry = plugin.entry(second);
            Optional<ItemIdentity> secondId = plugin.identity(second);
            if (secondEntry == null || secondId.isEmpty()) {
                feedback(false, "The second card is no longer valid.");
                render();
                return;
            }
            int firstLevel = CardRules.parseIntSafe(identity.state().get("level"), 0);
            int firstQuality = CardRules.parseIntSafe(identity.state().get("quality"), 100);
            int secondLevel = CardRules.parseIntSafe(secondId.get().state().get("level"), 0);
            int secondQuality = CardRules.parseIntSafe(secondId.get().state().get("quality"), 100);
            CardRules.MergeVerdict verdict = plugin.cardRules().checkMerge(
                    entry, firstLevel, firstQuality, secondEntry, secondLevel, secondQuality, plugin.catalog().entries);
            if (!verdict.allowed()) {
                feedback(false, verdict.message());
                render();
                return;
            }
            Catalog.Entry resultEntry = plugin.catalog().entries.get(verdict.resultId());
            boolean bound = Boolean.parseBoolean(identity.state().get("bound"))
                    || Boolean.parseBoolean(secondId.get().state().get("bound"));
            ItemStack forged = plugin.create(resultEntry, Map.of(
                    "level", "0",
                    "xp", "0",
                    "quality", Integer.toString(verdict.mergedQuality()),
                    "bound", Boolean.toString(bound),
                    "frame", resultEntry.frame()
            ));
            if (forged == null) {
                feedback(false, "Could not forge the resulting card. Your cards were not consumed.");
                return;
            }
            source.write(plugin, player, null);
            player.getInventory().setItem(secondSlot, null);
            secondSlot = null;
            secondSnapshot = null;
            player.getInventory().addItem(forged).values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
            feedback(true, "Forged a " + CardRules.titleCase(verdict.resultTier().name()) + " "
                    + CardRules.titleCase(resultEntry.legacyCard()) + " card (" + verdict.mergedQuality() + "%)!");
            player.closeInventory();
        }
    }

    private final class TradeScreen extends CardScreen {
        private boolean exchanged;
        private ItemStack unclaimed;

        TradeScreen(Player player, CardSource source, Catalog.Entry entry, ItemIdentity identity) {
            super(player, source, entry, identity, PackPresentation.MENU_TRADE_GLYPH, "Trade In");
        }

        @Override
        void render() {
            clear();
            if (exchanged) {
                if (unclaimed != null) set(13, unclaimed.clone(), e -> collect());
                set(22, icon(Material.NETHERITE_AXE, "<green>Trade Complete",
                        "<gray>Click your reward above to collect",
                        "<dark_gray>Unclaimed rewards are returned on close"), e -> collect());
                navigation(player::closeInventory, this::collect, "Collect Reward", Material.CHEST);
                return;
            }
            if (!refresh()) return;
            set(13, stack.clone(), null);
            navigation(this::openHomeScreen, () -> {
                if (refresh()) new BonusScreen(player, source, entry, identity).open();
            }, "Bonus Boosts", Material.NETHER_STAR);

            boolean bound = Boolean.parseBoolean(identity.state().get("bound"));
            if (source.equipped()) {
                set(22, icon(Material.BARRIER, "<red>This card is equipped",
                        "<gray>Take it out of /relics to trade it in"), null);
            } else if (bound || plugin.tradePayout(entry, identity).isEmpty()) {
                set(22, icon(Material.BARRIER, "<red>Cannot Trade In",
                        "<gray>" + (bound ? "Bound cards cannot be traded in." : "This card has no configured payout.")), null);
            } else {
                int quality = CardRules.parseIntSafe(identity.state().get("quality"), 100);
                CardRules.QualityBand band = plugin.cardRules().bandFor(quality);
                ItemStack reward = plugin.tradePayout(entry, identity).orElseThrow();
                int amount = reward.getAmount();
                String rewardLabel = CuriosPlugin.payoutName(reward);
                set(22, PackPresentation.decorateButton(new ItemStack(Material.NETHERITE_AXE), "<gold>Trade In", List.of(
                        "<gray>Quality Band: <" + band.colour() + ">" + band.label() + "</" + band.colour() + "> <dark_gray>[" + quality + "%]</dark_gray>",
                        "",
                        "<yellow>Collect: <white>" + amount + "x <aqua>" + rewardLabel,
                        "<dark_gray>Click to confirm",
                        "",
                        "<red>This consumes your card"
                ), "copper", plugin.texturedMenu()), e -> confirmTrade());
            }
        }

        private void confirmTrade() {
            if (exchanged || source.equipped() || !refresh()) return;
            ItemStack payout = Boolean.parseBoolean(identity.state().get("bound")) ? null
                    : plugin.tradePayout(entry, identity).orElse(null);
            if (payout == null) {
                feedback(false, "This card cannot be traded in.");
                render();
                return;
            }
            source.write(plugin, player, null);
            unclaimed = payout;
            exchanged = true;
            feedback(true, "Traded in! Click the reward to collect.");
            render();
        }

        private void collect() {
            if (unclaimed == null) return;
            var overflow = player.getInventory().addItem(unclaimed.clone());
            unclaimed = overflow.isEmpty() ? null : overflow.values().iterator().next();
            if (unclaimed == null) {
                feedback(true, "Reward collected!");
                player.closeInventory();
            } else {
                feedback(false, "Make room in your inventory to collect the remaining reward.");
                render();
            }
        }

        @Override
        void returnUnclaimed() {
            if (unclaimed == null) return;
            ItemStack toDeliver = unclaimed;
            unclaimed = null;
            player.getInventory().addItem(toDeliver).values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
        }
    }
}
