package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.*;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import static net.survivalfun.core.managers.core.Item.isGiveable;

public class Give implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;
    public Give(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        Meta.initialize(plugin);
        Potion.initialize(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission("core.give")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{command}", label.toLowerCase());
            return true;
        }

        String playerName;
        String itemArg;
        int amount;

        try {
            if (label.equalsIgnoreCase("give")) {
                if (args.length < 2) {
                    sender.sendMessage(lang.get("command-usage").replace("{cmd}", label).replace("{args}", "<player> <item> [amount]"));
                    return true;
                }
                playerName = args[0];
                itemArg = args[1];
                amount = args.length > 2 ? Integer.parseInt(args[2]) : 0;
            } else { // /i command
                if (!(sender instanceof Player)) {
                    Text.sendErrorMessage(sender, "not-a-player", lang);
                    return true;
                }
                if (args.length < 1) {
                    sender.sendMessage(lang.get("command-usage").replace("{cmd}", label).replace("{args}", "<item> [amount]"));
                    return true;
                }
                playerName = sender.getName();
                itemArg = args[0];
                amount = args.length > 1 ? Integer.parseInt(args[1]) : 0;
            }
            giveItems(playerName, itemArg, sender, amount);
        } catch (NumberFormatException e) {
            String invalidArg = label.equalsIgnoreCase("give") && args.length > 2 ? args[2] : args[1];
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", invalidArg);
        }
        return true;
    }

    private void giveItems(String playerName, String arg, CommandSender sender, int amount) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{player}", playerName);
            return;
        }

        try {
            String[] parts = arg.split(";", -1); // Use -1 limit to include trailing empty strings
            String itemName = parts[0];

            ItemStack item;
            // Parse all parts for enchantments
            Map<Enchantment, Integer> enchantments = Meta.getEnchantmentsFromParts(parts);

            if (itemName.equalsIgnoreCase("player_head")) {
                item = Meta.createPlayerHead(sender, parts, amount, enchantments);
                if (item == null) return; // Error handled in method
            } else {
                Material material = getMaterial(itemName, sender);
                if (material == null) return; // Error handled in method

                if (amount <= 0) {
                    amount = material.getMaxStackSize();
                }

                item = new ItemStack(material, amount);

                // Apply standard metadata (like name)
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    for (String part : parts) {
                        if (part.toLowerCase().startsWith("name:")) {
                            Meta.applyDisplayName(meta, part.substring(5));
                            break; // Only one name can be applied
                        }
                    }
                    item.setItemMeta(meta);
                }

                // Apply enchantments
                Meta.applyEnchantments(sender, item, enchantments);

                // Apply potion effects if applicable
                if (item.getType() == Material.POTION || item.getType() == Material.SPLASH_POTION || item.getType() == Material.LINGERING_POTION || item.getType() == Material.TIPPED_ARROW) {
                    Potion.applyPotionMeta(sender, item, parts);
                }
            }

            giveItemToPlayer(target, item, sender);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error processing give command for " + sender.getName() + ": " + e.getMessage(), e);
            Text.sendErrorMessage(sender, "give.error", lang);
        }
    }

    private void giveItemToPlayer(Player target, ItemStack item, CommandSender sender) {
        PlayerInventory inventory = target.getInventory();
        HashMap<Integer, ItemStack> leftover = inventory.addItem(item);

        if (!leftover.isEmpty()) {
            Text.sendErrorMessage(sender, "give.inventory-full", lang, "{player}", target.getName());
            // Drop remaining items at player's location
            for (ItemStack drop : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
        }

        String finalItemName;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            finalItemName = Meta.formatName(item.getType().name());
        } else {
            finalItemName = Meta.formatName(item.getType().name());
        }

        if (sender.getName().equals(target.getName())) {
            sender.sendMessage(lang.get("give.success")
                    .replace("{amount}", String.valueOf(item.getAmount()))
                    .replace("{item}", finalItemName));
        } else {
            sender.sendMessage(lang.get("give.success-other")
                    .replace("{name}", target.getName())
                    .replace("{amount}", String.valueOf(item.getAmount()))
                    .replace("{item}", finalItemName));

            if (target.isOnline()) {
                target.sendMessage(lang.get("give.receive")
                        .replace("{amount}", String.valueOf(item.getAmount()))
                        .replace("{item}", finalItemName));
            }
        }
    }

    private Material getMaterial(String itemName, CommandSender sender) {
        Material material = null;
        String baseItemName = itemName;

        // First try direct material lookup (case-insensitive)
        try {
            material = Material.matchMaterial(baseItemName);
            if (material != null && isGiveable(material)) {
                return material;
            }
        } catch (IllegalArgumentException ignored) {}

        // Check if the input contains a colon (for legacy data values)
        if (baseItemName.contains(":")) {
            // Try to resolve the full string as an alias first (e.g., "bed:1")
            String fullAlias = Alias.getAlias(baseItemName);
            if (fullAlias != null) {
                try {
                    material = Material.matchMaterial(fullAlias);
                    if (material != null && isGiveable(material)) {
                        return material;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            // Then try legacy ID
            material = LegacyID.getMaterialFromLegacyId(baseItemName);
            if (material != null && isGiveable(material)) {
                return material;
            }
        }

        // Try to resolve the input as an alias
        String alias = Alias.getAlias(baseItemName);
        if (alias != null) {
            material = Material.matchMaterial(alias);
            if (material != null && isGiveable(material)) {
                return material;
            }
        }

        // Try to resolve the input as a legacy ID (e.g., "1" for stone)
        material = LegacyID.getMaterialFromLegacyId(baseItemName);
        if (material != null && isGiveable(material)) {
            return material;
        }

        // Last attempt: Try with uppercase (official enum name)
        if (!baseItemName.equals(baseItemName.toUpperCase())) {
            try {
                material = Material.matchMaterial(baseItemName.toUpperCase());
                if (material != null && isGiveable(material)) {
                    return material;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // If we get here, the material couldn't be found
        Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
        return null;
    }
}