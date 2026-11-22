package codes.castled.partylocator;

import org.bukkit.entity.Player;

/** Puts a target's dot on, or takes it off, one viewer's locator bar. */
interface Waypoints {
    /** Shows {@code target} to {@code viewer} at its current block, or withdraws its dot. */
    void apply(Player viewer, Player target, boolean show);

    /** Forgets a player in both directions, withdrawing the dots it was shown. */
    void clear(Player player);

    void shutdown();
}
