package net.survivalfun.core.commands;

import net.survivalfun.core.utils.LegacyIDUtils;
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

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        // Simplified check - no need for null check
        if (itemInHand.getType() == Material.AIR) {
            player.sendMessage("You must be holding an item in your main hand.");
            return true;
        }

        int amount = itemInHand.getAmount();
        String legacyId = LegacyIDUtils.getLegacyIdFromMaterial(itemInHand.getType());

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