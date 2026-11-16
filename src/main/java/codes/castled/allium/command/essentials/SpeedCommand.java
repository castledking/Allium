package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.essentials.SpeedSpecParser;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

public final class SpeedCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public SpeedCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SPEED;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        var parsedOpt = SpeedSpecParser.parse(context.args());
        if (parsedOpt.isEmpty()) {
            context.sendInvalidArgs(context.sender() instanceof Player
                    ? spec().selfUsage()
                    : spec().consoleUsage());
            return;
        }
        var parsed = parsedOpt.get();
        Player target;
        if (parsed.targetArgIdx() >= 0) {
            target = context.resolveSelfOrTarget(parsed.targetArgIdx());
            if (target == null) {
                return;
            }
        } else {
            if (!(context.sender() instanceof Player player)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = player;
        }

        SpeedSpecParser.SpeedType type = parsed.explicitType() != null
                ? parsed.explicitType()
                : (target.isFlying() ? SpeedSpecParser.SpeedType.FLY : SpeedSpecParser.SpeedType.WALK);
        float bukkitSpeed = SpeedSpecParser.toBukkitSpeed(type, parsed.displaySpeed());
        if (type == SpeedSpecParser.SpeedType.FLY) {
            target.setFlySpeed(bukkitSpeed);
        } else {
            target.setWalkSpeed(bukkitSpeed);
        }

        String formattedSpeed = SpeedSpecParser.formatSpeed(parsed.displaySpeed());
        if (target == context.sender()) {
            UtilitySupport.send(plugin, target, Messages.SPEED_SET,
                    "{type}", type.displayName(),
                    "{speed}", formattedSpeed);
        } else {
            UtilitySupport.send(plugin, context.sender(), Messages.SPEED_SET_OTHER,
                    "{player}", target.getName(),
                    "{type}", type.displayName(),
                    "{speed}", formattedSpeed);
            UtilitySupport.send(plugin, target, Messages.SPEED_SET,
                    "{type}", type.displayName(),
                    "{speed}", formattedSpeed);
            UtilitySupport.audit(plugin, context.sender(), "speed",
                    "target=" + target.getName() + " type=" + type.name().toLowerCase(java.util.Locale.ROOT)
                            + " speed=" + formattedSpeed);
        }
    }
}
