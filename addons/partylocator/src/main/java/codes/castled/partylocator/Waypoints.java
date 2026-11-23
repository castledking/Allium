package codes.castled.partylocator;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Puts a target's dot on, or takes it off, one viewer's locator bar. */
interface Waypoints {
    /** Shows {@code target} to {@code viewer} at its current block, or withdraws its dot. */
    void apply(Player viewer, Player target, boolean show);

    /** Shows {@code targetId} to {@code viewer} at the snapped block coordinates, or withdraws its dot. */
    default void apply(Player viewer, UUID targetId, int x, int y, int z, boolean show) {
        Player target = viewer.getServer().getPlayer(targetId);
        if (target != null) apply(viewer, target, show);
    }

    /** Forgets a player in both directions, withdrawing the dots it was shown. */
    void clear(Player player);

    void shutdown();
}
