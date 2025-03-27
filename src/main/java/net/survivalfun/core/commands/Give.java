package net.survivalfun.core.commands;

import net.survivalfun.core.utils.LegacyIDUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class Give implements CommandExecutor {

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        // Define aliases for materials
        ALIASES.put("enderpearl", "ENDER_PEARL");
        ALIASES.put("pearl", "ENDER_PEARL");
        ALIASES.put("stonebrick", "STONE_BRICKS");
        ALIASES.put("goldapple", "GOLDEN_APPLE");
        ALIASES.put("godapple", "ENCHANTED_GOLDEN_APPLE");
        ALIASES.put("xp", "EXPERIENCE_BOTTLE");
        ALIASES.put("spawner", "SPAWNER");

        // Add more aliases as needed...

    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if ((args.length < 1 && label.equalsIgnoreCase("i")) || (args.length < 2 && !label.equalsIgnoreCase("i"))) {
            sender.sendMessage("§cUsage: " + (label.equalsIgnoreCase("i") ? "/i <item> [amount]" : "/give <player> <item> [amount]"));
            return true;
        }

        String materialName = getMaterialFromAlias(args[label.equalsIgnoreCase("i") ? 0 : 1]);
        Material material = Material.getMaterial(materialName.toUpperCase());

        if (material == null) {
            sender.sendMessage("§cInvalid item name! Make sure it's a valid Material.");
            return true;
        }

        int amount = material.getMaxStackSize();

        if ((label.equalsIgnoreCase("i") && args.length > 1) || (!label.equalsIgnoreCase("i") && args.length > 2)) {
            try {
                amount = Integer.parseInt(args[label.equalsIgnoreCase("i") ? 1 : 2]);
                if (amount <= 0) {
                    sender.sendMessage("§cThe amount must be greater than 0.");
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid amount specified! Please enter a valid number.");
                return true;
            }
        }

        Player target;
        if (label.equalsIgnoreCase("i")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            target = (Player) sender;
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found! Make sure the player is online.");
                return true;
            }
        }

        ItemStack item = new ItemStack(material, amount);
        int remainingItems = giveItems(target, item);
        int givenAmount = amount - remainingItems;

        if (givenAmount > 0) {
            target.sendMessage("§aYou have been given §e" + givenAmount + " §aof §e" + formatMaterialName(material) + "§a!");
        }
        if (!target.equals(sender) && givenAmount > 0) {
            sender.sendMessage("§aGave §e" + givenAmount + " §aof §e" + formatMaterialName(material) + " §ato §e" + target.getName() + "§a!");
        }

        if (remainingItems > 0) {
            sender.sendMessage("§cCould not give §e" + remainingItems + " §cof §e" + formatMaterialName(material) + " §cto §e" + target.getName() + "§c. Not enough space!");
        }

        return true;
    }

    private int giveItems(Player player, ItemStack item) {
        Inventory inventory = player.getInventory();
        int remaining = item.getAmount();
        int maxStackSize = item.getMaxStackSize();

        for (int i = 0; i < 36; i++) {
            if (remaining <= 0) break;
            ItemStack slot = inventory.getItem(i);

            if (slot == null || slot.getType() == Material.AIR) {
                int toAdd = Math.min(remaining, maxStackSize);
                inventory.setItem(i, new ItemStack(item.getType(), toAdd));
                remaining -= toAdd;
            } else if (slot.isSimilar(item)) {
                int spaceLeft = maxStackSize - slot.getAmount();
                if (spaceLeft > 0) {
                    int toAdd = Math.min(remaining, spaceLeft);
                    slot.setAmount(slot.getAmount() + toAdd);
                    remaining -= toAdd;
                }
            }
        }

        return remaining;
    }

    private String formatMaterialName(Material material) {
        String[] words = material.name().split("_");
        StringBuilder formattedName = new StringBuilder();
        for (String word : words) {
            formattedName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase()).append(" ");
        }
        return formattedName.toString().trim();
    }

    /**
     * Converts an alias or legacy item ID to a valid Material name.
     * - If input is a number or in "number:number" format (legacy ID), it checks the legacy ID map.
     * - If input is an alias, it uses the alias map.
     * - Otherwise, it returns the input in uppercase as a fallback.
     */
    private String getMaterialFromAlias(String input) {
        // First check for legacy ID format (either "id" or "id:data")
        if (input.matches("\\d+:\\d+") || input.matches("\\d+")) {
            Material material = LegacyIDUtils.getMaterialFromLegacyId(input);
            return material != null ? material.name() : "UNKNOWN";
        }

        // Fall back to checking aliases
        return ALIASES.getOrDefault(input.toLowerCase(), input.toUpperCase());
    }
}
