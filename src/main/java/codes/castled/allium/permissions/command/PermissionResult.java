package codes.castled.allium.permissions.command;

import org.bukkit.command.Command;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** The decision and the evidence used to reach it. */
public record PermissionResult(
        boolean allowed,
        @NotNull ResolutionType type,
        @Nullable String matchedPermission,
        @Nullable Command command
) {

    public PermissionResult {
        Objects.requireNonNull(type, "type");
    }

    public static @NotNull PermissionResult unknown(boolean allowed, @Nullable Command command) {
        return new PermissionResult(allowed, ResolutionType.UNKNOWN, null, command);
    }
}
