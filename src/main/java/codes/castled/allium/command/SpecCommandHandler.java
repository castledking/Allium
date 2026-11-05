package codes.castled.allium.command;

import codes.castled.allium.command.spec.CommandSpec;

/**
 * Handler for a command family backed by a platform-neutral {@link CommandSpec}.
 */
public interface SpecCommandHandler {

    /**
     * Returns the platform-neutral specification for this command family.
     */
    CommandSpec spec();

    /**
     * Executes the command after base permission and disabled-family checks have passed.
     */
    void execute(BukkitCommandContext context);
}
