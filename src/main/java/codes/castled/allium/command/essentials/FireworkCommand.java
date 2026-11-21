package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /firework <<meta>|power <power>|clear|fire [amount]>} edits or launches the held firework
 * rocket, matching Essentials' colour, fade, shape, effect, power, clear and fire syntax.
 */
public final class FireworkCommand implements SpecCommandHandler {

    public static final int MIN_POWER = 0;
    public static final int MAX_POWER = 4;
    public static final int MAX_EFFECTS = 16;
    private static final int DEFAULT_FIRE_LIMIT = 10;
    private static final int HARD_FIRE_LIMIT = 50;

    private final PluginStart plugin;

    public FireworkCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.FIREWORK;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        if (!(sender instanceof Player player)) {
            UtilitySupport.send(plugin, sender, Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length == 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (UtilitySupport.isAir(held) || held.getType() != Material.FIREWORK_ROCKET
                || !(held.getItemMeta() instanceof FireworkMeta heldMeta)) {
            UtilitySupport.send(plugin, player, Messages.FIREWORK_HOLD);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("clear".equals(sub)) {
            if (args.length != 1) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            if (UtilitySupport.isInventoryBusy(plugin, player)) {
                UtilitySupport.send(plugin, player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
                return;
            }
            ItemStack copy = held.clone();
            FireworkMeta meta = (FireworkMeta) copy.getItemMeta();
            meta.clearEffects();
            copy.setItemMeta(meta);
            player.getInventory().setItemInMainHand(copy);
            UtilitySupport.send(plugin, player, Messages.FIREWORK_CLEARED);
            UtilitySupport.audit(plugin, player, "firework_clear", "cleared");
            return;
        }

        if ("power".equals(sub) || "p".equals(sub)) {
            if (args.length != 2) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            if (UtilitySupport.isInventoryBusy(plugin, player)) {
                UtilitySupport.send(plugin, player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
                return;
            }
            Integer power = parseInt(args[1]);
            if (power == null || power < MIN_POWER || power > MAX_POWER) {
                UtilitySupport.send(plugin, player, Messages.FIREWORK_INVALID_POWER,
                        "{min}", Integer.toString(MIN_POWER), "{max}", Integer.toString(MAX_POWER));
                return;
            }
            ItemStack copy = held.clone();
            FireworkMeta meta = (FireworkMeta) copy.getItemMeta();
            meta.setPower(power);
            copy.setItemMeta(meta);
            player.getInventory().setItemInMainHand(copy);
            UtilitySupport.send(plugin, player, Messages.FIREWORK_POWER_SET, "{power}", Integer.toString(power));
            UtilitySupport.audit(plugin, player, "firework_power", "power=" + power);
            return;
        }

        if ("fire".equals(sub) || "f".equals(sub)) {
            if (!player.hasPermission("allium.firework.fire")) {
                UtilitySupport.send(plugin, player, Messages.NO_PERMISSION);
                return;
            }
            if (args.length > 2) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            int amount = 1;
            boolean directed = false;
            if (args.length == 2) {
                Integer parsed = parseInt(args[1]);
                if (parsed != null) {
                    if (parsed < 1) {
                        UtilitySupport.send(plugin, player, Messages.FIREWORK_INVALID_FORMAT, "{value}", args[1], "{key}", "amount");
                        return;
                    }
                    int limit = fireLimit();
                    if (parsed > limit) {
                        amount = limit;
                        UtilitySupport.send(plugin, player, Messages.FIREWORK_FIRE_LIMIT, "{limit}", Integer.toString(limit));
                    } else {
                        amount = parsed;
                    }
                } else {
                    directed = true;
                }
            }
            for (int i = 0; i < amount; i++) {
                Firework rocket = player.getWorld().spawn(player.getLocation(), Firework.class);
                FireworkMeta launchMeta = (FireworkMeta) heldMeta.clone();
                if (directed) {
                    Vector direction = player.getEyeLocation().getDirection().multiply(0.070);
                    if (launchMeta.getPower() > 1) {
                        launchMeta.setPower(1);
                    }
                    rocket.setVelocity(direction);
                }
                rocket.setFireworkMeta(launchMeta);
            }
            UtilitySupport.send(plugin, player, Messages.FIREWORK_FIRED, "{count}", Integer.toString(amount));
            UtilitySupport.audit(plugin, player, "firework_fire", "count=" + amount + " directed=" + directed);
            return;
        }

        if (UtilitySupport.isInventoryBusy(plugin, player)) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }

        // Parse meta tokens into one or more FireworkEffects before mutating the held item.
        List<FireworkEffect> parsed = new ArrayList<>();
        FireworkEffect.Builder builder = FireworkEffect.builder();
        boolean hasPrimaryColor = false;

        for (String arg : args) {
            int sep = arg.indexOf(':');
            if (sep <= 0 || sep == arg.length() - 1) {
                UtilitySupport.send(plugin, player, Messages.FIREWORK_INVALID_FORMAT, "{value}", arg, "{key}", "meta");
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            String key = arg.substring(0, sep).toLowerCase(Locale.ROOT);
            String val = arg.substring(sep + 1);
            switch (key) {
                case "color", "colour", "c" -> {
                    if (hasPrimaryColor) {
                        parsed.add(builder.build());
                        builder = FireworkEffect.builder();
                    }
                    List<Color> colors = parseColors(val);
                    if (colors == null) {
                        UtilitySupport.send(plugin, player, Messages.FIREWORK_INVALID_FORMAT, "{value}", val, "{key}", key);
                        return;
                    }
                    builder.withColor(colors);
                    hasPrimaryColor = true;
                }
                case "fade", "f" -> {
                    List<Color> colors = parseColors(val);
                    if (colors == null) {
                        UtilitySupport.send(plugin, player, Messages.FIREWORK_INVALID_FORMAT, "{value}", val, "{key}", key);
                        return;
                    }
                    builder.withFade(colors);
                }
                case "shape", "type", "s", "t" -> {
                    FireworkEffect.Type shape = parseShape(val);
                    if (shape == null) {
                        UtilitySupport.send(plugin, player, Messages.FIREWORK_INVALID_FORMAT, "{value}", val, "{key}", key);
                        return;
                    }
                    builder.with(shape);
                }
                case "effect", "e" -> {
                    String[] parts = val.split(",", -1);
                    for (String part : parts) {
                        String eff = part.trim().toLowerCase(Locale.ROOT);
                        if ("twinkle".equals(eff) || "flicker".equals(eff)) {
                            builder.flicker(true);
                        } else if ("trail".equals(eff)) {
                            builder.trail(true);
                        } else {
                            UtilitySupport.send(plugin, player, Messages.FIREWORK_INVALID_FORMAT, "{value}", val, "{key}", key);
                            return;
                        }
                    }
                }
                default -> {
                    UtilitySupport.send(plugin, player, Messages.FIREWORK_INVALID_FORMAT, "{value}", val, "{key}", key);
                    context.sendInvalidArgs(spec().selfUsage());
                    return;
                }
            }
        }

        if (hasPrimaryColor) {
            parsed.add(builder.build());
        }
        if (parsed.isEmpty()) {
            UtilitySupport.send(plugin, player, Messages.FIREWORK_COLOR_REQUIRED);
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        int existingCount = heldMeta.getEffectsSize();
        int totalCount = existingCount + parsed.size();
        if (totalCount > 1 && !player.hasPermission("allium.firework.multiple")) {
            UtilitySupport.send(plugin, player, Messages.FIREWORK_NO_MULTIPLE);
            return;
        }
        if (totalCount > MAX_EFFECTS) {
            UtilitySupport.send(plugin, player, Messages.FIREWORK_TOO_MANY, "{max}", Integer.toString(MAX_EFFECTS));
            return;
        }

        ItemStack copy = held.clone();
        FireworkMeta meta = (FireworkMeta) copy.getItemMeta();
        meta.addEffects(parsed);
        copy.setItemMeta(meta);
        player.getInventory().setItemInMainHand(copy);
        UtilitySupport.send(plugin, player, Messages.FIREWORK_ADDED,
                "{count}", Integer.toString(parsed.size()),
                "{total}", Integer.toString(totalCount));
        UtilitySupport.audit(plugin, player, "firework", "added=" + parsed.size() + " total=" + totalCount);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.firework")) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (prefix.startsWith("color:") || prefix.startsWith("colour:") || prefix.startsWith("c:")) {
                return completeColors(prefix);
            }
            List<String> out = new ArrayList<>();
            out.add("clear");
            out.add("power");
            out.add("color:");
            if (sender.hasPermission("allium.firework.fire")) {
                out.add("fire");
            }
            return out.stream().filter(v -> v.startsWith(prefix)).toList();
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("clear".equals(first)) {
            return List.of();
        }
        if ("power".equals(first) || "p".equals(first)) {
            if (args.length == 2) {
                return List.of("0", "1", "2", "3", "4").stream().filter(v -> v.startsWith(current)).toList();
            }
            return List.of();
        }
        if ("fire".equals(first) || "f".equals(first)) {
            if (args.length == 2 && sender.hasPermission("allium.firework.fire")) {
                return List.of("1", "3", "5", "aim").stream().filter(v -> v.startsWith(current)).toList();
            }
            return List.of();
        }

        if (current.startsWith("color:") || current.startsWith("colour:") || current.startsWith("c:")
                || current.startsWith("fade:") || current.startsWith("f:")) {
            return completeColors(current);
        }
        if (current.startsWith("shape:") || current.startsWith("type:") || current.startsWith("s:") || current.startsWith("t:")) {
            String keyPrefix = current.substring(0, current.indexOf(':') + 1);
            return List.of("ball", "large", "star", "creeper", "burst").stream()
                    .map(v -> keyPrefix + v)
                    .filter(v -> v.startsWith(current))
                    .toList();
        }
        if (current.startsWith("effect:") || current.startsWith("e:")) {
            String keyPrefix = current.substring(0, current.indexOf(':') + 1);
            return List.of("trail", "twinkle", "trail,twinkle", "twinkle,trail").stream()
                    .map(v -> keyPrefix + v)
                    .filter(v -> v.startsWith(current))
                    .toList();
        }

        List<String> keys = new ArrayList<>(List.of("fade:", "shape:ball", "shape:large", "shape:star", "shape:creeper",
                "shape:burst", "effect:trail", "effect:twinkle", "effect:trail,twinkle"));
        if (sender.hasPermission("allium.firework.multiple")) {
            keys.add("color:");
        }
        return keys.stream().filter(v -> v.startsWith(current)).toList();
    }

    private int fireLimit() {
        int configured = plugin.getConfig() == null ? DEFAULT_FIRE_LIMIT : plugin.getConfig().getInt("spawnmob.limit", DEFAULT_FIRE_LIMIT);
        return Math.max(1, Math.min(HARD_FIRE_LIMIT, configured));
    }

    private static List<String> completeColors(String current) {
        int colon = current.indexOf(':');
        int lastComma = current.lastIndexOf(',');
        int cut = Math.max(colon, lastComma) + 1;
        String prefix = current.substring(0, cut);
        String tail = current.substring(cut);
        List<String> out = new ArrayList<>();
        for (DyeColor dye : DyeColor.values()) {
            String name = dye.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(tail)) {
                out.add(prefix + name);
            }
        }
        return out;
    }

    @Nullable
    static List<Color> parseColors(String raw) {
        String[] parts = raw.split(",", -1);
        List<Color> colors = new ArrayList<>(parts.length);
        for (String part : parts) {
            Color c = PotionCommand.parseColor(part);
            if (c == null) {
                return null;
            }
            colors.add(c);
        }
        return colors.isEmpty() ? null : colors;
    }

    @Nullable
    static FireworkEffect.Type parseShape(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "ball" -> FireworkEffect.Type.BALL;
            case "large", "ball_large", "large_ball" -> FireworkEffect.Type.BALL_LARGE;
            case "star" -> FireworkEffect.Type.STAR;
            case "creeper" -> FireworkEffect.Type.CREEPER;
            case "burst" -> FireworkEffect.Type.BURST;
            default -> null;
        };
    }

    @Nullable
    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
