package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java 8 / 1.12.2-1.19.4 economy and worth module:
 * {@code /balance}, {@code /pay}, {@code /paytoggle}, {@code /eco}, {@code /baltop},
 * {@code /worth}, {@code /setworth}, {@code /sell}.
 */
final class LegacyEconomyModule implements Listener {

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final Map<UUID, BigDecimal> balances = new HashMap<UUID, BigDecimal>();
    private final Map<UUID, String> knownNames = new HashMap<UUID, String>();
    private final Set<UUID> payDisabled = new HashSet<UUID>();
    private final Map<String, BigDecimal> worthTable = new HashMap<String, BigDecimal>();

    LegacyEconomyModule(JavaPlugin plugin, LegacyConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    void enable() {
        loadEconomy();
        loadWorth();
    }

    void reload() {
        loadEconomy();
        loadWorth();
    }

    void disable() {
        saveEconomy();
        saveWorth();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        knownNames.put(p.getUniqueId(), p.getName());
        if (!balances.containsKey(p.getUniqueId())) {
            balances.put(p.getUniqueId(), config != null ? config.startingBalance() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private String formatMoney(BigDecimal amount) {
        return config != null ? config.formatCurrency(amount) : LegacyCompat.formatMoney(amount);
    }

    BigDecimal getBalance(UUID id) {
        BigDecimal val = balances.get(id);
        return val == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : val;
    }

    void setBalance(UUID id, String name, BigDecimal amount) {
        BigDecimal safe = amount == null || amount.compareTo(BigDecimal.ZERO) < 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : amount.setScale(2, RoundingMode.HALF_UP);
        balances.put(id, safe);
        if (name != null && !name.isEmpty()) {
            knownNames.put(id, name);
        }
        saveEconomy();
    }

    boolean handles(String commandName) {
        switch (commandName) {
            case "balance":
            case "pay":
            case "paytoggle":
            case "eco":
            case "baltop":
            case "worth":
            case "setworth":
            case "sell":
                return true;
            default:
                return false;
        }
    }

    boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "balance": return handleBalance(sender, args);
            case "pay": return handlePay(sender, args);
            case "paytoggle": return handlePayToggle(sender);
            case "eco": return handleEco(sender, args);
            case "baltop": return handleBaltop(sender, args);
            case "worth": return handleWorth(sender, args);
            case "setworth": return handleSetWorth(sender, args);
            case "sell": return handleSell(sender, args);
            default: return false;
        }
    }

    List<String> onTabComplete(CommandSender sender, String name, String[] args, String prefix) {
        switch (name) {
            case "balance":
            case "pay":
                return args.length == 1 ? onlinePlayers(prefix) : Collections.<String>emptyList();
            case "eco":
                if (args.length == 1) return filter(Arrays.asList("give", "take", "set", "reset"), prefix);
                if (args.length == 2) return onlinePlayers(prefix);
                return Collections.emptyList();
            case "sell":
                return args.length == 1 ? filter(Arrays.asList("hand", "all"), prefix) : Collections.<String>emptyList();
            case "worth":
                return args.length == 1 ? filter(Arrays.asList("hand"), prefix) : Collections.<String>emptyList();
            default:
                return Collections.emptyList();
        }
    }

    private boolean handleBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.balance")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /balance <player>");
                return true;
            }
            Player p = (Player) sender;
            sender.sendMessage(ChatColor.GREEN + "Balance: " + ChatColor.WHITE + formatMoney(getBalance(p.getUniqueId())));
            return true;
        }
        if (!sender.hasPermission("allium.balance.others")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to check other players' balances.");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        String targetName = target.getName() != null ? target.getName() : args[0];
        sender.sendMessage(ChatColor.GREEN + targetName + "'s balance: " + ChatColor.WHITE
                + formatMoney(getBalance(target.getUniqueId())));
        return true;
    }

    private boolean handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can pay other players.");
            return true;
        }
        if (!sender.hasPermission("allium.pay")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /pay.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /pay <player> <amount>");
            return true;
        }
        Player payer = (Player) sender;
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        if (target.getUniqueId().equals(payer.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You cannot pay yourself.");
            return true;
        }
        if (payDisabled.contains(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + target.getName() + " is not accepting payments right now.");
            return true;
        }
        BigDecimal amount = LegacyCompat.parseMoneyAmount(args[1]);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            sender.sendMessage(ChatColor.RED + "Enter a positive amount (e.g. 100, 2.5k).");
            return true;
        }
        BigDecimal payerBal = getBalance(payer.getUniqueId());
        if (payerBal.compareTo(amount) < 0) {
            sender.sendMessage(ChatColor.RED + "Insufficient funds. Balance: " + formatMoney(payerBal));
            return true;
        }
        setBalance(payer.getUniqueId(), payer.getName(), payerBal.subtract(amount));
        setBalance(target.getUniqueId(), target.getName(), getBalance(target.getUniqueId()).add(amount));
        String formatted = formatMoney(amount);
        payer.sendMessage(ChatColor.GREEN + "Paid " + ChatColor.WHITE + formatted + ChatColor.GREEN + " to " + ChatColor.WHITE + target.getName() + ChatColor.GREEN + ".");
        target.sendMessage(ChatColor.GREEN + "Received " + ChatColor.WHITE + formatted + ChatColor.GREEN + " from " + ChatColor.WHITE + payer.getName() + ChatColor.GREEN + ".");
        return true;
    }

    private boolean handlePayToggle(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can toggle payments.");
            return true;
        }
        if (!sender.hasPermission("allium.paytoggle")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /paytoggle.");
            return true;
        }
        Player p = (Player) sender;
        if (payDisabled.remove(p.getUniqueId())) {
            sender.sendMessage(ChatColor.GREEN + "Payments enabled.");
        } else {
            payDisabled.add(p.getUniqueId());
            sender.sendMessage(ChatColor.GRAY + "Payments disabled.");
        }
        saveEconomy();
        return true;
    }

    private boolean handleEco(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.eco")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /eco.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /eco <give|take|set|reset> <player> [amount]");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' not found.");
            return true;
        }
        String targetName = target.getName() != null ? target.getName() : args[1];
        UUID id = target.getUniqueId();
        if (action.equals("reset")) {
            setBalance(id, targetName, BigDecimal.ZERO);
            sender.sendMessage(ChatColor.GREEN + "Reset " + targetName + "'s balance to $0.00.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /eco " + action + " <player> <amount>");
            return true;
        }
        BigDecimal amount = LegacyCompat.parseMoneyAmount(args[2]);
        if (amount == null) {
            sender.sendMessage(ChatColor.RED + "Invalid amount '" + args[2] + "'.");
            return true;
        }
        BigDecimal current = getBalance(id);
        BigDecimal next;
        switch (action) {
            case "give":
                next = current.add(amount);
                break;
            case "take":
                next = current.subtract(amount);
                if (next.compareTo(BigDecimal.ZERO) < 0) next = BigDecimal.ZERO;
                break;
            case "set":
                next = amount;
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Usage: /eco <give|take|set|reset> <player> [amount]");
                return true;
        }
        setBalance(id, targetName, next);
        sender.sendMessage(ChatColor.GREEN + "Set " + targetName + "'s balance to " + ChatColor.WHITE + formatMoney(next) + ChatColor.GREEN + ".");
        if (target.isOnline() && target.getPlayer() != null && !target.getPlayer().equals(sender)) {
            target.getPlayer().sendMessage(ChatColor.GREEN + "Your balance is now " + ChatColor.WHITE + formatMoney(next) + ChatColor.GREEN + ".");
        }
        return true;
    }

    private boolean handleBaltop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.baltop")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /baltop.");
            return true;
        }
        int page = 1;
        if (args.length > 0) {
            try {
                page = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException ignored) {
            }
        }
        List<Map.Entry<UUID, BigDecimal>> entries = new ArrayList<Map.Entry<UUID, BigDecimal>>(balances.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<UUID, BigDecimal>>() {
            @Override
            public int compare(Map.Entry<UUID, BigDecimal> a, Map.Entry<UUID, BigDecimal> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        int pageSize = 10;
        int totalPages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        if (page > totalPages) page = totalPages;
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Top Balances " + ChatColor.GRAY + "(Page " + page + "/" + totalPages + "):");
        if (entries.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No accounts recorded yet.");
            return true;
        }
        int start = (page - 1) * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        for (int i = start; i < end; i++) {
            Map.Entry<UUID, BigDecimal> entry = entries.get(i);
            String name = knownNames.get(entry.getKey());
            if (name == null) {
                OfflinePlayer op = plugin.getServer().getOfflinePlayer(entry.getKey());
                name = op != null && op.getName() != null ? op.getName() : entry.getKey().toString();
            }
            sender.sendMessage(ChatColor.GRAY + "" + (i + 1) + ". " + ChatColor.WHITE + name
                    + ChatColor.GRAY + " - " + ChatColor.GREEN + formatMoney(entry.getValue()));
        }
        return true;
    }

    private boolean handleWorth(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.worth")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /worth.");
            return true;
        }
        Material mat;
        int amount = 1;
        if (args.length == 0 || args[0].equalsIgnoreCase("hand")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /worth <material>");
                return true;
            }
            ItemStack hand = ((Player) sender).getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                sender.sendMessage(ChatColor.RED + "Hold an item or specify a material name.");
                return true;
            }
            mat = hand.getType();
            amount = hand.getAmount();
        } else {
            mat = LegacyCompat.matchMaterial(args[0]);
            if (mat == null) {
                sender.sendMessage(ChatColor.RED + "Unknown material '" + args[0] + "'.");
                return true;
            }
        }
        BigDecimal unit = worthTable.get(mat.name());
        if (unit == null || unit.compareTo(BigDecimal.ZERO) <= 0) {
            sender.sendMessage(ChatColor.YELLOW + mat.name() + " has no configured sell price.");
            return true;
        }
        BigDecimal total = unit.multiply(BigDecimal.valueOf(amount));
        sender.sendMessage(ChatColor.GREEN + mat.name() + " x" + amount + " is worth "
                + ChatColor.WHITE + formatMoney(total)
                + ChatColor.GRAY + " (" + formatMoney(unit) + " each).");
        return true;
    }

    private boolean handleSetWorth(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.setworth")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /setworth.");
            return true;
        }
        Material mat;
        String priceRaw;
        if (args.length == 1 && sender instanceof Player) {
            ItemStack hand = ((Player) sender).getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                sender.sendMessage(ChatColor.RED + "Usage: /setworth [material] <price>");
                return true;
            }
            mat = hand.getType();
            priceRaw = args[0];
        } else if (args.length >= 2) {
            mat = LegacyCompat.matchMaterial(args[0]);
            priceRaw = args[1];
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /setworth [material] <price>");
            return true;
        }
        if (mat == null) {
            sender.sendMessage(ChatColor.RED + "Unknown material.");
            return true;
        }
        BigDecimal price = LegacyCompat.parseMoneyAmount(priceRaw);
        if (price == null) {
            sender.sendMessage(ChatColor.RED + "Invalid price '" + priceRaw + "'.");
            return true;
        }
        if (price.compareTo(BigDecimal.ZERO) == 0) {
            worthTable.remove(mat.name());
            saveWorth();
            sender.sendMessage(ChatColor.GREEN + "Removed sell price for " + mat.name() + ".");
            return true;
        }
        worthTable.put(mat.name(), price);
        saveWorth();
        sender.sendMessage(ChatColor.GREEN + "Set sell price for " + ChatColor.WHITE + mat.name()
                + ChatColor.GREEN + " to " + ChatColor.WHITE + formatMoney(price) + ChatColor.GREEN + ".");
        return true;
    }

    private boolean handleSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can sell items.");
            return true;
        }
        if (!sender.hasPermission("allium.sell")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /sell.");
            return true;
        }
        Player player = (Player) sender;
        boolean sellAll = args.length > 0 && args[0].equalsIgnoreCase("all");
        BigDecimal totalEarned = BigDecimal.ZERO;
        int totalItems = 0;

        if (!sellAll) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                sender.sendMessage(ChatColor.RED + "Hold an item to sell, or use /sell all.");
                return true;
            }
            BigDecimal unit = worthTable.get(hand.getType().name());
            if (unit == null || unit.compareTo(BigDecimal.ZERO) <= 0) {
                sender.sendMessage(ChatColor.RED + "That item cannot be sold.");
                return true;
            }
            totalItems = hand.getAmount();
            totalEarned = unit.multiply(BigDecimal.valueOf(totalItems));
            player.getInventory().setItemInMainHand(null);
        } else {
            ItemStack[] contents = player.getInventory().getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType() == Material.AIR) continue;
                BigDecimal unit = worthTable.get(stack.getType().name());
                if (unit != null && unit.compareTo(BigDecimal.ZERO) > 0) {
                    totalItems += stack.getAmount();
                    totalEarned = totalEarned.add(unit.multiply(BigDecimal.valueOf(stack.getAmount())));
                    contents[i] = null;
                }
            }
            if (totalItems > 0) {
                player.getInventory().setStorageContents(contents);
            }
        }
        if (totalItems == 0) {
            sender.sendMessage(ChatColor.RED + "You have no sellable items.");
            return true;
        }
        setBalance(player.getUniqueId(), player.getName(), getBalance(player.getUniqueId()).add(totalEarned));
        sender.sendMessage(ChatColor.GREEN + "Sold " + ChatColor.WHITE + totalItems + ChatColor.GREEN
                + " item(s) for " + ChatColor.WHITE + formatMoney(totalEarned) + ChatColor.GREEN + ".");
        return true;
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer resolvePlayer(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) return online;
        online = plugin.getServer().getPlayer(name);
        if (online != null) return online;
        for (Map.Entry<UUID, String> e : knownNames.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(name)) {
                return plugin.getServer().getOfflinePlayer(e.getKey());
            }
        }
        OfflinePlayer op = plugin.getServer().getOfflinePlayer(name);
        return op != null && (op.hasPlayedBefore() || balances.containsKey(op.getUniqueId())) ? op : null;
    }

    private void loadEconomy() {
        balances.clear();
        knownNames.clear();
        payDisabled.clear();
        File file = new File(plugin.getDataFolder(), "economy-legacy.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection accts = yml.getConfigurationSection("accounts");
        if (accts != null) {
            for (String key : accts.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    ConfigurationSection s = accts.getConfigurationSection(key);
                    if (s == null) continue;
                    String rawBal = s.getString("balance", "0.00");
                    BigDecimal bal = LegacyCompat.parseMoneyAmount(rawBal);
                    balances.put(id, bal != null ? bal : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                    String name = s.getString("name");
                    if (name != null) knownNames.put(id, name);
                    if (s.getBoolean("pay-disabled", false)) payDisabled.add(id);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveEconomy() {
        File file = new File(plugin.getDataFolder(), "economy-legacy.yml");
        YamlConfiguration yml = new YamlConfiguration();
        Set<UUID> all = new HashSet<UUID>(balances.keySet());
        all.addAll(payDisabled);
        for (UUID id : all) {
            String path = "accounts." + id;
            yml.set(path + ".balance", getBalance(id).toPlainString());
            String name = knownNames.get(id);
            if (name != null) yml.set(path + ".name", name);
            if (payDisabled.contains(id)) yml.set(path + ".pay-disabled", true);
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save economy-legacy.yml: " + e.getMessage());
        }
    }

    private void loadWorth() {
        worthTable.clear();
        File file = new File(plugin.getDataFolder(), "worth.yml");
        YamlConfiguration yml = null;
        if (file.isFile()) {
            yml = YamlConfiguration.loadConfiguration(file);
        } else {
            InputStream bundled = plugin.getResource("worth.yml");
            if (bundled != null) {
                try {
                    yml = YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8));
                } finally {
                    try { bundled.close(); } catch (IOException ignored) {}
                }
            }
        }
        if (yml == null) return;
        ConfigurationSection section = yml.getConfigurationSection("worth");
        if (section == null) section = yml.getConfigurationSection("prices");
        if (section == null) section = yml;
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) continue;
            double v = section.getDouble(key, -1.0);
            if (v > 0.0) {
                Material m = LegacyCompat.matchMaterial(key);
                String matKey = m != null ? m.name() : key.toUpperCase(Locale.ROOT);
                worthTable.put(matKey, BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP));
            }
        }
    }

    private void saveWorth() {
        File file = new File(plugin.getDataFolder(), "worth.yml");
        YamlConfiguration yml = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        for (Map.Entry<String, BigDecimal> entry : worthTable.entrySet()) {
            yml.set("worth." + entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().doubleValue());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save worth.yml: " + e.getMessage());
        }
    }

    private List<String> onlinePlayers(String prefix) {
        List<String> out = new ArrayList<String>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(p.getName());
            }
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        List<String> out = new ArrayList<String>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(c);
        }
        return out;
    }
}
