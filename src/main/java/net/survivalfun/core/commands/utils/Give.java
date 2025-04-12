package net.survivalfun.core.commands.utils;

import net.survivalfun.core.utils.AliasUtils;
import net.survivalfun.core.utils.LegacyIDUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static net.survivalfun.core.utils.ItemUtils.isGiveable;

public class Give implements CommandExecutor {
    private final FileConfiguration config;

    public Give(FileConfiguration config) {
        this.config = config;
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command
            , @NotNull String label
            , String @NotNull [] args) {
        try {
            if (label.equalsIgnoreCase("give")) {
                if (args.length == 3) {
                    Integer.parseInt(args[2]);
                }
            } else if (label.equalsIgnoreCase("i")) {
                if (args.length == 2) {
                    Integer.parseInt(args[1]);
                }
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid amount! Please check your syntax.");
            return true;
        }
        switch (label) {
            case "give": {
                switch (args.length) {
                    case 0 -> sender.sendMessage("Usage: /give <player> <item> [amount]");
                    case 1 -> sender.sendMessage("§cMissing argument: <item>");
                    case 2 -> giveItems(args[0], args[1], sender, 0, true
                            , getEnchantments(args[1])); // Default amount if not specified
                    case 3 -> giveItems(args[0], args[1], sender, Integer.parseInt(args[2])
                            , true
                            , getEnchantments(args[1]));
                }
                break;
            }
            case "i": {
                switch (args.length) {
                    case 0 -> sender.sendMessage("Usage: /i <item> [amount]");
                    case 1 -> giveItems(sender.getName(), args[0], sender, 0
                            , false
                            , getEnchantments(args[0]));
                    case 2 -> giveItems(sender.getName(), args[0], sender, Integer.parseInt(args[1])
                            , false
                            , getEnchantments(args[0]));
                }
                break;
            }
        }
        return true;
    }

    private Map<Enchantment, Integer> getEnchantments(String arg) {
        Map<Enchantment, Integer> enchantments = new HashMap<>();
        String[] enchantmentsArray = arg.split(";");
        for (String enchantmentString : enchantmentsArray) {
            String[] enchantmentParts = enchantmentString.split(":");
            if (enchantmentParts.length == 2) {
                Enchantment enchantment = Enchantment.getByKey(NamespacedKey
                        .minecraft(enchantmentParts[0]
                        .toLowerCase()));
                if (enchantment != null) {
                    int level = Integer.parseInt(enchantmentParts[1]);
                    enchantments.put(enchantment, level);
                }
            }
        }
        return enchantments;
    }


    public void applyEnchantments(CommandSender sender, ItemStack item, Material material, Map<Enchantment, Integer> enchantments) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            boolean allowUnsafe = config.getBoolean("allow-unsafe-enchants");
            boolean isEnchantedBook = meta instanceof EnchantmentStorageMeta;

            if (isEnchantedBook) {
                EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) meta;
                // Apply enchantments to enchanted books, bypassing conflict checks
                for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                    Enchantment enchantment = entry.getKey();
                    int level = entry.getValue();

                    int maxLevel = enchantment.getMaxLevel();
                    if (!allowUnsafe && level > maxLevel) {
                        sender.sendMessage("§cEnchantment " + formatName(enchantment.getKey().getKey())
                                + " exceeds maximum level (" + maxLevel + "). Setting level to max.");
                        level = maxLevel;
                    }

                    try {
                        bookMeta.addStoredEnchant(enchantment, level, true); // Ignore level restrictions and conflicts
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage("§cEnchantment " + formatName(enchantment.getKey().getKey())
                                + " cannot be applied to " + formatName(material.name()) + ".");
                    }
                }
                item.setItemMeta(bookMeta);


            } else if (allowUnsafe) {

                for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                    Enchantment enchantment = entry.getKey();
                    Integer level = entry.getValue();

                    try {
                        meta.addEnchant(enchantment, level, true); // Ignore level restrictions and conflicts
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage("§c" + formatName(enchantment.getKey().getKey())
                                + " can't be enchanted with " + formatName(material.name()) + ".");
                        //Don't return here, continue to apply the other enchantments
                    }
                }
                item.setItemMeta(meta);
            } else {
                // Apply enchantments safely, checking for level limits and conflicts
                for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                    Enchantment enchantment = entry.getKey();
                    int level = entry.getValue();

                    if (!enchantment.canEnchantItem(item)) {
                        sender.sendMessage("§c" + formatName(enchantment.getKey().getKey())
                                + " not applicable to " + formatName(material.name()) + ".");
                        continue; // Skip to the next enchantment
                    }

                    int maxLevel = enchantment.getMaxLevel();
                    if (level > maxLevel) {
                        sender.sendMessage("§c" + formatName(enchantment.getKey().getKey())
                                + " level too high, giving the max value (" + maxLevel + ")");
                        level = maxLevel;
                    }
                    try {
                        meta.addEnchant(enchantment, level, true);
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage("§c" + formatName(enchantment.getKey().getKey())
                                + " can't be enchanted with " + formatName(material.name()) + ".");
                        //Don't return here, continue to apply the other enchantments
                    }
                }
                item.setItemMeta(meta);
            }

        }
    }


    private void giveItems(String player, String arg, CommandSender sender, int amount, boolean canConsole, Map<Enchantment
        , Integer> enchantments) {
        if (!canConsole && !(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return;
        }


        try {
            // Extract base item name
            String[] parts = arg.split(";");
            String itemName = parts[0];

            Material material = getMaterial(itemName, sender);
            if (material == null) {
                return;
            }

            Player target = Bukkit.getPlayer(player);
            if (target == null) {
                sender.sendMessage("Player not found.");
                return;
            } else if (amount == 0) {
                amount = material.getMaxStackSize();
            }

            ItemStack item = new ItemStack(material, amount);
            ItemMeta meta = item.getItemMeta();

            String name = null;
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                if (part.startsWith("name=") || part.startsWith("name:")) {
                    name = part.substring(5)
                            .replace("_",
                                    " ");
                    name = ChatColor
                            .translateAlternateColorCodes('&', name);
                    break;
                }
            }

            if (meta != null) {
                if (name != null) {
                    Plugin plugin = Bukkit.getPluginManager().getPlugin("SFCore");
                    if (plugin == null) {
                        sender.sendMessage("Plugin not found.");
                        return;
                    }
                    NamespacedKey key = new NamespacedKey(plugin, "display_name");
                    PersistentDataContainer dataContainer = meta.getPersistentDataContainer();
                    dataContainer.set(key, PersistentDataType.STRING, name);
                    meta.setDisplayName(name);
                }
                item.setItemMeta(meta);
            }


            applyEnchantments(sender, item, material, enchantments);

            PlayerInventory inventory = target.getInventory();
            int maxStackSize = material.getMaxStackSize();
            int originalAmount = amount;
            int remainingAmount = 0;

            // Check if inventory is full BEFORE giving items for armor
            if (inventory.firstEmpty() == -1) {
                if (material.name().endsWith("_HELMET") && inventory.getHelmet() == null) {
                    originalAmount = 1;
                    item.setAmount(originalAmount);
                    inventory.setHelmet(item);
                    if (isEnchanted(item)) {
                        target.sendMessage("§aEquipped enchanted " + formatName(material.name()) + ".");
                    } else {
                        target.sendMessage("§aEquipped " + formatName(material.name()) + ".");
                    }
                    amount = 0;
                } else if (material.name().endsWith("_CHESTPLATE") && inventory.getChestplate() == null) {
                    originalAmount = 1;
                    item.setAmount(originalAmount);
                    inventory.setChestplate(item);
                    if (isEnchanted(item)) {
                        target.sendMessage("§aEquipped enchanted " + formatName(material.name()) + ".");
                    } else {
                        target.sendMessage("§aEquipped " + formatName(material.name()) + ".");
                    }
                    amount = 0;
                } else if (material.name().endsWith("_LEGGINGS") && inventory.getLeggings() == null) {
                    originalAmount = 1;
                    item.setAmount(originalAmount);
                    inventory.setLeggings(item);
                    if (isEnchanted(item)) {
                        target.sendMessage("§aEquipped enchanted " + formatName(material.name()) + ".");
                    } else {
                        target.sendMessage("§aEquipped " + formatName(material.name()) + ".");
                    }
                    amount = 0;
                } else if (material.name().endsWith("_BOOTS") && inventory.getBoots() == null) {
                    originalAmount = 1;
                    item.setAmount(originalAmount);
                    inventory.setBoots(item);
                    if (isEnchanted(item)) {
                        target.sendMessage("§aEquipped enchanted " + formatName(material.name()) + ".");
                    } else {
                        target.sendMessage("§aEquipped " + formatName(material.name()) + ".");
                    }
                    amount = 0;
                } else if (material.name().equals("SHIELD")) {
                    ItemStack offHandItem = inventory.getItemInOffHand();
                    if (!offHandItem.getType().isAir())
                        return;
                    inventory.setItemInOffHand(item);
                    if (isEnchanted(item)) {
                        target.sendMessage("§aEquipped enchanted " + formatName(material.name()) + ".");
                    } else {
                        target.sendMessage("§aEquipped " + formatName(material.name()) + ".");
                    }
                    amount = 0;
                }
            }
            // Give items in stacks
            while (amount > 0) {
                int stackSize = Math.min(amount, maxStackSize);
                ItemStack itemStack = item.clone();
                itemStack.setAmount(stackSize);

                boolean addedToExistingStack = false;

                // Find an existing stack to add to
                for (ItemStack invItem : inventory.getContents()) {
                    if (invItem != null && invItem.getType() == material && invItem.getAmount() < maxStackSize && invItem
                            .isSimilar(itemStack)) {
                        int freeSpace = maxStackSize - invItem.getAmount();
                        int addAmount = Math.min(stackSize, freeSpace);
                        invItem.setAmount(invItem.getAmount() + addAmount);
                        amount -= addAmount;
                        addedToExistingStack = true;
                        break;
                    }
                }

                // If no existing stack, add to a new slot
                if (!addedToExistingStack) {
                    int firstEmpty = inventory.firstEmpty();
                    if (firstEmpty == -1) {
                        // Inventory is full
                        break; // Stop giving items
                    }
                    inventory.setItem(firstEmpty, itemStack);
                    amount -= stackSize;
                }
            }

            remainingAmount = amount;
            int givenAmount = originalAmount - remainingAmount;
            if (givenAmount == 0) {
                sender.sendMessage("§cInventory full, make some space before giving more items.");
                return;
            }

            // Message
            String itemNameFormatted = formatName(material.name());
            String message;
            if (isEnchanted(item)) {
                itemNameFormatted = "enchanted " + itemNameFormatted;
            }

            if (sender instanceof Player && target.equals(sender)) {
                message = "§aYou have given yourself " + givenAmount + " " + itemNameFormatted + ".";
            } else {
                message = "§aYou have given " + target.getName() + " " + givenAmount + " " + itemNameFormatted + ".";
                target.sendMessage("§aYou have received " + givenAmount + " " + itemNameFormatted + " from " + sender
                        .getName() + ".");
            }

            sender.sendMessage(message);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid argument! Please check your syntax.");
        } catch (Exception e) {
            sender.sendMessage("§cAn unexpected error occurred. Please check your syntax.");
            e.printStackTrace();
        }
    }

    private boolean isEnchanted(ItemStack item) {
        item.getEnchantments();
        return !item.getEnchantments().isEmpty();
    }


    private String formatName(String name) {
        String lowerCaseName = name.toLowerCase().replace("_", " ");
        String[] words = lowerCaseName.split(" ");
        StringBuilder formattedName = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.equalsIgnoreCase("of")) {
                formattedName.append("of"); // Ensure "of" is always lowercase
            } else {
                if (!word.isEmpty()) { // Prevent issues with empty words
                    formattedName.append(word.substring(0, 1).toUpperCase()).append(word.substring(1));
                }
            }
            if (i < words.length - 1) {
                formattedName.append(" "); // Add space between words
            }
        }

        return formattedName.toString();
    }




    /**
     * Converts an alias or legacy item ID to a valid Material name.
     * - If input is a number or in "number:number" format (legacy ID), it checks the legacy ID map.
     * - If input is an alias, it uses the alias map.
     * - Otherwise, it returns the input in uppercase as a fallback.
     */
    private Material getMaterial(String itemName, CommandSender sender) {
        Material material = null;
        String baseItemName = itemName;

        if (itemName.contains(";")) {
            baseItemName = itemName.substring(0, itemName.indexOf(";"));
        }

        // 1. Try direct Material name (e.g., "DIAMOND_SWORD")
        try {
            material = Material.valueOf(baseItemName.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Not a direct Material name, try other methods
        }

        // 2. Check Legacy IDs
        if (material == null) {
            material = LegacyIDUtils.getMaterialFromLegacyId(baseItemName);
        }

        // 3. Check Aliases
        if (material == null) {
            String alias = AliasUtils.getAlias(baseItemName);
            if (alias != null) {
                try {
                    material = Material.valueOf(alias.toUpperCase());
                } catch (IllegalArgumentException e2) {
                    // Alias doesn't resolve to a valid Material
                    sender.sendMessage("§cInvalid item: " + itemName);
                    return null; // Indicate failure
                }
            }
        }

        if (material == null) {
            // Not a valid material, legacy ID, or alias
            sender.sendMessage("§cInvalid item: " + itemName);
            return null; // Indicate failure
        }

        if (!isGiveable(material)) {
            sender.sendMessage("§cInvalid item: " + itemName);
            return null; // Indicate failure
        }

        return material;
    }




}