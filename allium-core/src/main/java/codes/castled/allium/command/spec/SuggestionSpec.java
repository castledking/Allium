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

    /** Known players, online or offline (mail, payments): see {@link SuggestionSource#KNOWN_PLAYERS}. */
    public static SuggestionSpec knownPlayers(String requiredPermission) {
        return new SuggestionSpec(SuggestionSource.KNOWN_PLAYERS, List.of(), requiredPermission);
    }

    public static SuggestionSpec mailIds(boolean claimableOnly) {
        return new SuggestionSpec(claimableOnly ? SuggestionSource.MAIL_CLAIM_IDS : SuggestionSource.MAIL_DELETE_IDS,
                List.of(), "allium.mail");
    }

    public static SuggestionSpec gamemodes() {
        return new SuggestionSpec(SuggestionSource.GAMEMODES, DEFAULT_GAMEMODES, null);
    }

    public static SuggestionSpec literals(List<String> literals, String requiredPermission) {
        return new SuggestionSpec(SuggestionSource.LITERAL_LIST, literals, requiredPermission);
    }
}
