package codes.castled.allium.command.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.info.PlayerInfoService;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** {@code /near [radius]}: lists nearby visible players sorted ascending by distance. */
public final class NearCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final PlayerInfoService info;

    public NearCommand(PluginStart plugin, PlayerInfoService info) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.info = Objects.requireNonNull(info, "info");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.NEAR;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            plugin.getMessageManager().send(context.sender(), Messages.INFO_PLAYER_ONLY, "{cmd}", "near");
            return;
        }
        String[] args = context.args();
        if (args.length > 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        PlayerInfoService.InfoSettings settings = info.settings();
        double radius = settings.nearDefaultRadius();
        if (args.length == 1) {
            try {
                radius = Double.parseDouble(args[0]);
            } catch (NumberFormatException ignored) {
                plugin.getMessageManager().send(player, Messages.INFO_NEAR_INVALID_RADIUS);
                return;
            }
            if (!Double.isFinite(radius) || radius <= 0.0) {
                plugin.getMessageManager().send(player, Messages.INFO_NEAR_INVALID_RADIUS);
                return;
            }
            if (radius > settings.nearMaxRadius()
                    && !player.hasPermission(PlayerInfoService.PERMISSION_NEAR_BYPASS_MAX)) {
                plugin.getMessageManager().send(
                        player,
                        Messages.INFO_NEAR_TOO_FAR,
                        "{max}", Integer.toString(settings.nearMaxRadius())
                );
                return;
            }
        }

        String radiusLabel = formatRadius(radius);
        List<PlayerInfoService.NearbyEntry> nearby = info.findNearbyPlayers(player, radius);
        if (nearby.isEmpty()) {
            plugin.getMessageManager().send(player, Messages.INFO_NEAR_EMPTY, "{radius}", radiusLabel);
            return;
        }
        String formatted = nearby.stream()
                .map(entry -> entry.name() + " (" + Math.round(entry.distance()) + "m)"
                        + (entry.afk() ? " &7[AFK]&f" : "")
                        + (entry.vanished() ? " &8[Vanished]&f" : ""))
                .collect(Collectors.joining(", "));
        plugin.getMessageManager().send(
                player,
                Messages.INFO_NEAR_LIST,
                "{radius}", radiusLabel,
                "{count}", Integer.toString(nearby.size()),
                "{players}", formatted
        );
    }

    private static String formatRadius(double radius) {
        if (Math.abs(radius - Math.rint(radius)) < 1e-6) {
            return Long.toString(Math.round(radius));
        }
        return Double.toString(radius);
    }
}
