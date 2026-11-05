package codes.castled.allium.command.spec;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Platform-neutral subcommand node in a command tree.
 */
public record CommandNodeSpec(
        String name,
        List<String> aliases,
        String permissionNode,
        String usage,
        List<CommandArgSpec> arguments,
        List<CommandNodeSpec> children
) {

    public CommandNodeSpec {
        Objects.requireNonNull(name, "name");
        name = name.trim().toLowerCase(Locale.ROOT);
        aliases = aliases == null
                ? List.of()
                : aliases.stream().map(a -> a.trim().toLowerCase(Locale.ROOT)).toList();
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public boolean matches(String token) {
        if (token == null) {
            return false;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        return name.equals(normalized) || aliases.contains(normalized);
    }
}
