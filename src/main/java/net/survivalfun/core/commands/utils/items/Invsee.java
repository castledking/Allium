package net.survivalfun.core.commands.utils.items;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.FixedMetadataValue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class Invsee implements CommandExecutor, Listener {

    private final PluginStart plugin;
    private final Lang lang;

    public Invsee(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        Player player = (Player) sender;
        
        if (args.length < 1) {
            player.sendMessage("Usage: /invsee <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        String title = lang.get("invsee.inventory-title").replace("{player}", target.getName());
        Component titleComponent = LegacyComponentSerializer.legacySection().deserialize(Text.parseColors(title));
        Inventory inventory = Bukkit.createInventory(null, 54, titleComponent);
        PlayerInventory targetInventory = target.getInventory();

        // Copy armor
        inventory.setItem(5, targetInventory.getHelmet());
        inventory.setItem(6, targetInventory.getChestplate());
        inventory.setItem(7, targetInventory.getLeggings());
        inventory.setItem(8, targetInventory.getBoots());

        // Copy main inventory
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, targetInventory.getItem(i));
        }

        // Copy offhand
        inventory.setItem(44, targetInventory.getItemInOffHand());

        // Store target UUID in world metadata
        player.getWorld().setMetadata("invsee_target", new FixedMetadataValue(plugin, target.getUniqueId().toString()));
        
        player.openInventory(inventory);
        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = lang.get("invsee.inventory-title").replace("{player}", "");
        if (event.getView().getTitle().startsWith(title)) {
            Player viewer = (Player) event.getWhoClicked();
            String targetUUID = event.getInventory().getLocation().getWorld().getMetadata("invsee_target").get(0).asString();
            Player target = Bukkit.getPlayer(UUID.fromString(targetUUID));
            
            if (target == null) {
                viewer.closeInventory();
                return;
            }

            event.setCancelled(true);
            
            // Handle inventory swaps
            if (event.getClick().isShiftClick()) {
                // Shift click swaps
                ItemStack clickedItem = event.getCurrentItem();
                ItemStack cursorItem = event.getCursor();
                
                // Use non-deprecated method
                event.getInventory().setItem(event.getSlot(), cursorItem);
                viewer.setItemOnCursor(clickedItem);
            } else if (event.getClick().isLeftClick() || event.getClick().isRightClick()) {
                // Regular clicks
                ItemStack cursorItem = event.getCursor();
                ItemStack clickedItem = event.getCurrentItem();
                
                // Use non-deprecated method
                event.getInventory().setItem(event.getSlot(), cursorItem);
                viewer.setItemOnCursor(clickedItem);
            }
            
            viewer.updateInventory();
            target.updateInventory();
        }
    }
}