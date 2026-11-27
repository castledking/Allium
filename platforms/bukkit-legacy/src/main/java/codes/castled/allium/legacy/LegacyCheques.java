package codes.castled.allium.legacy;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code /cheque} on legacy Bukkit: money notes that are paid out once.
 *
 * <p>Items before 1.14 carry no hidden data, so the note holds only a random id, written as a dark
 * lore line (an all-colour-code line is dropped by 1.13+ servers); the amount, the signer and whether it was redeemed live in
 * cheques-legacy.yml. Redeeming pays the ledger's amount and marks the cheque redeemed in the same
 * save, so a copied or edited note pays nothing.
 *
 * <ul>
 *   <li>{@code /cheque <amount>}: takes the amount from the writer's balance.</li>
 *   <li>{@code /cheque <amount|min-max> <player>}: an admin cheque, minted without funds
 *       ({@code allium.cheque.others} or the console); a range rolls an amount in it.</li>
 *   <li>Right-clicking a note redeems it.</li>
 * </ul>
 */
final class LegacyCheques {
    static final String FILE = "cheques-legacy.yml";
    private static final String NAME = "§a§lMoney Note §7(Right Click)";

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final LegacyEconomyModule economy;
    private final Map<String, Entry> ledger = new LinkedHashMap<>();
    /** Makes and reads note items; tests swap in one that needs no server. */
    Notes notes = new PaperNotes();

    interface Notes {
        ItemStack create(String id, String value, String signer);

        String idOf(ItemStack item);
    }

    static final class PaperNotes implements Notes {
        @Override
        public ItemStack create(String id, String value, String signer) {
            ItemStack item = new ItemStack(Material.PAPER, 1);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(NAME);
            meta.setLore(Arrays.asList("§fValue: §a" + value, "§fSigner: §e" + signer, hidden(id)));
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
            return item;
        }

        @Override
        public String idOf(ItemStack item) {
            return LegacyCheques.idOf(item);
        }
    }

    static final class Entry {
        final BigDecimal amount;
        final String signer;
        final long issuedAt;
        boolean redeemed;

        Entry(BigDecimal amount, String signer, long issuedAt, boolean redeemed) {
            this.amount = amount;
            this.signer = signer;
            this.issuedAt = issuedAt;
            this.redeemed = redeemed;
        }
    }

    LegacyCheques(JavaPlugin plugin, LegacyMessageManager messages, LegacyEconomyModule economy) {
        this.plugin = plugin;
        this.messages = messages;
        this.economy = economy;
    }

    void load() {
        ledger.clear();
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) return;
        ConfigurationSection cheques = YamlConfiguration.loadConfiguration(file).getConfigurationSection("cheques");
        if (cheques == null) return;
        for (String id : cheques.getKeys(false)) {
            ConfigurationSection row = cheques.getConfigurationSection(id);
            BigDecimal amount = row == null ? null : LegacyCompat.parseMoneyAmount(row.getString("amount"));
            if (amount == null) continue;
            ledger.put(id, new Entry(amount, row.getString("signer", "?"), row.getLong("issued-at"), row.getBoolean("redeemed")));
        }
    }

    private boolean save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Entry> row : ledger.entrySet()) {
            String path = "cheques." + row.getKey();
            yml.set(path + ".amount", row.getValue().amount.toPlainString());
            yml.set(path + ".signer", row.getValue().signer);
            yml.set(path + ".issued-at", row.getValue().issuedAt);
            if (row.getValue().redeemed) yml.set(path + ".redeemed", true);
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
        if (!sender.hasPermission("allium.cheque")) {
            messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
            return true;
        }
        if (args.length == 1) {
            if (!(sender instanceof Player)) {
                LegacyUsageCards.invalid(sender, "/cheque <amount> <player>");
                return true;
            }
            BigDecimal amount = positive(args[0]);
            if (amount == null) return invalidAmount(sender);
            return playerCheque((Player) sender, amount);
        }
        if (args.length != 2) return false;
        if (sender instanceof Player && !sender.hasPermission("allium.cheque.others")) {
            messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
            return true;
        }
        // /cheque <amount|min-max> <player>, and legacy's /cheque <player> <amount|min-max>.
        BigDecimal amount = rolled(args[0]);
        String targetName = args[1];
        if (amount == null) {
            amount = rolled(args[1]);
            targetName = args[0];
        }
        if (amount == null) return invalidAmount(sender);
        Player target = plugin.getServer().getPlayerExact(targetName);
        if (target == null || sender instanceof Player && !((Player) sender).canSee(target)) {
            messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", targetName);
            return true;
        }
        if (target.getInventory().firstEmpty() < 0) {
            messages.send(sender, "economy.cheque-full", "{prefix} &c{player}'s inventory is full.", "{player}", target.getName());
            return true;
        }
        String signer = sender instanceof Player ? sender.getName() : "Server";
        String id = issue(amount, signer);
        if (id == null) return storageUnavailable(sender);
        target.getInventory().addItem(note(id, amount, signer));
        String formatted = economy.format(amount);
        messages.send(sender, "economy.cheque-given", "{prefix} &aGave an admin cheque for &e{amount} &ato &f{player}&a.",
                "{amount}", formatted, "{player}", target.getName());
        if (target != sender) {
            messages.send(target, "economy.cheque-received", "{prefix} &aYou received a cheque for &e{amount} &afrom &f{signer}&a.",
                    "{amount}", formatted, "{signer}", signer);
        }
        return true;
    }

    private boolean playerCheque(Player player, BigDecimal amount) {
        if (player.getInventory().firstEmpty() < 0) {
            messages.send(player, "economy.cheque-full", "{prefix} &c{player}'s inventory is full.", "{player}", player.getName());
            return true;
        }
        if (economy.getBalance(player.getUniqueId()).compareTo(amount) < 0) {
            messages.send(player, "economy.cheque-funds", "{prefix} &cYou need &e{amount} &cto write this cheque.",
                    "{amount}", economy.format(amount));
            return true;
        }
        // With economy.tax.apply-to.cheques, the writer pays the amount and the note is worth it less the tax.
        LegacyTax.Settings tax = economy.taxSettings();
        BigDecimal taxAmount = tax.chequeTax && !player.hasPermission("allium.tax.exempt") ? tax.tax(amount) : BigDecimal.ZERO;
        BigDecimal face = amount.subtract(taxAmount);
        if (face.signum() <= 0) {
            messages.send(player, "economy.below-minimum", "{prefix} &cAmount is below the minimum payment.");
            return true;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        ledger.put(id, new Entry(face, player.getName(), System.currentTimeMillis(), false));
        if (!save()) {
            ledger.remove(id);
            return storageUnavailable(player);
        }
        boolean paid = taxAmount.signum() > 0
                ? economy.adjust(player.getUniqueId(), player.getName(), face.negate())
                && economy.move(player.getUniqueId(), player.getName(), tax.accountId(), tax.account, taxAmount)
                : economy.adjust(player.getUniqueId(), player.getName(), amount.negate());
        if (!paid) {
            ledger.remove(id);
            save();
            return storageUnavailable(player);
        }
        player.getInventory().addItem(note(id, face, player.getName()));
        if (taxAmount.signum() > 0) {
            messages.send(player, "economy.cheque-created-taxed", "{prefix} &aCreated a cheque for &e{amount} &7({tax} tax)&a.",
                    "{amount}", economy.format(face), "{tax}", economy.format(taxAmount));
        } else {
            messages.send(player, "economy.cheque-created", "{prefix} &aCreated a cheque for &e{amount}&a.", "{amount}", economy.format(amount));
        }
        return true;
    }

    /** Right-clicking a note pays its ledger amount once. */
    void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        String id = item == null ? null : notes.idOf(item);
        if (id == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Entry entry = ledger.get(id);
        if (entry == null) {
            messages.send(player, "economy.cheque-invalid", "{prefix} &cThis cheque is not valid.");
            return;
        }
        if (entry.redeemed) {
            messages.send(player, "economy.cheque-spent", "{prefix} &cThis cheque has already been redeemed or voided.");
            return;
        }
        entry.redeemed = true;
        if (!save()) {
            entry.redeemed = false;
            storageUnavailable(player);
            return;
        }
        if (!economy.adjust(player.getUniqueId(), player.getName(), entry.amount)) {
            entry.redeemed = false;
            save();
            storageUnavailable(player);
            return;
        }
        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else removeHeld(player, item);
        messages.send(player, "economy.cheque-redeemed", "{prefix} &aRedeemed a cheque for &e{amount} &7(signed by {signer})&a.",
                "{amount}", economy.format(entry.amount), "{signer}", entry.signer);
    }

    private static void removeHeld(Player player, ItemStack item) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] == item || item.equals(contents[slot])) {
                player.getInventory().setItem(slot, null);
                return;
            }
        }
    }

    private String issue(BigDecimal amount, String signer) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ledger.put(id, new Entry(amount, signer, System.currentTimeMillis(), false));
        if (save()) return id;
        ledger.remove(id);
        return null;
    }

    ItemStack note(String id, BigDecimal amount, String signer) {
        return notes.create(id, economy.format(amount), signer);
    }

    /** The cheque id of a note, or null for any other item. */
    static String idOf(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (!NAME.equals(meta.getDisplayName()) || !meta.hasLore()) return null;
        List<String> lore = meta.getLore();
        return lore.size() < 3 ? null : revealed(lore.get(lore.size() - 1));
    }

    /** The note's id line: the 32-digit hex id in dark grey. */
    static String hidden(String id) {
        return "§8#" + id;
    }

    static String revealed(String line) {
        if (line == null || line.length() != 35 || !line.startsWith("§8#")) return null;
        String id = line.substring(3);
        for (char c : id.toCharArray()) if (Character.digit(c, 16) < 0) return null;
        return id;
    }

    private static BigDecimal positive(String raw) {
        BigDecimal amount = LegacyCompat.parseMoneyAmount(raw);
        return amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code 500} or a range such as {@code 500-1000}, rolled to a cent. */
    static BigDecimal rolled(String raw) {
        int dash = raw.indexOf('-', 1);
        if (dash < 0) return positive(raw);
        BigDecimal low = positive(raw.substring(0, dash));
        BigDecimal high = positive(raw.substring(dash + 1));
        if (low == null || high == null || high.compareTo(low) < 0) return null;
        long cents = high.subtract(low).movePointRight(2).longValueExact();
        return low.add(BigDecimal.valueOf(ThreadLocalRandom.current().nextLong(cents + 1), 2));
    }

    private boolean invalidAmount(CommandSender sender) {
        messages.send(sender, "economy.invalid-amount", "{prefix} &cEnter a positive amount (e.g. 100, 2.5k).");
        return true;
    }

    private boolean storageUnavailable(CommandSender sender) {
        messages.send(sender, "economy.storage-unavailable", "{prefix} &cEconomy storage is unavailable.");
        return true;
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        List<String> out = new ArrayList<>();
        if (args.length == 2 && sender.hasPermission("allium.cheque.others")) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)
                        && (!(sender instanceof Player) || ((Player) sender).canSee(online))) out.add(online.getName());
            }
        }
        return out;
    }
}
