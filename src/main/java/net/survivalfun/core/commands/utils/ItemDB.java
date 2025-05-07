package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.LegacyID;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.stream.Collectors;

public class ItemDB implements CommandExecutor {

    private final Lang lang;

    public ItemDB(PluginStart plugin) {
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("core.itemdb")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        // Simplified check - no need for null check
        if (itemInHand.getType() == Material.AIR) {
            player.sendMessage("You must be holding an item in your main hand.");
            return true;
        }

        int amount = itemInHand.getAmount();
        String legacyId = LegacyID.getLegacyIdFromMaterial(itemInHand.getType());

        // Format the output more cleanly
        player.sendMessage("§6Item Information:");
        player.sendMessage("§7Name: §f" + formatMaterialName(itemInHand.getType()));
        player.sendMessage("§7Amount: §f" + amount);
        player.sendMessage("§7Legacy ID: §f" + (legacyId != null ? legacyId : "Unable to find legacy ID."));
        player.sendMessage("§7Modern ID: §f" + itemInHand.getType().getKey());

        return true;
    }

    private String formatMaterialName(Material material) {
        return Arrays.stream(material.name().split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
}