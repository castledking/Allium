package codes.castled.allium.api;

import codes.castled.allium.command.spec.CommandSpec;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Command families for this addon. */
public interface AddonCommands {

    /**
     * Runs the command declared in the addon's {@code plugin.yml} under {@code spec.canonicalName()}
     * through Allium's command spec: Allium checks the base permission and completes from the spec's
     * tree, then calls the handler. {@code spec.namespace()} must be the addon's lowercase name, the
     * prefix Bukkit gives its commands.
     *
     * @throws IllegalArgumentException if the command is not in the addon's {@code plugin.yml}, or the namespace differs
     */
    void register(CommandSpec spec, Handler handler);

    /**
     * Registers a command with a custom server-thread tab completer (invoked after base-permission
     * and disabled-command checks).
     */
    default void register(CommandSpec spec, Handler handler, @Nullable Completer completer) {
        register(spec, handler);
    }

    @FunctionalInterface
    interface Handler {
        void execute(Context context);
    }

    @FunctionalInterface
    interface Completer {
        List<String> complete(Context context);
    }

    /** One invocation, on the server thread. */
    interface Context {
        org.bukkit.command.CommandSender sender();

        String label();

        String[] args();

        CommandSpec spec();
    }
}
