package codes.castled.allium.creative.manage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Where creative flags live: LuckPerms, Vault, or (with neither) nothing writable. Calls start on
 * the server thread; returned futures may complete on any thread.
 */
public interface PermissionBackend {

    /** {@code LuckPerms}, {@code Vault} or {@code none}, for messages and the GUI. */
    String name();

    boolean canWrite();

    /** Whether a holder's own nodes can be told apart from inherited ones (Vault cannot). */
    boolean knowsSetNodes();

    /** Group names, sorted; empty without group support. */
    List<String> groups();

    /** The group's canonical name, or null when there is no such group. */
    String group(String name);

    /** Every flag for {@code holder}. Fails with {@link IllegalArgumentException} for an unknown group. */
    CompletableFuture<Map<CreativeFlag, FlagValue>> read(CreativeHolder holder);

    /**
     * Gives the flag globally ({@code give}) or removes the holder's own global node. {@code actor}
     * names who did it, for the backend's own log.
     */
    default CompletableFuture<Void> write(CreativeHolder holder, CreativeFlag flag, boolean give, Actor actor) {
        return writeNode(holder, flag.node(), give, actor);
    }

    /**
     * Sets {@code node} true globally on the holder ({@code give}) or removes the holder's own global
     * node: what {@code lp user <name> permission set <node> true} (or {@code unset}) does.
     */
    CompletableFuture<Void> writeNode(CreativeHolder holder, String node, boolean give, Actor actor);

    /**
     * Whether an online player holds {@code node} through the permissions plugin (on their own, from a
     * group or a wildcard), not merely as an operator or by a plugin's default; null when this
     * backend cannot tell.
     */
    default Boolean holds(CreativeHolder player, String node) {
        return null;
    }

    /** Who changed a flag: a player (uuid) or the console (null uuid). */
    record Actor(java.util.UUID uuid, String name) {
    }
}
