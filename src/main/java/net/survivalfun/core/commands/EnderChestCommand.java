package net.survivalfun.core.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.util.SchedulerAdapter;

public class EnderChestCommand implements CommandExecutor, TabCompleter, Listener {

    private final Lang lang;
    private final Map<UUID, Inventory> openedEnderChests = new HashMap<>();

    public EnderChestCommand(PluginStart plugin) {
        this.lang = plugin.getLangManager();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player viewer)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        if (args.length == 0) {
            if (!viewer.hasPermission("allium.enderchest")) {
                Text.sendErrorMessage(viewer, "no-permission", lang, "{cmd}", label.toLowerCase(Locale.ROOT));
                return true;
            }
            openEnderChest(viewer, viewer, true);
            return true;
        }

        if (!viewer.hasPermission("allium.enderchest.other")) {
            Text.sendErrorMessage(viewer, "no-permission", lang, "{cmd}", label.toLowerCase(Locale.ROOT));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);

        if (target == null) {
            Text.sendErrorMessage(viewer, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        if (!target.hasPlayedBefore()) {
            Text.sendErrorMessage(viewer, "player-not-online", lang, "{name}", args[0]);
            return true;
        }

        openEnderChest(viewer, target, false);
        return true;
    }

    private void openEnderChest(Player viewer, Player target, boolean self) {
        SchedulerAdapter.runAtEntity(viewer, () -> {
            // Create a copy of the enderchest instead of using direct reference
            Inventory originalEnderChest = target.getEnderChest();
            Inventory editableEnderChest = Bukkit.createInventory(viewer, originalEnderChest.getSize(), "§8Ender Chest");

            // Copy all items from the original enderchest
            for (int i = 0; i < originalEnderChest.getSize(); i++) {
                ItemStack item = originalEnderChest.getItem(i);
                if (item != null) {
                    editableEnderChest.setItem(i, item.clone());
                }
            }

            // Track this opened inventory for syncing back later
            openedEnderChests.put(viewer.getUniqueId(), editableEnderChest);

            viewer.openInventory(editableEnderChest);
            if (self) {
                viewer.sendMessage(Text.colorize("&aOpened your ender chest."));
            } else {
                viewer.sendMessage(Text.colorize("&aOpened &e" + target.getName() + "&a's ender chest."));
            }
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Inventory closedInventory = openedEnderChests.get(playerId);

        if (closedInventory != null && event.getInventory().equals(closedInventory)) {
            // Only sync back if this is the player's own enderchest (not viewing others)
            if (player.hasPermission("allium.enderchest")) {
                // Sync changes back to the player's actual enderchest
                Inventory actualEnderChest = player.getEnderChest();

                // Clear the actual enderchest
                actualEnderChest.clear();

                // Copy all items back
                for (int i = 0; i < closedInventory.getSize(); i++) {
                    ItemStack item = closedInventory.getItem(i);
                    if (item != null) {
                        actualEnderChest.setItem(i, item.clone());
                    }
                }

                player.sendMessage(Text.colorize("&aEnder chest changes saved."));
            }

            // Clean up tracking
            openedEnderChests.remove(playerId);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        if (args.length == 1 && player.hasPermission("allium.enderchest.other")) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        }

        return Collections.emptyList();
    }
}
