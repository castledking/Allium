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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.Map;
import java.util.Locale;

public class Enchant implements CommandExecutor {

    private final PluginStart plugin;
    private final Lang lang;

    public Enchant(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player senderPlayer = sender instanceof Player player ? player : null;
        boolean canEnchantOthers = senderPlayer == null || sender.hasPermission("allium.enchant.other");
        boolean allowUnsafe = plugin.getConfig().getBoolean("allow-unsafe-enchants", false)
                && (senderPlayer == null || sender.hasPermission("allium.enchant.unsafe"));

        if (senderPlayer != null && !senderPlayer.hasPermission("allium.enchant")) {
            Text.sendErrorMessage(senderPlayer, "no-permission", lang, "{cmd}", "enchant");
            return true;
        }

        if (senderPlayer == null) {
            if (args.length < 3) {
                sendUsage(sender, true);
                return true;
            }
        } else if (args.length < 2) {
            sendUsage(sender, false);
            return true;
        } else if (args.length >= 3 && !canEnchantOthers) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "enchant other");
            return true;
        }

        Player target = senderPlayer;
        int enchantArgIndex = 0;
        if (senderPlayer == null || (canEnchantOthers && args.length >= 3)) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                Text.sendErrorMessage(sender, "invalid-player", lang, "{player}", args[0]);
                return true;
            }
            enchantArgIndex = 1;
        }

        if (args.length <= enchantArgIndex + 1) {
            sendUsage(sender, target != senderPlayer);
            return true;
        }

        ItemStack item = target.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            if (target == senderPlayer) {
                Text.sendErrorMessage(sender, "hold-item", lang, "{modify}", label);
            } else {
                sender.sendMessage(Text.colorize("&c" + target.getName() + " must be holding an item to enchant it."));
            }
            return true;
        }

        Enchantment enchantment = Meta.getEnchantment(args[enchantArgIndex].toLowerCase(Locale.ROOT));
        if (enchantment == null) {
            Text.sendErrorMessage(sender, "invalid", lang, "{syntax}", "Enchantment not found.");
            return true;
        }

        int level;
        try {
            level = Integer.parseInt(args[enchantArgIndex + 1]);
        } catch (NumberFormatException e) {
            Text.sendErrorMessage(sender, "invalid", lang, "{syntax}", "Level must be a number.");
            return true;
        }

        Map<Enchantment, Integer> toApply = Map.of(enchantment, level);

        Meta.applyEnchantments(sender, item, toApply, allowUnsafe);

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

            if (target != senderPlayer) {
                successMessage = "&aSuccessfully enchanted &6" + target.getName() + "'s " + itemName
                        + "&a with &e" + enchantName + " " + level + "&a.";
            }

            lang.sendMessage(sender, "enchant.success", successMessage);
        }
        return true;
    }

    private void sendUsage(CommandSender sender, boolean targetMode) {
        String usage = lang.get("command-usage")
                .replace("{cmd}", "enchant")
                .replace("{args}", targetMode ? "<player> <enchantment> <level>" : "<enchantment> <level>");
        lang.sendMessage(sender, usage);
    }

    private static boolean wasEnchantApplied(ItemStack item, Enchantment enchantment) {
        if (item.getType() == Material.ENCHANTED_BOOK) {
            if (!(item.getItemMeta() instanceof EnchantmentStorageMeta bookMeta)) return false;
            return bookMeta.hasStoredEnchant(enchantment);
        }
        return item.containsEnchantment(enchantment);
    }
}
