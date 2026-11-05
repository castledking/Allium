package codes.castled.allium.command.spec;

/**
 * Platform-neutral suggestion sources for command arguments.
 */
public enum SuggestionSource {
    /** No dynamic suggestions for this argument. */
    NONE,
    /** Visible online players filtered by sender visibility and permission. */
    ONLINE_PLAYERS,
    /** Canonical gamemode literals (survival, creative, adventure, spectator). */
    GAMEMODES,
    /** Explicit literal list declared on the {@link SuggestionSpec}. */
    LITERAL_LIST
}
