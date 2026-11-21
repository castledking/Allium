package codes.castled.allium.command.explosion;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.explosion.ExplosionSettings;
import codes.castled.allium.explosion.ExplosionSettings.Explosive;
import codes.castled.allium.explosion.RegenerationService;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.tool.BukkitParticles;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /explode [player] [power]}, from legacy Allium: drops a fireball of that power on a player.
 * Higher powers add effects: thunder and lightning from 16, a title from 31, a ring of lightning from
 * 51, a broadcast from 76 and a title for everyone from 101 (the powers past {@code max-power} need a
 * higher {@code explode.max-power}; the most is 127).
 */
public final class ExplodeCommand implements SpecCommandHandler {

    public static final String PERMISSION_OTHERS = "allium.explode.others";

    private final PluginStart plugin;
    private final RegenerationService regeneration;

    public ExplodeCommand(PluginStart plugin, RegenerationService regeneration) {
        this.plugin = plugin;
        this.regeneration = regeneration;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.EXPLODE;
    }

    /** {@code power} null means the default; {@code target} null means the sender. */
    record Request(@Nullable String target, @Nullable String power) {
    }

    /** Reads {@code [player] [power]}: a lone number is a power, anything else a player. */
    @Nullable
    static Request parse(String[] args, boolean player) {
        if (args.length > 2) return null;
        if (args.length == 0) return player ? new Request(null, null) : null;
        if (args.length == 1) {
            boolean number = args[0].matches("-?[0-9]+");
            if (number) return player ? new Request(null, args[0]) : null;
            return new Request(args[0], null);
        }
        return new Request(args[0], args[1]);
    }

    /** The power, or null if it is not a whole number between {@code min} and {@code max}. */
    @Nullable
    static Integer power(@Nullable String raw, ExplosionSettings settings) {
        if (raw == null) return settings.explodeDefaultPower();
        try {
            int power = Integer.parseInt(raw);
            return power < settings.explodeMinPower() || power > settings.explodeMaxPower() ? null : power;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        Request request = parse(context.args(), sender instanceof Player);
        if (request == null) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }
        ExplosionSettings settings = regeneration.settings();
        Integer power = power(request.power(), settings);
        if (power == null) {
            UtilitySupport.send(plugin, sender, Messages.EXPLODE_INVALID_POWER,
                    "{min}", Integer.toString(settings.explodeMinPower()), "{max}", Integer.toString(settings.explodeMaxPower()));
            return;
        }
        Player target;
        if (request.target() == null) {
            target = (Player) sender;
        } else {
            target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, sender, request.target());
            if (target == null) return;
            if (target != sender && !sender.hasPermission(PERMISSION_OTHERS)) {
                UtilitySupport.send(plugin, sender, Messages.NO_PERMISSION);
                return;
            }
        }
        launch(target, power, settings.explodeIncendiary());
        if (target == sender) {
            UtilitySupport.send(plugin, sender, Messages.EXPLODE_SELF, "{power}", Integer.toString(power));
        } else {
            UtilitySupport.send(plugin, sender, Messages.EXPLODE_OTHER, "{player}", target.getName(), "{power}", Integer.toString(power));
        }
        UtilitySupport.audit(plugin, sender, "explode", "target=" + target.getName() + " power=" + power);
    }

    private void launch(Player target, int power, boolean incendiary) {
        World world = target.getWorld();
        Location above = target.getLocation().add(0.0D, 1.0D, 0.0D);
        above.setPitch(90.0F);
        Fireball fireball = world.spawn(above, Fireball.class);
        regeneration.tag(fireball, Explosive.EXPLODE);
        fireball.setDirection(new Vector(0.0D, -1.0D, 0.0D));
        fireball.setVelocity(new Vector(0.0D, -3.0D, 0.0D));
        fireball.setYield(power);
        fireball.setIsIncendiary(incendiary);
        effects(target, power, above);
    }

    // -- Effects, tiered by power as in legacy Allium ----------------------------------------------

    private void effects(Player target, int power, Location center) {
        World world = target.getWorld();
        Location at = target.getLocation();
        double x = center.getX();
        double z = center.getZ();
        particle(world, "flame", at, 20 + power * 2, 1.0D, 0.5D, 1.0D, 0.05D);
        world.playSound(at, "minecraft:entity.generic.explode", 0.7F, 1.0F);
        if (power >= 16) {
            world.playSound(at, "minecraft:entity.lightning_bolt.thunder", 0.8F, 0.9F);
            particle(world, "lava", at, 10 + (power - 15) * 2, 1.5D, 0.5D, 1.5D, 0.1D);
            world.strikeLightningEffect(new Location(world, x, center.getY() - 15.0D, z));
        }
        if (power >= 31) {
            title(target, Messages.EXPLODE_TITLE_BOOM, target.getName());
            world.playSound(at, "minecraft:entity.wither.spawn", 0.6F, 0.7F);
            particle(world, "explosion", at, 5 + (power - 30) / 4, 2.0D, 1.0D, 2.0D, 0.1D);
            particle(world, "large_smoke", at, 50 + (power - 30) * 2, 3.0D, 1.5D, 3.0D, 0.1D);
        }
        if (power >= 51) {
            for (int i = 0; i < 5; i++) {
                double angle = i * (Math.PI * 2.0D / 5.0D);
                world.strikeLightningEffect(new Location(world, x + Math.cos(angle) * 3.0D, at.getY(), z + Math.sin(angle) * 3.0D));
            }
            particle(world, "explosion", at, 8 + (power - 50) / 5, 2.0D, 1.0D, 2.0D, 0.1D);
            world.playSound(at, "minecraft:entity.ender_dragon.death", 0.4F, 0.6F);
            title(target, Messages.EXPLODE_TITLE_MEGA, target.getName());
        }
        if (power >= 76) {
            int radius = 5 + (power - 75) / 5;
            for (int degrees = 0; degrees < 360; degrees += 10) {
                double angle = Math.toRadians(degrees);
                particle(world, "flame", new Location(world, x + radius * Math.cos(angle), at.getY(), z + radius * Math.sin(angle)),
                        10, 0.2D, 0.5D, 0.2D, 0.05D);
            }
            particle(world, "end_rod", at.clone().add(0.0D, 1.0D, 0.0D), 150 + (power - 75) * 3, 0.0D, 10.0D, 0.0D, 0.5D);
            world.playSound(at, "minecraft:entity.generic.explode", 1.0F, 0.5F);
            world.playSound(at, "minecraft:entity.wither.spawn", 0.5F, 0.8F);
            plugin.getMessageManager().broadcast(Messages.EXPLODE_BROADCAST, "{player}", target.getName());
        }
        if (power >= 101) {
            double baseY = at.getY();
            for (int i = 0; i < 15; i++) {
                int height = i;
                plugin.getScheduler().runOnServerLater(() -> {
                    double spiral = Math.max(0.5D, 7.0D - height * 0.4D);
                    for (int degrees = 0; degrees < 360; degrees += 15) {
                        double angle = Math.toRadians(degrees);
                        particle(world, degrees % 30 == 0 ? "soul_fire_flame" : "flame",
                                new Location(world, x + spiral * Math.cos(angle), baseY + height, z + spiral * Math.sin(angle)),
                                5, 0.1D, 0.1D, 0.1D, 0.01D);
                    }
                }, i * 100L);
            }
            int shockwave = 10 + (power - 100) / 3;
            for (int r = 1; r <= shockwave; r++) {
                int ring = r;
                plugin.getScheduler().runOnServerLater(() -> {
                    for (int degrees = 0; degrees < 360; degrees += 10) {
                        double angle = Math.toRadians(degrees);
                        particle(world, "cloud", new Location(world, x + ring * Math.cos(angle), baseY, z + ring * Math.sin(angle)),
                                1, 0.1D, 0.1D, 0.1D, 0.05D);
                    }
                }, ring * 100L);
            }
            world.playSound(at, "minecraft:entity.generic.explode", 2.0F, 0.3F);
            world.playSound(at, "minecraft:entity.wither.spawn", 1.0F, 0.4F);
            world.playSound(at, "minecraft:entity.ender_dragon.growl", 1.0F, 0.5F);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                title(online, Messages.EXPLODE_TITLE_CATACLYSM, target.getName());
                online.playSound(online.getLocation(), "minecraft:ambient.cave", 1.0F, 0.5F);
            }
        }
    }

    private static void particle(World world, String name, Location at, int count, double dx, double dy, double dz, double speed) {
        Particle particle = BukkitParticles.resolve(name).orElse(null);
        if (particle != null && particle.getDataType() == Void.class) {
            world.spawnParticle(particle, at, count, dx, dy, dz, speed);
        }
    }

    /** A title message's first line is the title and its second the subtitle. */
    @SuppressWarnings("deprecation")
    private void title(Player player, Messages message, String name) {
        String text = MessageManager.colorize(plugin.getMessageManager().resolve(message, "{player}", name));
        String[] lines = text.split("\n", 2);
        player.sendTitle(lines[0], lines.length > 1 ? lines[1] : "", 10, 30, 10);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0 || args.length > 2) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        ExplosionSettings settings = regeneration.settings();
        List<String> values = new ArrayList<>();
        boolean powerSlot = args.length == 2 || sender instanceof Player;
        if (powerSlot) {
            for (int power : new int[]{settings.explodeMinPower(), settings.explodeDefaultPower(), settings.explodeMaxPower()}) {
                String value = Integer.toString(power);
                if (!values.contains(value)) values.add(value);
            }
        }
        if (args.length == 1 && (sender.hasPermission(PERMISSION_OTHERS) || !(sender instanceof Player))) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (codes.castled.allium.naming.PlayerNameVisibility.canSeeTarget(sender, player)) values.add(player.getName());
            }
        }
        values.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(prefix));
        return values;
    }
}
