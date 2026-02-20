package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Meta;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;

import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.Map;

public class Enchant implements CommandExecutor {

    private final PluginStart plugin;
    private final Lang lang;

    public Enchant(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        Player player = (Player) sender;

        // Base permission required for any enchant usage
        if (!player.hasPermission("allium.enchant")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "enchant");
            return true;
        }

        if (args.length < 2) {
            String usage = lang.get("command-usage")
                .replace("{cmd}", "enchant")
                .replace("{args}", "<enchantment> <level>");
            lang.sendMessage(player, usage);
            return true;
        }

        ItemStack originalItem = player.getInventory().getItemInMainHand().clone();
        ItemStack item = originalItem;
        if (item == null || item.getType().isAir()) {
            Text.sendErrorMessage(player, "hold-item", lang, "{modify}", label);
            return true;
        }

        String enchantName = args[0].toLowerCase();
        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            Text.sendErrorMessage(player, "invalid", lang, "{arg}", "level");
            return true;
        }

        // Get the enchantment
        Enchantment enchantment = Meta.getEnchantment(enchantName);
        
        if (enchantment == null) {
            Text.sendErrorMessage(player, "invalid", lang, "{arg}", "enchantment");
            return true;
        }

        boolean isBook = item.getType().name().contains("ENCHANTED_BOOK");
        boolean allowUnsafe = PluginStart.getInstance().getConfig().getBoolean("allow-unsafe-enchants", false);
        boolean hasUnsafePerm = player.hasPermission("allium.enchant.unsafe");

        // Check if enchantment can be applied
        if (!Meta.canEnchant(item, enchantment, level)) {
            // If player has unsafe permission and config allows, proceed with unsafe application
            if (!(allowUnsafe && hasUnsafePerm)) {
                String itemName = toTitleCase(originalItem.getType().toString().toLowerCase().replace("_", " "));
                String enchantNameKey = toTitleCase(enchantment.getKey().getKey().toLowerCase().replace("_", " "));
                
                if (level > enchantment.getMaxLevel()) {
                    Text.sendErrorMessage(sender, "give.unsafe-level", lang, 
                        "{enchant}", enchantNameKey,
                        "{maxLevel}", String.valueOf(enchantment.getMaxLevel())
                    );
                } else if (!enchantment.canEnchantItem(item)) {
                    Text.sendErrorMessage(sender, "give.unsafe-enchant", lang,
                        "{enchant}", enchantNameKey,
                        "{item}", itemName
                    );
                } else {
                    Text.sendErrorMessage(sender, "give.conflicting-enchants", lang,
                        "{enchant}", enchantNameKey,
                        "{item}", itemName
                    );
                }
                return true;
            }
            // else: allowed to apply unsafely
        }

        // Apply the enchantment
        if (isBook) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            meta.addStoredEnchant(enchantment, level, allowUnsafe && hasUnsafePerm);
            item.setItemMeta(meta);
        } else {
            if (!Meta.canEnchant(item, enchantment, level)) {
                // Only reach here if unsafe is permitted
                item.addUnsafeEnchantment(enchantment, level);
            } else {
                // Safe path
                item.addEnchantment(enchantment, level);
            }
        }
        
        // Set the modified item back to player's main hand
        player.getInventory().setItemInMainHand(item);
        String itemName = toTitleCase(originalItem.getType().toString().toLowerCase().replace("_", " "));
        enchantName = toTitleCase(enchantment.getKey().getKey().toLowerCase().replace("_", " "));

        String successMessage = lang.get("enchant.success")
            .replace("{item}", (originalItem.getItemMeta() != null && originalItem.getItemMeta().getDisplayName() != null) ? originalItem.getItemMeta().getDisplayName() : itemName)
            .replace("{enchant}", enchantName)
            .replace("{level}", String.valueOf(level));
        lang.sendMessage(player, successMessage);
        return true;
    }
    
    private static String toTitleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                cap = true;
                sb.append(c);
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
