package codes.castled.allium.api;

import java.util.UUID;

/**
 * Allium's economy, for addons that charge or pay players. Calls are synchronous, like Vault's,
 * and work without Vault installed. Every method is safe while the economy is off: checks and
 * moves then fail and nothing changes.
 */
public interface AddonEconomy {

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
}
