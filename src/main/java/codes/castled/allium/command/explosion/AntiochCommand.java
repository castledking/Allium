package codes.castled.allium.command.explosion;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.explosion.ExplosionSettings.Explosive;
import codes.castled.allium.explosion.RegenerationService;
import codes.castled.allium.message.Messages;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /antioch} ({@code /grenade}, {@code /tnt}): primed TNT where you look, announced with
 * Essentials' Holy Hand Grenade lines unless {@code antioch.announce} is off.
 */
public final class AntiochCommand implements SpecCommandHandler {

    static final double MAX_RANGE = 128.0D;

    private final PluginStart plugin;
    private final RegenerationService regeneration;

    public AntiochCommand(PluginStart plugin, RegenerationService regeneration) {
        this.plugin = plugin;
        this.regeneration = regeneration;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.ANTIOCH;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        Location at = target(player);
        if (at == null) {
            UtilitySupport.send(plugin, player, Messages.EXPLOSIVE_NO_TARGET);
            return;
        }
        if (regeneration.settings().antiochAnnounce()) {
            plugin.getMessageManager().broadcast(Messages.ANTIOCH_BROADCAST, "{player}", player.getName());
        }
        TNTPrimed tnt = player.getWorld().spawn(at, TNTPrimed.class);
        regeneration.tag(tnt, Explosive.ANTIOCH);
        tnt.setSource(player);
        UtilitySupport.audit(plugin, player, "antioch", "location=" + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
    }

    /** Half a block out from the face of the block the player looks at, or null if none is in range. */
    @Nullable
    static Location target(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(MAX_RANGE, FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null) return null;
        BlockFace face = hit.getHitBlockFace() == null ? BlockFace.UP : hit.getHitBlockFace();
        Location at = hit.getHitBlock().getLocation().add(0.5D, 0.5D, 0.5D);
        return at.add(face.getModX() * 1.0D, face.getModY() * 1.0D, face.getModZ() * 1.0D);
    }
}
