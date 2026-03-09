package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Meta;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.Material;
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

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            Text.sendErrorMessage(player, "hold-item", lang, "{modify}", label);
            return true;
        }

        Enchantment enchantment = Meta.getEnchantment(args[0].toLowerCase());
        if (enchantment == null) {
            Text.sendErrorMessage(player, "invalid", lang, "{syntax}", "Enchantment not found.");
            return true;
        }

        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            Text.sendErrorMessage(player, "invalid", lang, "{syntax}", "Level must be a number.");
            return true;
        }

        boolean allowUnsafe = plugin.getConfig().getBoolean("allow-unsafe-enchants", false)
            && player.hasPermission("allium.enchant.unsafe");
        Map<Enchantment, Integer> toApply = Map.of(enchantment, level);

        Meta.applyEnchantments(player, item, toApply, allowUnsafe);

        boolean applied = wasEnchantApplied(item, enchantment);
        if (applied) {
            String itemName = Meta.formatName(item.getType().name());
            if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
                itemName = item.getItemMeta().getDisplayName();
            }
            String enchantName = Meta.formatName(enchantment.getKey().getKey());
            String successMessage = lang.get("enchant.success")
                .replace("{item}", itemName)
                .replace("{enchant}", enchantName)
                .replace("{level}", String.valueOf(level));
            lang.sendMessage(player, "enchant.success", successMessage);
        }
        return true;
    }

    private static boolean wasEnchantApplied(ItemStack item, Enchantment enchantment) {
        if (item.getType() == Material.ENCHANTED_BOOK) {
            if (!(item.getItemMeta() instanceof EnchantmentStorageMeta bookMeta)) return false;
            return bookMeta.hasStoredEnchant(enchantment);
        }
        return item.containsEnchantment(enchantment);
    }
}
