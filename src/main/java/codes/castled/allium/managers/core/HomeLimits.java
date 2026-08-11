package codes.castled.allium.managers.core;

import codes.castled.allium.managers.DB.Database;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Single source of truth for how many homes a player may set.
 *
 * <p>Two systems hand out homes: numbered permission nodes ({@code allium.sethome.<n>}) and the
 * {@code /core sethomes} database override. The effective limit is the higher of the two, so a
 * staff grant never silently downgrades a rank the player already has, and a rank upgrade is never
 * swallowed by a stale override.
 */
public final class HomeLimits {

    /** Effective limit for players who may set any number of homes. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /** What the database returns when no {@code /core sethomes} override exists. */
    public static final int NO_OVERRIDE = -1;

    private static final String NODE_PREFIX = "allium.sethome.";

    /** Highest node probed when a permission plugin resolves nodes it does not enumerate. */
    private static final int PROBE_CEILING = 100;

    /**
     * Overrides are read on every placeholder resolve, and each read costs two H2 round trips on
     * the main thread. A short TTL keeps scoreboard refreshes cheap while still picking up edits
     * made outside this process; writes through {@link #invalidate(UUID)} apply immediately.
     */
    private static final long OVERRIDE_TTL_MILLIS = 5_000L;
    private static final int CACHE_PRUNE_THRESHOLD = 256;
    private static final Map<UUID, CachedOverride> OVERRIDE_CACHE = new ConcurrentHashMap<>();

    private record CachedOverride(int value, long expiresAt) {}

    private HomeLimits() {
    }

    /**
     * The number of homes a player may set: the higher of their permission grant and their
     * {@code /core sethomes} override.
     */
    public static int getMaxHomes(Database database, OfflinePlayer player) {
        if (player == null) {
            return 0;
        }

        int override = getOverride(database, player.getUniqueId());
        Player online = player.getPlayer();
        if (online == null) {
            // Permissions cannot be resolved while a player is offline, so the override is all
            // there is to go on.
            return Math.max(override, 0);
        }

        int fromPermissions = getPermissionMaxHomes(online);
        return fromPermissions == UNLIMITED ? UNLIMITED : Math.max(fromPermissions, override);
    }

    /** The number of homes a player's permissions grant, ignoring any staff override. */
    public static int getPermissionMaxHomes(Player player) {
        if (player == null) {
            return 0;
        }

        if (player.hasPermission(NODE_PREFIX + "unlimited") || player.hasPermission(NODE_PREFIX + "*")) {
            return UNLIMITED;
        }

        int max = 0;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String node = info.getPermission();
            if (!node.regionMatches(true, 0, NODE_PREFIX, 0, NODE_PREFIX.length())) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(node.substring(NODE_PREFIX.length())));
            } catch (NumberFormatException ignored) {
                // Named children such as allium.sethome.unlimited are handled above.
            }
        }
        if (max > 0) {
            return max;
        }

        if (!player.hasPermission("allium.sethome")) {
            return 0;
        }

        // Some permission plugins resolve numbered nodes without listing them as effective
        // permissions, so probe before settling on the base grant of a single home.
        for (int i = PROBE_CEILING; i > 1; i--) {
            if (player.hasPermission(NODE_PREFIX + i)) {
                return i;
            }
        }
        return 1;
    }

    /** The raw {@code /core sethomes} value, or {@link #NO_OVERRIDE} when none is set. */
    public static int getOverride(Database database, UUID playerUUID) {
        if (database == null || playerUUID == null) {
            return NO_OVERRIDE;
        }

        long now = System.currentTimeMillis();
        CachedOverride cached = OVERRIDE_CACHE.get(playerUUID);
        if (cached != null && cached.expiresAt() > now) {
            return cached.value();
        }

        int value = database.getPlayerMaxHomes(playerUUID);
        if (OVERRIDE_CACHE.size() > CACHE_PRUNE_THRESHOLD) {
            OVERRIDE_CACHE.values().removeIf(entry -> entry.expiresAt() <= now);
        }
        OVERRIDE_CACHE.put(playerUUID, new CachedOverride(value, now + OVERRIDE_TTL_MILLIS));
        return value;
    }

    /** Drops the cached override for a player. Call after writing a new value. */
    public static void invalidate(UUID playerUUID) {
        if (playerUUID != null) {
            OVERRIDE_CACHE.remove(playerUUID);
        }
    }

    /** Drops every cached override. */
    public static void invalidateAll() {
        OVERRIDE_CACHE.clear();
    }

    /** Renders a limit for display, since {@link #UNLIMITED} is not a number worth showing. */
    public static String format(int maxHomes) {
        return maxHomes == UNLIMITED ? "unlimited" : String.valueOf(maxHomes);
    }
}
