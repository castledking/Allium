package codes.castled.allium.legacy;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Server taxes on legacy Bukkit, as on the modern platform ({@code economy.tax}): a percentage of
 * {@code /pay} and (optionally) player cheques goes to the tax account, and {@code /tax} shows the
 * rate and the account, imposes a tax on one player, everyone online ({@code *}) or every account
 * with money ({@code **}), lists the history, and refunds an entry after confirmation. History is
 * kept in tax-legacy.yml; taxed players get a mail receipt.
 */
final class LegacyTax {
    static final String FILE = "tax-legacy.yml";
    private static final int PAGE_SIZE = 10;

    /** {@code economy.tax}. */
    static final class Settings {
        final boolean enabled, payTax, chequeTax, inBaltop;
        final BigDecimal percent, minimum;
        final String account;
        final RoundingMode rounding;

        Settings(ConfigurationSection c) {
            enabled = c == null || c.getBoolean("enabled", true);
            BigDecimal parsed;
            try {
                parsed = new BigDecimal(c == null ? "5" : c.getString("percent", "5")).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException invalid) {
                parsed = BigDecimal.ZERO;
            }
            percent = parsed.max(BigDecimal.ZERO).min(new BigDecimal("100"));
            String name = c == null ? "Tax" : c.getString("account-name", "Tax");
            account = name == null || name.trim().isEmpty() ? "Tax" : name.trim();
            inBaltop = c == null || c.getBoolean("account-in-baltop", true);
            String mode = c == null ? "nearest" : c.getString("round-mode", "nearest").toLowerCase(Locale.ROOT);
            rounding = mode.startsWith("floor") || mode.equals("down") ? RoundingMode.FLOOR
                    : mode.startsWith("ceil") || mode.equals("up") ? RoundingMode.CEILING : RoundingMode.HALF_UP;
            BigDecimal min = LegacyCompat.parseMoneyAmount(c == null ? "0" : c.getString("minimum-tax", "0"));
            minimum = min == null ? BigDecimal.ZERO : min;
            payTax = c == null || c.getBoolean("apply-to.pay", true);
            chequeTax = c != null && c.getBoolean("apply-to.cheques", false);
        }

        boolean active() {
            return enabled && percent.signum() > 0;
        }

        UUID accountId() {
            return accountId(account);
        }

        /** The account's id, the same as the modern platform's: Bukkit's offline-player id for the name. */
        static UUID accountId(String name) {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }

        /** The tax on {@code gross} at the configured rate, at least {@code minimum-tax}, never more than gross. */
        BigDecimal tax(BigDecimal gross) {
            if (!active() || gross.signum() <= 0) return BigDecimal.ZERO.setScale(2);
            BigDecimal tax = gross.multiply(percent).divide(new BigDecimal("100"), 2, rounding);
            if (tax.signum() > 0 && minimum.signum() > 0) tax = tax.max(minimum);
            return tax.min(gross);
        }

        String rate() {
            return format(percent);
        }
    }

    static final class Entry {
        final long id;
        final UUID player;
        final String name, imposer;
        final BigDecimal amount;
        final BigDecimal percent;
        final long at;
        BigDecimal refunded;

        Entry(long id, UUID player, String name, BigDecimal amount, BigDecimal percent, String imposer, long at, BigDecimal refunded) {
            this.id = id;
            this.player = player;
            this.name = name;
            this.amount = amount;
            this.percent = percent;
            this.imposer = imposer;
            this.at = at;
            this.refunded = refunded;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final LegacyEconomyModule economy;
    private final List<Entry> history = new ArrayList<>();
    private long nextId = 1;
    /** Leaves a system mail for a player: (player, text). */
    BiConsumer<UUID, String> mail = (player, text) -> { };

    LegacyTax(JavaPlugin plugin, LegacyMessageManager messages, LegacyEconomyModule economy) {
        this.plugin = plugin;
        this.messages = messages;
        this.economy = economy;
    }

    void load() {
        history.clear();
        nextId = 1;
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, yml.getLong("next-id", 1));
        ConfigurationSection rows = yml.getConfigurationSection("history");
        if (rows == null) return;
        for (String key : rows.getKeys(false)) {
            ConfigurationSection row = rows.getConfigurationSection(key);
            try {
                BigDecimal refunded = row.isSet("refunded") ? new BigDecimal(row.getString("refunded")) : null;
                history.add(new Entry(Long.parseLong(key), UUID.fromString(row.getString("player")), row.getString("name", "?"),
                        new BigDecimal(row.getString("amount")), new BigDecimal(row.getString("percent")),
                        row.getString("imposer", "?"), row.getLong("at"), refunded));
                nextId = Math.max(nextId, Long.parseLong(key) + 1);
            } catch (RuntimeException skipped) {
                plugin.getLogger().warning("Skipped an unreadable tax history entry " + key + " in " + FILE);
            }
        }
    }

    private boolean save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Entry entry : history) {
            String path = "history." + entry.id;
            yml.set(path + ".player", entry.player.toString());
            yml.set(path + ".name", entry.name);
            yml.set(path + ".amount", entry.amount.toPlainString());
            yml.set(path + ".percent", entry.percent.toPlainString());
            yml.set(path + ".imposer", entry.imposer);
            yml.set(path + ".at", entry.at);
            if (entry.refunded != null) yml.set(path + ".refunded", entry.refunded.toPlainString());
        }
        try {
            LegacyCompat.saveYamlAtomically(yml, new File(plugin.getDataFolder(), FILE));
            return true;
        } catch (IOException failed) {
            plugin.getLogger().warning("Could not save " + FILE + ": " + failed.getMessage());
            return false;
        }
    }

    boolean command(CommandSender sender, String[] args) {
        Settings tax = economy.taxSettings();
        if (args.length == 0) return info(sender, tax);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "balance":
            case "bal":
                if (!permitted(sender, "allium.tax.balance")) return true;
                messages.send(sender, "economy.balance", "{prefix} &aBalance: &f{amount}", "{amount}",
                        economy.format(economy.getBalance(tax.accountId())));
                return true;
            case "impose":
                if (!permitted(sender, "allium.tax.impose")) return true;
                return impose(sender, rest, tax);
            case "history":
                if (!permitted(sender, "allium.tax.history")) return true;
                return history(sender, rest);
            case "refund":
                if (!permitted(sender, "allium.tax.refund")) return true;
                return refund(sender, rest, tax);
            default:
                return info(sender, tax);
        }
    }

    private boolean info(CommandSender sender, Settings tax) {
        List<String> applies = new ArrayList<>();
        if (tax.payTax) applies.add("pay");
        if (tax.chequeTax) applies.add("cheques");
        String appliesText = applies.isEmpty() ? "none" : String.join(", ", applies);
        String status = tax.active() ? "enabled" : "disabled";
        if (!sender.hasPermission("allium.tax.balance")) {
            messages.send(sender, "economy.tax-info-rate", "{prefix} &fTax: &e{rate} &7({status}; applies to: {applies})",
                    "{rate}", tax.rate(), "{status}", status, "{applies}", appliesText);
        } else {
            messages.send(sender, "economy.tax-info",
                    "{prefix} &fTax: &e{rate} &7({status}; applies to: {applies}) &fAccount &e{account}&f: &a{balance}",
                    "{rate}", tax.rate(), "{status}", status, "{applies}", appliesText, "{account}", tax.account,
                    "{balance}", economy.format(economy.getBalance(tax.accountId())));
        }
        return true;
    }

    private boolean impose(CommandSender sender, String[] args, Settings tax) {
        if (args.length != 2) {
            LegacyUsageCards.invalid(sender, "/tax impose <player|*|**> <percent>");
            return true;
        }
        BigDecimal percent = percent(args[1], false);
        if (percent == null) return invalid(sender);
        String imposer = sender instanceof Player ? sender.getName() : "Console";
        List<UUID> targets = new ArrayList<>();
        if ("*".equals(args[0]) || "**".equals(args[0])) {
            if ("**".equals(args[0])) {
                if (!permitted(sender, "allium.tax.impose.offline")) return true;
                for (Map.Entry<UUID, BigDecimal> account : economy.balances().entrySet()) {
                    if (account.getValue().signum() > 0 && !account.getKey().equals(tax.accountId())) targets.add(account.getKey());
                }
            } else {
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    if (!online.hasPermission("allium.tax.exempt") && !online.getUniqueId().equals(tax.accountId())) targets.add(online.getUniqueId());
                }
            }
            int count = 0;
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (UUID target : targets) {
                BigDecimal taxed = imposeOne(target, economy.nameOf(target), percent, imposer, tax);
                if (taxed != null) {
                    count++;
                    total = total.add(taxed);
                }
            }
            messages.send(sender, "economy.tax-imposed-multi",
                    "{prefix} &aImposed &e{percent} &atax on &f{count} &aplayer(s), collecting &e{total}&a.",
                    "{percent}", format(percent), "{count}", Integer.toString(count), "{total}", economy.format(total));
            plugin.getLogger().info("Tax " + format(percent) + " imposed by " + imposer + " on " + count + " account(s): " + total);
            return true;
        }
        OfflinePlayer target = economy.resolveAccount(args[0]);
        if (target == null || target.getUniqueId().equals(tax.accountId())) {
            messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", args[0]);
            return true;
        }
        String name = target.getName() != null ? target.getName() : args[0];
        BigDecimal taxed = imposeOne(target.getUniqueId(), name, percent, imposer, tax);
        if (taxed == null) {
            messages.send(sender, "economy.tax-no-balance", "{prefix} &c{player} has no taxable balance.", "{player}", name);
            return true;
        }
        messages.send(sender, "economy.tax-imposed", "{prefix} &aImposed &e{percent} &atax (&e{amount}&a) on &f{player}&a.",
                "{percent}", format(percent), "{amount}", economy.format(taxed), "{player}", name);
        plugin.getLogger().info("Tax " + format(percent) + " imposed by " + imposer + " on " + name + ": " + taxed);
        return true;
    }

    /** Moves {@code percent} of the account to the tax account; the amount, or null when nothing was taken. */
    private BigDecimal imposeOne(UUID target, String name, BigDecimal percent, String imposer, Settings tax) {
        BigDecimal before = economy.getBalance(target);
        if (before.signum() <= 0) return null;
        BigDecimal taxed = before.multiply(percent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP).min(before);
        if (taxed.signum() <= 0) return null;
        Entry entry = new Entry(nextId++, target, name, taxed, percent, imposer, System.currentTimeMillis(), null);
        history.add(entry);
        if (!save()) {
            history.remove(entry);
            return null;
        }
        if (!economy.move(target, name, tax.accountId(), tax.account, taxed)) {
            history.remove(entry);
            save();
            return null;
        }
        Player online = plugin.getServer().getPlayer(target);
        String percentText = format(percent);
        if (online != null && online.isOnline()) {
            messages.send(online, "economy.tax-imposed-notify", "{prefix} &eA &f{percent} &etax (&f{amount}&e) was imposed on your balance.",
                    "{percent}", percentText, "{amount}", economy.format(taxed));
        }
        mail.accept(target, plain(messages.resolve("economy.tax-imposed-mail",
                "&eA &f{percent} &eserver tax (&f{amount}&e) was imposed on your balance by &f{imposer}&e.",
                "{percent}", percentText, "{amount}", economy.format(taxed), "{imposer}", imposer)));
        return taxed;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (args.length > 2) {
            LegacyUsageCards.invalid(sender, "/tax history [player] [page]");
            return true;
        }
        String player = null;
        int page = 1;
        if (args.length == 1) {
            if (args[0].matches("[0-9]{1,6}")) page = Integer.parseInt(args[0]);
            else player = args[0];
        } else if (args.length == 2) {
            player = args[0];
            if (!args[1].matches("[0-9]{1,6}")) return invalid(sender);
            page = Integer.parseInt(args[1]);
        }
        UUID filter = null;
        if (player != null) {
            OfflinePlayer target = economy.resolveAccount(player);
            if (target == null) {
                messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", player);
                return true;
            }
            filter = target.getUniqueId();
        }
        List<Entry> rows = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (filter == null || history.get(i).player.equals(filter)) rows.add(history.get(i));
        }
        if (rows.isEmpty()) {
            messages.send(sender, "economy.tax-history-empty", "{prefix} &7No tax history records found.");
            return true;
        }
        int pages = (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.max(1, Math.min(page, pages));
        messages.send(sender, "economy.tax-history-header", "{prefix} &fTax history for &e{target} &7(page {page}/{pages}):",
                "{target}", player == null ? "all players" : player, "{page}", Integer.toString(page), "{pages}", Integer.toString(pages));
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        boolean canRefund = sender instanceof Player && sender.hasPermission("allium.tax.refund");
        for (Entry row : rows.subList((page - 1) * PAGE_SIZE, Math.min(rows.size(), page * PAGE_SIZE))) {
            String status = row.refunded != null ? "&a[Refunded " + economy.format(row.refunded) + "]" : "&e[Pending]";
            String line = messages.resolveColored("economy.tax-history-entry",
                    "&7[&e#{id}&7] &f{date} &b{player} &c-{amount} &7({percent}) &eby {imposer} {status}",
                    "{id}", Long.toString(row.id), "{date}", date.format(new Date(row.at)), "{player}", row.name,
                    "{amount}", economy.format(row.amount), "{percent}", format(row.percent), "{imposer}", row.imposer,
                    "{status}", LegacyCompat.color(status));
            if (canRefund && row.refunded == null) {
                LegacyCompat.sendInteractive(sender, line, "/tax refund " + row.id + " 100", true,
                        LegacyCompat.color("&aClick to refund tax #" + row.id + " (100%)"));
            } else {
                sender.sendMessage(line);
            }
        }
        return true;
    }

    private boolean refund(CommandSender sender, String[] args, Settings tax) {
        if (args.length < 1 || args.length > 3) {
            LegacyUsageCards.invalid(sender, "/tax refund <id|player> [percent] [confirm]");
            return true;
        }
        BigDecimal percent = new BigDecimal("100");
        boolean confirmed = false;
        if (args.length >= 2) {
            if ("confirm".equalsIgnoreCase(args[1]) && args.length == 2) confirmed = true;
            else if ((percent = percent(args[1], false)) == null) return invalid(sender);
        }
        if (args.length == 3) {
            if (!"confirm".equalsIgnoreCase(args[2])) return invalid(sender);
            confirmed = true;
        }
        Entry entry = null;
        if (args[0].matches("[0-9]{1,18}")) {
            long id = Long.parseLong(args[0]);
            for (Entry row : history) if (row.id == id) entry = row;
        } else {
            OfflinePlayer target = economy.resolveAccount(args[0]);
            if (target != null) {
                for (Entry row : history) if (row.player.equals(target.getUniqueId()) && row.refunded == null) entry = row;
            }
        }
        if (entry == null) {
            messages.send(sender, "economy.tax-refund-not-found", "{prefix} &cNo refundable tax entry found for &f{target}&c.", "{target}", args[0]);
            return true;
        }
        if (entry.refunded != null) {
            messages.send(sender, "economy.tax-already-refunded", "{prefix} &cTax &6#{id} &chas already been refunded.", "{id}", Long.toString(entry.id));
            return true;
        }
        BigDecimal amount = entry.amount.multiply(percent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) return invalid(sender);
        if (!confirmed) {
            String pct = format(percent);
            String confirm = "/tax refund " + entry.id + " " + pct.substring(0, pct.length() - 1) + " confirm";
            String text = messages.resolveColored("economy.tax-refund-confirm",
                    "{prefix} &eConfirm refund of tax &6#{id} &e(&a{amount}&e, &a{percent}&e) to &f{player}&e: run &f{command}",
                    "{id}", Long.toString(entry.id), "{amount}", economy.format(amount), "{percent}", pct, "{player}", entry.name,
                    "{command}", sender instanceof Player ? "[CLICK TO CONFIRM]" : confirm);
            LegacyCompat.sendInteractive(sender, text, confirm, true, LegacyCompat.color("&aClick to run: &f" + confirm));
            return true;
        }
        if (economy.getBalance(tax.accountId()).compareTo(amount) < 0) {
            messages.send(sender, "economy.tax-funds", "{prefix} &cThe &e{account} &caccount does not have enough funds for that refund.",
                    "{account}", tax.account);
            return true;
        }
        entry.refunded = amount;
        if (!save()) {
            entry.refunded = null;
            return storage(sender);
        }
        if (!economy.move(tax.accountId(), tax.account, entry.player, entry.name, amount)) {
            entry.refunded = null;
            save();
            return storage(sender);
        }
        String refunder = sender instanceof Player ? sender.getName() : "Console";
        messages.send(sender, "economy.tax-refunded", "{prefix} &aRefunded &e{amount} &afor tax &6#{id} &ato &f{player}&a.",
                "{amount}", economy.format(amount), "{id}", Long.toString(entry.id), "{player}", entry.name);
        Player online = plugin.getServer().getPlayer(entry.player);
        if (online != null && online.isOnline()) {
            messages.send(online, "economy.tax-refunded-notify", "{prefix} &aYou received a tax refund of &e{amount}&a.",
                    "{amount}", economy.format(amount));
        } else {
            mail.accept(entry.player, plain(messages.resolve("economy.tax-refunded-mail",
                    "&aYou received a server tax refund of &e{amount} &afrom &f{refunder}&a.",
                    "{amount}", economy.format(amount), "{refunder}", refunder)));
        }
        plugin.getLogger().info("Tax #" + entry.id + " refunded by " + refunder + ": " + amount);
        return true;
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String action : Arrays.asList("balance", "impose", "history", "refund")) {
                if (action.startsWith(prefix) && sender.hasPermission("allium.tax." + action)) out.add(action);
            }
        } else if (args.length == 2 && Arrays.asList("impose", "history", "refund").contains(args[0].toLowerCase(Locale.ROOT))) {
            if ("impose".equalsIgnoreCase(args[0])) out.add("*");
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(online.getName());
            }
        }
        return out;
    }

    List<Entry> entries() {
        return Collections.unmodifiableList(history);
    }

    /** {@code 5}, {@code 12.5}: a percentage from 0.01 (or 0) to 100 with up to two decimals. */
    static BigDecimal percent(String raw, boolean allowZero) {
        if (raw == null || !raw.trim().matches("[0-9]{1,3}(\\.[0-9]{1,2})?")) return null;
        BigDecimal value = new BigDecimal(raw.trim()).setScale(2, RoundingMode.UNNECESSARY);
        if (value.compareTo(new BigDecimal("100")) > 0 || (allowZero ? value.signum() < 0 : value.signum() <= 0)) return null;
        return value;
    }

    static String format(BigDecimal percent) {
        BigDecimal stripped = percent.stripTrailingZeros();
        if (stripped.scale() < 0) stripped = stripped.setScale(0);
        return stripped.toPlainString() + "%";
    }

    private static String plain(String text) {
        return org.bukkit.ChatColor.stripColor(LegacyCompat.color(text));
    }

    private boolean permitted(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return false;
    }

    private boolean invalid(CommandSender sender) {
        messages.send(sender, "economy.invalid-amount", "{prefix} &cEnter a valid percentage between 0.01 and 100.");
        return true;
    }

    private boolean storage(CommandSender sender) {
        messages.send(sender, "economy.storage-unavailable", "{prefix} &cEconomy storage is unavailable.");
        return true;
    }
}
