package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

public final class SmiteCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public SmiteCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SMITE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (context.args().length != 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, context.sender(), context.args()[0]);
        if (target == null) {
            return;
        }
        target.getWorld().strikeLightningEffect(target.getLocation());
        UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_SMITE, "{player}", target.getName());
        if (target != context.sender()) {
            UtilitySupport.send(plugin, target, Messages.UTILITY_SMITED);
        }
        UtilitySupport.audit(plugin, context.sender(), "smite", "target=" + target.getName());
    }
}
