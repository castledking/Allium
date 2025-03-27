package net.survivalfun.core.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class Give implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Command format: /give <player> <item> [amount] or /i <item> [amount]

        // Ensure command and arguments are minimally valid
        if (args.length < 1 && label.equalsIgnoreCase("i") || args.length < 2 && !label.equalsIgnoreCase("i")) {
            sender.sendMessage("§cUsage: " + (label.equalsIgnoreCase("i") ? "/i <item> [amount]" : "/give <player> <item> [amount]"));
            return true;
        }

        String materialName = args[label.equalsIgnoreCase("i") ? 0 : 1]; // Item name depends on the command
        int amount = 1; // Default amount

        // When using /i and no amount is provided, select defaults for stackable/unstackable
        if (label.equalsIgnoreCase("i")) {
            // Handle /i command without requiring an amount
            Material material = Material.getMaterial(materialName.toUpperCase());
            if (material == null) {
                sender.sendMessage("§cInvalid item name! Make sure it's a valid Material.");
                return true;
            }

            if (args.length == 1) {
                // No amount specified, default stack sizes based on max stack size
                if (material.getMaxStackSize() > 1) {
                    amount = 64; // Default stack amount for stackable items
                } else {
                    amount = 1;  // Default stack amount for unstackable items
                }
            } else {
                try {
                    amount = Integer.parseInt(args[1]); // Parse amount if provided
                    if (amount <= 0) {
                        sender.sendMessage("§cThe amount must be greater than 0.");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid amount specified! Please enter a valid number.");
                    return true;
                }
            }
        }
        else {
            // For /give command, parse or default the amount
            if (args.length > 2) {
                try {
                    amount = Integer.parseInt(args[2]);
                    if (amount <= 0) {
                        sender.sendMessage("§cThe amount must be greater than 0.");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid amount specified! Please enter a valid number.");
                    return true;
                }
            }
        }

        // Validate material
        Material material = Material.getMaterial(materialName.toUpperCase());
        if (material == null) {
            sender.sendMessage("§cInvalid item name! Make sure it's a valid Material.");
            return true;
        }

        // Determine target player
        Player target;
        if (label.equalsIgnoreCase("i")) {
            // Sender is the target when using /i
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            target = (Player) sender;
        } else {
            // For /give, obtain the specified target player
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found! Make sure the player is online.");
                return true;
            }
        }


        // Create the ItemStack
        ItemStack item = new ItemStack(material, amount);

        // Give as many items as possible and calculate how many were actually added
        int remainingItems = giveItems(target, item);

        // Calculate how many items were actually received
        int givenAmount = amount - remainingItems;

        // Notify recipient and sender
        if (givenAmount > 0) {
            target.sendMessage("§aYou have been given §e" + givenAmount + " §aof §e" + material.name().toLowerCase() + "§a!");
        }
        if (!target.equals(sender) && givenAmount > 0) {
            sender.sendMessage("§aGave §e" + givenAmount + " §aof §e" + material.name().toLowerCase() + " §ato §e" + target.getName() + "§a!");
        }

        // Notify if some items couldn't be given due to lack of space
        if (remainingItems > 0) {
            sender.sendMessage("§cCould not give §e" + remainingItems + " §cof §e" + material.name().toLowerCase() + " §cto §e" + target.getName() + "§c. Not enough space!");
        }

        return true;
    }

    /**
     * Adds as many items as possible to the player's inventory, prioritizing the main inventory.
     * If the player's inventory is full and the item is armor, places it in an empty armor slot.
     *
     * @param player The player to give items to.
     * @param item   The item to add to their inventory.
     * @return The number of items that could not be added.
     */
    private int giveItems(Player player, ItemStack item) {
        // Get the player's inventory
        Inventory inventory = player.getInventory();
        int remaining = item.getAmount(); // Total items left to distribute
        int maxStackSize = item.getMaxStackSize(); // Max stack size for this item

        // Step 1: Try to add items to the main inventory (slots 0–35)
        for (int i = 0; i < 36; i++) {
            if (remaining <= 0) {
                break; // Stop if all items have been distributed
            }

            ItemStack slot = inventory.getItem(i); // Current slot

            if (slot == null || slot.getType() == Material.AIR) {
                // Empty slot: Add as much as possible
                int toAdd = Math.min(remaining, maxStackSize);
                inventory.setItem(i, new ItemStack(item.getType(), toAdd));
                remaining -= toAdd;
            } else if (slot.isSimilar(item)) {
                // Existing stack of the same material and metadata
                int spaceLeft = maxStackSize - slot.getAmount();
                if (spaceLeft > 0) {
                    int toAdd = Math.min(remaining, spaceLeft);
                    slot.setAmount(slot.getAmount() + toAdd);
                    remaining -= toAdd;
                }
            }
        }

        // Step 2: If items are left and the item is armor, try placing it into an armor slot
        if (remaining > 0 && isArmor(item.getType())) {
            remaining = addToArmorSlots(player, item, remaining);
        }

        return remaining; // Return the number of items that couldn't be added
    }

    /**
     * Checks if the given material is a type of armor.
     *
     * @param material The material to check.
     * @return True if the material is armor, false otherwise.
     */
    private boolean isArmor(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") ||
                name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    /**
     * Attempts to add armor to the appropriate empty armor slot of the player using getArmorContents.
     *
     * @param player    The player to give the armor to.
     * @param item      The armor item to add.
     * @param remaining The number of armor items remaining to be added.
     * @return The remaining number of items after attempting to fill armor slots.
     */
    private int addToArmorSlots(Player player, ItemStack item, int remaining) {
        if (remaining <= 0) {
            return 0;
        }

        // Get player's inventory
        PlayerInventory inventory = player.getInventory();

        // Get an array of the current armor contents
        ItemStack[] armorContents = inventory.getArmorContents();

        // Map item type to armor slot index (0 = boots, 1 = leggings, 2 = chestplate, 3 = helmet)
        int slotIndex = -1;
        switch (item.getType()) {
            case DIAMOND_BOOTS:
            case IRON_BOOTS:
            case GOLDEN_BOOTS:
            case LEATHER_BOOTS:
            case CHAINMAIL_BOOTS:
            case NETHERITE_BOOTS:
                slotIndex = 0; // Boots slot
                break;

            case DIAMOND_LEGGINGS:
            case IRON_LEGGINGS:
            case GOLDEN_LEGGINGS:
            case LEATHER_LEGGINGS:
            case CHAINMAIL_LEGGINGS:
            case NETHERITE_LEGGINGS:
                slotIndex = 1; // Leggings slot
                break;

            case DIAMOND_CHESTPLATE:
            case IRON_CHESTPLATE:
            case GOLDEN_CHESTPLATE:
            case LEATHER_CHESTPLATE:
            case CHAINMAIL_CHESTPLATE:
            case NETHERITE_CHESTPLATE:
                slotIndex = 2; // Chestplate slot
                break;

            case DIAMOND_HELMET:
            case IRON_HELMET:
            case GOLDEN_HELMET:
            case LEATHER_HELMET:
            case CHAINMAIL_HELMET:
            case NETHERITE_HELMET:
                slotIndex = 3; // Helmet slot
                break;

            default:
                return remaining; // Return the remaining count if not an armor piece
        }

        // Check if the corresponding slot is empty
        if (armorContents[slotIndex] == null || armorContents[slotIndex].getType() == Material.AIR) {
            // Place one armor item into the slot
            armorContents[slotIndex] = new ItemStack(item.getType(), 1);
            remaining--; // Reduce remaining count
        }

        // Update the player's armor contents
        inventory.setArmorContents(armorContents);

        return remaining; // Return the remaining items not added
    }



}