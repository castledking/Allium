package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.moderation.InspectionService;

import java.util.Objects;

/** {@code /invsee <player>} command handler. */
public final class InvseeCommand implements SpecCommandHandler {

    private final InspectionService inspection;

    public InvseeCommand(PluginStart plugin, InspectionService inspection) {
        Objects.requireNonNull(plugin, "plugin");
        this.inspection = Objects.requireNonNull(inspection, "inspection");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.INVSEE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        inspection.dispatchInvsee(context.sender(), context.args());
    }
}
