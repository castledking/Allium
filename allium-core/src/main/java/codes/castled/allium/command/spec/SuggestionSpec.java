package codes.castled.allium.command.spec;

import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral suggestion descriptor for a command argument.
 */
public record SuggestionSpec(
        SuggestionSource source,
        List<String> literals,
        String requiredPermission
) {

    public static final List<String> DEFAULT_GAMEMODES = List.of(
            "survival", "creative", "adventure", "spectator"
    );

    public SuggestionSpec {
        Objects.requireNonNull(source, "source");
        literals = literals == null ? List.of() : List.copyOf(literals);
    }

    public static SuggestionSpec none() {
        return new SuggestionSpec(SuggestionSource.NONE, List.of(), null);
    }

    public static SuggestionSpec onlinePlayers(String requiredPermission) {
        return new SuggestionSpec(SuggestionSource.ONLINE_PLAYERS, List.of(), requiredPermission);
    }

    public static SuggestionSpec gamemodes() {
        return new SuggestionSpec(SuggestionSource.GAMEMODES, DEFAULT_GAMEMODES, null);
    }

    public static SuggestionSpec literals(List<String> literals, String requiredPermission) {
        return new SuggestionSpec(SuggestionSource.LITERAL_LIST, literals, requiredPermission);
    }
}
