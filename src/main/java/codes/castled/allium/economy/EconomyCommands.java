package codes.castled.allium.economy;

import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;

public record EconomyCommands(CommandSpec spec, EconomyRuntime runtime) implements SpecCommandHandler {
    @Override public void execute(BukkitCommandContext context){runtime.dispatch(context.sender(),spec.canonicalName(),context.args());}

    @Override
    public AliasTransform transformExecutionArgs(String label, String[] args) {
        String fixed = switch (spec.canonicalName()) {
            case "paytoggle" -> CoreCommandSpecs.payToggleAlias(label);
            case "payconfirmtoggle" -> CoreCommandSpecs.payConfirmAlias(label);
            default -> null;
        };
        return fixed == null ? new AliasTransform.Pass(args)
                : new AliasTransform.Pass(SpecCommandHandler.prepend(fixed, args));
    }
}
