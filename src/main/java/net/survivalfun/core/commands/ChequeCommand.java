package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.economy.EconomyManager;
import net.survivalfun.core.util.MoneyAmountUtil;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * /cheque <amount> | /cheque <player> <amount>
 * Amount supports 1m, 1k, 1b, 1t. Self creates a withdrawn cheque; console/admin can create for others.
 */
public class ChequeCommand implements CommandExecutor, TabCompleter {

    private static final String CHEQUE_AMOUNT_KEY = "cheque_amount";
    private static final String CHEQUE_SIGNER_KEY = "cheque_signer";

    private final PluginStart plugin;
    private final NamespacedKey amountKey;
    private final NamespacedKey signerKey;

    public ChequeCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.amountKey = new NamespacedKey(plugin, CHEQUE_AMOUNT_KEY);
        this.signerKey = new NamespacedKey(plugin, CHEQUE_SIGNER_KEY);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /cheque <amount> | /cheque <player> <amount>");
            return true;
        }

        if (!sender.hasPermission("allium.cheque")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }

        EconomyManager economy = plugin.getEconomyManager();
        boolean hasPlayerArg = args.length >= 2;

        BigDecimal amount;
        try {
            amount = MoneyAmountUtil.parse(args[hasPlayerArg ? 1 : 0], false);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c" + e.getMessage());
            return true;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            sender.sendMessage("§cAmount must be positive.");
            return true;
        }

        Player creator = sender instanceof Player ? (Player) sender : null;
        Player target;

        if (hasPlayerArg) {
            if (!sender.hasPermission("allium.cheque.other")) {
                sender.sendMessage("§cYou don't have permission to give cheques to others.");
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[0]);
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cUsage: /cheque <player> <amount>");
                return true;
            }
            target = (Player) sender;
        }

        boolean withdrawFromCreator = creator != null;
        if (withdrawFromCreator) {
            if (!economy.hasEnough(creator.getUniqueId(), amount)) {
                sender.sendMessage("§cYou don't have enough balance. You need " + economy.formatBalance(amount) + ".");
                return true;
            }
            economy.withdraw(creator.getUniqueId(), amount);
        }

        String signerName = creator != null ? creator.getName() : "Console";
        ItemStack cheque = createCheque(amount, signerName);
        if (target.getInventory().addItem(cheque).isEmpty()) {
            if (creator != null && target.equals(creator)) {
                sender.sendMessage("§aCreated a cheque for " + economy.formatBalance(amount) + ".");
            } else {
                sender.sendMessage("§aCreated a cheque for " + economy.formatBalance(amount) + " and gave it to " + target.getName() + ".");
                target.sendMessage("§aYou received a cheque for " + economy.formatBalance(amount) + " from " + signerName + ".");
            }
        } else {
            sender.sendMessage("§c" + target.getName() + "'s inventory is full.");
            if (withdrawFromCreator) {
                economy.deposit(creator.getUniqueId(), amount);
            }
        }
        return true;
    }

    private ItemStack createCheque(BigDecimal amount, String signerName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(amountKey, PersistentDataType.DOUBLE, amount.doubleValue());
            meta.getPersistentDataContainer().set(signerKey, PersistentDataType.STRING, signerName);
            meta.setDisplayName("§a§lMoney Note §7(Right Click)");
            meta.setLore(List.of(
                "§fValue: §a" + plugin.getEconomyManager().formatBalance(amount),
                "§fSigner: §e" + signerName
            ));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    public NamespacedKey getAmountKey() { return amountKey; }
    public NamespacedKey getSignerKey() { return signerKey; }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("allium.cheque.other")) {
                String a = args[0].toLowerCase(Locale.ENGLISH);
                List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ENGLISH).startsWith(a)).collect(Collectors.toList());
                if (!a.matches("^[0-9.-kmbt]*$")) return players;
                List<String> amounts = new ArrayList<>();
                amounts.add("100");
                amounts.add("1m");
                amounts.addAll(players);
                return amounts.stream().filter(s -> s.toLowerCase(Locale.ENGLISH).startsWith(a)).limit(20).collect(Collectors.toList());
            }
            return Collections.singletonList("100");
        }
        if (args.length == 2 && sender.hasPermission("allium.cheque.other")) {
            String a = args[1].toLowerCase(Locale.ENGLISH);
            List<String> out = new ArrayList<>();
            out.add("100");
            out.add("1m");
            return out.stream().filter(s -> s.toLowerCase(Locale.ENGLISH).startsWith(a)).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
