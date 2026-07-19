package codes.castled.allium.permissions.command;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class LuckPermsCommandPermissionAdapter implements CommandPermissionAdapter {

    @Override
    public Optional<PermissionResult> resolve(CommandPermissionContext context) {
        List<String> candidates = PermissionCandidates.forContext(context);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            Class<?> playerAdapterClass = Class.forName("net.luckperms.api.platform.PlayerAdapter");
            Class<?> permissionDataClass = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
            Class<?> resultClass = Class.forName("net.luckperms.api.cacheddata.Result");
            Class<?> nodeClass = Class.forName("net.luckperms.api.node.Node");
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            Object playerAdapter = luckPermsClass
                    .getMethod("getPlayerAdapter", Class.class)
                    .invoke(luckPerms, Player.class);
            Object permissionData = playerAdapterClass
                    .getMethod("getPermissionData", Object.class)
                    .invoke(playerAdapter, context.player());
            Method queryPermission = permissionDataClass.getMethod("queryPermission", String.class);
            Object permissionMapValue = permissionDataClass.getMethod("getPermissionMap").invoke(permissionData);
            Set<String> exactEffectivePermissions = permissionMapValue instanceof Map<?, ?> permissionMap
                    ? permissionMap.keySet().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(permission -> permission.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet())
                    : Set.of();

            for (String candidate : candidates) {
                // Querying an exact node already lets LuckPerms return the wildcard node that
                // caused it. Querying a wildcard candidate directly could mistake a default
                // false parent permission for a command-specific denial.
                if (candidate.endsWith(".*")) {
                    continue;
                }
                if (!context.registeredPermissions().contains(candidate)
                        && !exactEffectivePermissions.contains(candidate)) {
                    continue;
                }
                Object queryResult = queryPermission.invoke(permissionData, candidate);
                Object tristate = resultClass.getMethod("result").invoke(queryResult);
                if (tristate == null || "UNDEFINED".equals(tristate.toString())) {
                    continue;
                }

                Object causingNode = resultClass.getMethod("node").invoke(queryResult);
                String matchedPermission = candidate;
                if (causingNode != null) {
                    Object key = nodeClass.getMethod("getKey").invoke(causingNode);
                    if (key != null) {
                        matchedPermission = key.toString();
                    }
                }

                return Optional.of(new PermissionResult(
                        "TRUE".equals(tristate.toString()),
                        ResolutionType.LUCKPERMS,
                        matchedPermission,
                        context.command()
                ));
            }
        } catch (Throwable ignored) {
            // LuckPerms is optional or unavailable; the registered-permission adapter is next.
        }
        return Optional.empty();
    }
}
