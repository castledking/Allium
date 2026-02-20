package net.survivalfun.core.commands;

import net.kyori.adventure.text.Component;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class TrashCommand implements CommandExecutor, TabCompleter, Listener {

    private static final int INVENTORY_SIZE = 54;
    private static final Component INVENTORY_TITLE = Component.text("Disposal");

    private final Lang lang;
    private final Set<UUID> processedInventories = new HashSet<>();

    public TrashCommand(PluginStart plugin) {
        this.lang = plugin.getLangManager();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        if (!player.hasPermission("allium.dispose")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label.toLowerCase(Locale.ROOT));
            return true;
        }

        openTrashInventory(player);
        return true;
    }

    private void openTrashInventory(Player player) {
        SchedulerAdapter.runAtEntity(player, () -> {
            TrashHolder holder = new TrashHolder(player.getUniqueId());
            Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, INVENTORY_TITLE);
            holder.setInventory(inventory);
            player.openInventory(inventory);
            player.sendMessage(Text.colorize("&aOpened disposal inventory. Items inside will be destroyed when closed."));
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof TrashHolder trashHolder)) {
            return;
        }

        UUID inventoryId = trashHolder.getOwner();

        // Check if we've already processed this inventory to prevent duplicates
        if (processedInventories.contains(inventoryId)) {
            return;
        }

        // Check if inventory is not empty before processing
        if (inventory.isEmpty()) {
            return;
        }

        // Mark this inventory as processed
        processedInventories.add(inventoryId);

        inventory.clear();
        if (event.getPlayer() instanceof Player player && player.getUniqueId().equals(inventoryId)) {
            player.sendMessage(Text.colorize("&cItems in the disposal were destroyed."));
        }

        // Clean up after a delay to allow for potential re-opening
        SchedulerAdapter.runDelayed(() -> processedInventories.remove(inventoryId), 20L); // 1 second delay
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }

    private static final class TrashHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private TrashHolder(UUID owner) {
            this.owner = owner;
        }

        private UUID getOwner() {
            return owner;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
