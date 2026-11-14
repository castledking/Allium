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
    LITERAL_LIST,
    /**
     * Every known player name, online or offline, from the bounded in-memory name index (never
     * storage or the server's offline-player list). Online targets obey sender visibility.
     */
    KNOWN_PLAYERS,
    /** Claimable gift IDs in the sender's memory-only mailbox snapshot, plus all when nonempty. */
    MAIL_CLAIM_IDS,
    /** Deletable text-mail IDs in the sender's memory-only mailbox snapshot. */
    MAIL_DELETE_IDS,
    HOME_NAMES,
    WARP_NAMES,
    /** Loaded kits accessible to the sender (or all loaded kits for admin delete). */
    KIT_NAMES,
    /** All loaded kit names for administrative commands such as {@code /delkit}. */
    ALL_KIT_NAMES
}
