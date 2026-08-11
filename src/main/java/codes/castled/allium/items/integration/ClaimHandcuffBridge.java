package codes.castled.allium.items.integration;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Late-bound adapter to GPExpansion's fork-neutral GPBridge. Keeping every GPX reference reflective
 * lets Allium start without GPX and works with either upstream GriefPrevention or GriefPrevention3D.
 */
public final class ClaimHandcuffBridge {
    public record Check(boolean allowed, String claimId, String error) {
        static Check denied(final String error) { return new Check(false, null, error); }
        static Check allowed(final String claimId) { return new Check(true, claimId, null); }
    }

    public Check check(final Player actor, final Player target) {
        try {
            final Runtime runtime = runtime();
            if (runtime == null) {
                return Check.denied("Claim handcuffs require GPExpansion and GriefPrevention.");
            }
            final Object claim = optional(invoke(runtime.bridge(), "getClaimAt",
                    new Class<?>[]{Location.class, Player.class}, target.getLocation(), target));
            if (claim == null) return Check.denied("That player is not inside a claim.");
            final Object mainClaim = mainClaim(runtime.bridge(), claim);
            final String claimId = (String) optional(invoke(runtime.bridge(), "getClaimId",
                    new Class<?>[]{Object.class}, mainClaim));
            if (claimId == null) return Check.denied("Could not resolve that claim's ID.");

            final boolean owner = (boolean) invoke(runtime.bridge(), "isOwner",
                    new Class<?>[]{Object.class, UUID.class}, mainClaim, actor.getUniqueId());
            final Set<?> actorTrust = (Set<?>) invoke(runtime.bridge(), "getTrustLevels",
                    new Class<?>[]{Object.class, UUID.class}, mainClaim, actor.getUniqueId());
            final boolean manager = actorTrust.stream().anyMatch(level -> "MANAGE".equals(level.toString()));
            if (!owner && !manager) {
                return Check.denied("You must own that claim or have manager trust.");
            }

            final boolean targetOwner = (boolean) invoke(runtime.bridge(), "isOwner",
                    new Class<?>[]{Object.class, UUID.class}, mainClaim, target.getUniqueId());
            final Collection<?> targetTrust = (Collection<?>) invoke(runtime.bridge(), "getTrustLevels",
                    new Class<?>[]{Object.class, UUID.class}, mainClaim, target.getUniqueId());
            if (targetOwner || !targetTrust.isEmpty()) {
                return Check.denied("Claim handcuffs only work on untrusted players.");
            }

            final Object dataStore = runtime.plugin().getClass().getMethod("getClaimDataStore")
                    .invoke(runtime.plugin());
            @SuppressWarnings("unchecked")
            final Set<UUID> banned = (Set<UUID>) dataStore.getClass()
                    .getMethod("getBannedPlayers", String.class).invoke(dataStore, claimId);
            if (banned.contains(target.getUniqueId())) {
                return Check.denied(target.getName() + " is already banned from that claim.");
            }
            return Check.allowed(claimId);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return Check.denied("Claim integration is unavailable: " + concise(e));
        }
    }

    public boolean isStillInside(final Player target, final String expectedClaimId) {
        try {
            final Runtime runtime = runtime();
            if (runtime == null) return false;
            final Object claim = optional(invoke(runtime.bridge(), "getClaimAt",
                    new Class<?>[]{Location.class, Player.class}, target.getLocation(), target));
            if (claim == null) return false;
            final Object mainClaim = mainClaim(runtime.bridge(), claim);
            final String actual = (String) optional(invoke(runtime.bridge(), "getClaimId",
                    new Class<?>[]{Object.class}, mainClaim));
            return expectedClaimId.equals(actual);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** The caller must dismount and restore the target before invoking this method. */
    public boolean ban(final String claimId, final Player target, final CommandSender actor) {
        try {
            final Runtime runtime = runtime();
            if (runtime == null) return false;
            final Object dataStore = runtime.plugin().getClass().getMethod("getClaimDataStore")
                    .invoke(runtime.plugin());
            dataStore.getClass().getMethod("addBannedPlayer", String.class, UUID.class, CommandSender.class)
                    .invoke(dataStore, claimId, target.getUniqueId(), actor);
            dataStore.getClass().getMethod("save").invoke(dataStore);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    private Runtime runtime() throws ReflectiveOperationException {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("GPExpansion");
        if (plugin == null || !plugin.isEnabled()) return null;
        final ClassLoader loader = plugin.getClass().getClassLoader();
        final Class<?> bridgeClass = loader.loadClass("codes.castled.gpexpansion.gp.GPBridge");
        final Object bridge = bridgeClass.getConstructor().newInstance();
        final boolean available = (boolean) bridgeClass.getMethod("isAvailable").invoke(bridge);
        return available ? new Runtime(plugin, bridge) : null;
    }

    private Object mainClaim(final Object bridge, final Object claim) throws ReflectiveOperationException {
        final Object parent = optional(invoke(bridge, "getParentClaim",
                new Class<?>[]{Object.class}, claim));
        return parent == null ? claim : parent;
    }

    private Object invoke(final Object target, final String name, final Class<?>[] types,
                          final Object... args) throws ReflectiveOperationException {
        final Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, args);
    }

    private Object optional(final Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : null;
    }

    private static String concise(final Throwable throwable) {
        final Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        final String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private record Runtime(Plugin plugin, Object bridge) {}
}
