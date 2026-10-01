package codes.castled.allium.packetevents;

import org.bukkit.entity.Player;

/**
 * Per-viewer control of the locator bar (the waypoint dots added in 1.21.6).
 *
 * Vanilla decides who appears on a viewer's locator bar with {@code CraftPlayer#canSee},
 * so the only API-level way to remove a dot is {@code hidePlayer} - which also strips the
 * player from the tab list and makes {@code canSee} false, breaking name completion and
 * any plugin (EssentialsX especially) that treats "cannot see" as "not there".
 *
 * The PacketEvents implementation instead filters the waypoint packets themselves, leaving
 * tab list and {@code canSee} untouched.
 */
public interface LocatorVisibility {

    /** False when no implementation is available (no PacketEvents, or disabled in config). */
    boolean isActive();

    /** Hides or shows {@code target}'s locator dot for {@code viewer} only. */
    void setHidden(Player viewer, Player target, boolean hidden);

    /** Forgets all state for a player, in both directions. */
    void clear(Player player);

    /** Unregisters listeners. */
    void shutdown();
}
