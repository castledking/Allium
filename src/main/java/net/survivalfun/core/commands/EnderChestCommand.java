package net.survivalfun.core.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import net.survivalfun.core.inventory.OfflineInventoryData;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.util.SchedulerAdapter;

public class EnderChestCommand implements CommandExecutor, TabCompleter, Listener {

    private final PluginStart plugin;
    private final Lang lang;

    public EnderChestCommand(PluginStart plugin) {
        this.plugin = plugin;
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
            openOnlineEnderChest(viewer, viewer, true);
            return true;
        }

        if (!viewer.hasPermission("allium.enderchest.other")) {
            Text.sendErrorMessage(viewer, "no-permission", lang, "{cmd}", label.toLowerCase(Locale.ROOT));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target != null) {
            openOnlineEnderChest(viewer, target, false);
            return true;
        }

        OfflinePlayer offlineTarget = resolveOfflineTarget(args[0]);
        if (offlineTarget == null) {
            Text.sendErrorMessage(viewer, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        plugin.getOfflineInventoryManager().getEffectiveInventory(offlineTarget.getUniqueId())
            .whenComplete((data, error) -> SchedulerAdapter.runAtEntity(viewer, () -> {
                if (error != null) {
                    viewer.sendMessage(Text.colorize("&cFailed to load offline ender chest: " + error.getMessage()));
                    return;
                }

                String targetName = offlineTarget.getName() != null ? offlineTarget.getName() : args[0];
                ItemStack[] enderChest = data == null || data.enderChest() == null
                    ? new ItemStack[27]
                    : OfflineInventoryData.resize(data.enderChest(), 27);
                openOfflineEnderChest(viewer, offlineTarget.getUniqueId(), targetName, enderChest);
            }));
        return true;
    }

    private void openOnlineEnderChest(Player viewer, Player target, boolean self) {
        SchedulerAdapter.runAtEntity(viewer, () -> {
            viewer.openInventory(target.getEnderChest());
            if (self) {
                viewer.sendMessage(Text.colorize("&aOpened your ender chest."));
            } else {
                viewer.sendMessage(Text.colorize("&aOpened &e" + target.getName() + "&a's ender chest."));
            }
        });
    }

    private void openOfflineEnderChest(Player viewer, UUID targetId, String targetName, ItemStack[] contents) {
        SchedulerAdapter.runAtEntity(viewer, () -> {
            String title = "§8Ender Chest: " + targetName + " §7(offline)";
            ItemStack[] initialContents = OfflineInventoryData.resize(contents, 27);
            Inventory editableEnderChest = Bukkit.createInventory(
                new EnderChestHolder(targetId, targetName, initialContents),
                initialContents.length,
                title
            );

            for (int i = 0; i < initialContents.length; i++) {
                editableEnderChest.setItem(i, cloneItem(initialContents[i]));
            }

            viewer.openInventory(editableEnderChest);
            viewer.sendMessage(Text.colorize("&aOpened &e" + targetName + "&a's ender chest &7(offline)."));
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!(event.getInventory().getHolder() instanceof EnderChestHolder holder)) {
            return;
        }

        ItemStack[] updatedContents = snapshotContents(event.getInventory());
        if (sameContents(holder.initialContents(), updatedContents)) {
            return;
        }

        Player onlineTarget = Bukkit.getPlayer(holder.targetId());
        if (onlineTarget != null) {
            SchedulerAdapter.runAtEntity(onlineTarget, () -> {
                onlineTarget.getEnderChest().setContents(OfflineInventoryData.resize(updatedContents, onlineTarget.getEnderChest().getSize()));
                onlineTarget.updateInventory();
                plugin.getOfflineInventoryManager().savePlayerState(onlineTarget);
            });
            player.sendMessage(Text.colorize("&aEnder chest changes saved for &e" + holder.targetName() + "&a."));
            return;
        }

        plugin.getOfflineInventoryManager().getEffectiveInventory(holder.targetId())
            .whenComplete((currentState, error) -> SchedulerAdapter.runAtEntity(player, () -> {
                if (error != null || currentState == null) {
                    player.sendMessage(Text.colorize("&cFailed to save offline ender chest changes."));
                    return;
                }

                OfflineInventoryData override = new OfflineInventoryData(
                    null,
                    null,
                    null,
                    updatedContents,
                    null
                );
                OfflineInventoryData merged = currentState.merge(override);
                plugin.getOfflineInventoryManager().queueOfflineInventoryEdit(
                    holder.targetId(),
                    holder.targetName(),
                    merged,
                    override
                );
                player.sendMessage(Text.colorize("&aQueued ender chest changes for &e" + holder.targetName() + "&a."));
            }));
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

    private @Nullable OfflinePlayer resolveOfflineTarget(String targetName) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(targetName);
        if (cached != null) {
            return cached;
        }

        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(targetName)) {
                return offlinePlayer;
            }
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetName);
        return offlinePlayer.hasPlayedBefore() ? offlinePlayer : null;
    }

    private static ItemStack[] snapshotContents(Inventory inventory) {
        ItemStack[] contents = new ItemStack[inventory.getSize()];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = cloneItem(inventory.getItem(i));
        }
        return contents;
    }

    private static boolean sameContents(ItemStack[] first, ItemStack[] second) {
        return Arrays.deepEquals(normalize(first), normalize(second));
    }

    private static ItemStack[] normalize(ItemStack[] contents) {
        ItemStack[] normalized = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            normalized[i] = cloneItem(contents[i]);
        }
        return normalized;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private record EnderChestHolder(UUID targetId, String targetName, ItemStack[] initialContents) implements org.bukkit.inventory.InventoryHolder {
        @Override
        public @Nullable Inventory getInventory() {
            return null;
        }
    }
}
