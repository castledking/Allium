package net.survivalfun.core.commands.utils.items;

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

        if (args.length < 2) {
            player.sendMessage(lang.get("command-usage")
            .replace("{cmd}", "/enchant")
            .replace("{args}", "<enchantment> <level>"));
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
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
            player.sendMessage(lang.get("enchant.invalid-enchant"));
            return true;
        }

        boolean isBook = item.getType().name().contains("ENCHANTED_BOOK");
        boolean allowUnsafe = PluginStart.getInstance().getConfig().getBoolean("allow-unsafe-enchants", false);

        // Check if enchantment can be applied
        if (!Meta.canEnchant(item, enchantment, level)) {
            String itemName = item.getType().toString().toLowerCase().replace("_", " ");
            String enchantNameKey = enchantment.getKey().getKey().replace("_", " ");
            
            if (level > enchantment.getMaxLevel()) {
                sender.sendMessage(Text.parseColors("&c" + enchantNameKey + " has a max level of " + enchantment.getMaxLevel()));
            } else if (!enchantment.canEnchantItem(item)) {
                sender.sendMessage(Text.parseColors("&c" + enchantNameKey + " can't be applied to " + itemName));
            } else {
                sender.sendMessage(Text.parseColors("&c" + enchantNameKey + " conflicts with existing enchantments"));
            }
            return true;
        }

        // Apply the enchantment
        if (isBook) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            meta.addStoredEnchant(enchantment, level, allowUnsafe);
            item.setItemMeta(meta);
        } else {
            item.addUnsafeEnchantment(enchantment, level);
        }

        player.sendMessage(lang.get("enchant.success")
        .replace("{item}", item.getType().name())
        .replace("{enchant}", enchantment.getKey().getKey()));
        return true;
    }
}
