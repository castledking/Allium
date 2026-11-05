package codes.castled.allium.command.spec;

import java.util.Objects;

/**
 * Platform-neutral positional argument specification.
 */
public record CommandArgSpec(
        String name,
        boolean required,
        String permissionNode,
        SuggestionSpec suggestion
) {

    public CommandArgSpec {
        Objects.requireNonNull(name, "name");
        suggestion = suggestion == null ? SuggestionSpec.none() : suggestion;
    }

    public static CommandArgSpec required(String name, String permissionNode, SuggestionSpec suggestion) {
        return new CommandArgSpec(name, true, permissionNode, suggestion);
    }

    public static CommandArgSpec optional(String name, String permissionNode, SuggestionSpec suggestion) {
        return new CommandArgSpec(name, false, permissionNode, suggestion);
    }
}
