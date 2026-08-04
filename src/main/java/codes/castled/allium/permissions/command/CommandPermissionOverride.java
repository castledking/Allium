package codes.castled.allium.permissions.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A staff-set permission mapping for a command the resolver pipeline could
 * not classify on its own. Consulted only as a fallback when every
 * {@link CommandPermissionAdapter} declines to resolve a command.
 */
public record CommandPermissionOverride(
        @NotNull String commandLabel,
        @NotNull String permission,
        boolean denyAlts,
        @NotNull String source,
        @Nullable UUID setBy,
        @Nullable Instant updatedAt
) {

    public CommandPermissionOverride {
        Objects.requireNonNull(commandLabel, "commandLabel");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(source, "source");
    }
}
