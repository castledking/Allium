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
}
