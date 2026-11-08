package codes.castled.allium.api;

import codes.castled.allium.command.spec.CommandSpec;

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

    @FunctionalInterface
    interface Handler {
        void execute(Context context);
    }

    /** One invocation, on the server thread. */
    interface Context {
        org.bukkit.command.CommandSender sender();

        String label();

        String[] args();

        CommandSpec spec();
    }
}
