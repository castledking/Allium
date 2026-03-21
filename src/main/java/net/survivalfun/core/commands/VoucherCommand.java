package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.voucher.Voucher;
import net.survivalfun.core.voucher.VouchersConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * /voucher give <player> <voucher> | /voucher list | /voucher reload
 */
public class VoucherCommand implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final NamespacedKey voucherIdKey;

    public VoucherCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.voucherIdKey = new NamespacedKey(plugin, "voucher_id");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length < 1) {
            showHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ENGLISH);
        switch (sub) {
            case "give":
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /voucher give <player> <voucher>");
                    return true;
                }
                if (!sender.hasPermission("allium.voucher.give")) {
                    sender.sendMessage("§cYou don't have permission.");
                    return true;
                }
                giveVoucher(sender, args[1], args[2]);
                break;
            case "list":
                listVouchers(sender);
                break;
            case "reload":
                if (!sender.hasPermission("allium.voucher.reload")) {
                    sender.sendMessage("§cYou don't have permission.");
                    return true;
                }
                plugin.getVouchersConfig().reloadConfig();
                sender.sendMessage("§aVouchers config reloaded.");
                break;
            default:
                showHelp(sender);
                break;
        }
        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Voucher Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/voucher give <player> <voucher> " + ChatColor.WHITE + "- Give a voucher to a player");
        sender.sendMessage(ChatColor.YELLOW + "/voucher list " + ChatColor.WHITE + "- List all available vouchers");
        if (sender.hasPermission("allium.voucher.reload")) {
            sender.sendMessage(ChatColor.YELLOW + "/voucher reload " + ChatColor.WHITE + "- Reload vouchers config");
        }
    }

    private void giveVoucher(CommandSender sender, String playerName, String voucherId) {
        org.bukkit.entity.Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("§cPlayer not found: " + playerName);
            return;
        }
        Voucher voucher = plugin.getVouchersConfig().getVoucher(voucherId);
        if (voucher == null) {
            sender.sendMessage("§cVoucher not found: " + voucherId);
            return;
        }
        ItemStack item = createVoucherItem(voucher);
        if (target.getInventory().addItem(item).isEmpty()) {
            sender.sendMessage("§aGave " + voucher.getId() + " to " + target.getName());
            target.sendMessage("§aYou received: " + ChatColor.translateAlternateColorCodes('&', voucher.getDisplayName()));
        } else {
            sender.sendMessage("§c" + target.getName() + "'s inventory is full.");
        }
    }

    private void listVouchers(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Available Vouchers ===");
        for (String id : plugin.getVouchersConfig().getVoucherIds()) {
            Voucher v = plugin.getVouchersConfig().getVoucher(id);
            if (v != null) {
                sender.sendMessage(ChatColor.YELLOW + "- " + id + ": " + ChatColor.WHITE + ChatColor.translateAlternateColorCodes('&', v.getDisplayName()));
            }
        }
    }

    private ItemStack createVoucherItem(Voucher voucher) {
        ItemStack item = new ItemStack(voucher.getMaterial(), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', voucher.getDisplayName()));
            if (!voucher.getLore().isEmpty()) {
                meta.setLore(voucher.getLore().stream()
                    .map(s -> ChatColor.translateAlternateColorCodes('&', s))
                    .collect(Collectors.toList()));
            }
            if (voucher.isEnchanted()) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.getPersistentDataContainer().set(voucherIdKey, PersistentDataType.STRING, voucher.getId());
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            out.add("give");
            out.add("list");
            if (sender.hasPermission("allium.voucher.reload")) out.add("reload");
            return out.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ENGLISH))).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).filter(n -> n.toLowerCase(Locale.ENGLISH).startsWith(args[1].toLowerCase(Locale.ENGLISH))).collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return new ArrayList<>(plugin.getVouchersConfig().getVoucherIds()).stream().filter(id -> id.toLowerCase(Locale.ENGLISH).startsWith(args[2].toLowerCase(Locale.ENGLISH))).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
