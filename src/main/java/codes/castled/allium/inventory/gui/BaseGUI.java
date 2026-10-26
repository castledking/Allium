package codes.castled.allium.inventory.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public abstract class BaseGUI implements InventoryHolder {
    protected final Map<Integer, Consumer<InventoryClickEvent>> clickHandlers = new HashMap<>();
    protected final Player player;
    protected Inventory inventory;
    protected final String title;
    protected final int size;
    protected final PluginStart plugin;

    public BaseGUI(Player player, String title, int rows, PluginStart plugin) {
        this.player = player;
        this.title = title;
        this.size = rows * 9; 
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public abstract void initialize();

    public void open() {
    // Initialize on the main thread
    SchedulerAdapter.runAtEntity(player, () -> {
        initialize();
        player.openInventory(inventory);
    });
    }

    public void setItem(int slot, ItemStack item, Consumer<InventoryClickEvent> clickHandler) {
        if (slot >= 0 && slot < size) {
            // Schedule inventory updates on the main thread
            SchedulerAdapter.runAtEntity(player, () -> {
                inventory.setItem(slot, item);
                if (clickHandler != null) {
                    clickHandlers.put(slot, clickHandler);
                }
            });
        }
    }

    public void handleClick(InventoryClickEvent event) {
        if (shouldCancelClick(event)) {
            event.setCancelled(true);
        }
        int slot = event.getRawSlot();
        if (clickHandlers.containsKey(slot)) {
            // Run click handler on the main thread
            SchedulerAdapter.runAtEntity(player, () -> 
                clickHandlers.get(slot).accept(event)
            );
        }
    }

    /**
     * Whether vanilla's click response should be refused at this slot. Every
     * panel cancels, so a staff chat GUI never has its icons lifted; one that
     * wants to hand slots to the player instead — an editor trading through a
     * snapshot panel — returns false for them. Cancelling from this method
     * keeps a button's own handler running, so the return value never hides a
     * slot the clickHandlers map owns.
     */
    protected boolean shouldCancelClick(InventoryClickEvent event) {
        return true;
    }

    /**
     * A drag down the raw slots of this panel. The stock answer is nothing:
     * the families here have lived without a drag rule this long, and only the
     * GUIs that need the strictness off — a panel whose state becomes a
     * restore — override it, since a drag can scatter items past exactly the
     * slots a click handler or restore reads.
     */
    public void handleDrag(InventoryDragEvent event) {}

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}