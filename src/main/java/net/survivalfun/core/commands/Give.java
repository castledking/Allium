package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.events.ItemGiveEvent;
import net.survivalfun.core.items.CustomItem;
import net.survivalfun.core.items.CustomItemRegistry;
import net.survivalfun.core.managers.core.*;
import net.survivalfun.core.managers.core.Item;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Location;
import java.util.Random;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

// Using local isGiveable method

public class Give implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;
    // Field to store parsed alias result for parameter application
    private ParsedAliasResult parsedAliasResult;

    /**
     * Result of parsing a complex alias
     */
    private static class ParsedAliasResult {
        final Material material;
        final String parameters;

        ParsedAliasResult(Material material, String parameters) {
            this.material = material;
            this.parameters = parameters;
        }
    }

    /**
     * Parsed item spec with optional inline amount (e.g. "dirt:1" or "stick;sharpness:5:12")
     */
    private static class ItemSpec {
        final String itemString;
        final int amount;

        ItemSpec(String itemString, int amount) {
            this.itemString = itemString;
            this.amount = amount;
        }
    }

    /** Result from give operation when equip flag is used */
    private static class GiveResult {
        final int totalGiven;
        final int equippedCount;
        final String equippedItemName; // single item name, or null if multiple

        GiveResult(int totalGiven, int equippedCount, String equippedItemName) {
            this.totalGiven = totalGiven;
            this.equippedCount = equippedCount;
            this.equippedItemName = equippedItemName;
        }
    }

    /**
     * Parse comma-separated item specs, extracting :amount from the end of each if present.
     * Examples: "dirt:1,grass_block:1" -> [(dirt,1), (grass_block,1)]
     *           "stick;sharpness:5:1" -> [(stick;sharpness:5, 1)]
     */
    private List<ItemSpec> parseItemSpecs(String arg, int defaultAmount) {
        // Strip -e/-equip from end of arg (e.g. "dhelmet,dboots -e" -> "dhelmet,dboots")
        arg = arg.trim();
        if (arg.endsWith(" -e") || arg.endsWith(" -equip")) {
            arg = arg.substring(0, arg.lastIndexOf(" -")).trim();
        }
        List<ItemSpec> specs = new ArrayList<>();
        for (String part : arg.split(",", -1)) {
            part = part.trim();
            if (part.isEmpty() || "-e".equalsIgnoreCase(part) || "-equip".equalsIgnoreCase(part)) continue;
            int amount = defaultAmount;
            int lastColon = part.lastIndexOf(':');
            if (lastColon > 0) {
                String suffix = part.substring(lastColon + 1);
                if (suffix.matches("\\d+")) {
                    try {
                        amount = Integer.parseInt(suffix);
                        part = part.substring(0, lastColon).trim();
                    } catch (NumberFormatException ignored) {}
                }
            }
            specs.add(new ItemSpec(part, amount));
        }
        return specs;
    }
    public Give(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        Meta.initialize(plugin);
        Potion.initialize(plugin);
        Spawner.initialize(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("allium.give")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label.toLowerCase());
            return true;
        }

        String playerName;
        String itemArg;
        int amount;
        boolean equipFlag = false;

        try {
            if (label.equalsIgnoreCase("give")) {
                if (args.length < 2) {
                    sender.sendMessage(lang.get("command-usage").replace("{cmd}", label).replace("{args}", "<player> <item> [amount] [-e]"));
                    return true;
                }
                playerName = args[0];
                itemArg = args[1];
                amount = 0;
                if (args.length >= 3 && ("-e".equalsIgnoreCase(args[2]) || "-equip".equalsIgnoreCase(args[2]))) {
                    equipFlag = true;
                } else if (args.length >= 3) {
                    amount = Integer.parseInt(args[2]);
                }
                if (args.length >= 4 && ("-e".equalsIgnoreCase(args[3]) || "-equip".equalsIgnoreCase(args[3]))) {
                    equipFlag = true;
                }
            } else { // /i command
                if (!(sender instanceof Player)) {
                    Text.sendErrorMessage(sender, "contact-admin", lang);
                    return true;
                }
                if (args.length < 1) {
                    sender.sendMessage(lang.get("command-usage").replace("{cmd}", label).replace("{args}", "<item> [amount] [-e]"));
                    return true;
                }
                playerName = sender.getName();
                itemArg = args[0];
                amount = 0;
                if (args.length >= 2 && ("-e".equalsIgnoreCase(args[1]) || "-equip".equalsIgnoreCase(args[1]))) {
                    equipFlag = true;
                } else if (args.length >= 2) {
                    amount = Integer.parseInt(args[1]);
                }
                if (args.length >= 3 && ("-e".equalsIgnoreCase(args[2]) || "-equip".equalsIgnoreCase(args[2]))) {
                    equipFlag = true;
                }
            }
            String source = label.equalsIgnoreCase("give") ? "give" : "item";
            giveItems(playerName, itemArg, sender, amount, source, equipFlag);
        } catch (NumberFormatException e) {
            String invalidArg = label.equalsIgnoreCase("give") && args.length > 2 ? args[2] : (args.length > 1 ? args[1] : args[0]);
            Text.sendErrorMessage(sender, "invalid", lang, "{arg}", invalidArg);
        }
        return true;
    }

    private void giveItems(String targetSelector, String arg, CommandSender sender, int amount, String source, boolean equipFlag) {
        // Handle selectors
        if (targetSelector.startsWith("@")) {
            // Get the command block or console sender's location for selector targeting
            Location origin = null;
            if (sender instanceof BlockCommandSender) {
                origin = ((BlockCommandSender) sender).getBlock().getLocation();
            } else if (sender instanceof Entity) {
                origin = ((Entity) sender).getLocation();
            }

            // Parse the selector
            try {
                List<Entity> entities = new ArrayList<>();
                
                if (targetSelector.startsWith("@a")) {
                    // All players
                    entities.addAll(Bukkit.getOnlinePlayers());
                } else if (targetSelector.startsWith("@p")) {
                    // Nearest player
                    if (origin != null) {
                        Player nearest = null;
                        double nearestDistance = Double.MAX_VALUE;
                        
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (origin.getWorld() != player.getWorld()) continue;
                            
                            double distance = origin.distanceSquared(player.getLocation());
                            if (distance < nearestDistance) {
                                nearest = player;
                                nearestDistance = distance;
                            }
                        }
                        
                        if (nearest != null) {
                            entities.add(nearest);
                        }
                    }
                } else if (targetSelector.startsWith("@r")) {
                    // Random player
                    List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                    if (!players.isEmpty()) {
                        entities.add(players.get(new Random().nextInt(players.size())));
                    }
                } else if (targetSelector.startsWith("@s")) {
                    // Self
                    if (sender instanceof Entity) {
                        entities.add((Entity) sender);
                    }
                } else if (targetSelector.startsWith("@e")) {
                    // All entities
                    if (origin != null) {
                        // Simple radius-based entity selection
                        int radius = 10; // Default radius
                        entities.addAll(origin.getWorld().getNearbyEntities(origin, radius, radius, radius));
                    }
                }

                // Process each entity - support comma-separated items with optional :amount
                List<ItemSpec> specs = parseItemSpecs(arg, amount);
                for (Entity entity : entities) {
                    if (entity instanceof Player) {
                        if (equipFlag) {
                            List<ItemSpec> armorSpecs = new ArrayList<>();
                            List<ItemSpec> otherSpecs = new ArrayList<>();
                            for (ItemSpec spec : specs) {
                                ItemStack probe = createItemStack(spec.itemString, sender, 1);
                                if (probe != null && Armor.isArmor(probe.getType())) {
                                    armorSpecs.add(spec);
                                } else {
                                    otherSpecs.add(spec);
                                }
                            }
                            if (!armorSpecs.isEmpty()) {
                                giveItemsToPlayerWithEquip((Player) entity, armorSpecs, sender, source);
                            }
                            for (ItemSpec spec : otherSpecs) {
                                int maxStack = getMaterialMaxStackSize(spec.itemString, sender);
                                if (maxStack <= 0) maxStack = 64;
                                if (spec.amount <= maxStack) {
                                    ItemStack item = createItemStack(spec.itemString, sender, spec.amount);
                                    if (item != null) giveItemToPlayer((Player) entity, item, sender, source);
                                } else {
                                    ItemStack template = createItemStack(spec.itemString, sender, 1);
                                    if (template != null) giveItemToPlayerBulk((Player) entity, template, spec.amount, sender, source);
                                }
                            }
                        } else {
                            for (ItemSpec spec : specs) {
                                int maxStack = getMaterialMaxStackSize(spec.itemString, sender);
                                if (maxStack <= 0) maxStack = 64;
                                if (spec.amount <= maxStack) {
                                    ItemStack item = createItemStack(spec.itemString, sender, spec.amount);
                                    if (item != null) {
                                        giveItemToPlayer((Player) entity, item, sender, source);
                                    }
                                } else {
                                    ItemStack template = createItemStack(spec.itemString, sender, 1);
                                    if (template != null) {
                                        giveItemToPlayerBulk((Player) entity, template, spec.amount, sender, source);
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (!entities.isEmpty()) {
                    return; // Successfully processed selector
                }
                
            } catch (Exception e) {
                Text.sendErrorMessage(sender, "invalid-selector", lang, "{selector}", targetSelector);
                return;
            }
            
            // If we get here, the selector didn't match any entities
            Text.sendErrorMessage(sender, "no-entities-found", lang);
            return;
        }
        
        // Handle regular player name
        Player target = Bukkit.getPlayer(targetSelector);
        if (target == null) {
            if (Bukkit.getOfflinePlayer(targetSelector).hasPlayedBefore()) {
                String targetName = Bukkit.getOfflinePlayer(targetSelector).getName();
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", targetName);
                return;
            }
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", targetSelector);
            return;
        }

        try {
            List<ItemSpec> specs = parseItemSpecs(arg, amount);
            if (equipFlag) {
                List<ItemSpec> armorSpecs = new ArrayList<>();
                List<ItemSpec> otherSpecs = new ArrayList<>();
                for (ItemSpec spec : specs) {
                    ItemStack probe = createItemStack(spec.itemString, sender, 1);
                    if (probe != null && Armor.isArmor(probe.getType())) {
                        armorSpecs.add(spec);
                    } else {
                        otherSpecs.add(spec);
                    }
                }
                if (!armorSpecs.isEmpty()) {
                    giveItemsToPlayerWithEquip(target, armorSpecs, sender, source);
                }
                for (ItemSpec spec : otherSpecs) {
                    int maxStack = getMaterialMaxStackSize(spec.itemString, sender);
                    if (maxStack <= 0) maxStack = 64;
                    if (spec.amount <= maxStack) {
                        ItemStack item = createItemStack(spec.itemString, sender, spec.amount);
                        if (item != null) giveItemToPlayer(target, item, sender, source);
                    } else {
                        ItemStack template = createItemStack(spec.itemString, sender, 1);
                        if (template != null) giveItemToPlayerBulk(target, template, spec.amount, sender, source);
                    }
                }
            } else {
                for (ItemSpec spec : specs) {
                    int maxStack = getMaterialMaxStackSize(spec.itemString, sender);
                    if (maxStack <= 0) maxStack = 64;
                    if (spec.amount <= maxStack) {
                        ItemStack item = createItemStack(spec.itemString, sender, spec.amount);
                        if (item == null) return; // Error handled in createItemStack
                        giveItemToPlayer(target, item, sender, source);
                    } else {
                        ItemStack template = createItemStack(spec.itemString, sender, 1);
                        if (template == null) return;
                        giveItemToPlayerBulk(target, template, spec.amount, sender, source);
                    }
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error processing give command for " + sender.getName() + ": " + e.getMessage(), e);
            Text.sendErrorMessage(sender, "contact-admin", lang);
        }
    }
    
    /**
     * Creates an ItemStack from the given item string
     * @param itemString The item string to parse (e.g., "diamond_sword", "stone;Sharpness:5")
     * @param sender The command sender (for error messages)
     * @param amount The amount of items to create
     * @return The created ItemStack, or null if there was an error
     */
    private ItemStack createItemStack(String itemString, CommandSender sender, int amount) {
        try {
            String[] parts = itemString.split(";", -1);
            String itemName = parts[0];
            
            // Handle NBT data if present (1.21.10+ format or legacy)
            if (itemName.contains("[")) {
                // New 1.21.10+ format: iron_sword[minecraft:unbreakable={boolean:['true']}]
                String[] nbtParts = itemName.split("\\[", 2);
                String materialName = nbtParts[0];
                
                Material material = Material.matchMaterial(materialName);
                if (material == null) {
                    Text.sendErrorMessage(sender, "invalid-item", lang, "{item}", materialName);
                    return null;
                }
                
                ItemStack item = new ItemStack(material, amount <= 0 ? material.getMaxStackSize() : Math.min(amount, material.getMaxStackSize()));
                
                // Extract NBT string
                String nbtString = nbtParts[1].substring(0, nbtParts[1].length() - 1);
                
                // Parse NBT tags (simplified)
                if (nbtString.contains("minecraft:unbreakable")) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setUnbreakable(true);
                        item.setItemMeta(meta);
                    }
                }
                // Add more NBT parsing as needed
                
                return item;
            } else if (itemName.startsWith("{")) {
                // Legacy format handling
                String materialName = itemString.contains("id:\"") ? 
                    itemString.split("id:\\\"")[1].split("\\\"")[0] : 
                    "stone";
                Material material = Material.matchMaterial(materialName);
                if (material == null) {
                    Text.sendErrorMessage(sender, "invalid-item", lang, "{item}", materialName);
                    return null;
                }
                return new ItemStack(material, amount <= 0 ? material.getMaxStackSize() : Math.min(amount, material.getMaxStackSize()));
            }
            
            // Check for custom item prefixes (ci: or custom:)
            if (itemName.toLowerCase().startsWith("ci:") || itemName.toLowerCase().startsWith("custom:")) {
                String prefix = itemName.toLowerCase().startsWith("ci:") ? "ci:" : "custom:";
                String customItemId = itemName.substring(prefix.length());
                
                CustomItem customItem = CustomItemRegistry.getInstance().getItem(customItemId);
                if (customItem == null) {
                    Text.sendErrorMessage(sender, "invalid-item", lang, "{item}", customItemId);
                    return null;
                }
                
                ItemStack item = customItem.createItemStack(amount <= 0 ? 1 : Math.min(amount, 64));
                
                // Apply any additional parameters (enchantments, etc.)
                if (parts.length > 1) {
                    // Preserve custommodeldata before modifications
                    int customModelData = 0;
                    if (item.hasItemMeta() && item.getItemMeta().hasCustomModelData()) {
                        customModelData = item.getItemMeta().getCustomModelData();
                    }
                    
                    Map<Enchantment, Integer> enchantments = Meta.getEnchantmentsFromParts(parts);
                    Meta.applyEnchantments(sender, item, enchantments);
                    
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        for (int i = 1; i < parts.length; i++) {
                            String part = parts[i];
                            if (part.toLowerCase().startsWith("name:")) {
                                Meta.applyDisplayName(meta, part.substring(5));
                            }
                            if (part.toLowerCase().startsWith("lore:")) {
                                List<String> loreLines = Arrays.asList(part.substring(5).split("\\\\n"));
                                meta.setLore(loreLines);
                            }
                        }
                        
                        // Handle flag: parameter (e.g., flag:UNBREAKABLE or flag:HIDE_ENCHANTS)
                        for (int i = 1; i < parts.length; i++) {
                            String part = parts[i];
                            if (part.toLowerCase().startsWith("flag:")) {
                                Flag.applyItemFlags(meta, part.substring(5));
                            }
                        }
                        
                        // Restore custommodeldata after all modifications
                        if (customModelData > 0) {
                            meta.setCustomModelData(customModelData);
                        }
                        
                        item.setItemMeta(meta);
                    }
                }
                
                return item;
            }
            
            // Regular item parsing
            Material material = getMaterial(itemName, sender);
            if (material == null) {
                return null; // Error message already sent in getMaterial
            }
            
            ItemStack item = new ItemStack(material, amount <= 0 ? material.getMaxStackSize() : Math.min(amount, material.getMaxStackSize()));
            
            // Apply alias parameters (potion effects, spawner entity, etc.) if this was a complex alias
            if (parsedAliasResult != null && parsedAliasResult.parameters != null) {
                // Handle potion items specially
                if (material == Material.POTION || material == Material.SPLASH_POTION ||
                    material == Material.LINGERING_POTION || material == Material.TIPPED_ARROW) {
                    // Create parts array for Potion.applyPotionMeta
                    String[] potionParts = ("potion;" + parsedAliasResult.parameters).split(";");
                    Potion.applyPotionMeta(sender, item, potionParts);
                } else if (material == Material.SPAWNER) {
                    // Handle spawner entity type from alias
                    String[] spawnerParts = ("spawner;" + parsedAliasResult.parameters).split(";");
                    Spawner.applySpawnerMeta(sender, item, spawnerParts);
                } else {
                    // Apply other alias parameters (enchantments, names, etc.)
                    applyAliasParameters(item, parsedAliasResult.parameters, sender);
                }
                // Clear the parsed result after use
                parsedAliasResult = null;
            }
            
            // Apply potion meta when user provides effect params directly (e.g. potion;poison, splash_potion;poison)
            // This fixes potion/splash_potion/tipped_arrow/lingering_potion with ;effect giving uncraftable potions
            if (parts.length > 1 && (material == Material.POTION || material == Material.SPLASH_POTION ||
                material == Material.LINGERING_POTION || material == Material.TIPPED_ARROW)) {
                Potion.applyPotionMeta(sender, item, parts);
            }
            
            // Apply spawner meta when user provides entity type (e.g. spawner;Axolotl)
            if (parts.length > 1 && material == Material.SPAWNER) {
                Spawner.applySpawnerMeta(sender, item, parts);
            }
            
            // Apply player head owner when user provides name (e.g. player_head;Notch)
            if (parts.length > 1 && material == Material.PLAYER_HEAD) {
                String owner = null;
                for (int i = 1; i < parts.length; i++) {
                    String part = parts[i];
                    if (part.toLowerCase().startsWith("owner:")) {
                        owner = part.substring(6).trim();
                        break;
                    }
                }
                if (owner == null) {
                    owner = parts[1].trim(); // First param is owner (e.g. player_head;Notch)
                }
                if (owner != null && !owner.isEmpty()) {
                    ItemStack skull = resolvePlayerHead(owner, sender);
                    if (skull != null) {
                        item.setItemMeta(skull.getItemMeta());
                    }
                }
            }
            
            // Apply any additional parameters (enchantments, flags, etc.)
            if (parts.length > 1) {
                Map<Enchantment, Integer> enchantments = Meta.getEnchantmentsFromParts(parts);
                Meta.applyEnchantments(sender, item, enchantments);
                
                // Handle other item meta (name, lore, flags, etc.)
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    for (int i = 1; i < parts.length; i++) {
                        String part = parts[i];
                        if (part.toLowerCase().startsWith("name:")) {
                            Meta.applyDisplayName(meta, part.substring(5));
                            break; // Only one name can be applied
                        }
                    }
                    for (int i = 1; i < parts.length; i++) {
                        String part = parts[i];
                        if (part.toLowerCase().startsWith("flag:")) {
                            Flag.applyItemFlags(meta, part.substring(5));
                        }
                    }
                    item.setItemMeta(meta);
                }
            }
            
            return item;
            
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error creating item stack: " + e.getMessage(), e);
            Text.sendErrorMessage(sender, "error-creating-item", lang);
            return null;
        }
    }
    
    /**
     * Gets the max stack size for an item string (for deciding bulk give vs single give).
     * Returns 64 for custom items or when material cannot be determined.
     */
    private int getMaterialMaxStackSize(String itemString, CommandSender sender) {
        String[] parts = itemString.split(";", -1);
        String itemName = parts[0];
        if (itemName.toLowerCase().startsWith("ci:") || itemName.toLowerCase().startsWith("custom:")) {
            return 64;
        }
        Material material = getMaterial(itemName, sender);
        return material != null ? material.getMaxStackSize() : 64;
    }

    private void giveItemToPlayer(Player target, ItemStack item, CommandSender sender, String source) {
        PlayerInventory inventory = target.getInventory();
        
        // Get the configuration values that will be needed throughout the method
        boolean dropOverflowItems = plugin.getConfig().getBoolean("give.drop-overflow-items", true);
        int maxItemsDropped = plugin.getConfig().getInt("give.max-items-dropped", 64);
        
        // Check if the player's inventory is completely full before attempting to give items
        // Only show inventory full message if we can't equip armor or drop items
        boolean isInventoryCompletelyFull = isInventoryCompletelyFull(inventory, item);
        boolean isArmor = Armor.isArmor(item.getType());
        
        if (isInventoryCompletelyFull && !isArmor && !dropOverflowItems) {
            // Only show inventory full message if we can't equip armor and drop-overflow-items is false
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
            // For armor: try armor slot BEFORE offhand so helmet goes to helmet slot, not offhand
            if (!leftover.isEmpty() && Armor.isArmor(item.getType())) {
                ItemStack firstLeftover = leftover.values().iterator().next();
                ItemStack armorItem = firstLeftover.clone();
                armorItem.setAmount(1); // Only equip one piece
                
                boolean equipped = Armor.tryEquipArmor(inventory, armorItem);
                if (equipped) {
                    wasEquipped = true;
                    addedAsArmor++;
                    if (armorItem.getAmount() >= firstLeftover.getAmount()) {
                        leftover.remove(leftover.keySet().iterator().next());
                    } else {
                        firstLeftover.setAmount(firstLeftover.getAmount() - 1);
                    }
                }
            }
            
            // Try offhand if there are still leftovers and offhand is empty
            if (!leftover.isEmpty() && inventory.getItemInOffHand().getType() == Material.AIR) {
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
                            Text.sendDebugLog(INFO, "Limited drops: requested=" + originalRequestedAmount + 
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
            // Use the display name if available (for spawners, potions, etc.)
            finalItemName = meta.getDisplayName();
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
                Text.sendDebugLog(INFO, "Success message calculation: inventory=" + addedToInventory + 
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
                Text.sendDebugLog(INFO, "Success message calculation: inventory=" + addedToInventory + 
                                      ", offhand=" + addedToOffhand + 
                                      ", armor=" + addedAsArmor + 
                                      ", totalGiven=" + totalGiven);
            }
        } else {
            // If dropping with no limit, count everything
            totalGiven = totalInventoryItems + droppedAmount;
            
            // Debug log
            if (plugin.getConfig().getBoolean("give.debug")) {
                Text.sendDebugLog(INFO, "Success message calculation: inventory=" + addedToInventory + 
                                      ", offhand=" + addedToOffhand + 
                                      ", armor=" + addedAsArmor + 
                                      ", droppedAmount=" + droppedAmount + 
                                      ", totalGiven=" + totalGiven);
            }
        }

        // Fire event for other plugins (e.g. WAYC Creative Tracker)
        if (totalGiven > 0) {
            ItemGiveEvent evt = new ItemGiveEvent(target, item, totalGiven, sender, source);
            org.bukkit.Bukkit.getPluginManager().callEvent(evt);
        }
    
    // Send success message (use lang key for sound; give.equipped has no {amount})
    if (sender.getName().equals(target.getName())) {
        String messageKey = wasEquipped ? "give.equipped" : "give.success";
        if (wasEquipped) {
            lang.sendMessage(sender, messageKey, "{item}", finalItemName);
        } else {
            lang.sendMessage(sender, messageKey, "{name}", "yourself", "{amount}", String.valueOf(totalGiven), "{item}", finalItemName);
        }
    } else {
        lang.sendMessage(sender, "give.success", "{name}", target.getName(), "{amount}", String.valueOf(totalGiven), "{item}", finalItemName);
        if (target.isOnline()) {
            String receiveKey = wasEquipped ? "give.equipped" : "give.receive";
            if (wasEquipped) {
                lang.sendMessage(target, receiveKey, "{item}", finalItemName);
            } else {
                lang.sendMessage(target, receiveKey, "{amount}", String.valueOf(totalGiven), "{item}", finalItemName);
            }
        }
        }
    }

    /**
     * Gives multiple items to a player when amount exceeds max stack size (e.g. 100 totems).
     * Respects item max stack size and config give.max-items-dropped for overflow.
     */
    private void giveItemToPlayerBulk(Player target, ItemStack template, int totalAmount, CommandSender sender, String source) {
        PlayerInventory inventory = target.getInventory();
        boolean dropOverflowItems = plugin.getConfig().getBoolean("give.drop-overflow-items", true);
        int maxItemsDropped = plugin.getConfig().getInt("give.max-items-dropped", 64);
        int maxStack = template.getType().getMaxStackSize();
        boolean isArmor = Armor.isArmor(template.getType());

        if (isInventoryCompletelyFull(inventory, template) && !isArmor && !dropOverflowItems) {
            Text.sendErrorMessage(sender, "inventory-full", lang);
            return;
        }

        int totalGiven = 0;
        int totalDropped = 0;
        boolean wasEquipped = false;
        int remainingToGive = totalAmount;
        boolean inventoryFull = false;

        while (remainingToGive > 0) {
            if (inventoryFull) {
                // Inventory/offhand/armor full - drop remaining chunks directly (respecting max-items-dropped)
                if (!dropOverflowItems) break;
                int dropBudget = maxItemsDropped >= 0 ? Math.max(0, maxItemsDropped - totalDropped) : Integer.MAX_VALUE;
                if (dropBudget <= 0) break;
                int chunkSize = Math.min(remainingToGive, Math.min(maxStack, dropBudget));
                ItemStack dropStack = template.clone();
                dropStack.setAmount(chunkSize);
                target.getWorld().dropItemNaturally(target.getLocation(), dropStack);
                totalGiven += chunkSize;
                totalDropped += chunkSize;
                remainingToGive -= chunkSize;
                continue;
            }

            int chunkSize = Math.min(remainingToGive, maxStack);
            ItemStack stack = template.clone();
            stack.setAmount(chunkSize);

            HashMap<Integer, ItemStack> leftover = inventory.addItem(stack);
            int added = chunkSize - (leftover.isEmpty() ? 0 : calculateTotalAmount(leftover.values()));
            totalGiven += added;
            remainingToGive -= chunkSize;

            if (!leftover.isEmpty()) {
                if (isArmor) {
                    ItemStack firstLeftover = leftover.values().iterator().next();
                    ItemStack armorItem = firstLeftover.clone();
                    armorItem.setAmount(1);
                    if (Armor.tryEquipArmor(inventory, armorItem)) {
                        wasEquipped = true;
                        totalGiven++;
                        leftover.remove(leftover.keySet().iterator().next());
                    }
                }
                if (!leftover.isEmpty() && inventory.getItemInOffHand().getType() == Material.AIR) {
                    ItemStack firstLeftover = leftover.values().iterator().next();
                    int amountForOffhand = Math.min(firstLeftover.getAmount(), Math.min(99, maxStack));
                    ItemStack offhandItem = firstLeftover.clone();
                    offhandItem.setAmount(amountForOffhand);
                    inventory.setItemInOffHand(offhandItem);
                    totalGiven += amountForOffhand;
                    if (amountForOffhand >= firstLeftover.getAmount()) {
                        leftover.remove(leftover.keySet().iterator().next());
                    } else {
                        firstLeftover.setAmount(firstLeftover.getAmount() - amountForOffhand);
                    }
                }
                if (!leftover.isEmpty() && dropOverflowItems) {
                    int dropBudget = maxItemsDropped >= 0 ? Math.max(0, maxItemsDropped - totalDropped) : Integer.MAX_VALUE;
                    for (Map.Entry<Integer, ItemStack> entry : leftover.entrySet()) {
                        if (dropBudget <= 0) break;
                        ItemStack drop = entry.getValue().clone();
                        int amountToDrop = Math.min(drop.getAmount(), dropBudget);
                        if (amountToDrop > 0) {
                            ItemStack dropStack = drop.clone();
                            dropStack.setAmount(amountToDrop);
                            target.getWorld().dropItemNaturally(target.getLocation(), dropStack);
                            totalDropped += amountToDrop;
                            totalGiven += amountToDrop;
                            dropBudget -= amountToDrop;
                        }
                    }
                }
                inventoryFull = true; // Next chunks go to drops
            }
        }

        String finalItemName;
        ItemMeta meta = template.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            finalItemName = meta.getDisplayName();
        } else {
            finalItemName = Meta.formatName(template.getType().name());
        }
        if (meta != null && meta.hasEnchants()) {
            finalItemName = lang.getFirstColorCode("give.equipped") + "enchanted " + finalItemName;
        }

        if (totalGiven > 0) {
            ItemGiveEvent evt = new ItemGiveEvent(target, template, totalGiven, sender, source);
            org.bukkit.Bukkit.getPluginManager().callEvent(evt);
        }

        if (sender.getName().equals(target.getName())) {
            String messageKey = wasEquipped ? "give.equipped" : "give.success";
            if (wasEquipped) {
                lang.sendMessage(sender, messageKey, "{item}", finalItemName);
            } else {
                lang.sendMessage(sender, messageKey, "{name}", "yourself", "{amount}", String.valueOf(totalGiven), "{item}", finalItemName);
            }
        } else {
            lang.sendMessage(sender, "give.success", "{name}", target.getName(), "{amount}", String.valueOf(totalGiven), "{item}", finalItemName);
            if (target.isOnline()) {
                String receiveKey = wasEquipped ? "give.equipped" : "give.receive";
                if (wasEquipped) {
                    lang.sendMessage(target, receiveKey, "{item}", finalItemName);
                } else {
                    lang.sendMessage(target, receiveKey, "{amount}", String.valueOf(totalGiven), "{item}", finalItemName);
                }
            }
        }
    }

    /**
     * Gives items with -e flag: equips armor first, then populates inventory/drops.
     * Sends one message at end. For multi-equip uses "items" placeholder.
     */
    private void giveItemsToPlayerWithEquip(Player target, List<ItemSpec> specs, CommandSender sender, String source) {
        int totalGiven = 0;
        int equippedCount = 0;
        String singleEquippedName = null;
        PlayerInventory inventory = target.getInventory();
        boolean dropOverflowItems = plugin.getConfig().getBoolean("give.drop-overflow-items", true);
        int maxItemsDropped = plugin.getConfig().getInt("give.max-items-dropped", 64);
        int[] totalDroppedSoFar = {0};

        for (ItemSpec spec : specs) {
            ItemStack item = createItemStack(spec.itemString, sender, spec.amount);
            if (item == null) return;
            int toEquip = 1;
            int toGive = Math.max(0, spec.amount - toEquip);
            ItemStack equipPiece = item.clone();
            equipPiece.setAmount(1);
            boolean equipped = Armor.tryEquipArmor(inventory, equipPiece);
            if (equipped) {
                equippedCount++;
                singleEquippedName = formatItemName(equipPiece);
                totalGiven++;
                if (target.isOnline()) {
                    ItemGiveEvent evt = new ItemGiveEvent(target, equipPiece, 1, sender, source);
                    org.bukkit.Bukkit.getPluginManager().callEvent(evt);
                }
            } else {
                toGive = spec.amount;
            }
            if (toGive > 0) {
                ItemStack remainder = item.clone();
                remainder.setAmount(toGive);
                int dropBudget = maxItemsDropped >= 0 ? Math.max(0, maxItemsDropped - totalDroppedSoFar[0]) : Integer.MAX_VALUE;
                GiveResult r = giveItemToPlayerSilent(target, remainder, dropOverflowItems, dropBudget, totalDroppedSoFar);
                totalGiven += r.totalGiven;
                if (r.totalGiven > 0) {
                    ItemGiveEvent evt = new ItemGiveEvent(target, remainder, r.totalGiven, sender, source);
                    org.bukkit.Bukkit.getPluginManager().callEvent(evt);
                }
            }
        }

        if (totalGiven == 0) return;

        // Only show equipped message when we actually equipped (populated empty armor slot)
        boolean showEquippedMessage = equippedCount > 0;
        String itemDisplay = equippedCount > 1 ? "items" : (singleEquippedName != null ? singleEquippedName : "items");
        String messageKey = showEquippedMessage ? (equippedCount > 1 ? "give.equipped-multiple" : "give.equipped") : "give.success";
        String receiveKey = showEquippedMessage ? (equippedCount > 1 ? "give.equipped-multiple" : "give.equipped") : "give.receive";

        if (sender.getName().equals(target.getName())) {
            if (showEquippedMessage) {
                lang.sendMessage(sender, messageKey, "{item}", itemDisplay);
            } else {
                lang.sendMessage(sender, messageKey, "{name}", "yourself", "{amount}", String.valueOf(totalGiven), "{item}", itemDisplay);
            }
        } else {
            lang.sendMessage(sender, "give.success", "{name}", target.getName(), "{amount}", String.valueOf(totalGiven), "{item}", itemDisplay);
            if (target.isOnline()) {
                if (showEquippedMessage) {
                    lang.sendMessage(target, receiveKey, "{item}", itemDisplay);
                } else {
                    lang.sendMessage(target, receiveKey, "{amount}", String.valueOf(totalGiven), "{item}", itemDisplay);
                }
            }
        }
    }

    private String formatItemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        String name = Meta.formatName(item.getType().name());
        if (meta != null && meta.hasEnchants()) {
            name = lang.getFirstColorCode("give.equipped") + "enchanted " + name;
        }
        return name;
    }

    /**
     * Gives item to player without sending success message. Returns total given.
     * Used by giveItemsToPlayerWithEquip for remainder after equipping.
     * @param totalDroppedSoFar int[1] to accumulate drops (respects config max-items-dropped across calls)
     */
    private GiveResult giveItemToPlayerSilent(Player target, ItemStack item,
            boolean dropOverflowItems, int dropBudget, int[] totalDroppedSoFar) {
        PlayerInventory inventory = target.getInventory();
        int originalAmount = item.getAmount();
        HashMap<Integer, ItemStack> leftover = inventory.addItem(item);
        int added = originalAmount - (leftover.isEmpty() ? 0 : calculateTotalAmount(leftover.values()));
        int totalGiven = added;

        if (!leftover.isEmpty()) {
            if (inventory.getItemInOffHand().getType() == Material.AIR) {
                ItemStack first = leftover.values().iterator().next();
                int amt = Math.min(first.getAmount(), Math.min(99, first.getType().getMaxStackSize()));
                ItemStack off = first.clone();
                off.setAmount(amt);
                inventory.setItemInOffHand(off);
                totalGiven += amt;
                if (amt >= first.getAmount()) {
                    leftover.remove(leftover.keySet().iterator().next());
                } else {
                    first.setAmount(first.getAmount() - amt);
                }
            }
            if (!leftover.isEmpty() && dropOverflowItems && dropBudget > 0) {
                for (Map.Entry<Integer, ItemStack> e : leftover.entrySet()) {
                    if (dropBudget <= 0) break;
                    ItemStack drop = e.getValue().clone();
                    int amt = Math.min(drop.getAmount(), dropBudget);
                    if (amt > 0) {
                        ItemStack d = drop.clone();
                        d.setAmount(amt);
                        target.getWorld().dropItemNaturally(target.getLocation(), d);
                        totalGiven += amt;
                        dropBudget -= amt;
                        if (totalDroppedSoFar != null) {
                            totalDroppedSoFar[0] += amt;
                        }
                    }
                }
            }
        }
        return new GiveResult(totalGiven, 0, null);
    }

    /**
     * Enhanced method to parse complex aliases and apply item effects
     * @param aliasName The alias name to parse
     * @param sender Command sender for error messages
     * @return ParsedAliasResult containing material and parameters, or null if invalid
     */
    private ParsedAliasResult parseComplexAlias(String aliasName, CommandSender sender) {
        // First check if this is a direct material
        try {
            Material directMaterial = Material.matchMaterial(aliasName);
            if (directMaterial != null && Item.isGiveable(directMaterial)) {
                return new ParsedAliasResult(directMaterial, null);
            }
        } catch (IllegalArgumentException ignored) {}

        // Check for alias in itemdb.yml
        String fullAlias = Alias.getAlias(aliasName);
        if (fullAlias != null) {
            // Check if this is a complex alias with parameters
            if (fullAlias.contains(";")) {
                String materialPart = Alias.getMaterialPartFromAlias(aliasName);
                String parameters = Alias.getParametersFromAlias(aliasName);

                if (materialPart != null) {
                    try {
                        Material material = Material.matchMaterial(materialPart);
                        if (material != null && Item.isGiveable(material)) {
                            return new ParsedAliasResult(material, parameters);
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            } else {
                // Simple alias without parameters
                try {
                    Material material = Material.matchMaterial(fullAlias);
                    if (material != null && Item.isGiveable(material)) {
                        return new ParsedAliasResult(material, null);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // Check legacy ID mappings
        Material legacyMaterial = LegacyID.getMaterialFromLegacyId(aliasName);
        if (legacyMaterial != null && Item.isGiveable(legacyMaterial)) {
            String legacyParams = LegacyID.getExtraDataForLegacyId(aliasName);
            if (legacyParams != null && legacyParams.isEmpty()) {
                legacyParams = null;
            }
            return new ParsedAliasResult(legacyMaterial, legacyParams);
        }

        return null;
    }

    /**
     * Apply complex alias parameters to an ItemStack
     * @param item The ItemStack to modify
     * @param parameters The parameters string from the alias
     * @param sender Command sender for error messages
     * @return true if parameters were applied successfully
     */
    private boolean applyAliasParameters(ItemStack item, String parameters, CommandSender sender) {
        if (parameters == null || parameters.isEmpty()) {
            return true;
        }

        String[] parts = parameters.split(";");

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();

            if (part.toLowerCase().startsWith("name:")) {
                // Custom display name
                String nameValue = part.substring(5);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    Meta.applyDisplayName(meta, nameValue);
                    item.setItemMeta(meta);
                }
            } else if (part.toLowerCase().startsWith("owner:")) {
                // Player head owner (explicit syntax)
                if (item.getType() == Material.PLAYER_HEAD) {
                    String ownerName = part.substring(6).trim();
                    ItemStack skull = resolvePlayerHead(ownerName, sender);
                    if (skull != null) {
                        item.setItemMeta(skull.getItemMeta());
                    }
                }
            } else if (item.getType() == Material.PLAYER_HEAD && !part.toLowerCase().startsWith("name:")) {
                // Player head owner (implicit syntax - no owner: prefix needed)
                ItemStack skull = resolvePlayerHead(part.trim(), sender);
                if (skull != null) {
                    item.setItemMeta(skull.getItemMeta());
                }
            } else if (isEnchantmentParameter(part)) {
                // Enchantment (format: ENCHANTMENT:LEVEL)
                String[] enchantmentParts = part.split(":");
                if (enchantmentParts.length == 2) {
                    try {
                        org.bukkit.enchantments.Enchantment enchantment = org.bukkit.enchantments.Enchantment.getByName(enchantmentParts[0].toUpperCase());
                        if (enchantment != null) {
                            int level = Integer.parseInt(enchantmentParts[1]);
                            item.addUnsafeEnchantment(enchantment, level);
                        }
                    } catch (NumberFormatException e) {
                        Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "enchantment level");
                        return false;
                    } catch (IllegalArgumentException e) {
                        Text.sendErrorMessage(sender, "invalid", lang, "{arg}", "enchantment level");
                        return false;
                    }
                }
            } else if (isPotionEffectParameter(part)) {
                // Potion effect - use the existing Potion.applyPotionMeta method
                // Supports: POTION, SPLASH_POTION, LINGERING_POTION, TIPPED_ARROW
                if (item.getType() == Material.POTION || item.getType() == Material.SPLASH_POTION ||
                    item.getType() == Material.LINGERING_POTION || item.getType() == Material.TIPPED_ARROW) {
                    // Create parts array for Potion.applyPotionMeta
                    // Format: ["potion", "effect1", "duration1", "amplifier1", "effect2", "duration2", "amplifier2", ...]
                    String[] potionParts = new String[1 + (part.split(";").length)];
                    potionParts[0] = "potion";

                    String[] effectParts = part.split(";");

                    for (int j = 0; j < effectParts.length; j++) {
                        String[] effectPartSplit = effectParts[j].split(":");
                        if (effectPartSplit.length == 3) {
                            potionParts[j + 1] = effectPartSplit[0] + ":" + effectPartSplit[1] + ":" + effectPartSplit[2];
                        } else {
                            Text.sendErrorMessage(sender, "give.invalid-potion-effect", lang, "{arg}", effectParts[j]);
                            return false;
                        }
                    }

                    Potion.applyPotionMeta(sender, item, potionParts);
                }
            }
        }

        return true;
    }

    /**
     * Check if a parameter is an enchantment
     */
    private boolean isEnchantmentParameter(String part) {
        return part.contains(":") && !part.toLowerCase().startsWith("name:") && !part.toLowerCase().startsWith("owner:");
    }

    /**
     * Check if a parameter is a potion effect
     */
    private boolean isPotionEffectParameter(String part) {
        return part.contains(";") && !part.toLowerCase().startsWith("name:") && !part.toLowerCase().startsWith("owner:");
    }

    private Material getMaterial(String itemName, CommandSender sender) {
        Material material = null;
        String baseItemName = itemName;

        // Use enhanced alias parsing for complex syntaxes
        ParsedAliasResult parsedResult = parseComplexAlias(baseItemName, sender);
        if (parsedResult != null) {
            // Store the parsed result for later use
            parsedAliasResult = parsedResult;
            return parsedResult.material;
        }

        // First check if the input is already a valid Material enum name
        try {
            material = Material.matchMaterial(baseItemName);
            if (material != null && Item.isGiveable(material)) {
                return material;
            }
        } catch (IllegalArgumentException ignored) {}

        // Try legacy ID resolution for numeric inputs (like "443")
        try {
            int numericId = Integer.parseInt(baseItemName);
            material = LegacyID.getMaterialFromLegacyId(baseItemName);
            if (material != null && Item.isGiveable(material)) {
                return material;
            }
        } catch (NumberFormatException ignored) {
            // Not a numeric ID, continue with other resolution methods
        }

        // If not a direct material, try alias resolution
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

        // Try to resolve the input as an alias (get the full complex alias if it exists)
        String fullAlias = Alias.getAlias(baseItemName);
        if (fullAlias != null) {
            // Check if this is a complex alias with parameters
            if (fullAlias.contains(";")) {
                // For complex aliases, use the material part for Material lookup
                String complexMaterialPart = Alias.getMaterialPartFromAlias(baseItemName);
                if (complexMaterialPart != null) {
                    material = Material.matchMaterial(complexMaterialPart);
                    if (material != null && Item.isGiveable(material)) {
                        return material;
                    }
                }
            } else {
                // For simple aliases, use the full alias directly
                material = Material.matchMaterial(fullAlias);
                if (material != null && Item.isGiveable(material)) {
                    return material;
                }
            }
        }

        // Then try direct material lookup (case-insensitive) - fallback
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
     * Resolves a player head by owner name. Uses Bukkit PlayerProfile API first (EssentialsX-style),
     * then OfflinePlayer, Mojang HTTP, Minecraft-Heads.
     */
    private ItemStack resolvePlayerHead(String ownerName, CommandSender sender) {
        ItemStack head = Skull.fetchPlayerHeadViaProfile(ownerName);
        if (head != null) return head;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(ownerName);
        if (offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline()) {
            head = Skull.createPlayerHead(ownerName);
            if (head != null) return head;
        }
        head = Skull.fetchPlayerHeadFromMojang(ownerName);
        if (head != null) return head;
        return Skull.fetchPlayerHeadFromMinecraftHeads(ownerName);
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
        if (material.name().endsWith("_HELMET") && Armor.isArmorSlotEmpty(inventory.getHelmet())) {
            return false; // Helmet slot is empty
        } else if (material.name().endsWith("_CHESTPLATE") && Armor.isArmorSlotEmpty(inventory.getChestplate())) {
            return false; // Chestplate slot is empty
        } else if (material.name().endsWith("_LEGGINGS") && Armor.isArmorSlotEmpty(inventory.getLeggings())) {
            return false; // Leggings slot is empty
        } else if (material.name().endsWith("_BOOTS") && Armor.isArmorSlotEmpty(inventory.getBoots())) {
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
}