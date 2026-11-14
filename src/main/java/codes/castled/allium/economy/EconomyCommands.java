package codes.castled.allium.economy;

import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;

public record EconomyCommands(CommandSpec spec, EconomyRuntime runtime) implements SpecCommandHandler {
    @Override public void execute(BukkitCommandContext context){runtime.dispatch(context.sender(),spec.canonicalName(),context.args());}
}
