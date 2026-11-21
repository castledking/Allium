package codes.castled.allium.recipe;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /recipe}'s read-only crafting-table window. Nothing in it can be taken, placed or moved,
 * and the player's own inventory is locked while it is open. Ingredients that accept several items
 * cycle each second. Left-click the result for the next recipe and right-click it for the previous
 * one; click an ingredient to see how it is made; shift-click to go back. Server thread.
 */
public final class RecipeViewer implements Listener {

    private static final long CYCLE_MILLIS = 1000L;

    private final PluginStart plugin;
    private final Set<Menu> open = ConcurrentHashMap.newKeySet();
    @Nullable
    private TaskHandle ticker;
    private long frame;

    /** One recipe list and where in it the viewer is. */
    private record Page(ItemStack item, List<RecipeLayout> layouts, int index) {
        Page at(int next) {
            return new Page(item, layouts, Math.floorMod(next, layouts.size()));
        }

        RecipeLayout layout() {
            return layouts.get(index);
        }
    }

    private static final class Menu implements InventoryHolder {
        private Inventory inventory;
        private final Player viewer;
        private Page page;
        private final Deque<Page> back = new ArrayDeque<>();

        Menu(Player viewer, Page page) {
            this.viewer = viewer;
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public RecipeViewer(PluginStart plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void close() {
        HandlerList.unregisterAll(this);
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Menu menu : List.copyOf(open)) {
            if (menu.viewer.getOpenInventory().getTopInventory() == menu.inventory) menu.viewer.closeInventory();
        }
        open.clear();
    }

    /** The recipes that make {@code item}, in viewing order. */
    public List<RecipeLayout> recipes(ItemStack item) {
        return RecipeLayout.forItem(item, plugin.getServer().getRecipesFor(item));
    }

    /**
     * What a held item is looked up as: a plain item by its type alone (so damage or a name does not
     * matter), a custom item as itself.
     */
    public ItemStack lookup(ItemStack held) {
        ItemStack plain = new ItemStack(held.getType());
        boolean custom = plugin.getItemService() != null && plugin.getItemService().identify(held)
                .map(ref -> !ref.appearance().isVanilla() || ref.behaviorId().isPresent()).orElse(false);
        if (!custom) return plain;
        ItemStack copy = held.clone();
        copy.setAmount(1);
        ItemMeta meta = copy.getItemMeta();
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            damageable.setDamage(0);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    /** Opens the recipes of {@code item} at {@code index} (0-based); false when nothing makes it. */
    public boolean open(Player player, ItemStack item, int index) {
        List<RecipeLayout> layouts = recipes(item);
        if (layouts.isEmpty()) return false;
        Menu menu = new Menu(player, new Page(item, layouts, Math.max(0, Math.min(index, layouts.size() - 1))));
        menu.inventory = plugin.getServer().createInventory(menu, InventoryType.WORKBENCH, title(menu.page));
        render(menu);
        InventoryView view = player.openInventory(menu.inventory);
        if (view == null || view.getTopInventory() != menu.inventory) return true;
        open.add(menu);
        if (ticker == null) ticker = plugin.getScheduler().repeatOnServer(this::tick, CYCLE_MILLIS, CYCLE_MILLIS);
        return true;
    }

    private boolean tick() {
        frame++;
        for (Menu menu : List.copyOf(open)) {
            if (!menu.viewer.isOnline() || menu.viewer.getOpenInventory().getTopInventory() != menu.inventory) {
                open.remove(menu);
                continue;
            }
            if (menu.page.layout().cycles()) render(menu);
        }
        if (open.isEmpty()) {
            ticker = null;
            return false;
        }
        return true;
    }

    private void render(Menu menu) {
        RecipeLayout layout = menu.page.layout();
        ItemStack[] contents = new ItemStack[10];
        contents[RecipeLayout.RESULT] = layout.result().clone();
        for (int slot = 1; slot <= 9; slot++) {
            ItemStack shown = layout.shown(slot, frame);
            if (shown != null) contents[slot] = shown.clone();
        }
        if (layout.station() > 0 && layout.kind().station() != null) {
            contents[layout.station()] = station(layout);
        }
        if (layout.kind().fuel()) contents[RecipeLayout.BOTTOM] = described(new ItemStack(Material.COAL), "§7Or any other fuel");
        menu.inventory.setContents(contents);
    }

    private static ItemStack station(RecipeLayout layout) {
        List<String> lore = new ArrayList<>();
        lore.add("§7" + layout.kind().label());
        if (layout.cookingTicks() > 0) {
            double seconds = layout.cookingTicks() / 20.0D;
            lore.add("§7Takes §f" + (seconds == Math.rint(seconds) ? Long.toString((long) seconds) : Double.toString(seconds)) + "s");
        }
        if (layout.experience() > 0F) lore.add("§7Gives §f" + layout.experience() + " §7XP");
        return described(new ItemStack(layout.kind().station()), lore.toArray(String[]::new));
    }

    /** An item keeping its own (translated) name, with lore. */
    private static ItemStack described(ItemStack stack, String... lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(lore));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String title(Page page) {
        String title = page.layouts().size() > 1 ? "Recipe " + (page.index() + 1) + "/" + page.layouts().size() + ": " : "Recipe: ";
        String name = UtilitySupport.itemName(page.item());
        String full = title + name;
        return full.length() <= 48 ? full : full.substring(0, 48);
    }

    private void show(Menu menu, Page page) {
        menu.page = page;
        render(menu);
        InventoryView view = menu.viewer.getOpenInventory();
        if (view.getTopInventory() == menu.inventory) view.setTitle(title(page));
    }

    // -- Locked window ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw > 9 || event.getClick() == ClickType.DOUBLE_CLICK) return;
        boolean shift = event.getClick().isShiftClick();
        boolean right = event.getClick().isRightClick();
        ItemStack clicked = event.getView().getTopInventory().getItem(raw);
        // Changing the window inside its own click event is unsafe; do it on the next tick.
        plugin.getScheduler().runOnServer(() -> {
            if (menu.viewer.getOpenInventory().getTopInventory() != menu.inventory) return;
            if (shift) {
                if (!menu.back.isEmpty()) show(menu, menu.back.pop());
                return;
            }
            if (raw == RecipeLayout.RESULT) {
                if (menu.page.layouts().size() > 1) show(menu, menu.page.at(menu.page.index() + (right ? -1 : 1)));
                return;
            }
            RecipeLayout layout = menu.page.layout();
            if (clicked == null || clicked.getType().isAir() || raw == layout.station()
                    || layout.kind().fuel() && raw == RecipeLayout.BOTTOM) {
                return;
            }
            ItemStack ingredient = lookup(clicked);
            List<RecipeLayout> layouts = recipes(ingredient);
            if (layouts.isEmpty()) return;
            menu.back.push(menu.page);
            if (menu.back.size() > 32) menu.back.removeLast();
            show(menu, new Page(ingredient, layouts, 0));
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            open.remove(menu);
            // None of the illustrated items belong to the viewer.
            menu.inventory.clear();
        }
    }
}
