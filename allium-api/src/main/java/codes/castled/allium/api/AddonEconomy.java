package codes.castled.allium.api;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Allium's economy, for addons that charge or pay players. Calls are synchronous, like Vault's,
 * and work without Vault installed. Every method is safe while the economy is off: checks and
 * moves then fail and nothing changes.
 *
 * <p>Addons performing paid player-data mutations should prefer
 * {@link #executePaidAction(Player, double, BooleanSupplier)} and
 * {@link #reconcilePaidActions(Player)}, which own reservation, disconnect validation, durable
 * receipt recording ({@code allium:addon_receipts}), capture/release settlement, and deferred
 * reconciliation in one shared lifecycle.
 */
public interface AddonEconomy {

    /** Player-data PDC key holding comma-separated operation UUIDs for proven paid addon mutations. */
    NamespacedKey ADDON_RECEIPTS_KEY = new NamespacedKey("allium", "addon_receipts");

    /** Player-data PDC key holding deferred paid-action settlements awaiting retry. */
    NamespacedKey ADDON_PENDING_SETTLEMENTS_KEY = new NamespacedKey("allium", "addon_pending_settlements");

    int MAX_ADDON_RECEIPTS = 64;

    /** Lifecycle status of a receipt-backed paid addon action. */
    enum PaidActionStatus {
        FREE_APPLIED,
        CAPTURED,
        CAPTURE_DEFERRED,
        RELEASED,
        RELEASE_DEFERRED,
        INSUFFICIENT_FUNDS,
        ECONOMY_UNAVAILABLE,
        DISCONNECTED,
        REFUSED
    }

    /** Result of {@link #executePaidAction(Player, double, BooleanSupplier)}. */
    record PaidActionResult(PaidActionStatus status, UUID operationId) {
        public boolean applied() {
            return status == PaidActionStatus.FREE_APPLIED
                    || status == PaidActionStatus.CAPTURED
                    || status == PaidActionStatus.CAPTURE_DEFERRED;
        }
    }

    /** An economy that is never available. */
    AddonEconomy UNAVAILABLE = new AddonEconomy() {
        @Override public boolean available() { return false; }
        @Override public boolean has(UUID player, double amount) { return false; }
        @Override public boolean withdraw(UUID player, double amount) { return false; }
        @Override public boolean deposit(UUID player, double amount) { return false; }
        @Override public String format(double amount) { return String.format(java.util.Locale.ROOT, "$%,.2f", amount); }
    };

    /** Whether money can be checked and moved right now. */
    boolean available();

    /** Whether the player's account holds at least {@code amount}. */
    boolean has(UUID player, double amount);

    /** Takes {@code amount} (whole cents, above zero) from the player. False when nothing was taken. */
    boolean withdraw(UUID player, double amount);

    /** Gives {@code amount} (whole cents, above zero) to the player, such as a refund. False when nothing was given. */
    boolean deposit(UUID player, double amount);

    /** {@code amount} as Allium shows money, such as {@code $1,500.00}. */
    String format(double amount);

    /**
     * Places a durable hold of {@code amount} (whole cents, above zero) for an addon workflow
     * identified by {@code operationId}. Calling again with the same {@code operationId} is
     * idempotent.
     */
    default boolean reserve(UUID operationId, UUID player, double amount) {
        return withdraw(player, amount);
    }

    /**
     * Captures a previously reserved {@code operationId} after the paid addon mutation succeeded.
     * Idempotent when re-invoked for an already-captured operation.
     */
    default boolean capture(UUID operationId, UUID player, double amount) {
        return true;
    }

    /**
     * Releases (refunds) a previously reserved {@code operationId} when the paid addon mutation
     * did not succeed. Idempotent when re-invoked for an already-released operation.
     */
    default boolean release(UUID operationId, UUID player, double amount) {
        return deposit(player, amount);
    }

    /**
     * Returns whether {@code operationId} currently has an active, unsettled reservation hold.
     */
    default boolean isHeld(UUID operationId) {
        return false;
    }

    /**
     * Executes a paid player-data mutation under a stable {@code operationId}: checks session
     * liveness, reserves funds when {@code amount > 0}, runs {@code action}, records the durable
     * receipt in {@link #ADDON_RECEIPTS_KEY} + {@link Player#saveData()} when applied, and settles
     * via {@link #capture} or {@link #release} with durable deferred reconciliation on transient
     * settlement failure.
     */
    default PaidActionResult executePaidAction(Player player, double amount, BooleanSupplier action) {
        return executePaidAction(player, amount, action, ADDON_PENDING_SETTLEMENTS_KEY);
    }

    /** Variant allowing a custom pending-settlements PDC key (for compatibility/testing). */
    default PaidActionResult executePaidAction(Player player, double amount, BooleanSupplier action,
                                               NamespacedKey pendingKey) {
        UUID operationId = UUID.randomUUID();
        if (player == null || !player.isOnline()) {
            return new PaidActionResult(PaidActionStatus.DISCONNECTED, operationId);
        }
        if (amount > 0) {
            if (!available()) {
                return new PaidActionResult(PaidActionStatus.ECONOMY_UNAVAILABLE, operationId);
            }
            if (!has(player.getUniqueId(), amount) || !reserve(operationId, player.getUniqueId(), amount)) {
                return new PaidActionResult(PaidActionStatus.INSUFFICIENT_FUNDS, operationId);
            }
        }

        boolean applied;
        try {
            applied = player.isOnline() && action != null && action.getAsBoolean();
        } catch (RuntimeException failed) {
            applied = false;
        }

        if (applied) {
            if (amount > 0) {
                AddonPaidReceipts.recordReceipt(player, operationId);
                player.saveData();
                if (capture(operationId, player.getUniqueId(), amount)) {
                    return new PaidActionResult(PaidActionStatus.CAPTURED, operationId);
                }
                AddonPaidReceipts.recordPending(player, pendingKey, operationId, amount);
                player.saveData();
                return new PaidActionResult(PaidActionStatus.CAPTURE_DEFERRED, operationId);
            }
            return new PaidActionResult(PaidActionStatus.FREE_APPLIED, operationId);
        }

        if (amount > 0) {
            if (release(operationId, player.getUniqueId(), amount)) {
                return new PaidActionResult(PaidActionStatus.RELEASED, operationId);
            }
            AddonPaidReceipts.recordPending(player, pendingKey, operationId, amount);
            player.saveData();
            return new PaidActionResult(PaidActionStatus.RELEASE_DEFERRED, operationId);
        }
        return new PaidActionResult(PaidActionStatus.REFUSED, operationId);
    }

    /**
     * Reconciles any deferred paid-action settlements for {@code player} using the durable receipt
     * proof in {@link #ADDON_RECEIPTS_KEY}: captures iff the receipt is present, releases otherwise.
     */
    default int reconcilePaidActions(Player player) {
        return reconcilePaidActions(player, ADDON_PENDING_SETTLEMENTS_KEY);
    }

    /** Variant allowing a custom pending-settlements PDC key (for compatibility/testing). */
    default int reconcilePaidActions(Player player, NamespacedKey pendingKey) {
        if (player == null || !available()) return 0;
        List<AddonPaidReceipts.PendingEntry> pending = AddonPaidReceipts.readPending(player, pendingKey);
        if (pending.isEmpty()) return 0;
        Set<UUID> receipts = AddonPaidReceipts.readReceipts(player);
        List<AddonPaidReceipts.PendingEntry> remaining = new ArrayList<>();
        int settled = 0;
        for (AddonPaidReceipts.PendingEntry item : pending) {
            boolean hasReceipt = receipts.contains(item.operationId());
            boolean done = hasReceipt
                    ? capture(item.operationId(), player.getUniqueId(), item.amount())
                    : release(item.operationId(), player.getUniqueId(), item.amount());
            if (done || !isHeld(item.operationId())) {
                settled++;
            } else {
                remaining.add(item);
            }
        }
        if (settled > 0) {
            AddonPaidReceipts.writePending(player, pendingKey, remaining);
            player.saveData();
        }
        return settled;
    }

    /** Package-private PDC helpers supporting {@link #executePaidAction} and {@link #reconcilePaidActions}. */
    final class AddonPaidReceipts {
        private AddonPaidReceipts() {}

        record PendingEntry(UUID operationId, double amount) {}

        static void recordReceipt(Player player, UUID operationId) {
            LinkedHashSet<UUID> receipts = readReceipts(player);
            receipts.add(operationId);
            while (receipts.size() > MAX_ADDON_RECEIPTS) {
                Iterator<UUID> it = receipts.iterator();
                it.next();
                it.remove();
            }
            String joined = String.join(",", receipts.stream().map(UUID::toString).toList());
            player.getPersistentDataContainer().set(ADDON_RECEIPTS_KEY, PersistentDataType.STRING, joined);
        }

        static LinkedHashSet<UUID> readReceipts(Player player) {
            String raw = player.getPersistentDataContainer().get(ADDON_RECEIPTS_KEY, PersistentDataType.STRING);
            LinkedHashSet<UUID> out = new LinkedHashSet<>();
            if (raw == null || raw.isBlank()) return out;
            for (String token : raw.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    out.add(UUID.fromString(trimmed));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return out;
        }

        static void recordPending(Player player, NamespacedKey key, UUID operationId, double amount) {
            List<PendingEntry> list = new ArrayList<>(readPending(player, key));
            list.removeIf(existing -> existing.operationId().equals(operationId));
            list.add(new PendingEntry(operationId, amount));
            writePending(player, key, list);
        }

        static List<PendingEntry> readPending(Player player, NamespacedKey key) {
            String raw = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (raw == null || raw.isBlank()) return List.of();
            List<PendingEntry> out = new ArrayList<>();
            for (String entry : raw.split(";")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                try {
                    UUID id = UUID.fromString(trimmed.substring(0, eq));
                    double amount = Double.parseDouble(trimmed.substring(eq + 1));
                    if (Double.isFinite(amount) && amount > 0) {
                        out.add(new PendingEntry(id, amount));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            return out;
        }

        static void writePending(Player player, NamespacedKey key, List<PendingEntry> list) {
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            if (list.isEmpty()) {
                pdc.remove(key);
            } else {
                String encoded = String.join(";", list.stream()
                        .map(s -> s.operationId() + "=" + s.amount())
                        .toList());
                pdc.set(key, PersistentDataType.STRING, encoded);
            }
        }
    }
}
