package net.survivalfun.core.inventory.gui;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.util.SchedulerAdapter;


import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

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
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (clickHandlers.containsKey(slot)) {
            // Run click handler on the main thread
            SchedulerAdapter.runAtEntity(player, () -> 
                clickHandlers.get(slot).accept(event)
            );
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}