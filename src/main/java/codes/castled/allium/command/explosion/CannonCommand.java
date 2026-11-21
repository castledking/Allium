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
import org.bukkit.Location;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * {@code /beezooka} ({@code /beecannon}) and {@code /kittycannon}: a bee or a tamed kitten flies
 * where you look and pops a second later in a harmless explosion, as in Essentials.
 */
public final class CannonCommand implements SpecCommandHandler {

    private static final long FLIGHT_MILLIS = 1000L;

    private final PluginStart plugin;
    private final RegenerationService regeneration;
    private final boolean bee;

    private CannonCommand(PluginStart plugin, RegenerationService regeneration, boolean bee) {
        this.plugin = plugin;
        this.regeneration = regeneration;
        this.bee = bee;
    }

    public static CannonCommand beezooka(PluginStart plugin, RegenerationService regeneration) {
        return new CannonCommand(plugin, regeneration, true);
    }

    public static CannonCommand kittycannon(PluginStart plugin, RegenerationService regeneration) {
        return new CannonCommand(plugin, regeneration, false);
    }

    @Override
    public CommandSpec spec() {
        return bee ? CoreCommandSpecs.BEEZOOKA : CoreCommandSpecs.KITTYCANNON;
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
        Location eye = player.getEyeLocation();
        Entity flier;
        if (bee) {
            flier = player.getWorld().spawn(eye, Bee.class);
        } else {
            // A cat's coat is picked at random when it spawns.
            Cat cat = player.getWorld().spawn(eye, Cat.class);
            cat.setTamed(true);
            cat.setBaby();
            flier = cat;
        }
        regeneration.tag(flier, bee ? Explosive.BEEZOOKA : Explosive.KITTYCANNON);
        flier.setVelocity(eye.getDirection().multiply(2.0D));
        plugin.getScheduler().runOnServerLater(() -> {
            Location at = flier.getLocation();
            flier.remove();
            if (at.getWorld() != null) at.getWorld().createExplosion(at, 0.0F, false, false);
        }, FLIGHT_MILLIS);
        UtilitySupport.audit(plugin, player, spec().canonicalName(), "");
    }
}
