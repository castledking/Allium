package codes.castled.allium.command.tools;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.ride.Rides;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /unrestrain [player]} (staff): frees every restrained player, as pre-recode Allium's
 * {@code /unrestrain} did, or one named player, restrained or only carried.
 */
public final class UnrestrainCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public UnrestrainCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.UNRESTRAIN;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length > 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Rides rides = Rides.current();
        if (rides == null) {
            UtilitySupport.send(plugin, sender, Messages.MODULE_DISABLED);
            return;
        }
        List<UUID> targets;
        if (args.length == 1) {
            Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, sender, args[0]);
            if (target == null) {
                return;
            }
            if (!rides.isCarried(target.getUniqueId())) {
                UtilitySupport.send(plugin, sender, Messages.UNRESTRAIN_NOT_RESTRAINED, "{player}", target.getName());
                return;
            }
            targets = List.of(target.getUniqueId());
        } else {
            targets = rides.restrained();
            if (targets.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.UNRESTRAIN_NONE);
                return;
            }
        }
        int count = 0;
        for (UUID id : targets) {
            UUID carrierId = rides.carrierOf(id);
            Entity released = rides.let(id);
            if (released == null) {
                continue;
            }
            count++;
            if (released instanceof Player player && player.isOnline()) {
                UtilitySupport.send(plugin, player, Messages.RIDE_RELEASED_TARGET, "{player}", sender.getName());
            }
            Player carrier = carrierId == null ? null : plugin.getServer().getPlayer(carrierId);
            if (carrier != null && carrier != sender) {
                UtilitySupport.send(plugin, carrier, Messages.RIDE_RELEASED, "{target}", released.getName());
            }
        }
        plugin.logger().info(sender.getName() + " used /unrestrain and freed " + count + " player(s).");
        UtilitySupport.send(plugin, sender, Messages.UNRESTRAIN_RELEASED, "{count}", Integer.toString(count));
    }

    /** Players who ride someone. */
    public List<String> complete(CommandSender sender, String[] args) {
        Rides rides = Rides.current();
        if (args.length != 1 || rides == null || !sender.hasPermission(spec().basePermission())) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> rides.isCarried(player.getUniqueId()))
                .filter(player -> PlayerNameVisibility.canSeeTarget(sender, player))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
