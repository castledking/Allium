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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /fireball [type] [speed] [ride]} ({@code /fireentity}, {@code /fireskull}): throws a
 * fireball or another projectile where you look. Each type needs {@code allium.fireball.<type>},
 * riding it {@code allium.fireball.ride}. {@code /fireskull} throws a wither skull unless a type is
 * given. Arrows and tridents cannot be picked up.
 */
public final class FireballCommand implements SpecCommandHandler {

    public static final String PERMISSION_TYPE = "allium.fireball.";
    public static final String PERMISSION_RIDE = "allium.fireball.ride";
    static final double DEFAULT_SPEED = 2.0D;

    /** Every type and its entity class, in Essentials' order; types the server lacks are left out. */
    private static final Map<String, Class<? extends Projectile>> TYPES = types();

    private final PluginStart plugin;
    private final RegenerationService regeneration;

    public FireballCommand(PluginStart plugin, RegenerationService regeneration) {
        this.plugin = plugin;
        this.regeneration = regeneration;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.FIREBALL;
    }

    public static List<String> typeNames() {
        return List.copyOf(TYPES.keySet());
    }

    /** What {@code /fireball} asked for. */
    record Request(String type, double speed, boolean ride) {
    }

    /** Thrown for a usage error; a null message means the usage card. */
    static final class Refused extends Exception {
        @Nullable
        final Messages message;
        final String[] replacements;

        Refused(@Nullable Messages message, String... replacements) {
            super(null, null, false, false);
            this.message = message;
            this.replacements = replacements;
        }
    }

    /** Reads {@code [type] [speed] [ride]}; {@code type} defaults to what the label implies. */
    static Request parse(String label, String[] args, java.util.Set<String> known, double maxSpeed) throws Refused {
        String type = label.equals("fireskull") ? "skull" : "fireball";
        int index = 0;
        if (args.length > 0 && known.contains(args[0].toLowerCase(Locale.ROOT))) {
            type = args[0].toLowerCase(Locale.ROOT);
            index++;
        } else if (args.length > 0 && number(args[0]) == null) {
            throw new Refused(Messages.FIREBALL_UNKNOWN_TYPE, "{type}", args[0], "{types}", String.join(", ", known));
        }
        double speed = Math.min(DEFAULT_SPEED, maxSpeed);
        if (args.length > index && !args[index].equalsIgnoreCase("ride")) {
            Double parsed = number(args[index]);
            if (parsed == null) throw new Refused(Messages.FIREBALL_INVALID_SPEED, "{max}", format(maxSpeed));
            speed = Math.max(0.0D, Math.min(parsed, maxSpeed));
            index++;
        }
        boolean ride = false;
        if (args.length > index) {
            if (!args[index].equalsIgnoreCase("ride")) throw new Refused(null);
            ride = true;
            index++;
        }
        if (args.length > index) throw new Refused(null);
        return new Request(type, speed, ride);
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        Request request;
        try {
            request = parse(label(context.label()), context.args(), TYPES.keySet(), regeneration.settings().fireballMaxSpeed());
        } catch (Refused refused) {
            if (refused.message == null) context.sendInvalidArgs(spec().selfUsage());
            else UtilitySupport.send(plugin, player, refused.message, refused.replacements);
            return;
        }
        if (!player.hasPermission(PERMISSION_TYPE + request.type())) {
            UtilitySupport.send(plugin, player, Messages.FIREBALL_NO_PERMISSION, "{type}", request.type());
            return;
        }
        if (request.ride() && !player.hasPermission(PERMISSION_RIDE)) {
            UtilitySupport.send(plugin, player, Messages.NO_PERMISSION);
            return;
        }
        Vector direction = player.getEyeLocation().getDirection().multiply(request.speed());
        Location at = player.getEyeLocation().add(direction);
        Projectile projectile = player.getWorld().spawn(at, TYPES.get(request.type()));
        regeneration.tag(projectile, Explosive.FIREBALL);
        projectile.setShooter(player);
        projectile.setVelocity(direction);
        if (projectile instanceof AbstractArrow arrow) arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        if (request.ride()) projectile.addPassenger(player);
        UtilitySupport.audit(plugin, player, "fireball", "type=" + request.type() + " speed=" + format(request.speed()));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0 || args.length > 3) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            for (String type : TYPES.keySet()) {
                if (sender.hasPermission(PERMISSION_TYPE + type)) values.add(type);
            }
        } else if (args.length == 2) {
            values.addAll(List.of("1", "2", "3", "4", "5"));
        }
        if (args.length >= 2 && sender.hasPermission(PERMISSION_RIDE)) values.add("ride");
        values.removeIf(value -> !value.startsWith(prefix));
        return values;
    }

    private static String label(@Nullable String label) {
        if (label == null) return "fireball";
        String bare = label.toLowerCase(Locale.ROOT);
        int colon = bare.indexOf(':');
        return colon < 0 ? bare : bare.substring(colon + 1);
    }

    @Nullable
    private static Double number(String raw) {
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static Map<String, Class<? extends Projectile>> types() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("fireball", "org.bukkit.entity.Fireball");
        names.put("small", "org.bukkit.entity.SmallFireball");
        names.put("large", "org.bukkit.entity.LargeFireball");
        names.put("arrow", "org.bukkit.entity.Arrow");
        names.put("skull", "org.bukkit.entity.WitherSkull");
        names.put("egg", "org.bukkit.entity.Egg");
        names.put("snowball", "org.bukkit.entity.Snowball");
        names.put("expbottle", "org.bukkit.entity.ThrownExpBottle");
        names.put("dragon", "org.bukkit.entity.DragonFireball");
        names.put("splashpotion", "org.bukkit.entity.SplashPotion");
        names.put("lingeringpotion", "org.bukkit.entity.LingeringPotion");
        names.put("trident", "org.bukkit.entity.Trident");
        names.put("windcharge", "org.bukkit.entity.WindCharge");
        Map<String, Class<? extends Projectile>> types = new LinkedHashMap<>();
        names.forEach((type, name) -> {
            try {
                types.put(type, Class.forName(name).asSubclass(Projectile.class));
            } catch (ClassNotFoundException | ClassCastException | LinkageError absent) {
                // Not on this server version (wind charges are 1.21+).
            }
        });
        return Collections.unmodifiableMap(types);
    }
}
