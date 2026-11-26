package codes.castled.allium.api;

import org.bukkit.entity.Player;

/** Optional player selection, resolved on the player's owning thread. */
public interface JoinQuitMessageSelection {
    record Selection(String template) {}

    /** Null indicates the player has never chosen in this provider. A null template restores defaults. */
    Selection selected(Player player, String kind);
}
