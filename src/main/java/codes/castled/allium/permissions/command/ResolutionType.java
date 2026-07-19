package codes.castled.allium.permissions.command;

/** Identifies which layer made a command permission decision. */
public enum ResolutionType {
    BRIGADIER,
    BUKKIT,
    PAPER_BASIC,
    VANILLA,
    COMMANDAPI,
    NIGHTCORE,
    PLUGIN_METADATA,
    LUCKPERMS,
    REGISTERED_PERMISSION,
    HEURISTIC,
    UNKNOWN
}
