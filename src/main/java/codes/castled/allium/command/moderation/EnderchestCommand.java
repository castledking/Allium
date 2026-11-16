package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.moderation.InspectionService;

import java.util.Objects;

/** {@code /enderchest [player]} (aliases {@code /echest}, {@code /ec}) command handler. */
public final class EnderchestCommand implements SpecCommandHandler {

    private final InspectionService inspection;

    public EnderchestCommand(PluginStart plugin, InspectionService inspection) {
        Objects.requireNonNull(plugin, "plugin");
        this.inspection = Objects.requireNonNull(inspection, "inspection");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.ENDERCHEST;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        inspection.dispatchEnderchest(context.sender(), context.args());
    }
}
