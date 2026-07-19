package codes.castled.allium.permissions.command;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable input shared by command permission adapters. */
public record CommandPermissionContext(
        @NotNull Player player,
        @NotNull String fullCommandLine,
        @NotNull String label,
        @NotNull List<String> arguments,
        @NotNull Command command,
        @Nullable String pluginName,
        @NotNull Set<String> registeredPermissions
) {

    public CommandPermissionContext {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(fullCommandLine, "fullCommandLine");
        Objects.requireNonNull(label, "label");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(command, "command");
        registeredPermissions = Set.copyOf(registeredPermissions);
    }
}
