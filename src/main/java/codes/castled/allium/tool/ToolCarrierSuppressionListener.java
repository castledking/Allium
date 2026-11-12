package codes.castled.allium.tool;

import codes.castled.allium.item.ItemService;
import org.bukkit.Material;
import org.bukkit.block.BlastFurnace;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Furnace;
import org.bukkit.block.Smoker;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Keeps Allium tools (their carriers are a phantom membrane, a golden hoe, a netherite axe) from
 * being used up as a material: crafting and the crafter, furnace, blast furnace and smoker smelting
 * and fuel, brewing, the anvil's material (second) slot, smithing, grindstones, villager trades, and
 * hopper or other automated transfers into those stations.
 *
 * <p>Only Allium tools are blocked: stacks carrying an Allium behavior, or a pre-recode tool ID.
 * Plain items and other plugins' custom items are unaffected. A tool may still be repaired, renamed
 * or combined with a book in the anvil's first slot, and enchanted on an enchanting table.
 */
public final class ToolCarrierSuppressionListener implements Listener {

    private static final NamespacedKey LEGACY_ITEM_ID = new NamespacedKey("allium", "custom_item_id");

    private final Plugin plugin;
    private final ItemService items;

    public ToolCarrierSuppressionListener(Plugin plugin, ItemService items) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.items = Objects.requireNonNull(items, "items");
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerCrafterHookIfAvailable();
    }

    public void disable() {
        HandlerList.unregisterAll(this);
    }

    public boolean isSuppressedTool(@Nullable ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING)) {
            return true; // a tool, signed or not: a forged one must not be consumed either
        }
        String legacy = pdc.get(LEGACY_ITEM_ID, PersistentDataType.STRING);
        return legacy != null && isLegacyToolId(legacy);
    }

    /** Pre-recode tool IDs (allium:custom_item_id) that import into a tool on first use. */
    static boolean isLegacyToolId(String id) {
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("tree_axe") || lower.startsWith("phantom_obliterator") || lower.startsWith("mob_disarmer");
    }

    // -- Crafting ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        for (ItemStack matrixItem : event.getInventory().getMatrix()) {
            if (isSuppressedTool(matrixItem)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        for (ItemStack matrixItem : event.getInventory().getMatrix()) {
            if (isSuppressedTool(matrixItem)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // -- Smelting & Fuel ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        if (isSuppressedTool(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (isSuppressedTool(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    // -- Brewing -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (isSuppressedTool(event.getContents().getIngredient())) {
            event.setCancelled(true);
        }
    }

    // -- Anvil -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        // The first slot is the item being worked on: a tool may be repaired, renamed or given a
        // book's enchantments there. Only the second slot is used up.
        AnvilInventory inv = event.getInventory();
        if (isSuppressedTool(inv.getItem(1))) {
            event.setResult(null);
        }
    }

    // -- Smithing ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        for (ItemStack item : event.getInventory().getContents()) {
            if (isSuppressedTool(item)) {
                event.setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSmithItem(SmithItemEvent event) {
        for (ItemStack item : event.getInventory().getContents()) {
            if (isSuppressedTool(item)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // -- GUI clicks & Station interactions ---------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null) {
            return;
        }
        InventoryType type = top.getType();
        int rawSlot = event.getRawSlot();
        boolean clickInTop = rawSlot >= 0 && rawSlot < top.getSize();

        // 1. Grindstone
        if (type == InventoryType.GRINDSTONE) {
            if (clickInTop) {
                if (isSuppressedTool(event.getCursor())) {
                    event.setCancelled(true);
                    return;
                }
                if (rawSlot == 2 && (isSuppressedTool(top.getItem(0)) || isSuppressedTool(top.getItem(1)))) {
                    event.setCancelled(true);
                    return;
                }
            } else if (event.isShiftClick() && isSuppressedTool(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }

        // 2. Villager Trading (Merchant)
        if (type == InventoryType.MERCHANT) {
            if (clickInTop) {
                if (rawSlot == 0 || rawSlot == 1) {
                    if (isSuppressedTool(event.getCursor())) {
                        event.setCancelled(true);
                        return;
                    }
                }
                if (rawSlot == 2 && (isSuppressedTool(top.getItem(0)) || isSuppressedTool(top.getItem(1)))) {
                    event.setCancelled(true);
                    return;
                }
            } else if (event.isShiftClick() && isSuppressedTool(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }

        // 3. Brewing Stand
        if (type == InventoryType.BREWING) {
            if (clickInTop) {
                if (isSuppressedTool(event.getCursor())) {
                    event.setCancelled(true);
                    return;
                }
            } else if (event.isShiftClick() && isSuppressedTool(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }

        // 4. Furnaces (Furnace, Blast Furnace, Smoker)
        if (type == InventoryType.FURNACE || type == InventoryType.BLAST_FURNACE || type == InventoryType.SMOKER) {
            if (clickInTop) {
                if (rawSlot == 0 || rawSlot == 1) { // 0 = ingredient, 1 = fuel
                    if (isSuppressedTool(event.getCursor())) {
                        event.setCancelled(true);
                        return;
                    }
                }
            } else if (event.isShiftClick() && isSuppressedTool(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }

        // 5. Crafter (1.21+)
        if ("CRAFTER".equals(type.name())) {
            if (clickInTop && isSuppressedTool(event.getCursor())) {
                event.setCancelled(true);
                return;
            }
            if (!clickInTop && event.isShiftClick() && isSuppressedTool(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isSuppressedTool(event.getOldCursor())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top == null) {
            return;
        }
        InventoryType type = top.getType();
        if (type == InventoryType.BREWING
                || type == InventoryType.FURNACE
                || type == InventoryType.BLAST_FURNACE
                || type == InventoryType.SMOKER
                || type == InventoryType.GRINDSTONE
                || type == InventoryType.MERCHANT
                || type == InventoryType.SMITHING
                || "CRAFTER".equals(type.name())) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    // -- Hopper & Automation Transfers -------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!isSuppressedTool(event.getItem())) {
            return;
        }
        if (isRestrictedDestination(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    public boolean isRestrictedDestination(@Nullable Inventory dest) {
        if (dest == null) {
            return false;
        }
        InventoryType type = dest.getType();
        if (type == InventoryType.BREWING
                || type == InventoryType.FURNACE
                || type == InventoryType.BLAST_FURNACE
                || type == InventoryType.SMOKER
                || type == InventoryType.ANVIL
                || type == InventoryType.SMITHING
                || type == InventoryType.GRINDSTONE
                || type == InventoryType.MERCHANT
                || type == InventoryType.WORKBENCH
                || type == InventoryType.CRAFTING) {
            return true;
        }
        if ("CRAFTER".equals(type.name())) {
            return true;
        }
        InventoryHolder holder = dest.getHolder();
        if (holder instanceof BrewingStand
                || holder instanceof Furnace
                || holder instanceof BlastFurnace
                || holder instanceof Smoker) {
            return true;
        }
        if (holder != null && holder.getClass().getSimpleName().contains("Crafter")) {
            return true;
        }
        return false;
    }

    private void registerCrafterHookIfAvailable() {
        try {
            Class<?> crafterClass = Class.forName("org.bukkit.event.block.CrafterCraftEvent");
            if (Event.class.isAssignableFrom(crafterClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends Event> eventClass = (Class<? extends Event>) crafterClass;
                plugin.getServer().getPluginManager().registerEvent(
                        eventClass,
                        this,
                        EventPriority.HIGHEST,
                        (l, event) -> handleCrafterCraft(event),
                        plugin
                );
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Not on 1.21+
        }
    }

    /** CrafterCraftEvent (1.21+) carries the crafter block, not its grid: read the grid from the block. */
    private void handleCrafterCraft(Event event) {
        if (!(event instanceof Cancellable cancellable) || !(event instanceof BlockEvent blockEvent)) {
            return;
        }
        if (!(blockEvent.getBlock().getState() instanceof InventoryHolder holder)) {
            return;
        }
        for (ItemStack item : holder.getInventory().getContents()) {
            if (isSuppressedTool(item)) {
                cancellable.setCancelled(true);
                return;
            }
        }
    }
}
