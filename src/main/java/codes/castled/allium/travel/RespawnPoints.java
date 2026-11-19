package codes.castled.allium.travel;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * A player's bed or charged respawn anchor. Spigot 1.20.1 only has {@code getBedSpawnLocation()}
 * and {@code setBedSpawnLocation(Location, boolean)}; 1.20.4+ added {@code getRespawnLocation()}
 * and {@code setRespawnLocation(Location, boolean)} and Paper 26.x dropped the old names from
 * {@code Player}. Both are resolved once by name.
 */
final class RespawnPoints {

    private static final Method GETTER = findGetter();
    private static final Method SETTER = findSetter();

    private RespawnPoints() {
    }

    private static Method findGetter() {
        for (String name : new String[]{"getRespawnLocation", "getBedSpawnLocation"}) {
            try {
                return OfflinePlayer.class.getMethod(name);
            } catch (NoSuchMethodException missing) {
                // try the older name
            }
        }
        return null;
    }

    private static Method findSetter() {
        for (String name : new String[]{"setRespawnLocation", "setBedSpawnLocation"}) {
            try {
                return Player.class.getMethod(name, Location.class, boolean.class);
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

    /** Server thread: updates an online player's bed/respawn location (forced=true so daytime bed clicks work). */
    static boolean set(Player player, Location location) {
        if (SETTER == null || player == null || location == null || location.getWorld() == null) {
            return false;
        }
        try {
            SETTER.invoke(player, location, true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return false;
        }
    }
}
