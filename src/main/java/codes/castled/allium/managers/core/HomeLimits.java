package codes.castled.allium.managers.core;

import codes.castled.allium.managers.DB.Database;

import java.util.Collection;
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
 *
 * <p>Permissions normally only resolve for players who are online. When LuckPerms is installed its
 * users get resolved from storage too, so offline operations such as {@code /core sethomes +N} know
 * what the affected player already has.
 */
public final class HomeLimits {

    /** Effective limit for players who may set any number of homes. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /** What the database returns when no {@code /core sethomes} override exists. */
    public static final int NO_OVERRIDE = -1;

    /**
     * The outcome of resolving a player's permission grant while they are offline, via LuckPerms.
     * {@code resolved()} is false when LuckPerms is absent or the player has no LuckPerms user, so
     * callers can tell "they genuinely have no homes" apart from "nobody knows".
     */
    public record OfflinePermissionResult(boolean resolved, int maxHomes) {}

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
        int fromPermissions;
        if (online == null) {
            // Permissions normally cannot be resolved while a player is offline, but LuckPerms can
            // load their user from storage and answer anyway. Without it the override is all there
            // is to go on.
            OfflinePermissionResult offline = resolveOfflinePermissionMaxHomes(player);
            if (!offline.resolved()) {
                return Math.max(override, 0);
            }
            fromPermissions = offline.maxHomes();
        } else {
            fromPermissions = getPermissionMaxHomes(online);
        }
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

    /**
     * The number of homes a player's permissions grant, ignoring any staff override.
     *
     * <p>For online players this is {@link #getPermissionMaxHomes(Player)}. For offline players it
     * falls back to LuckPerms, which can resolve permissions without a live connection;
     * {@code resolved()} is false when that is not possible.
     */
    public static OfflinePermissionResult getOfflinePermissionMaxHomes(OfflinePlayer player) {
        if (player == null) {
            return new OfflinePermissionResult(false, 0);
        }
        Player online = player.getPlayer();
        if (online != null) {
            return new OfflinePermissionResult(true, getPermissionMaxHomes(online));
        }
        return resolveOfflinePermissionMaxHomes(player);
    }

    /**
     * Resolves a player's permission homes through LuckPerms while they are offline.
     *
     * <p>All access is reflective so LuckPerms stays an optional soft-dependency. The user is
     * pulled from LuckPerms the same way the rest of the plugin does
     * ({@code LuckPermsProvider.get()} + {@code UserManager.loadUser}), and inherited permission
     * nodes are examined with non-contextual query options, mirroring the meta lookup in
     * {@code JoinQuitMessages}.
     *
     * <p>Permission-plugin default values (grants that apply to anyone without an explicit node)
     * are not visible on an offline user's nodes and are therefore not credited here.
     */
    private static OfflinePermissionResult resolveOfflinePermissionMaxHomes(OfflinePlayer player) {
        try {
            Object luckPerms = Class.forName("net.luckperms.api.LuckPermsProvider")
                    .getMethod("get").invoke(null);
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            Object userManager = luckPermsClass.getMethod("getUserManager").invoke(luckPerms);

            UUID uuid = player.getUniqueId();
            Class<?> userManagerClass = Class.forName("net.luckperms.api.model.user.UserManager");
            Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
            Object user = userManagerClass.getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null) {
                Object future = userManagerClass.getMethod("loadUser", UUID.class).invoke(userManager, uuid);
                user = future == null ? null : Class.forName("java.util.concurrent.CompletableFuture")
                        .getMethod("join").invoke(future);
            }
            if (user == null) {
                return new OfflinePermissionResult(false, 0);
            }

            Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
            Object nonContextual = queryOptionsClass.getMethod("nonContextual").invoke(null);
            Class<?> nodeTypeClass = Class.forName("net.luckperms.api.node.NodeType");
            Object permissionType = nodeTypeClass.getField("PERMISSION").get(null);
            Collection<?> nodes = (Collection<?>) userClass
                    .getMethod("resolveInheritedNodes", nodeTypeClass, queryOptionsClass)
                    .invoke(user, permissionType, nonContextual);

            Class<?> nodeClass = Class.forName("net.luckperms.api.node.Node");
            int max = 0;
            boolean hasBaseGrant = false;
            boolean baseDenied = false;
            for (Object node : nodes) {
                Object keyObj = nodeClass.getMethod("getKey").invoke(node);
                if (!(keyObj instanceof String raw)) {
                    continue;
                }
                boolean negated = raw.startsWith("!");
                String key = negated ? raw.substring(1) : raw;
                String remainder;
                if (key.equalsIgnoreCase("allium.sethome")) {
                    remainder = "";
                } else if (key.regionMatches(true, 0, NODE_PREFIX, 0, NODE_PREFIX.length())) {
                    remainder = key.substring(NODE_PREFIX.length());
                } else {
                    continue;
                }
                if (negated) {
                    if (remainder.isEmpty()) {
                        baseDenied = true;
                    }
                    continue;
                }
                if (remainder.equalsIgnoreCase("unlimited") || remainder.equals("*")) {
                    return new OfflinePermissionResult(true, UNLIMITED);
                }
                if (remainder.isEmpty()) {
                    hasBaseGrant = true;
                    continue;
                }
                try {
                    max = Math.max(max, Integer.parseInt(remainder));
                } catch (NumberFormatException ignored) {
                    // Named children such as allium.sethome.unlimited are handled above.
                }
            }
            if (max > 0) {
                return new OfflinePermissionResult(true, max);
            }
            return new OfflinePermissionResult(true, hasBaseGrant && !baseDenied ? 1 : 0);
        } catch (Throwable ignored) {
            // LuckPerms is optional or unavailable; offline permission resolution is not possible.
            return new OfflinePermissionResult(false, 0);
        }
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
