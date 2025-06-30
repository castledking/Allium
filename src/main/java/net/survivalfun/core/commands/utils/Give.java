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
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

// Using local isGiveable method

public class Give implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;
    public Give(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        Meta.initialize(plugin);
        Potion.initialize(plugin);
        Spawner.initialize(plugin);
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
            // Check if this is an alias that contains potion/enchantment parameters
            // Extract just the alias name (before any semicolon) for lookup
            String aliasName = arg.contains(";") ? arg.split(";")[0] : arg;
            
            String aliasParams = Alias.getParametersFromAlias(aliasName);
            String[] parts;
            String itemName;
            
            if (aliasParams != null) {
                // This is a complex alias with parameters
                // Get just the material part from the alias (e.g., POTION)
                itemName = Alias.getMaterialPartFromAlias(aliasName);
                
                // Check if the user specified additional parameters in the command (like name:)
                if (arg.contains(";")) {
                    // Get user parameters portion
                    String userParams = arg.substring(arg.indexOf(";") + 1);
                    
                    // For regular processing, use the user parameters combined with alias parameters
                    // but keep the base itemName from the alias
                    parts = new String[] {itemName}; // Start with just the material name
                    // Add alias parameters
                    String[] aliasParts = aliasParams.split(";");
                    // Add user parameters
                    String[] userParts = userParams.split(";");
                    
                    // Combine all parts into a single array
                    String[] combinedParts = new String[1 + aliasParts.length + userParts.length];
                    combinedParts[0] = itemName;
                    System.arraycopy(aliasParts, 0, combinedParts, 1, aliasParts.length);
                    System.arraycopy(userParts, 0, combinedParts, 1 + aliasParts.length, userParts.length);
                    parts = combinedParts;
                } else {
                    // No user parameters, just use the alias parameters
                    parts = (itemName + ";" + aliasParams).split(";", -1);
                }
            } else {
                // Regular processing
                parts = arg.split(";", -1); // Use -1 limit to include trailing empty strings
                itemName = parts[0];
            }

            ItemStack item;
            // Parse all parts for enchantments
            Map<Enchantment, Integer> enchantments = Meta.getEnchantmentsFromParts(parts);

            if (itemName.equalsIgnoreCase("player_head")) {
                item = Meta.createPlayerHead(sender, parts, amount, enchantments);
                if (item == null) return; // Error handled in method
            } else {
                Material material = getMaterial(itemName, sender);
                if (material == null) return; // Error handled in method

                // Extra check to ensure the material is in the isGiveable list
                if (!Item.isGiveable(material)) {
                    Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
                    return;
                }

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

                // Check for potion with only a semicolon (special case for showing usage)
                if (itemName.equalsIgnoreCase("potion") && parts.length > 1 && parts[1].isEmpty()) {
                    // The Potion class will handle displaying the usage message
                    // We still create a normal potion as fallback
                }

                // Apply potion effects if applicable
                if (item.getType() == Material.POTION || item.getType() == Material.SPLASH_POTION || item.getType() == Material.LINGERING_POTION || item.getType() == Material.TIPPED_ARROW) {
                    // The parts array already contains the merged alias and user parameters
                    // No need to recreate it - just use it directly
                    Potion.applyPotionMeta(sender, item, parts);
                }

                // Apply spawner entity type if applicable
                if (item.getType() == Material.SPAWNER) {
                    Spawner.applySpawnerMeta(sender, item, parts);
                }
            }

            giveItemToPlayer(target, item, sender);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error processing give command for " + sender.getName() + ": " + e.getMessage(), e);
            Text.sendErrorMessage(sender, "contact-admin", lang);
        }
    }

    private void giveItemToPlayer(Player target, ItemStack item, CommandSender sender) {
        PlayerInventory inventory = target.getInventory();
        
        // Get the configuration values that will be needed throughout the method
        boolean dropOverflowItems = plugin.getConfig().getBoolean("give.drop-overflow-items", true);
        int maxItemsDropped = plugin.getConfig().getInt("give.max-items-dropped", 64);
        
        // Check if the player's inventory is completely full before attempting to give items
        boolean isInventoryCompletelyFull = isInventoryCompletelyFull(inventory, item);
        
        if (isInventoryCompletelyFull) {
            // Player has no space at all - no empty slots, no populatable stacks, and no armor equippables
            Text.sendErrorMessage(sender, "inventory-full", lang);
            return; // Cancel giving items and don't show success message
        }
        
        // Store the original amount requested before any modifications
        int originalRequestedAmount = item.getAmount();
        
        // Try to add to main inventory first
        HashMap<Integer, ItemStack> leftover = inventory.addItem(item);
        
        // Initialize counters for tracking items given
        int addedToInventory = originalRequestedAmount - (leftover.isEmpty() ? 0 : calculateTotalAmount(leftover.values()));
        int addedToOffhand = 0;
        int addedAsArmor = 0;
        
        int droppedAmount = 0;
        boolean wasEquipped = false;
        
        if (!leftover.isEmpty()) {
            // Try offhand if there are leftovers and offhand is empty
            if (inventory.getItemInOffHand().getType() == Material.AIR) {
                ItemStack firstLeftover = leftover.values().iterator().next();
                // Limit stack size to 99 for serialization compatibility
                int maxSerializableStackSize = 99;
                int materialMaxStackSize = firstLeftover.getType().getMaxStackSize();
                int maxAllowedStackSize = Math.min(maxSerializableStackSize, materialMaxStackSize);
                int amountForOffhand = Math.min(firstLeftover.getAmount(), maxAllowedStackSize);
                
                // Put the item in the offhand with limited stack size
                ItemStack offhandItem = firstLeftover.clone();
                offhandItem.setAmount(amountForOffhand);
                inventory.setItemInOffHand(offhandItem);
                
                // Update the leftover map
                if (amountForOffhand >= firstLeftover.getAmount()) {
                    leftover.remove(leftover.keySet().iterator().next());
                } else {
                    firstLeftover.setAmount(firstLeftover.getAmount() - amountForOffhand);
                }
                
                // Track items added to offhand
                addedToOffhand = amountForOffhand;
            }
            
            // If there are still leftovers, try to equip as armor if applicable
            if (!leftover.isEmpty() && isArmor(item.getType())) {
                ItemStack firstLeftover = leftover.values().iterator().next();
                ItemStack armorItem = firstLeftover.clone();
                armorItem.setAmount(1); // Only equip one piece
                
                // Try to equip the armor in the appropriate slot
                boolean equipped = tryEquipArmor(inventory, armorItem);
                
                if (equipped) {
                    wasEquipped = true;
                    
                    // Track items added as armor
                    addedAsArmor++;
                    
                    if (armorItem.getAmount() >= firstLeftover.getAmount()) {
                        leftover.remove(leftover.keySet().iterator().next());
                    } else {
                        firstLeftover.setAmount(firstLeftover.getAmount() - 1);
                    }
                }
            }
            
            // We already have the configuration values from the beginning of the method
            
            // If there are still leftovers after trying offhand and armor
            if (!leftover.isEmpty()) {
                // Calculate total leftover items
                for (ItemStack drop : leftover.values()) {
                    droppedAmount += drop.getAmount();
                }
                
                if (dropOverflowItems) {
                    // We don't show inventory-full message here anymore since we're dropping items
                    
                    // Check if we need to limit the number of dropped items
                    if (maxItemsDropped >= 0) {
                        // We'll strictly enforce the maxItemsDropped limit
                        
                        // Calculate how many items we can actually drop based on the limit
                        int totalToDrop = Math.min(droppedAmount, maxItemsDropped);
                        int remainingToDropCount = totalToDrop;
                        
                        // Drop items up to the limit
                        for (Map.Entry<Integer, ItemStack> entry : leftover.entrySet()) {
                            if (remainingToDropCount <= 0) break;
                            
                            ItemStack drop = entry.getValue().clone();
                            int amountToDrop = Math.min(drop.getAmount(), remainingToDropCount);
                            
                            if (amountToDrop > 0) {
                                ItemStack dropStack = drop.clone();
                                dropStack.setAmount(amountToDrop);
                                target.getWorld().dropItemNaturally(target.getLocation(), dropStack);
                                remainingToDropCount -= amountToDrop;
                            }
                        }
                        
                        // Update the actual dropped amount to reflect the limit
                        int actualDroppedAmount = totalToDrop - remainingToDropCount;
                        droppedAmount = actualDroppedAmount;
                        
                        // Log the actual dropped amount for debugging
                        if (plugin.getConfig().getBoolean("give.debug")) {
                            plugin.getLogger().info("Limited drops: requested=" + originalRequestedAmount + 
                                                  ", dropped=" + actualDroppedAmount + 
                                                  ", maxLimit=" + maxItemsDropped);
                        }
                    } else {
                        // Drop all leftover items (no limit)
                        for (ItemStack drop : leftover.values()) {
                            target.getWorld().dropItemNaturally(target.getLocation(), drop);
                        }
                    }
                } else {
                    // Not dropping items, just count them as not given
                    // We don't need to do anything else here since we already calculated droppedAmount
                }
            }
        }
        
        String finalItemName;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            finalItemName = Meta.formatName(item.getType().name());
        } else {
            finalItemName = Meta.formatName(item.getType().name());
        }

        // Add 'enchanted' if the item has enchantments
        if (meta != null && meta.hasEnchants()) {
            finalItemName = lang.getFirstColorCode("give.equipped") + "enchanted " + finalItemName;
        }
        
        // Build message based on what happened
        String message;
        
        // Calculate the actual number of items given to the player
        // This will be used in the success message
        int totalGiven;
        
        // Calculate total items actually given to the player (inventory + offhand + armor + limited drops)
        int totalInventoryItems = addedToInventory + addedToOffhand + addedAsArmor;
        
        if (dropOverflowItems && maxItemsDropped >= 0) {
            // Count what was added to inventory plus what was dropped (up to the max)
            int droppedToCount = Math.min(droppedAmount, maxItemsDropped);
            totalGiven = totalInventoryItems + droppedToCount;
            
            // Debug log
            if (plugin.getConfig().getBoolean("give.debug")) {
                plugin.getLogger().info("Success message calculation: inventory=" + addedToInventory + 
                                      ", offhand=" + addedToOffhand + 
                                      ", armor=" + addedAsArmor + 
                                      ", droppedToCount=" + droppedToCount + 
                                      ", totalGiven=" + totalGiven);
            }
        } else if (!dropOverflowItems) {
            // If not dropping overflow, only count what went into inventory
            totalGiven = totalInventoryItems;
            
            // Debug log
            if (plugin.getConfig().getBoolean("give.debug")) {
                plugin.getLogger().info("Success message calculation: inventory=" + addedToInventory + 
                                      ", offhand=" + addedToOffhand + 
                                      ", armor=" + addedAsArmor + 
                                      ", totalGiven=" + totalGiven);
            }
        } else {
            // If dropping with no limit, count everything
            totalGiven = totalInventoryItems + droppedAmount;
            
            // Debug log
            if (plugin.getConfig().getBoolean("give.debug")) {
                plugin.getLogger().info("Success message calculation: inventory=" + addedToInventory + 
                                      ", offhand=" + addedToOffhand + 
                                      ", armor=" + addedAsArmor + 
                                      ", droppedAmount=" + droppedAmount + 
                                      ", totalGiven=" + totalGiven);
            }
        }
        
        // Add equipped armor piece if applicable
        if (wasEquipped) {
            totalGiven++; // Add the equipped armor piece
        }
    
    // Construct the message with the total amount given (inventory + equipped)
    StringBuilder messageBuilder = new StringBuilder();
    messageBuilder.append(lang.get("give.success")
        .replace("{name}", "yourself")
        .replace("{amount}", String.valueOf(totalGiven))
        .replace("{item}", finalItemName));
    
    message = messageBuilder.toString();

    if (sender.getName().equals(target.getName())) {
        sender.sendMessage(message);
    } else {
        // For other-player messages
        // Reuse the totalGiven value calculated above
        
        // Construct the message with the total amount given (inventory + equipped)
        StringBuilder otherPlayerMessageBuilder = new StringBuilder();
        otherPlayerMessageBuilder.append(lang.get("give.success")
            .replace("{name}", target.getName())
            .replace("{amount}", String.valueOf(totalGiven))
            .replace("{item}", finalItemName));
            
        message = otherPlayerMessageBuilder.toString();
        
        sender.sendMessage(message);

        if (target.isOnline()) {
            // Message for the receiving player
            // Reuse the totalGiven value calculated above
            
            // Construct the message with the total amount given (inventory + equipped)
            StringBuilder receiverMessageBuilder = new StringBuilder();
            receiverMessageBuilder.append(lang.get("give.receive")
                .replace("{amount}", String.valueOf(totalGiven))
                .replace("{item}", finalItemName));
                
            message = receiverMessageBuilder.toString();
            
            message = receiverMessageBuilder.toString();
            
            target.sendMessage(message);
        }
    }
}

    private Material getMaterial(String itemName, CommandSender sender) {
        Material material = null;
        String baseItemName = itemName;

        // First check if we're dealing with an alias that might have potion parameters
        String materialPart = Alias.getMaterialPartFromAlias(baseItemName);
        if (materialPart != null) {
            try {
                material = Material.matchMaterial(materialPart);
                if (material != null && Item.isGiveable(material)) {
                    return material;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // Check if the input contains a colon (for legacy data values)
        if (baseItemName.contains(":")) {
            // Try to resolve the full string as an alias first (e.g., "bed:1")
            String fullAlias = Alias.getAlias(baseItemName);
            if (fullAlias != null) {
                try {
                    material = Material.matchMaterial(fullAlias);
                    if (material != null && Item.isGiveable(material)) {
                        return material;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            // Then try legacy ID
            material = LegacyID.getMaterialFromLegacyId(baseItemName);
            if (material != null && Item.isGiveable(material)) {
                return material;
            }
        }

        // Try to resolve the input as an alias
        String alias = Alias.getAlias(baseItemName);
        if (alias != null) {
            material = Material.matchMaterial(alias);
            if (material != null && Item.isGiveable(material)) {
                return material;
            }
        }

        // Try to resolve the input as a legacy ID (e.g., "1" for stone)
        material = LegacyID.getMaterialFromLegacyId(baseItemName);
        if (material != null && Item.isGiveable(material)) {
            return material;
        }

        // Then try direct material lookup (case-insensitive)
        try {
            material = Material.matchMaterial(baseItemName);
            if (material != null && Item.isGiveable(material)) {
                return material;
            }
        } catch (IllegalArgumentException ignored) {}

        // Last attempt: Try with uppercase (official enum name)
        if (!baseItemName.equals(baseItemName.toUpperCase())) {
            try {
                material = Material.matchMaterial(baseItemName.toUpperCase());
                if (material != null && Item.isGiveable(material)) {
                    return material;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // If we get here, the material couldn't be found
        Text.sendErrorMessage(sender, "give.invalid-item", lang, "{item}", itemName);
        return null;
    }
    
    /**
     * Checks if the given material is an armor item.
     * 
     * @param material The material to check
     * @return true if the material is armor, false otherwise
     */
    private boolean isArmor(Material material) {
        if (material == null) return false;
        
        String name = material.name();
        return name.endsWith("_HELMET") || 
               name.endsWith("_CHESTPLATE") || 
               name.endsWith("_LEGGINGS") || 
               name.endsWith("_BOOTS") ||
               material == Material.TURTLE_HELMET ||
               material == Material.ELYTRA;
    }
    
    /**
     * Checks if a player's inventory is completely full with no space for the given item.
     * This means no empty slots, no populatable stacks, and no armor equippables.
     * 
     * @param inventory The player's inventory to check
     * @param item The item we're trying to give
     * @return true if the inventory is completely full, false otherwise
     */
    private boolean isInventoryCompletelyFull(PlayerInventory inventory, ItemStack item) {
        // Check if there's any empty slot
        for (ItemStack slot : inventory.getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) {
                return false; // Found an empty slot
            }
        }
        
        // Check if offhand is empty
        if (inventory.getItemInOffHand().getType() == Material.AIR) {
            return false; // Offhand is empty
        }
        
        // Check if the item is armor and if the corresponding armor slot is empty
        Material material = item.getType();
        if (material.name().endsWith("_HELMET") && inventory.getHelmet() == null) {
            return false; // Helmet slot is empty
        } else if (material.name().endsWith("_CHESTPLATE") && inventory.getChestplate() == null) {
            return false; // Chestplate slot is empty
        } else if (material.name().endsWith("_LEGGINGS") && inventory.getLeggings() == null) {
            return false; // Leggings slot is empty
        } else if (material.name().endsWith("_BOOTS") && inventory.getBoots() == null) {
            return false; // Boots slot is empty
        }
        
        // If we got here, the inventory is completely full
        return true;
    }
    
    /**
     * Calculate the total amount of items in a collection of ItemStacks
     * 
     * @param items Collection of ItemStacks to calculate total amount for
     * @return The total amount of items
     */
    private int calculateTotalAmount(Collection<ItemStack> items) {
        int total = 0;
        for (ItemStack stack : items) {
            if (stack != null) {
                total += stack.getAmount();
            }
        }
        return total;
    }
    
    /**
     * Attempts to equip an armor item in the appropriate slot.
     * 
     * @param inventory The player's inventory
     * @param armorItem The armor item to equip
     * @return true if the armor was equipped, false otherwise
     */
    private boolean tryEquipArmor(PlayerInventory inventory, ItemStack armorItem) {
        if (armorItem == null) return false;
        
        Material material = armorItem.getType();
        
        // Determine the appropriate slot based on the armor type
        if (material.name().endsWith("_HELMET") || material.name().equals("TURTLE_HELMET")) {
            if (inventory.getHelmet() == null) {
                inventory.setHelmet(armorItem);
                return true;
            }
        } else if (material.name().endsWith("_CHESTPLATE") || material.name().equals("ELYTRA")) {
            if (inventory.getChestplate() == null) {
                inventory.setChestplate(armorItem);
                return true;
            }
        } else if (material.name().endsWith("_LEGGINGS")) {
            if (inventory.getLeggings() == null) {
                inventory.setLeggings(armorItem);
                return true;
            }
        } else if (material.name().endsWith("_BOOTS")) {
            if (inventory.getBoots() == null) {
                inventory.setBoots(armorItem);
                return true;
            }
        }
        
        return false;
    }
}