package codes.castled.allium.permissions.command;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Resolves the permission decision for a complete player command invocation. */
public interface CommandPermissionResolver {

    @NotNull PermissionResult resolve(@NotNull Player player, @NotNull String fullCommandLine);
}
