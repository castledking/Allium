package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

final class LegacyFireworkCommand {
    private static final int MAX_EFFECTS = 16;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;

    LegacyFireworkCommand(LegacyConfigManager config, LegacyMessageManager messages) {
        this.config = config;
        this.messages = messages;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /firework.");
            return true;
        }
        if (args.length == 0) return false;
        Player player = (Player) sender;
        if (!player.hasPermission("allium.firework")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to edit fireworks.");
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemMeta itemMeta = held == null ? null : held.getItemMeta();
        if (!(itemMeta instanceof FireworkMeta)) {
            notify(player, "hold", "&cHold a firework rocket in your main hand.");
            return true;
        }
        FireworkMeta meta = (FireworkMeta) itemMeta;
        String operation = args[0].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "clear":
                if (args.length != 1) return false;
                meta.clearEffects();
                replace(player, held, meta);
                notify(player, "cleared", "&aCleared all effects from the held firework.");
                return true;
            case "power":
            case "p":
                if (args.length != 2) return false;
                Integer power = parseInt(args[1]);
                if (power == null || power < 0 || power > 4) {
                    notify(player, "invalid-power", "&cFirework power must be from {min} to {max}.",
                            "{min}", "0", "{max}", "4");
                    return true;
                }
                meta.setPower(power);
                replace(player, held, meta);
                notify(player, "power-set", "&aSet held firework power to {power}.", "{power}", power.toString());
                return true;
            case "fire":
            case "f":
                return fire(player, meta, args);
            default:
                return addEffects(player, held, meta, args);
        }
    }

    private boolean fire(Player player, FireworkMeta meta, String[] args) {
        if (!player.hasPermission("allium.firework.fire")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to launch fireworks.");
            return true;
        }
        if (args.length > 2) return false;
        boolean directed = args.length == 2 && "aim".equalsIgnoreCase(args[1]);
        Integer count = args.length == 1 || directed ? Integer.valueOf(1) : parseInt(args[1]);
        if (count == null || count < 1) return invalid(player, "amount", args[1]);
        int limit = Math.max(1, Math.min(50, config.raw().getInt("spawnmob.limit", 10)));
        if (count > limit) {
            count = limit;
            notify(player, "fire-limit", "&eClamped firework launch count to {limit}.", "{limit}", Integer.toString(limit));
        }
        FireworkMeta launchMeta = meta.clone();
        if (directed && launchMeta.getPower() > 1) launchMeta.setPower(1);
        int launched = 0;
        for (int i = 0; i < count; i++) {
            Firework rocket = player.getWorld().spawn(player.getLocation(), Firework.class);
            if (rocket == null || !rocket.isValid()) continue;
            if (directed) rocket.setVelocity(player.getEyeLocation().getDirection().multiply(0.070));
            rocket.setFireworkMeta(launchMeta);
            launched++;
        }
        notify(player, "fired", "&aLaunched {count} firework(s).", "{count}", Integer.toString(launched));
        return true;
    }

    private boolean addEffects(Player player, ItemStack held, FireworkMeta meta, String[] args) {
        List<FireworkEffect> effects = new ArrayList<>();
        FireworkEffect.Builder builder = FireworkEffect.builder();
        boolean hasColor = false;
        for (String arg : args) {
            int split = arg.indexOf(':');
            if (split <= 0 || split == arg.length() - 1) return invalid(player, "effect", arg);
            String key = arg.substring(0, split).toLowerCase(Locale.ROOT);
            String value = arg.substring(split + 1);
            switch (key) {
                case "c":
                case "colour":
                case "color":
                    List<Color> primary = colors(value);
                    if (primary.isEmpty()) return invalid(player, key, value);
                    if (hasColor) {
                        effects.add(builder.build());
                        builder = FireworkEffect.builder();
                    }
                    builder.withColor(primary);
                    hasColor = true;
                    break;
                case "f":
                case "fade":
                    List<Color> fades = colors(value);
                    if (fades.isEmpty()) return invalid(player, key, value);
                    builder.withFade(fades);
                    break;
                case "s":
                case "t":
                case "shape":
                case "type":
                    FireworkEffect.Type type = shape(value);
                    if (type == null) return invalid(player, key, value);
                    builder.with(type);
                    break;
                case "e":
                case "effect":
                    for (String flag : value.toLowerCase(Locale.ROOT).split(",", -1)) {
                        switch (flag) {
                            case "trail": builder.trail(true); break;
                            case "flicker":
                            case "twinkle": builder.flicker(true); break;
                            default: return invalid(player, key, flag);
                        }
                    }
                    break;
                default:
                    return invalid(player, key, value);
            }
        }
        if (!hasColor) {
            notify(player, "color-required", "&cUse color:red,blue to specify at least one colour.");
            return true;
        }
        effects.add(builder.build());
        int total = meta.getEffectsSize() + effects.size();
        if (total > MAX_EFFECTS) {
            notify(player, "too-many", "&cA firework can hold at most {max} effects.", "{max}", Integer.toString(MAX_EFFECTS));
            return true;
        }
        if (total > 1 && !player.hasPermission("allium.firework.multiple")) {
            notify(player, "no-multiple", "&cYou do not have permission to add multiple effects.");
            return true;
        }
        meta.addEffects(effects);
        replace(player, held, meta);
        notify(player, "added", "&aAdded {count} effect(s) to the held firework ({total} total).",
                "{count}", Integer.toString(effects.size()), "{total}", Integer.toString(total));
        return true;
    }

    private static void replace(Player player, ItemStack held, FireworkMeta meta) {
        ItemStack copy = held.clone();
        copy.setItemMeta(meta);
        player.getInventory().setItemInMainHand(copy);
    }

    private static List<Color> colors(String value) {
        List<Color> colors = new ArrayList<>();
        for (String field : value.split(",", -1)) {
            String name = field.trim();
            if (name.matches("#[0-9a-fA-F]{6}")) {
                colors.add(Color.fromRGB(Integer.parseInt(name.substring(1), 16)));
                continue;
            }
            name = name.toUpperCase(Locale.ROOT).replace('-', '_');
            if ("GREY".equals(name)) name = "GRAY";
            if ("LIGHT_GREY".equals(name) || "SILVER".equals(name)) name = "LIGHT_GRAY";
            try {
                colors.add(DyeColor.valueOf(name).getFireworkColor());
            } catch (IllegalArgumentException failure) {
                if (!"LIGHT_GRAY".equals(name)) return Collections.emptyList();
                colors.add(DyeColor.valueOf("SILVER").getFireworkColor());
            }
        }
        return colors;
    }

    private static FireworkEffect.Type shape(String value) {
        String name = value.toUpperCase(Locale.ROOT).replace('-', '_');
        if ("LARGE".equals(name) || "LARGE_BALL".equals(name)) name = "BALL_LARGE";
        try {
            return FireworkEffect.Type.valueOf(name);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private boolean invalid(Player player, String key, String value) {
        notify(player, "invalid-format", "&cInvalid value {value} for {key}.", "{value}", value, "{key}", key);
        return true;
    }

    private void notify(Player player, String key, String fallback, String... replacements) {
        messages.send(player, "utility.firework." + key, "{prefix} " + fallback, replacements);
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        List<String> values;
        if (args.length == 1) {
            values = new ArrayList<>(Arrays.asList("clear", "power", "color:"));
            if (sender.hasPermission("allium.firework.fire")) values.add("fire");
        } else if (args.length == 2 && "power".equalsIgnoreCase(args[0])) {
            values = Arrays.asList("0", "1", "2", "3", "4");
        } else if (args.length == 2 && "fire".equalsIgnoreCase(args[0]) && sender.hasPermission("allium.firework.fire")) {
            values = Arrays.asList("1", "aim");
        } else if (args.length > 1) {
            values = Arrays.asList("fade:", "shape:ball", "shape:large", "shape:star", "shape:creeper", "shape:burst",
                    "effect:trail", "effect:twinkle");
        } else {
            return Collections.emptyList();
        }
        return values.stream().filter(value -> value.startsWith(prefix)).collect(Collectors.toList());
    }
}
