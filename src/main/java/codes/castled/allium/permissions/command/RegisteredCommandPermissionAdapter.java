package codes.castled.allium.permissions.command;

import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class RegisteredCommandPermissionAdapter implements CommandPermissionAdapter {

    @Override
    public Optional<PermissionResult> resolve(CommandPermissionContext context) {
        Set<String> exactEffectivePermissions = context.player().getEffectivePermissions().stream()
                .map(info -> info.getPermission().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        for (String candidate : PermissionCandidates.forContext(context)) {
            boolean wildcard = candidate.endsWith(".*");
            Permission registered = Bukkit.getPluginManager().getPermission(candidate);
            if (registered != null) {
                // A broad wildcard is a valid grant, but its default false value alone does
                // not prove that this particular command requires the wildcard.
                if (wildcard && !context.player().hasPermission(registered)) {
                    continue;
                }
                return Optional.of(new PermissionResult(
                        context.player().hasPermission(registered),
                        ResolutionType.REGISTERED_PERMISSION,
                        registered.getName(),
                        context.command()
                ));
            }

            if (exactEffectivePermissions.contains(candidate)) {
                if (wildcard && !context.player().hasPermission(candidate)) {
                    continue;
                }
                return Optional.of(new PermissionResult(
                        context.player().hasPermission(candidate),
                        ResolutionType.HEURISTIC,
                        candidate,
                        context.command()
                ));
            }
        }
        return Optional.empty();
    }
}
