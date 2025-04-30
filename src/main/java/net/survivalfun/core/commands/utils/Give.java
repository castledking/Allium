package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Alias;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.core.LegacyID;
import org.bukkit.Bukkit;
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
import java.util.logging.Level;

import static net.survivalfun.core.managers.core.Item.isGiveable;

public class Give implements CommandExecutor {
    private final FileConfiguration config;
    private final PluginStart plugin;
    private final Lang lang;


    public Give(PluginStart plugin, FileConfiguration config, Lang lang) {
        this.config = config;
        this.plugin = plugin;
        this.lang = lang;
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command
            , @NotNull String label
            , String @NotNull [] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing GC command");
            return true;
        }
        if (!sender.hasPermission("core.give")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{command}", label.toLowerCase());
            return true;
        }
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
            // Determine which argument caused the error based on the command label
            String invalidArg;
            if (label.equalsIgnoreCase("give")) {
                invalidArg = args[2]; // For /give command, the amount is the 3rd argument (index 2)
            } else {
                invalidArg = args[1]; // For /i command, the amount is the 2nd argument (index 1)
            }

            Text.sendErrorMessage(sender, lang.get("invalid"), lang, "{arg}", invalidArg);
            return true;
        }
        switch (label) {
            case "give": {
                switch (args.length) {
                    case 0 -> sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "[player] <item> [amount]"));
                    case 1 -> giveItems(sender.getName(), args[0], sender, 0, false
                            , getEnchantments(args[0]));
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
                    case 0 -> sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", label)
                            .replace("{args}", "<item> [amount]"));
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
        if (meta == null) return;

        boolean allowUnsafe = config.getBoolean("allow-unsafe-enchants");
        boolean isEnchantedBook = meta instanceof EnchantmentStorageMeta;

        if (isEnchantedBook) {
            EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) meta;
            // Enchanted books ignore conflicts
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                Enchantment enchantment = entry.getKey();
                int level = entry.getValue();

                if (!allowUnsafe && level > enchantment.getMaxLevel()) {
                    Text.sendErrorMessage(sender, "give.unsafe-level", lang,
                            "{enchant}", formatName(enchantment.getKey().getKey()),
                            "{maxLevel}", String.valueOf(enchantment.getMaxLevel()));
                    level = enchantment.getMaxLevel();
                }

                try {
                    bookMeta.addStoredEnchant(enchantment, level, true);
                } catch (IllegalArgumentException e) {
                    Text.sendErrorMessage(sender, "give.unsafe-enchant", lang,
                            "{enchant}", formatName(enchantment.getKey().getKey()),
                            "{item}", formatName(material.name()));
                }
            }
            item.setItemMeta(bookMeta);
            return;
        }

        if (allowUnsafe) {
            // Unsafe enchants allowed, directly apply everything
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                try {
                    meta.addEnchant(entry.getKey(), entry.getValue(), true);
                } catch (IllegalArgumentException e) {
                    Text.sendErrorMessage(sender, "give.unsafe-enchant", lang,
                            "{enchant}", formatName(String.valueOf(entry.getKey().getKey())),
                            "{item}", formatName(material.name()));
                }
            }
            item.setItemMeta(meta);
            return;
        }

        // SAFE MODE: Proper conflict checking BEFORE applying enchantments
        Map<Enchantment, Integer> addedEnchantments = new LinkedHashMap<>();

        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();

            // Check for enchant applicability
            if (!enchantment.canEnchantItem(item)) {
                Text.sendErrorMessage(sender, "give.unsafe-enchant", lang,
                        "{enchant}", formatName(enchantment.getKey().getKey()),
                        "{item}", formatName(material.name()));
                continue;
            }

            // Check enchant max level
            if (level > enchantment.getMaxLevel()) {
                Text.sendErrorMessage(sender, "give.unsafe-level", lang,
                        "{enchant}", formatName(enchantment.getKey().getKey()),
                        "{maxLevel}", String.valueOf(enchantment.getMaxLevel()));
                level = enchantment.getMaxLevel();
            }

            // Temporarily add enchant to a copy of meta to check conflict
            boolean conflicts = false;
            for (Enchantment existingEnch : addedEnchantments.keySet()) {
                if (existingEnch.conflictsWith(enchantment)) {
                    // Conflicting enchantment found
                    conflicts = true;

                    Text.sendErrorMessage(sender, "give.conflicting-enchants", lang,
                            "{enchant}", formatName(existingEnch.getKey().getKey()),
                            "{enchant2}", formatName(enchantment.getKey().getKey()),
                            "{item}", formatName(material.name()));
                    break; // Stop checking after found conflict
                }
            }

            // If no conflict, store temporarily
            if (!conflicts) {
                addedEnchantments.put(enchantment, level);
            }
        }

        // Apply only non-conflicting enchantments officially to the item's meta
        for (Map.Entry<Enchantment, Integer> allowedEnchEntry : addedEnchantments.entrySet()) {
            meta.addEnchant(allowedEnchEntry.getKey(), allowedEnchEntry.getValue(), true);
        }

        item.setItemMeta(meta);
    }



    private void giveItems(String player, String arg, CommandSender sender, int amount, boolean canConsole, Map<Enchantment
        , Integer> enchantments) {
        if (!canConsole && !(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
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
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", player);
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
                    name = Text.parseColors(name);
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
                Text.sendErrorMessage(sender, "give.inventory-full", lang);
                return;
            }

            // Message
            String itemNameFormatted = formatName(material.name());
            String message;
            if (isEnchanted(item)) {
                itemNameFormatted = "enchanted " + itemNameFormatted;
            }

            if (sender instanceof Player && target.equals(sender)) {
                message = lang.get("give.success")
                        .replace("{amount}", String.valueOf(givenAmount))
                        .replace("{item}", itemNameFormatted);
            } else {
                message = lang.get("give.success-other")
                        .replace("{name}", target.getName())
                        .replace("{amount}", String.valueOf(givenAmount))
                        .replace("{item}", itemNameFormatted);
            }

            sender.sendMessage(message);
        } catch (IllegalArgumentException e) {
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "item");
        } catch (Exception e) {
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "item");
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
            material = LegacyID.getMaterialFromLegacyId(baseItemName);
        }

        // 3. Check Aliases
        if (material == null) {
            String alias = Alias.getAlias(baseItemName);
            if (alias != null) {
                try {
                    material = Material.valueOf(alias.toUpperCase());
                } catch (IllegalArgumentException e2) {
                    // Alias doesn't resolve to a valid Material
                    Text.sendErrorMessage(sender, lang.get("give.invalid-item"), lang, "{item}", itemName);
                    return null; // Indicate failure
                }
            }
        }

        if (material == null) {
            // Not a valid material, legacy ID, or alias
            Text.sendErrorMessage(sender, lang.get("give.invalid-item"), lang, "{item}", itemName);
            return null; // Indicate failure
        }

        if (!isGiveable(material)) {
            Text.sendErrorMessage(sender, lang.get("give.invalid-item"), lang, "{item}", itemName);
            return null; // Indicate failure
        }

        return material;
    }

}