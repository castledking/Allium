package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** {@code /coords [player]} ({@code /getpos}, {@code /whereami}): world, block position, yaw and pitch. */
public final class CoordsCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public CoordsCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.COORDS;
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
            Location at = target.getLocation();
            String[] values = {
                    "{player}", target.getName(),
                    "{world}", at.getWorld() == null ? "?" : at.getWorld().getName(),
                    "{x}", Integer.toString(at.getBlockX()),
                    "{y}", Integer.toString(at.getBlockY()),
                    "{z}", Integer.toString(at.getBlockZ()),
                    "{yaw}", Long.toString(Math.round(yaw(at.getYaw()))),
                    "{pitch}", Long.toString(Math.round(at.getPitch()))
            };
            UtilitySupport.send(plugin, context.sender(), target == context.sender() ? Messages.UTILITY_COORDS_SELF : Messages.UTILITY_COORDS_OTHER, values);
        });
    }

    /** Yaw in 0..360, as the debug screen shows it after wrapping. */
    static double yaw(float raw) {
        double yaw = raw % 360;
        return yaw < 0 ? yaw + 360 : yaw;
    }
}
