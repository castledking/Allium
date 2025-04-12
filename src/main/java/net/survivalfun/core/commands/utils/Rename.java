package net.survivalfun.core.commands.utils;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import net.survivalfun.core.utils.ColorUtils;

public class Rename implements CommandExecutor {

    private final JavaPlugin plugin;

    public Rename(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (!player.hasPermission("core.rename")) {
            player.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§cUsage: /rename <name>");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        player.updateInventory();


        if (item.getType().isAir()) {
            player.sendMessage("You must be holding an item to rename it.");
            return true;
        }


        String name = String.join(" ", args);

        // Handle hex colors if the player has the color permission
        if (player.hasPermission("core.rename.color")) {
            name = ColorUtils.colorizeHex(name);
        } else {
            name = ChatColor.stripColor(name);
        }
        // Translate legacy color codes
        name = ChatColor.translateAlternateColorCodes('&', name);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
            player.sendMessage("§aItem renamed to: §f" + name);
        } else {
            player.sendMessage("§cCould not rename the item.");
        }

        return true;
    }
}
