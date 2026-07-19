package codes.castled.allium.permissions.command;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A pluggable command-framework adapter.
 *
 * <p>An empty result means the adapter does not understand this command and the
 * next adapter should be tried. An {@link ResolutionType#UNKNOWN} result means
 * the adapter owns the command but cannot safely inspect its permission rule.</p>
 */
public interface CommandPermissionAdapter {

    @NotNull Optional<PermissionResult> resolve(@NotNull CommandPermissionContext context);
}
