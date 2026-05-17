package codes.castled.allium.listeners.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;

import codes.castled.allium.inventory.gui.BaseGUI;

public class GUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof BaseGUI) {
            ((BaseGUI) holder).handleClick(event);
        }
    }
}
