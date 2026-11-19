package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

/** {@code /compass}: the direction the player faces, as Essentials words it (0 is north). */
public final class CompassCommand implements SpecCommandHandler {

    private static final Messages[] DIRECTIONS = {
            Messages.UTILITY_COMPASS_NORTH, Messages.UTILITY_COMPASS_NORTH_EAST, Messages.UTILITY_COMPASS_EAST,
            Messages.UTILITY_COMPASS_SOUTH_EAST, Messages.UTILITY_COMPASS_SOUTH, Messages.UTILITY_COMPASS_SOUTH_WEST,
            Messages.UTILITY_COMPASS_WEST, Messages.UTILITY_COMPASS_NORTH_WEST
    };

    private final PluginStart plugin;

    public CompassCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.COMPASS;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        if (context.args().length > 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        int bearing = bearing(player.getLocation().getYaw());
        String direction = plugin.getMessageManager().resolve(DIRECTIONS[direction(bearing)]);
        UtilitySupport.send(plugin, player, Messages.UTILITY_COMPASS, "{direction}", direction, "{degrees}", Integer.toString(bearing));
    }

    /** Degrees clockwise from north: Minecraft's yaw 180 faces north, -90 east, 0 south and 90 west. */
    static int bearing(float yaw) {
        return (int) Math.floorMod(Math.round(yaw) + 180, 360);
    }

    /** One of the eight 45-degree sectors, north first, each centred on its direction. */
    static int direction(int bearing) {
        return Math.floorMod(Math.round(bearing / 45f), 8);
    }
}
