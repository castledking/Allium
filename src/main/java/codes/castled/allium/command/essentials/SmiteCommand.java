package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

public final class SmiteCommand implements SpecCommandHandler {

    private static final double MAX_RANGE = 128.0D;

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
        String[] args = context.args();
        if (args.length > 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        if (args.length == 0) {
            if (!(context.sender() instanceof Player player)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            RayTraceResult hit = player.rayTraceBlocks(MAX_RANGE, FluidCollisionMode.NEVER);
            if (hit == null || hit.getHitBlock() == null) {
                UtilitySupport.send(plugin, player, Messages.UTILITY_SMITE_NO_TARGET);
                return;
            }
            Location strikeAt = hit.getHitPosition() != null
                    ? hit.getHitPosition().toLocation(player.getWorld())
                    : hit.getHitBlock().getLocation().add(0.5D, 1.0D, 0.5D);
            Runnable strike = () -> {
                player.getWorld().strikeLightningEffect(strikeAt);
                UtilitySupport.send(plugin, player, Messages.UTILITY_SMITE_HERE,
                        "{x}", Integer.toString(strikeAt.getBlockX()),
                        "{y}", Integer.toString(strikeAt.getBlockY()),
                        "{z}", Integer.toString(strikeAt.getBlockZ()));
                UtilitySupport.audit(plugin, player, "smite",
                        "location=" + strikeAt.getBlockX() + "," + strikeAt.getBlockY() + "," + strikeAt.getBlockZ());
            };
            if (BukkitServerThread.isFolia() && plugin.getScheduler() != null) {
                var region = BukkitServerThread.region(plugin, player.getWorld(),
                        strikeAt.getBlockX() >> 4, strikeAt.getBlockZ() >> 4);
                if (region.isServerThread()) strike.run();
                else plugin.getScheduler().runOn(region, strike, () -> {}, 0L);
            } else {
                strike.run();
            }
            return;
        }
        Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, context.sender(), args[0]);
        if (target == null) {
            return;
        }
        UtilitySupport.onTarget(plugin, context.sender(), target, () -> {
            target.getWorld().strikeLightningEffect(target.getLocation());
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_SMITE, "{player}", target.getName());
            if (target != context.sender()) {
                UtilitySupport.send(plugin, target, Messages.UTILITY_SMITED);
            }
            UtilitySupport.audit(plugin, context.sender(), "smite", "target=" + target.getName());
        });
    }
}
