package codes.castled.allium.travel;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * A player's bed or charged respawn anchor. Spigot 1.20.1 only has {@code getBedSpawnLocation()};
 * 1.20.4+ added {@code getRespawnLocation()} and Paper 26.x removed the old name, so the method is
 * found once by name. Both check that the bed or anchor is still there and return null when not.
 */
final class RespawnPoints {

    private static final Method GETTER = find();

    private RespawnPoints() {
    }

    private static Method find() {
        for (String name : new String[]{"getRespawnLocation", "getBedSpawnLocation"}) {
            try {
                return Player.class.getMethod(name);
            } catch (NoSuchMethodException missing) {
                // try the older name
            }
        }
        return null;
    }

    /** Server thread: the check may load the bed's chunk. */
    @Nullable
    static Location of(Player player) {
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
