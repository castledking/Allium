package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.entity.Player;

public final class FeedCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public FeedCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.FEED;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return;
        }

        target.setFoodLevel(20);
        target.setSaturation(20f);

        if (target != context.sender()) {
            context.sendOutcome(context.sender(), "other", "{player}", target.getName());
        }
        context.sendOutcome(target, "self");
    }
}
