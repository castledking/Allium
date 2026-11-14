package codes.castled.allium.command.spec;

import java.util.List;

/**
 * Platform-neutral context passed into {@link CommandSpec#complete(CommandCompletionContext, String[])}
 * for permission- and visibility-aware tab completion.
 */
public interface CommandCompletionContext {

    /**
     * Returns true if the sender holds the specified permission node.
     */
    boolean hasPermission(String permissionNode);

    /**
     * Returns the names of currently online players that the sender is allowed to see
     * (excluding vanished/hidden players the sender cannot see).
     */
    List<String> visibleOnlinePlayers();

    /**
     * Known player names (online or offline) starting with {@code prefix}, from memory only.
     * Online targets must be filtered using this sender's current visibility.
     * Without a name index this is the visible online players.
     */
    default List<String> knownPlayers(String prefix) {
        return visibleOnlinePlayers();
    }

    /** Short IDs from this sender's previously loaded mailbox snapshot; never performs I/O. */
    default List<String> mailIds(boolean claimableOnly) {
        return List.of();
    }
    /** Previously loaded home/accessible warp names; prefix filtering precedes the result cap. */
    default List<String> travelNames(boolean warps, String prefix) { return List.of(); }
}
