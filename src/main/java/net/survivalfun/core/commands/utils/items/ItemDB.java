package net.survivalfun.core.commands.utils.items;

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
            if (!player.hasPermission("allium.itemdb")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
                return true;
            }

            ItemStack itemInHand = player.getInventory().getItemInMainHand();

            if (itemInHand.getType() == Material.AIR) {
                Text.sendErrorMessage(player, "hold-item", lang, "{modify}", "inspect");
                return true;
            }

            sendItemInfo(player, itemInHand, null);
            return true;
        } else {
            // Console or other sender: require player name as first argument
            if (args.length == 0) {
                sender.sendMessage(lang.get("command-usage")
                .replace("{cmd}", label)
                .replace("{args}", "<player>"));
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
            
            sendItemInfo(sender, itemInHand, target.getName());
            return true;
        }
    }

    private void sendItemInfo(CommandSender sender, ItemStack item, String targetPlayerName) {
        int amount = item.getAmount();
        String legacyId = LegacyID.getLegacyIdFromMaterial(item.getType());
        String formattedName = formatMaterialName(item.getType());
        String modernId = item.getType().getKey().toString();

        // Send header with conditional name suffix
        String nameSuffix = (targetPlayerName != null) ? " for " + targetPlayerName : "";
        sender.sendMessage(lang.get("itemdb.header").replace(" {name}", nameSuffix));

        // Send item information
        sender.sendMessage(lang.get("itemdb.name").replace("{item}", formattedName));
        sender.sendMessage(lang.get("itemdb.amount").replace("{amount}", String.valueOf(amount)));
        if (legacyId != null) {
            sender.sendMessage(lang.get("itemdb.legacy-id").replace("{legacy_id}", legacyId));
        }
        sender.sendMessage(lang.get("itemdb.modern-id").replace("{modern_id}", modernId));
    }

    private String formatMaterialName(Material material) {
        return Arrays.stream(material.name().split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
}