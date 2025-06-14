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
        if (sender instanceof Player player) {
            if (!player.hasPermission("core.itemdb")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                return true;
            }

            ItemStack itemInHand = player.getInventory().getItemInMainHand();

            if (itemInHand.getType() == Material.AIR) {
                Text.sendErrorMessage(player, "hold-item", lang, "{modify}", "inspect");
                return true;
            }

            int amount = itemInHand.getAmount();
            String legacyId = LegacyID.getLegacyIdFromMaterial(itemInHand.getType());

            player.sendMessage("§6Item Information:");
            player.sendMessage("§7Name: §f" + formatMaterialName(itemInHand.getType()));
            player.sendMessage("§7Amount: §f" + amount);
            player.sendMessage("§7Legacy ID: §f" + (legacyId != null ? legacyId : "Unable to find legacy ID."));
            player.sendMessage("§7Modern ID: §f" + itemInHand.getType().getKey());

            return true;
        } else {
            // Console or other sender: require player name as first argument
            if (args.length == 0) {
                sender.sendMessage("Usage: /itemdb <player>");
                return true;
            }
            Player target = org.bukkit.Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", args[0]);
                return true;
            }
            ItemStack itemInHand = target.getInventory().getItemInMainHand();
            if (itemInHand.getType() == Material.AIR) {
                Text.sendErrorMessage(sender, "hold-item", lang, "{modify}", "inspect");
                return true;
            }
            int amount = itemInHand.getAmount();
            String legacyId = LegacyID.getLegacyIdFromMaterial(itemInHand.getType());

            sender.sendMessage("§6Item Information for " + target.getName() + ":");
            sender.sendMessage("§7Name: §f" + formatMaterialName(itemInHand.getType()));
            sender.sendMessage("§7Amount: §f" + amount);
            sender.sendMessage("§7Legacy ID: §f" + (legacyId != null ? legacyId : "Unable to find legacy ID."));
            sender.sendMessage("§7Modern ID: §f" + itemInHand.getType().getKey());
            return true;
        }
    }

    private String formatMaterialName(Material material) {
        return Arrays.stream(material.name().split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
}