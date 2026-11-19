package codes.castled.allium.travel;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * A player's bed or charged respawn anchor. Spigot 1.20.1 only has {@code getBedSpawnLocation()};
 * 1.20.4+ added {@code getRespawnLocation()} and Paper 26.x dropped the old name from {@code Player},
 * so the method is found once by name on {@link OfflinePlayer}. For an online player both check that
 * the bed or anchor is still there; for an offline one they read the saved respawn point.
 */
final class RespawnPoints {

    private static final Method GETTER = find();

    private RespawnPoints() {
    }

    private static Method find() {
        for (String name : new String[]{"getRespawnLocation", "getBedSpawnLocation"}) {
            try {
                return OfflinePlayer.class.getMethod(name);
            } catch (NoSuchMethodException missing) {
                // try the older name
            }
        }
        return null;
    }

    /** Server thread: the check may load the bed's chunk, or read an offline player's file. */
    @Nullable
    static Location of(OfflinePlayer player) {
        if (GETTER == null || player == null) {
            return null;
        }
        try {
            Object location = GETTER.invoke(player);
            return location instanceof Location found && found.getWorld() != null ? found : null;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return null;
        }
    }
}
