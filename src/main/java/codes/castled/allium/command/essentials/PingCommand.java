package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

public final class PingCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public PingCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.PING;
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
            int ping = Math.max(0, target.getPing());
            if (target == context.sender()) {
                UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_PING_SELF,
                        "{ping}", Integer.toString(ping));
            } else {
                UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_PING_OTHER,
                        "{player}", target.getName(),
                        "{ping}", Integer.toString(ping));
            }
        });
    }
}
