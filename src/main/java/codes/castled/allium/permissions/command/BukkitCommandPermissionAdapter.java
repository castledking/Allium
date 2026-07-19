package codes.castled.allium.permissions.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

final class BukkitCommandPermissionAdapter implements CommandPermissionAdapter {

    @Override
    public Optional<PermissionResult> resolve(CommandPermissionContext context) {
        Command command = context.command();
        String permission = command.getPermission();

        if (permission != null && !permission.isBlank()) {
            boolean allowed = command.testPermissionSilent(context.player());
            return Optional.of(new PermissionResult(
                    allowed,
                    ResolutionType.PLUGIN_METADATA,
                    matchedExplicitPermission(context, permission, allowed),
                    command
            ));
        }

        try {
            Method permissionTest = command.getClass().getMethod("testPermissionSilent", CommandSender.class);
            if (permissionTest.getDeclaringClass() == Command.class) {
                return Optional.empty();
            }

            boolean allowed = command.testPermissionSilent(context.player());
            return Optional.of(new PermissionResult(
                    allowed,
                    frameworkType(command),
                    null,
                    command
            ));
        } catch (NoSuchMethodException ignored) {
            return Optional.empty();
        }
    }

    private String matchedExplicitPermission(CommandPermissionContext context,
                                             String permission,
                                             boolean allowed) {
        String[] permissions = Arrays.stream(permission.split(";"))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
        if (allowed) {
            for (String candidate : permissions) {
                if (context.player().hasPermission(candidate)) {
                    return candidate;
                }
            }
        }
        return permissions.length == 0 ? permission : permissions[0];
    }

    private ResolutionType frameworkType(Command command) {
        String className = command.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("commandapi") || className.contains("dev.jorel")) {
            return ResolutionType.COMMANDAPI;
        }
        return ResolutionType.BUKKIT;
    }
}
