package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.util.SetExpFix;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * /xpbottle <amount|amountL> [player]
 * amount = raw XP, 10L = 10 levels. Range (e.g. 300-500) only when target player is used; requires allium.xpbottle.other.
 */
public class XpBottleCommand implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final NamespacedKey xpAmountKey;
    private final NamespacedKey xpSignerKey;

    public XpBottleCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.xpAmountKey = new NamespacedKey(plugin, "xp_voucher_amount");
        this.xpSignerKey = new NamespacedKey(plugin, "xp_voucher_signer");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /xpbottle <amount|amountL> [player]");
            return true;
        }

        boolean hasPlayerArg = args.length >= 2;
        boolean allowRange = hasPlayerArg && (sender.hasPermission("allium.xpbottle.other") || !(sender instanceof Player));

        int xpAmount;
        try {
            xpAmount = parseXpAmount(args[0], allowRange);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c" + e.getMessage());
            return true;
        }

        if (xpAmount <= 0) {
            sender.sendMessage("§cAmount must be positive.");
            return true;
        }

        Player target;
        Player creator = sender instanceof Player ? (Player) sender : null;
        boolean selfWithdraw = !hasPlayerArg;

        if (hasPlayerArg) {
            if (!sender.hasPermission("allium.xpbottle.other")) {
                sender.sendMessage("§cYou don't have permission to give XP bottles to others.");
                return true;
            }
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[1]);
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cUsage: /xpbottle <amount|amountL> <player>");
                return true;
            }
            target = (Player) sender;
        }

        if (!sender.hasPermission("allium.xpbottle")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }

        if (selfWithdraw) {
            int currentExp = SetExpFix.getTotalExperience(target);
            if (currentExp < xpAmount) {
                sender.sendMessage("§cYou don't have enough XP to withdraw " + xpAmount + ".");
                return true;
            }
            SetExpFix.setTotalExperience(target, currentExp - xpAmount);
        }

        String signer = creator != null ? creator.getName() : "Console";
        ItemStack bottle = createXpBottle(xpAmount, signer);
        if (target.getInventory().addItem(bottle).isEmpty()) {
            if (selfWithdraw) {
                sender.sendMessage("§aCreated an XP voucher for " + formatXp(xpAmount) + ".");
            } else if (target.equals(sender)) {
                sender.sendMessage("§aCreated an XP voucher for " + formatXp(xpAmount) + ".");
            } else {
                sender.sendMessage("§aCreated an XP voucher for " + formatXp(xpAmount) + " and gave it to " + target.getName() + ".");
                target.sendMessage("§aYou received an XP voucher for " + formatXp(xpAmount) + " from " + signer + ".");
            }
        } else {
            if (selfWithdraw) {
                int currentExp = SetExpFix.getTotalExperience(target);
                SetExpFix.setTotalExperience(target, currentExp + xpAmount);
            }
            sender.sendMessage("§c" + target.getName() + "'s inventory is full.");
        }
        return true;
    }

    /**
     * Parse amount: raw number, or number+L for levels, or min-max range (only when allowRange).
     */
    private int parseXpAmount(String arg, boolean allowRange) {
        String s = arg.trim().toLowerCase(Locale.ENGLISH);
        if (allowRange && s.contains("-") && !s.startsWith("-")) {
            String[] parts = s.split("-", 2);
            if (parts.length == 2) {
                int min = parseSingleXp(parts[0].trim());
                int max = parseSingleXp(parts[1].trim());
                if (min < 0 || max < 0 || min > max) throw new IllegalArgumentException("Invalid XP range.");
                if (min == max) return min;
                return ThreadLocalRandom.current().nextInt(min, max + 1);
            }
        }
        return parseSingleXp(s);
    }

    private int parseSingleXp(String s) {
        boolean levels = s.endsWith("l");
        if (levels) s = s.substring(0, s.length() - 1);
        int value;
        try {
            value = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid amount: " + s);
        }
        if (value <= 0) throw new IllegalArgumentException("Amount must be positive.");
        if (levels) return SetExpFix.getExpToLevel(value);
        return value;
    }

    private ItemStack createXpBottle(int xpAmount, String signer) {
        ItemStack item = new ItemStack(Material.EXPERIENCE_BOTTLE, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(xpAmountKey, PersistentDataType.INTEGER, xpAmount);
            meta.getPersistentDataContainer().set(xpSignerKey, PersistentDataType.STRING, signer);
            meta.setDisplayName("§b§lXP Voucher §7(Right Click)");
            meta.setLore(List.of(
                "§fExperience: §a" + xpAmount + " XP",
                "§fSigner: §e" + signer
            ));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatXp(int xp) {
        if (xp >= 1000000) return (xp / 1000000) + "M XP";
        if (xp >= 1000) return (xp / 1000) + "K XP";
        return xp + " XP";
    }

    public NamespacedKey getXpAmountKey() {
        return xpAmountKey;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            out.add("100");
            out.add("10L");
            if (sender.hasPermission("allium.xpbottle.other")) out.add("300-500");
            String a = args[0].toLowerCase(Locale.ENGLISH);
            return out.stream().filter(s -> s.toLowerCase(Locale.ENGLISH).startsWith(a)).collect(Collectors.toList());
        }
        if (args.length == 2 && sender.hasPermission("allium.xpbottle.other")) {
            String a = args[1].toLowerCase(Locale.ENGLISH);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ENGLISH).startsWith(a)).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
