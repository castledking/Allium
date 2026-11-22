package codes.castled.allium.ride;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link ClaimAccess} through GPExpansion's fork-neutral {@code GPBridge} and its claim data store,
 * as pre-recode Allium's claim handcuffs used them. Every GPExpansion reference is reflective, so
 * Allium starts without it, and it works over upstream GriefPrevention or GriefPrevention3D.
 */
public final class GpExpansionClaims implements ClaimAccess {

    private final Supplier<PluginManager> plugins;

    public GpExpansionClaims(Supplier<PluginManager> plugins) {
        this.plugins = plugins;
    }

    private record Runtime(Plugin plugin, Object bridge) {
    }

    @Override
    public Check check(Player actor, Player target) {
        try {
            Runtime runtime = runtime();
            if (runtime == null) {
                return Check.denied(Refusal.UNAVAILABLE);
            }
            Object claim = optional(invoke(runtime.bridge(), "getClaimAt", new Class<?>[]{Location.class, Player.class},
                    target.getLocation(), target));
            if (claim == null) {
                return Check.denied(Refusal.NOT_IN_CLAIM);
            }
            Object main = mainClaim(runtime.bridge(), claim);
            String claimId = (String) optional(invoke(runtime.bridge(), "getClaimId", new Class<?>[]{Object.class}, main));
            if (claimId == null) {
                return Check.denied(Refusal.NO_CLAIM_ID);
            }
            boolean owner = (boolean) invoke(runtime.bridge(), "isOwner", new Class<?>[]{Object.class, UUID.class}, main, actor.getUniqueId());
            Collection<?> actorTrust = (Collection<?>) invoke(runtime.bridge(), "getTrustLevels",
                    new Class<?>[]{Object.class, UUID.class}, main, actor.getUniqueId());
            boolean manager = actorTrust.stream().anyMatch(level -> "MANAGE".equals(String.valueOf(level)));
            if (!owner && !manager) {
                return Check.denied(Refusal.NOT_MANAGER);
            }
            boolean targetOwner = (boolean) invoke(runtime.bridge(), "isOwner", new Class<?>[]{Object.class, UUID.class}, main, target.getUniqueId());
            Collection<?> targetTrust = (Collection<?>) invoke(runtime.bridge(), "getTrustLevels",
                    new Class<?>[]{Object.class, UUID.class}, main, target.getUniqueId());
            if (targetOwner || !targetTrust.isEmpty()) {
                return Check.denied(Refusal.TRUSTED);
            }
            Object store = dataStore(runtime);
            @SuppressWarnings("unchecked")
            Set<UUID> banned = (Set<UUID>) store.getClass().getMethod("getBannedPlayers", String.class).invoke(store, claimId);
            if (banned != null && banned.contains(target.getUniqueId())) {
                return Check.denied(Refusal.ALREADY_BANNED);
            }
            return Check.allowed(claimId);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            return Check.denied(Refusal.UNAVAILABLE, concise(failed));
        }
    }

    @Override
    public boolean isInside(Player target, String claimId) {
        try {
            Runtime runtime = runtime();
            if (runtime == null) {
                return false;
            }
            Object claim = optional(invoke(runtime.bridge(), "getClaimAt", new Class<?>[]{Location.class, Player.class},
                    target.getLocation(), target));
            if (claim == null) {
                return false;
            }
            Object main = mainClaim(runtime.bridge(), claim);
            return claimId.equals(optional(invoke(runtime.bridge(), "getClaimId", new Class<?>[]{Object.class}, main)));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            return false;
        }
    }

    @Override
    public boolean ban(String claimId, Player target, CommandSender actor) {
        try {
            Runtime runtime = runtime();
            if (runtime == null) {
                return false;
            }
            Object store = dataStore(runtime);
            store.getClass().getMethod("addBannedPlayer", String.class, UUID.class, CommandSender.class)
                    .invoke(store, claimId, target.getUniqueId(), actor);
            store.getClass().getMethod("save").invoke(store);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            return false;
        }
    }

    @Nullable
    private Runtime runtime() throws ReflectiveOperationException {
        PluginManager manager = plugins.get();
        Plugin plugin = manager == null ? null : manager.getPlugin("GPExpansion");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        Class<?> type = plugin.getClass().getClassLoader().loadClass("codes.castled.gpexpansion.gp.GPBridge");
        Object bridge = type.getConstructor().newInstance();
        return (boolean) type.getMethod("isAvailable").invoke(bridge) ? new Runtime(plugin, bridge) : null;
    }

    private static Object dataStore(Runtime runtime) throws ReflectiveOperationException {
        return runtime.plugin().getClass().getMethod("getClaimDataStore").invoke(runtime.plugin());
    }

    private static Object mainClaim(Object bridge, Object claim) throws ReflectiveOperationException {
        Object parent = optional(invoke(bridge, "getParentClaim", new Class<?>[]{Object.class}, claim));
        return parent == null ? claim : parent;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, args);
    }

    @Nullable
    private static Object optional(@Nullable Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : null;
    }

    private static String concise(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
