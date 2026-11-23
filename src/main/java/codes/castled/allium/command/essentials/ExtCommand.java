package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

public final class ExtCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public ExtCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.EXT;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (context.args().length > 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return;
        }
        UtilitySupport.onTarget(plugin, context.sender(), target, () -> {
            target.setFireTicks(0);
            if (target == context.sender()) {
                UtilitySupport.send(plugin, target, Messages.UTILITY_EXT_SELF);
            } else {
                UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_EXT_OTHER,
                        "{player}", target.getName());
                UtilitySupport.send(plugin, target, Messages.UTILITY_EXT_SELF);
                UtilitySupport.audit(plugin, context.sender(), "ext", "target=" + target.getName());
            }
        });
    }
}
