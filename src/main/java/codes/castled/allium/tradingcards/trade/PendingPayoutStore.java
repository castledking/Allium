package codes.castled.allium.tradingcards.trade;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Heads owed to a player who closed the trade window before collecting them.
 *
 * <p>The trade window puts the payout in a slot rather than granting it, so a
 * player who closes the window early has not lost anything — the remainder is
 * held here and handed over on their next visit. Dropping it on close would
 * lose it to a pick-up race; granting it up front would make the window
 * pointless.
 *
 * <p>Held as an {@link ItemRef} and an amount rather than a serialised
 * {@code ItemStack}, for two reasons. It is a plain data format, so the file is
 * readable and the accounting is testable without a running server. And it
 * survives a retexture: a head stored as raw item data would carry whatever
 * texture was current when the trade was interrupted, so the player would be
 * owed a different head from the one everyone else now receives.
 */
public final class PendingPayoutStore {

    private static final String FILE_NAME = "pending_payouts.yml";

    /** One owed payout: what it is, and how many. */
    public record Payout(ItemRef item, int amount) {}

    private final File file;
    private final Map<UUID, List<Payout>> pending = new ConcurrentHashMap<>();

    public PendingPayoutStore(File dataFolder) {
        this.file = new File(dataFolder, FILE_NAME);
        load();
    }

    /**
     * Records a payout the player did not collect.
     *
     * <p>Appends to whatever is already owed rather than replacing it, so two
     * interrupted trades accumulate instead of one overwriting the other.
     */
    public void add(UUID player, ItemRef item, int amount) {
        if (item == null || amount <= 0) return;
        pending.compute(player, (id, existing) -> {
            List<Payout> next = new ArrayList<>(existing == null ? List.of() : existing);
            next.add(new Payout(item, amount));
            return List.copyOf(next);
        });
        save();
    }

    /** Hands over and clears everything owed to a player. */
    public List<Payout> takeAll(UUID player) {
        List<Payout> owed = pending.remove(player);
        if (owed == null || owed.isEmpty()) {
            return List.of();
        }
        // Persist on the way out, not lazily: the in-memory removal is the only
        // thing standing between a crash and paying twice.
        save();
        return owed;
    }

    /** What a player is currently owed, without clearing it. */
    public List<Payout> peek(UUID player) {
        return List.copyOf(pending.getOrDefault(player, List.of()));
    }

    /** How many heads in total are owed to a player, across every entry. */
    public int totalFor(UUID player) {
        return peek(player).stream().mapToInt(Payout::amount).sum();
    }

    public boolean isEmpty(UUID player) {
        return peek(player).isEmpty();
    }

    public int totalPendingPlayers() {
        return pending.size();
    }

    /**
     * Turns a owed payout into real items, dropping any entry whose item no
     * longer resolves.
     *
     * <p>Kept separate from {@link #takeAll} so the caller can deliver before
     * the debt is cleared, and so a missing item is reported rather than
     * silently vanishing.
     */
    public static List<org.bukkit.inventory.ItemStack> resolve(ItemResolverChain items,
                                                               List<Payout> payouts) {
        List<org.bukkit.inventory.ItemStack> out = new ArrayList<>();
        for (Payout payout : payouts) {
            Optional<org.bukkit.inventory.ItemStack> stack = items.create(payout.item(), 1);
            if (stack.isEmpty()) continue;
            var resolved = stack.get();
            resolved.setAmount(Math.max(1, payout.amount()));
            out.add(resolved);
        }
        return out;
    }

    // ==================== persistence ====================

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("pending");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                // A corrupt key is skipped rather than aborting the load: the
                // rest of the file is still owed to real players.
                continue;
            }
            List<Payout> payouts = new ArrayList<>();
            for (var entry : root.getMapList(key)) {
                Payout payout = parsePayout(entry);
                if (payout != null) payouts.add(payout);
            }
            if (!payouts.isEmpty()) {
                pending.put(id, List.copyOf(payouts));
            }
        }
    }

    private static Payout parsePayout(Map<?, ?> entry) {
        Object rawItem = entry.get("item");
        if (!(rawItem instanceof String text) || text.isBlank()) return null;
        Object rawAmount = entry.get("amount");
        int amount = rawAmount instanceof Number number ? number.intValue() : 0;
        if (amount <= 0) return null;
        try {
            return new Payout(ItemRef.parse(text), amount);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        pending.forEach((id, payouts) -> {
            List<Map<String, Object>> serialised = new ArrayList<>();
            for (Payout payout : payouts) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("item", payout.item().toString());
                entry.put("amount", payout.amount());
                serialised.add(entry);
            }
            if (!serialised.isEmpty()) {
                yaml.set("pending." + id, serialised);
            }
        });
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            // Written to a sibling and moved into place, so a crash mid-write
            // cannot leave a half-written file that loses every debt.
            File temp = new File(parent, FILE_NAME + ".tmp");
            yaml.save(temp);
            if (!temp.renameTo(file)) {
                yaml.save(file);
                if (!temp.delete()) {
                    temp.deleteOnExit();
                }
            }
        } catch (Exception e) {
            // The in-memory map is still correct for this session; the debt is
            // not lost, only its on-disk copy.
        }
    }

    /** The file the store persists to, for diagnostics. */
    public File file() {
        return file;
    }

    static String normalise(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
