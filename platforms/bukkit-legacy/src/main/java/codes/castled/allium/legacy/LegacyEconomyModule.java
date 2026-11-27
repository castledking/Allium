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
import org.bukkit.event.player.PlayerQuitEvent;
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
 * {@code /balance}, {@code /pay}, {@code /paytoggle}, {@code /payconfirmtoggle}, {@code /eco}, {@code /baltop},
 * {@code /worth}, {@code /setworth}, {@code /sell}.
 */
final class LegacyEconomyModule implements Listener {

    private static final long CONFIRMATION_MILLIS = 30_000L;
    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;
    private final Map<UUID, BigDecimal> balances = new HashMap<UUID, BigDecimal>();
    private final Map<UUID, String> knownNames = new HashMap<UUID, String>();
    private final Set<UUID> payDisabled = new HashSet<UUID>();
    private final Set<UUID> payConfirm = new HashSet<UUID>();
    private final Map<UUID, PendingPayment> pendingPayments = new HashMap<UUID, PendingPayment>();
    private final Map<String, BigDecimal> worthTable = new HashMap<String, BigDecimal>();
    private final LegacyCheques cheques;
    private final LegacyTax tax;

    LegacyEconomyModule(JavaPlugin plugin, LegacyConfigManager config, LegacyMessageManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.cheques = new LegacyCheques(plugin, messages, this);
        this.tax = new LegacyTax(plugin, messages, this);
    }

    private void send(CommandSender recipient, String message) {
        messages.sendPrefixed(recipient, message);
    }

    void enable() {
        loadEconomy();
        loadWorth();
        cheques.load();
        tax.load();
    }

    void reload() {
        loadEconomy();
        loadWorth();
        cheques.load();
        tax.load();
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        cheques.onInteract(event);
    }

    void disable() {
        pendingPayments.clear();
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingPayments.remove(event.getPlayer().getUniqueId());
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

    /** Money as this server shows it. */
    String format(BigDecimal amount) {
        return formatMoney(amount);
    }

    /**
     * Adds {@code delta} (negative to take) to a balance and saves; false, with nothing changed, when
     * the balance would go below zero or the save fails.
     */
    boolean adjust(UUID id, String name, BigDecimal delta) {
        BigDecimal next = getBalance(id).add(delta).setScale(2, RoundingMode.HALF_UP);
        if (next.compareTo(BigDecimal.ZERO) < 0) return false;
        BigDecimal previous = balances.put(id, next);
        String previousName = name == null ? null : knownNames.put(id, name);
        if (saveEconomy()) return true;
        restore(balances, id, previous);
        if (name != null) restore(knownNames, id, previousName);
        return false;
    }

    LegacyCheques cheques() {
        return cheques;
    }

    LegacyTax tax() {
        return tax;
    }

    /** {@code economy.tax}, read fresh so /allium reload applies. */
    LegacyTax.Settings taxSettings() {
        return new LegacyTax.Settings(config == null ? null : config.raw().getConfigurationSection("economy.tax"));
    }

    Map<UUID, BigDecimal> balances() {
        return Collections.unmodifiableMap(balances);
    }

    String nameOf(UUID id) {
        String name = knownNames.get(id);
        if (name != null) return name;
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(id);
        return player != null && player.getName() != null ? player.getName() : id.toString().substring(0, 8);
    }

    OfflinePlayer resolveAccount(String name) {
        return resolvePlayer(name);
    }

    /** Moves {@code amount} between two accounts in one save; false, with nothing changed, when it cannot. */
    boolean move(UUID from, String fromName, UUID to, String toName, BigDecimal amount) {
        BigDecimal left = getBalance(from).subtract(amount);
        if (amount.signum() <= 0 || left.signum() < 0) return false;
        BigDecimal previousFrom = balances.put(from, left.setScale(2, RoundingMode.HALF_UP));
        BigDecimal previousTo = balances.put(to, getBalance(to).add(amount).setScale(2, RoundingMode.HALF_UP));
        String previousFromName = fromName == null ? null : knownNames.put(from, fromName);
        String previousToName = toName == null ? null : knownNames.put(to, toName);
        if (saveEconomy()) return true;
        restore(balances, from, previousFrom);
        restore(balances, to, previousTo);
        if (fromName != null) restore(knownNames, from, previousFromName);
        if (toName != null) restore(knownNames, to, previousToName);
        return false;
    }

    boolean handles(String commandName) {
        switch (commandName) {
            case "balance":
            case "pay":
            case "eco":
            case "baltop":
            case "worth":
            case "setworth":
            case "sell":
            case "cheque":
            case "tax":
                return true;
            default:
                return false;
        }
    }

    boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "balance": return handleBalance(sender, args);
            case "pay": return handlePay(sender, args);
            case "eco": return handleEco(sender, args);
            case "baltop": return handleBaltop(sender, args);
            case "worth": return handleWorth(sender, args);
            case "setworth": return handleSetWorth(sender, args);
            case "sell": return handleSell(sender, args);
            case "cheque": return cheques.command(sender, args);
            case "tax": return tax.command(sender, args);
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
            case "cheque":
                return cheques.complete(sender, args, prefix);
            case "tax":
                return tax.complete(sender, args, prefix);
            case "worth":
                return args.length == 1 ? filter(Arrays.asList("hand"), prefix) : Collections.<String>emptyList();
            default:
                return Collections.emptyList();
        }
    }

    private boolean handleBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.balance")) {
            send(sender, ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                LegacyUsageCards.invalid(sender, "/balance <player>");
                return true;
            }
            Player p = (Player) sender;
            send(sender, ChatColor.GREEN + "Balance: " + ChatColor.WHITE + formatMoney(getBalance(p.getUniqueId())));
            return true;
        }
        if (!sender.hasPermission("allium.balance.others")) {
            send(sender, ChatColor.RED + "You do not have permission to check other players' balances.");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        String targetName = target.getName() != null ? target.getName() : args[0];
        send(sender, ChatColor.GREEN + targetName + "'s balance: " + ChatColor.WHITE
                + formatMoney(getBalance(target.getUniqueId())));
        return true;
    }

    private boolean handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can pay other players.");
            return true;
        }
        if (!sender.hasPermission("allium.pay")) {
            send(sender, ChatColor.RED + "You do not have permission to use /pay.");
            return true;
        }
        if (args.length < 2) {
            LegacyUsageCards.invalid(sender, "/pay <player> <amount>");
            return true;
        }
        Player payer = (Player) sender;
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        if (target.getUniqueId().equals(payer.getUniqueId())) {
            send(sender, ChatColor.RED + "You cannot pay yourself.");
            return true;
        }
        if (!acceptsPayments(target.getUniqueId())) {
            send(sender, ChatColor.RED + target.getName() + " is not accepting payments right now.");
            return true;
        }
        BigDecimal amount = LegacyCompat.parseMoneyAmount(args[1]);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            send(sender, ChatColor.RED + "Enter a positive amount (e.g. 100, 2.5k).");
            return true;
        }
        UUID payerId = payer.getUniqueId();
        UUID targetId = target.getUniqueId();
        BigDecimal payerBal = getBalance(payerId);
        if (payerBal.compareTo(amount) < 0) {
            send(sender, ChatColor.RED + "Insufficient funds. Balance: " + formatMoney(payerBal));
            return true;
        }
        String formatted = formatMoney(amount);
        if (payConfirm.contains(payerId)) {
            long now = System.currentTimeMillis();
            PendingPayment pending = pendingPayments.remove(payerId);
            if (pending == null || !pending.matches(targetId, amount, now)) {
                pendingPayments.put(payerId, new PendingPayment(targetId, amount, now + CONFIRMATION_MILLIS));
                messages.send(payer, "economy.pay-confirm",
                        "{prefix} &eConfirm paying &f{amount} &eto &f{player}&e? Type &f/pay {player} {raw_amount} &eagain within &f30s&e.",
                        "{amount}", formatted, "{player}", target.getName(), "{raw_amount}", args[1]);
                return true;
            }
        }
        LegacyTax.Settings taxRules = taxSettings();
        BigDecimal taxAmount = taxRules.payTax && !payer.hasPermission("allium.tax.exempt")
                ? taxRules.tax(amount) : BigDecimal.ZERO.setScale(2);
        BigDecimal net = amount.subtract(taxAmount);
        if (taxAmount.signum() > 0 && net.signum() <= 0) {
            messages.send(payer, "economy.below-minimum", "{prefix} &cAmount is below the minimum payment.");
            return true;
        }
        if (taxAmount.signum() == 0) {
            if (!transfer(payer, target, payerBal.subtract(amount), getBalance(targetId).add(amount))) {
                messages.send(payer, "economy.storage-unavailable", "{prefix} &cEconomy storage is unavailable.");
                return true;
            }
        } else if (!taxedTransfer(payer, target, amount, net, taxAmount, taxRules)) {
            messages.send(payer, "economy.storage-unavailable", "{prefix} &cEconomy storage is unavailable.");
            return true;
        }
        if (taxAmount.signum() == 0) {
            send(payer, ChatColor.GREEN + "Paid " + ChatColor.WHITE + formatted + ChatColor.GREEN + " to " + ChatColor.WHITE + target.getName() + ChatColor.GREEN + ".");
        } else {
            messages.send(payer, "economy.payment-sent-taxed", "{prefix} &aPaid &f{net} &ato &f{player} &7({tax} tax)&a.",
                    "{net}", formatMoney(net), "{player}", target.getName(), "{tax}", formatMoney(taxAmount));
        }
        send(target, ChatColor.GREEN + "Received " + ChatColor.WHITE + formatMoney(net) + ChatColor.GREEN + " from " + ChatColor.WHITE + payer.getName() + ChatColor.GREEN + ".");
        return true;
    }

    private boolean transfer(Player payer, Player target, BigDecimal payerBalance, BigDecimal targetBalance) {
        UUID payerId = payer.getUniqueId();
        UUID targetId = target.getUniqueId();
        BigDecimal previousPayer = balances.put(payerId, payerBalance);
        BigDecimal previousTarget = balances.put(targetId, targetBalance);
        String previousPayerName = knownNames.put(payerId, payer.getName());
        String previousTargetName = knownNames.put(targetId, target.getName());
        if (saveEconomy()) return true;
        restore(balances, payerId, previousPayer);
        restore(balances, targetId, previousTarget);
        restore(knownNames, payerId, previousPayerName);
        restore(knownNames, targetId, previousTargetName);
        return false;
    }

    /** A payment whose tax goes to the tax account, saved once with all three balances. */
    private boolean taxedTransfer(Player payer, Player target, BigDecimal amount, BigDecimal net, BigDecimal taxAmount,
                                 LegacyTax.Settings rules) {
        UUID taxId = rules.accountId();
        UUID payerId = payer.getUniqueId(), targetId = target.getUniqueId();
        BigDecimal previousPayer = balances.put(payerId, getBalance(payerId).subtract(amount));
        BigDecimal previousTarget = balances.put(targetId, getBalance(targetId).add(net));
        BigDecimal previousTax = balances.put(taxId, getBalance(taxId).add(taxAmount));
        String previousTaxName = knownNames.put(taxId, rules.account);
        knownNames.put(payerId, payer.getName());
        knownNames.put(targetId, target.getName());
        if (saveEconomy()) return true;
        restore(balances, payerId, previousPayer);
        restore(balances, targetId, previousTarget);
        restore(balances, taxId, previousTax);
        restore(knownNames, taxId, previousTaxName);
        return false;
    }

    private static <V> void restore(Map<UUID, V> entries, UUID player, V value) {
        if (value == null) entries.remove(player);
        else entries.put(player, value);
    }

    boolean changePaymentPreference(CommandSender sender, String command, String label, String[] args) {
        String permission = "allium." + command;
        if (!sender.hasPermission(permission)) {
            messages.send(sender, "no-permission", "{prefix} &cYou do not have permission to use this command.", "{cmd}", label);
            return true;
        }
        boolean confirmation = "payconfirmtoggle".equals(command);
        Boolean desired = forcedPaymentState(label, confirmation);
        int targetIndex = 0;
        if (desired == null && args.length > 0) {
            if ("on".equalsIgnoreCase(args[0])) desired = Boolean.TRUE;
            if ("off".equalsIgnoreCase(args[0])) desired = Boolean.FALSE;
            if (desired != null) targetIndex++;
        }
        if (args.length > targetIndex + 1) return false;
        UUID id;
        if (args.length > targetIndex) {
            if (!sender.hasPermission(permission + ".others")) {
                messages.send(sender, "no-permission", "{prefix} &cYou do not have permission to use this command.", "{cmd}", label);
                return true;
            }
            String name = args[targetIndex];
            id = knownAccountId(name);
            if (id == null) {
                send(sender, ChatColor.RED + "Player '" + name + "' not found.");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) return false;
            id = ((Player) sender).getUniqueId();
        }
        boolean previous = confirmation ? payConfirm.contains(id) : acceptsPayments(id);
        boolean enabled = desired == null ? !previous : desired.booleanValue();
        boolean saved = confirmation ? savePreference(payConfirm, id, enabled) : setAcceptsPayments(id, enabled);
        if (!saved) {
            messages.send(sender, "economy.storage-unavailable", "{prefix} &cEconomy storage is unavailable.");
            return true;
        }
        if (!confirmation) {
            if (enabled) {
                messages.send(sender, "economy.payments-enabled", "{prefix} &aPayments enabled.");
            } else {
                messages.send(sender, "economy.payments-disabled", "{prefix} &7Payments disabled.");
            }
            return true;
        }
        if (!enabled) pendingPayments.remove(id);
        if (enabled) {
            messages.send(sender, "economy.pay-confirm-on", "{prefix} &aPayment confirmation enabled.");
        } else {
            messages.send(sender, "economy.pay-confirm-off", "{prefix} &7Payment confirmation disabled.");
        }
        return true;
    }

    List<String> completePaymentPreference(CommandSender sender, String command, String label, String[] args, String prefix) {
        String permission = "allium." + command;
        if (!sender.hasPermission(permission)) return Collections.emptyList();
        boolean others = sender.hasPermission(permission + ".others");
        if (forcedPaymentState(label, "payconfirmtoggle".equals(command)) != null) {
            return others && args.length == 1 ? onlinePlayers(sender, prefix) : Collections.<String>emptyList();
        }
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<String>(filter(Arrays.asList("on", "off"), prefix));
            if (others) suggestions.addAll(onlinePlayers(sender, prefix));
            return suggestions;
        }
        if (others && args.length == 2 && ("on".equalsIgnoreCase(args[0]) || "off".equalsIgnoreCase(args[0]))) {
            return onlinePlayers(sender, prefix);
        }
        return Collections.emptyList();
    }

    private static Boolean forcedPaymentState(String label, boolean confirmation) {
        String alias = label.substring(label.lastIndexOf(':') + 1);
        String family = confirmation ? "payconfirm" : "pay";
        if ((family + "on").equalsIgnoreCase(alias)) return Boolean.TRUE;
        if ((family + "off").equalsIgnoreCase(alias)) return Boolean.FALSE;
        return null;
    }

    private UUID knownAccountId(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> entry : knownNames.entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    private static final class PendingPayment {
        private final UUID recipient;
        private final BigDecimal amount;
        private final long expiresAt;

        private PendingPayment(UUID recipient, BigDecimal amount, long expiresAt) {
            this.recipient = recipient;
            this.amount = amount;
            this.expiresAt = expiresAt;
        }

        private boolean matches(UUID target, BigDecimal offered, long now) {
            return now < expiresAt && recipient.equals(target) && amount.compareTo(offered) == 0;
        }
    }

    boolean acceptsPayments(UUID player) {
        return !payDisabled.contains(player);
    }

    boolean setAcceptsPayments(UUID player, boolean enabled) {
        return savePreference(payDisabled, player, !enabled);
    }

    private boolean savePreference(Set<UUID> preference, UUID player, boolean present) {
        if (preference.contains(player) == present) return true;
        if (present) preference.add(player);
        else preference.remove(player);
        if (saveEconomy()) return true;
        if (present) preference.remove(player);
        else preference.add(player);
        return false;
    }

    private boolean handleEco(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.eco")) {
            send(sender, ChatColor.RED + "You do not have permission to use /eco.");
            return true;
        }
        if (args.length < 2) {
            LegacyUsageCards.invalid(sender, "/eco <give|take|set|reset> <player> [amount]");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[1] + "' not found.");
            return true;
        }
        String targetName = target.getName() != null ? target.getName() : args[1];
        UUID id = target.getUniqueId();
        if (action.equals("reset")) {
            setBalance(id, targetName, BigDecimal.ZERO);
            send(sender, ChatColor.GREEN + "Reset " + targetName + "'s balance to $0.00.");
            return true;
        }
        if (args.length < 3) {
            LegacyUsageCards.invalid(sender, "/eco " + action + " <player> <amount>");
            return true;
        }
        BigDecimal amount = LegacyCompat.parseMoneyAmount(args[2]);
        if (amount == null) {
            send(sender, ChatColor.RED + "Invalid amount '" + args[2] + "'.");
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
                LegacyUsageCards.invalid(sender, "/eco <give|take|set|reset> <player> [amount]");
                return true;
        }
        setBalance(id, targetName, next);
        send(sender, ChatColor.GREEN + "Set " + targetName + "'s balance to " + ChatColor.WHITE + formatMoney(next) + ChatColor.GREEN + ".");
        if (target.isOnline() && target.getPlayer() != null && !target.getPlayer().equals(sender)) {
            send(target.getPlayer(), ChatColor.GREEN + "Your balance is now " + ChatColor.WHITE + formatMoney(next) + ChatColor.GREEN + ".");
        }
        return true;
    }

    private boolean handleBaltop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.baltop")) {
            send(sender, ChatColor.RED + "You do not have permission to use /baltop.");
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
        LegacyTax.Settings taxRules = taxSettings();
        if (!taxRules.inBaltop) {
            UUID taxId = taxRules.accountId();
            entries.removeIf(entry -> entry.getKey().equals(taxId));
        }
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
            send(sender, ChatColor.GRAY + "No accounts recorded yet.");
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
            send(sender, ChatColor.RED + "You do not have permission to use /worth.");
            return true;
        }
        Material mat;
        int amount = 1;
        if (args.length == 0 || args[0].equalsIgnoreCase("hand")) {
            if (!(sender instanceof Player)) {
                LegacyUsageCards.invalid(sender, "/worth <material>");
                return true;
            }
            ItemStack hand = ((Player) sender).getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                send(sender, ChatColor.RED + "Hold an item or specify a material name.");
                return true;
            }
            mat = hand.getType();
            amount = hand.getAmount();
        } else {
            mat = LegacyCompat.matchMaterial(args[0]);
            if (mat == null) {
                send(sender, ChatColor.RED + "Unknown material '" + args[0] + "'.");
                return true;
            }
        }
        BigDecimal unit = worthTable.get(mat.name());
        if (unit == null || unit.compareTo(BigDecimal.ZERO) <= 0) {
            send(sender, ChatColor.YELLOW + mat.name() + " has no configured sell price.");
            return true;
        }
        BigDecimal total = unit.multiply(BigDecimal.valueOf(amount));
        send(sender, ChatColor.GREEN + mat.name() + " x" + amount + " is worth "
                + ChatColor.WHITE + formatMoney(total)
                + ChatColor.GRAY + " (" + formatMoney(unit) + " each).");
        return true;
    }

    private boolean handleSetWorth(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.setworth")) {
            send(sender, ChatColor.RED + "You do not have permission to use /setworth.");
            return true;
        }
        Material mat;
        String priceRaw;
        if (args.length == 1 && sender instanceof Player) {
            ItemStack hand = ((Player) sender).getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                LegacyUsageCards.invalid(sender, "/setworth [material] <price>");
                return true;
            }
            mat = hand.getType();
            priceRaw = args[0];
        } else if (args.length >= 2) {
            mat = LegacyCompat.matchMaterial(args[0]);
            priceRaw = args[1];
        } else {
            LegacyUsageCards.invalid(sender, "/setworth [material] <price>");
            return true;
        }
        if (mat == null) {
            send(sender, ChatColor.RED + "Unknown material.");
            return true;
        }
        BigDecimal price = LegacyCompat.parseMoneyAmount(priceRaw);
        if (price == null) {
            send(sender, ChatColor.RED + "Invalid price '" + priceRaw + "'.");
            return true;
        }
        if (price.compareTo(BigDecimal.ZERO) == 0) {
            worthTable.remove(mat.name());
            saveWorth();
            send(sender, ChatColor.GREEN + "Removed sell price for " + mat.name() + ".");
            return true;
        }
        worthTable.put(mat.name(), price);
        saveWorth();
        send(sender, ChatColor.GREEN + "Set sell price for " + ChatColor.WHITE + mat.name()
                + ChatColor.GREEN + " to " + ChatColor.WHITE + formatMoney(price) + ChatColor.GREEN + ".");
        return true;
    }

    private boolean handleSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can sell items.");
            return true;
        }
        if (!sender.hasPermission("allium.sell")) {
            send(sender, ChatColor.RED + "You do not have permission to use /sell.");
            return true;
        }
        Player player = (Player) sender;
        boolean sellAll = args.length > 0 && args[0].equalsIgnoreCase("all");
        BigDecimal totalEarned = BigDecimal.ZERO;
        int totalItems = 0;

        if (!sellAll) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                send(sender, ChatColor.RED + "Hold an item to sell, or use /sell all.");
                return true;
            }
            BigDecimal unit = worthTable.get(hand.getType().name());
            if (unit == null || unit.compareTo(BigDecimal.ZERO) <= 0) {
                send(sender, ChatColor.RED + "That item cannot be sold.");
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
            send(sender, ChatColor.RED + "You have no sellable items.");
            return true;
        }
        setBalance(player.getUniqueId(), player.getName(), getBalance(player.getUniqueId()).add(totalEarned));
        send(sender, ChatColor.GREEN + "Sold " + ChatColor.WHITE + totalItems + ChatColor.GREEN
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
        payConfirm.clear();
        pendingPayments.clear();
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
                    if (s.getBoolean("pay-confirm", false)) payConfirm.add(id);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private boolean saveEconomy() {
        File file = new File(plugin.getDataFolder(), "economy-legacy.yml");
        YamlConfiguration yml = new YamlConfiguration();
        Set<UUID> all = new HashSet<UUID>(balances.keySet());
        all.addAll(payDisabled);
        all.addAll(payConfirm);
        for (UUID id : all) {
            String path = "accounts." + id;
            yml.set(path + ".balance", getBalance(id).toPlainString());
            String name = knownNames.get(id);
            if (name != null) yml.set(path + ".name", name);
            if (payDisabled.contains(id)) yml.set(path + ".pay-disabled", true);
            if (payConfirm.contains(id)) yml.set(path + ".pay-confirm", true);
        }
        try {
            LegacyCompat.saveYamlAtomically(yml, file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save economy-legacy.yml: " + e.getMessage());
            return false;
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
        return onlinePlayers(null, prefix);
    }

    private List<String> onlinePlayers(CommandSender sender, String prefix) {
        List<String> out = new ArrayList<String>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (sender instanceof Player && !((Player) sender).canSee(p)) continue;
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
