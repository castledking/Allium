package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.economy.PaidCommandService;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

public final class FeedCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final Supplier<PaidCommandService> paidCommands;

    public FeedCommand(PluginStart plugin) {
        this(plugin, () -> null);
    }

    public FeedCommand(PluginStart plugin, Supplier<PaidCommandService> paidCommands) {
        this.plugin = plugin;
        this.paidCommands = paidCommands == null ? () -> null : paidCommands;
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
        PaidCommandService paid = paidCommands.get();
        if (paid != null && target == context.sender() && context.sender() instanceof Player self) {
            paid.execute(self, "feed", () -> true, () -> {
                applyFeed(context, self);
                return true;
            });
            return;
        }
        UtilitySupport.onTarget(plugin, context.sender(), target, () -> applyFeed(context, target));
    }

    private void applyFeed(BukkitCommandContext context, Player target) {
        target.setFoodLevel(20);
        target.setSaturation(20f);

        if (target != context.sender()) {
            context.sendOutcome(context.sender(), "other", "{player}", target.getName());
        }
        context.sendOutcome(target, "self");
    }
}
