package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.item.library.PotionAdapter;
import codes.castled.allium.message.Messages;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /potion <clear|apply|effect:<effect> power:<power> duration:<seconds>>} ({@code /elixer})
 * edits or applies custom effects on the held potion or tipped arrow, matching Essentials.
 */
public final class PotionCommand implements SpecCommandHandler {

    private static final int MAX_DURATION_SECONDS = 1_000_000;
    private static final int MAX_POWER = 256;
    private static final int MAX_AMPLIFIER = 255;
    private static final List<String> COMMON_DURATIONS = List.of("30", "60", "120", "300", "600", "-1");

    /** Common Essentials and legacy effect aliases mapped to Minecraft namespaced keys. */
    private static final Map<String, String> EFFECT_ALIASES = Map.ofEntries(
            Map.entry("swiftness", "speed"),
            Map.entry("slow", "slowness"),
            Map.entry("fast_digging", "haste"),
            Map.entry("digspeed", "haste"),
            Map.entry("slow_digging", "mining_fatigue"),
            Map.entry("fatigue", "mining_fatigue"),
            Map.entry("increase_damage", "strength"),
            Map.entry("dmg", "strength"),
            Map.entry("heal", "instant_health"),
            Map.entry("healing", "instant_health"),
            Map.entry("instant_heal", "instant_health"),
            Map.entry("harm", "instant_damage"),
            Map.entry("harming", "instant_damage"),
            Map.entry("jump", "jump_boost"),
            Map.entry("leaping", "jump_boost"),
            Map.entry("confusion", "nausea"),
            Map.entry("regen", "regeneration"),
            Map.entry("damage_resistance", "resistance"),
            Map.entry("fireres", "fire_resistance"),
            Map.entry("fire_res", "fire_resistance"),
            Map.entry("water_breath", "water_breathing"),
            Map.entry("waterbreath", "water_breathing"),
            Map.entry("invis", "invisibility"),
            Map.entry("nv", "night_vision"),
            Map.entry("nightvision", "night_vision")
    );

    /** Fallback map of Bukkit static constants used only if the server registry is uninitialized (unit tests). */
    private static final Map<String, PotionEffectType> STATIC_EFFECTS = loadStaticEffects();

    private static Map<String, PotionEffectType> loadStaticEffects() {
        Map<String, PotionEffectType> map = new java.util.LinkedHashMap<>();
        for (java.lang.reflect.Field field : PotionEffectType.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != PotionEffectType.class) {
                continue;
            }
            try {
                PotionEffectType type = (PotionEffectType) field.get(null);
                if (type != null) {
                    map.putIfAbsent(canonicalKey(type), type);
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
                // ignore inaccessible constants
            }
        }
        return Map.copyOf(map);
    }

    private final PluginStart plugin;

    public PotionCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.POTION;
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
            Set<String> allowed = allowedEffectKeys(player);
            if (allowed.isEmpty()) {
                UtilitySupport.send(plugin, player, Messages.POTION_NONE_ALLOWED);
            } else {
                UtilitySupport.send(plugin, player, Messages.POTION_LIST, "{effects}", String.join(", ", allowed));
            }
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isPotionCarrier(held) || !(held.getItemMeta() instanceof PotionMeta pmeta)) {
            UtilitySupport.send(plugin, player, Messages.POTION_HOLD);
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
            PotionMeta meta = (PotionMeta) copy.getItemMeta();
            meta.clearCustomEffects();
            copy.setItemMeta(meta);
            player.getInventory().setItemInMainHand(copy);
            UtilitySupport.send(plugin, player, Messages.POTION_CLEARED);
            UtilitySupport.audit(plugin, player, "potion_clear", "type=" + copy.getType().name());
            return;
        }

        if ("apply".equals(sub)) {
            if (args.length != 1) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            if (!player.hasPermission("allium.potion.apply")) {
                UtilitySupport.send(plugin, player, Messages.NO_PERMISSION);
                return;
            }
            List<PotionEffect> effects = pmeta.getCustomEffects();
            if (effects.isEmpty()) {
                UtilitySupport.send(plugin, player, Messages.POTION_NO_EFFECTS);
                return;
            }
            for (PotionEffect effect : effects) {
                player.addPotionEffect(effect, true);
            }
            UtilitySupport.send(plugin, player, Messages.POTION_APPLIED, "{count}", Integer.toString(effects.size()));
            UtilitySupport.audit(plugin, player, "potion_apply", "count=" + effects.size());
            return;
        }

        if (UtilitySupport.isInventoryBusy(plugin, player)) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }

        List<PotionEffect> parsedEffects = new ArrayList<>();
        Boolean splashOverride = null;
        Color colorOverride = null;

        PotionEffectType pendingType = null;
        Integer pendingAmplifier = null;
        Integer pendingDurationTicks = null;

        for (String arg : args) {
            int sep = arg.indexOf(':');
            if (sep <= 0 || sep == arg.length() - 1) {
                UtilitySupport.send(plugin, player, Messages.POTION_INVALID_META, "{value}", arg);
                return;
            }
            String key = arg.substring(0, sep).toLowerCase(Locale.ROOT);
            String val = arg.substring(sep + 1);
            switch (key) {
                case "effect", "e" -> {
                    if (pendingType != null) {
                        UtilitySupport.send(plugin, player, Messages.POTION_INVALID);
                        context.sendInvalidArgs(spec().selfUsage());
                        return;
                    }
                    PotionEffectType resolved = resolveEffect(val);
                    if (resolved == null) {
                        UtilitySupport.send(plugin, player, Messages.POTION_INVALID_META, "{value}", val);
                        return;
                    }
                    if (!hasEffectPermission(player, resolved)) {
                        UtilitySupport.send(plugin, player, Messages.POTION_NO_EFFECT_PERM, "{effect}", canonicalKey(resolved));
                        return;
                    }
                    pendingType = resolved;
                }
                case "power", "p" -> {
                    Integer parsed = parseInt(val, 0, MAX_POWER);
                    if (parsed == null) {
                        UtilitySupport.send(plugin, player, Messages.POTION_INVALID_META, "{value}", val);
                        return;
                    }
                    pendingAmplifier = Math.max(0, Math.min(MAX_AMPLIFIER, parsed - 1));
                }
                case "amplifier", "a" -> {
                    Integer parsed = parseInt(val, 0, MAX_AMPLIFIER);
                    if (parsed == null) {
                        UtilitySupport.send(plugin, player, Messages.POTION_INVALID_META, "{value}", val);
                        return;
                    }
                    pendingAmplifier = parsed;
                }
                case "duration", "d" -> {
                    Integer ticks = parseDurationTicks(val);
                    if (ticks == null) {
                        UtilitySupport.send(plugin, player, Messages.POTION_INVALID_META, "{value}", val);
                        return;
                    }
                    pendingDurationTicks = ticks;
                }
                case "splash", "s" -> {
                    Boolean parsed = parseBool(val);
                    if (parsed == null) {
                        UtilitySupport.send(plugin, player, Messages.POTION_INVALID_META, "{value}", val);
                        return;
                    }
                    splashOverride = parsed;
                }
                case "color", "colour", "c" -> {
                    Color parsed = parseColor(val);
                    if (parsed == null) {
                        UtilitySupport.send(plugin, player, Messages.POTION_INVALID_META, "{value}", val);
                        return;
                    }
                    colorOverride = parsed;
                }
                default -> {
                    UtilitySupport.send(plugin, player, Messages.POTION_INVALID_META, "{value}", arg);
                    return;
                }
            }

            if (pendingType != null && pendingAmplifier != null && pendingDurationTicks != null) {
                int ticks = isInstant(pendingType) && pendingDurationTicks == 0 ? 1 : pendingDurationTicks;
                parsedEffects.add(new PotionEffect(pendingType, ticks, pendingAmplifier));
                pendingType = null;
                pendingAmplifier = null;
                pendingDurationTicks = null;
            }
        }

        if (pendingType != null || pendingAmplifier != null || pendingDurationTicks != null
                || (parsedEffects.isEmpty() && splashOverride == null && colorOverride == null)) {
            UtilitySupport.send(plugin, player, Messages.POTION_INVALID);
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        // Enforce allium.potions.multiple when the resulting potion carries more than one custom effect.
        Set<PotionEffectType> distinct = new LinkedHashSet<>();
        for (PotionEffect existing : pmeta.getCustomEffects()) {
            distinct.add(existing.getType());
        }
        for (PotionEffect added : parsedEffects) {
            distinct.add(added.getType());
        }
        if (distinct.size() > 1 && !player.hasPermission("allium.potions.multiple")) {
            UtilitySupport.send(plugin, player, Messages.POTION_NO_MULTIPLE);
            return;
        }

        ItemStack copy = held.clone();
        if (splashOverride != null) {
            if (splashOverride && copy.getType() == Material.POTION) {
                copy.setType(Material.SPLASH_POTION);
            } else if (!splashOverride && copy.getType() == Material.SPLASH_POTION) {
                copy.setType(Material.POTION);
            }
        }
        PotionMeta meta = (PotionMeta) copy.getItemMeta();
        for (PotionEffect effect : parsedEffects) {
            meta.addCustomEffect(effect, true);
        }
        if (colorOverride != null) {
            meta.setColor(colorOverride);
        }
        if (!parsedEffects.isEmpty()) {
            PotionAdapter.nameFromEffects(meta, copy.getType());
        }
        copy.setItemMeta(meta);
        player.getInventory().setItemInMainHand(copy);

        List<String> summary = new ArrayList<>();
        for (PotionEffect effect : parsedEffects) {
            String dur = effect.getDuration() == PotionEffect.INFINITE_DURATION
                    ? "infinite"
                    : (effect.getDuration() / 20) + "s";
            summary.add(canonicalKey(effect.getType()) + " " + (effect.getAmplifier() + 1) + " (" + dur + ")");
        }
        if (splashOverride != null) {
            summary.add("splash:" + splashOverride);
        }
        if (colorOverride != null) {
            summary.add(String.format(Locale.ROOT, "#%06X", colorOverride.asRGB() & 0xFFFFFF));
        }
        String joined = String.join(", ", summary);
        UtilitySupport.send(plugin, player, Messages.POTION_UPDATED, "{summary}", joined);
        UtilitySupport.audit(plugin, player, "potion", "applied=" + joined);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.potion")) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            out.add("clear");
            if (sender.hasPermission("allium.potion.apply")) {
                out.add("apply");
            }
            for (String key : allowedEffectKeys(sender)) {
                out.add("effect:" + key);
            }
            out.add("splash:true");
            out.add("splash:false");
            return out.stream().filter(v -> v.startsWith(prefix)).limit(64).toList();
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if ("clear".equals(first) || "apply".equals(first)) {
            return List.of();
        }
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (current.startsWith("effect:") || current.startsWith("e:")) {
            String keyPrefix = current.substring(0, current.indexOf(':') + 1);
            String valPrefix = current.substring(current.indexOf(':') + 1);
            return allowedEffectKeys(sender).stream()
                    .filter(k -> k.startsWith(valPrefix))
                    .map(k -> keyPrefix + k)
                    .limit(64)
                    .toList();
        }
        if (current.startsWith("power:") || current.startsWith("p:")) {
            String keyPrefix = current.substring(0, current.indexOf(':') + 1);
            return List.of("1", "2", "3", "4").stream().map(v -> keyPrefix + v).filter(v -> v.startsWith(current)).toList();
        }
        if (current.startsWith("amplifier:") || current.startsWith("a:")) {
            String keyPrefix = current.substring(0, current.indexOf(':') + 1);
            return List.of("0", "1", "2", "3").stream().map(v -> keyPrefix + v).filter(v -> v.startsWith(current)).toList();
        }
        if (current.startsWith("duration:") || current.startsWith("d:")) {
            String keyPrefix = current.substring(0, current.indexOf(':') + 1);
            return COMMON_DURATIONS.stream().map(v -> keyPrefix + v).filter(v -> v.startsWith(current)).toList();
        }
        if (current.startsWith("splash:") || current.startsWith("s:")) {
            String keyPrefix = current.substring(0, current.indexOf(':') + 1);
            return List.of("true", "false").stream().map(v -> keyPrefix + v).filter(v -> v.startsWith(current)).toList();
        }
        if (current.startsWith("color:") || current.startsWith("colour:") || current.startsWith("c:")) {
            String keyPrefix = current.substring(0, current.indexOf(':') + 1);
            String valPrefix = current.substring(current.indexOf(':') + 1);
            List<String> out = new ArrayList<>();
            for (DyeColor dye : DyeColor.values()) {
                String name = dye.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(valPrefix)) {
                    out.add(keyPrefix + name);
                }
            }
            return out;
        }

        // Suggest the next logical token based on what's already present in the current group.
        boolean hasEffect = false;
        boolean hasPower = false;
        boolean hasDuration = false;
        boolean hasSplash = false;
        for (int i = 0; i < args.length - 1; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            if (a.startsWith("effect:") || a.startsWith("e:")) hasEffect = true;
            else if (a.startsWith("power:") || a.startsWith("p:") || a.startsWith("amplifier:") || a.startsWith("a:")) hasPower = true;
            else if (a.startsWith("duration:") || a.startsWith("d:")) hasDuration = true;
            else if (a.startsWith("splash:") || a.startsWith("s:")) hasSplash = true;
            if (hasEffect && hasPower && hasDuration) {
                hasEffect = false;
                hasPower = false;
                hasDuration = false;
            }
        }
        List<String> suggestions = new ArrayList<>();
        if (!hasEffect) {
            for (String key : allowedEffectKeys(sender)) {
                suggestions.add("effect:" + key);
            }
        } else if (!hasPower) {
            suggestions.addAll(List.of("power:1", "power:2", "power:3", "power:4", "amplifier:0", "amplifier:1", "amplifier:2", "amplifier:3"));
        } else if (!hasDuration) {
            for (String d : COMMON_DURATIONS) {
                suggestions.add("duration:" + d);
            }
        }
        if (!hasSplash) {
            suggestions.add("splash:true");
            suggestions.add("splash:false");
        }
        return suggestions.stream().filter(v -> v.startsWith(current)).limit(64).toList();
    }

    static boolean isPotionCarrier(@Nullable ItemStack stack) {
        if (UtilitySupport.isAir(stack)) {
            return false;
        }
        return switch (stack.getType()) {
            case POTION, SPLASH_POTION, LINGERING_POTION, TIPPED_ARROW -> true;
            default -> false;
        };
    }

    @SuppressWarnings("deprecation")
    @Nullable
    static PotionEffectType resolveEffect(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace('-', '_');
        String aliased = EFFECT_ALIASES.getOrDefault(normalized, normalized);
        try {
            PotionEffectType byKey = PotionEffectType.getByKey(NamespacedKey.minecraft(aliased));
            if (byKey != null) {
                return byKey;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid namespaced key characters; try legacy lookup below.
        }
        PotionEffectType byName = PotionEffectType.getByName(aliased.toUpperCase(Locale.ROOT));
        if (byName != null) {
            return byName;
        }
        PotionEffectType rawByName = PotionEffectType.getByName(normalized.toUpperCase(Locale.ROOT));
        if (rawByName != null) {
            return rawByName;
        }
        PotionEffectType fallback = STATIC_EFFECTS.get(aliased);
        return fallback != null ? fallback : STATIC_EFFECTS.get(normalized);
    }

    @SuppressWarnings("deprecation")
    static boolean hasEffectPermission(CommandSender sender, PotionEffectType type) {
        if (sender.hasPermission("allium.potions.*")) {
            return true;
        }
        String key = canonicalKey(type);
        if (sender.hasPermission("allium.potions." + key)) {
            return true;
        }
        String legacy = legacyName(type);
        return legacy != null && sender.hasPermission("allium.potions." + legacy.toLowerCase(Locale.ROOT));
    }

    static Set<String> allowedEffectKeys(CommandSender sender) {
        Set<String> out = new TreeSet<>();
        boolean anyRegistered = false;
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type == null) {
                continue;
            }
            anyRegistered = true;
            if (hasEffectPermission(sender, type)) {
                out.add(canonicalKey(type));
            }
        }
        if (!anyRegistered) {
            for (Map.Entry<String, PotionEffectType> entry : STATIC_EFFECTS.entrySet()) {
                if (hasEffectPermission(sender, entry.getValue())) {
                    out.add(entry.getKey());
                }
            }
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    static String canonicalKey(PotionEffectType type) {
        try {
            NamespacedKey key = type.getKey();
            if (key != null && !key.getKey().isEmpty()) {
                return key.getKey().toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException ignored) {
            // Fallback for mocks without NamespacedKey
        }
        String legacy = legacyName(type);
        return legacy == null ? "unknown" : legacy.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static String legacyName(PotionEffectType type) {
        try {
            return type.getName();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isInstant(PotionEffectType type) {
        try {
            return type.isInstant();
        } catch (RuntimeException ignored) {
            return type == PotionEffectType.HEAL || type == PotionEffectType.HARM || type == PotionEffectType.SATURATION;
        }
    }

    @Nullable
    private static Integer parseInt(String raw, int min, int max) {
        try {
            int v = Integer.parseInt(raw);
            return v >= min && v <= max ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Integer parseDurationTicks(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if ("-1".equals(lower) || "inf".equals(lower) || "infinite".equals(lower)) {
            return PotionEffect.INFINITE_DURATION;
        }
        Integer seconds = parseInt(raw, 0, MAX_DURATION_SECONDS);
        return seconds == null ? null : seconds * 20;
    }

    @Nullable
    private static Boolean parseBool(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> Boolean.TRUE;
            case "false", "no", "off", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    @Nullable
    static Color parseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("#")) {
            String hex = trimmed.substring(1);
            if (hex.matches("[0-9a-fA-F]{6}")) {
                return Color.fromRGB(Integer.parseInt(hex, 16));
            }
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT).replace('-', '_');
        if ("GREY".equals(upper)) upper = "GRAY";
        else if ("LIGHT_GREY".equals(upper) || "SILVER".equals(upper)) upper = "LIGHT_GRAY";
        try {
            return DyeColor.valueOf(upper).getFireworkColor();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
