package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandArgSpec;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.SuggestionSpec;
import codes.castled.allium.message.Messages;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class PlayerUtilityCommand implements SpecCommandHandler {

    public enum Action {
        DEPTH("depth", "Show your elevation relative to sea level", List.of("height")),
        ICE("ice", "Apply the frost overlay", List.of()),
        REST("rest", "Reset the time since rest", List.of());

        private final CommandSpec spec;

        public CommandSpec spec() {
            return spec;
        }

        Action(String label, String description, List<String> aliases) {
            boolean targeted = !label.equals("depth");
            String permission = "allium." + label;
            String others = targeted ? permission + ".others" : null;
            String usage = "/" + label;
            spec = new CommandSpec(label, CommandSpec.DEFAULT_NAMESPACE, "essentials", aliases, description,
                    usage + (targeted ? " [player]" : ""), usage + (targeted ? " <player>" : ""),
                    permission, others, targeted
                    ? List.of(CommandArgSpec.optional("player", others, SuggestionSpec.onlinePlayers(others)))
                    : List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
        }
    }

    private final PluginStart plugin;
    private final Action action;

    public PlayerUtilityCommand(PluginStart plugin, Action action) {
        this.plugin = plugin;
        this.action = action;
    }

    @Override
    public CommandSpec spec() {
        return action.spec;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (context.args().length > (action == Action.DEPTH ? 0 : 1)) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return;
        }
        switch (action) {
            case DEPTH -> {
                int height = target.getLocation().getBlockY() - target.getWorld().getSeaLevel();
                Messages message = height > 0 ? Messages.DEPTH_ABOVE
                        : height < 0 ? Messages.DEPTH_BELOW : Messages.DEPTH_LEVEL;
                UtilitySupport.send(plugin, target, message, "{blocks}", Integer.toString(Math.abs(height)));
            }
            case ICE -> {
                target.setFreezeTicks(target.getMaxFreezeTicks());
                UtilitySupport.send(plugin, target, Messages.ICE_SELF);
                if (target != context.sender()) {
                    UtilitySupport.send(plugin, context.sender(), Messages.ICE_OTHER, "{player}", target.getName());
                    UtilitySupport.audit(plugin, context.sender(), "ice", "target=" + target.getName());
                }
            }
            case REST -> {
                target.setStatistic(Statistic.TIME_SINCE_REST, 0);
                UtilitySupport.send(plugin, target, Messages.REST_SELF);
                if (target != context.sender()) {
                    UtilitySupport.send(plugin, context.sender(), Messages.REST_OTHER, "{player}", target.getName());
                    UtilitySupport.audit(plugin, context.sender(), "rest", "target=" + target.getName());
                }
            }
        }
    }
}
