package codes.castled.allium.rtp;

import codes.castled.allium.economy.Money;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code rtp.yml}: how {@code /rtp} picks a spot. A location is a named area ({@code locations.<name>});
 * any world name also works as an area with the defaults, which is what {@code default-location:
 * '{world}'} gives a player in their own world.
 */
public record RtpSettings(String defaultLocation, boolean perLocationPermission, int findAttempts, int cacheSize,
                          double defaultMaxRange, Set<String> excludedBiomes, List<String> avoidBlocks, boolean avoidClaims,
                          Set<String> disabledWorlds, @Nullable String disabledWorldRedirect,
                          boolean firstJoin, String firstJoinLocation, Map<String, Area> locations,
                          Money cost, Map<String, Long> cooldownTiers, boolean onDeath, String onDeathLocation, int biomeScan) {

    /** Seconds; the permission {@code allium.rtp.cooldown.<tier>} selects a tier, the shortest held wins. */
    public static final String COOLDOWN_TIER_PERMISSION = "allium.rtp.cooldown.";

    public enum Shape { SQUARE, CIRCLE }

    /**
     * Where to search. A null centre is the world border's centre, a null maximum range is
     * {@code default-max-range}, and null heights are the world's own (32 to 120 in the nether, so the
     * roof is never a landing).
     */
    public record Area(String world, @Nullable Double centerX, @Nullable Double centerZ, double minRange,
                       @Nullable Double maxRange, Shape shape, @Nullable Integer minY, @Nullable Integer maxY,
                       @Nullable Money cost) {

        public Area {
            if (world == null || world.isBlank()) throw new IllegalArgumentException("a location needs a world");
            if (!Double.isFinite(minRange) || minRange < 0) throw new IllegalArgumentException("min-range must be 0 or more");
            if (maxRange != null && (!Double.isFinite(maxRange) || maxRange <= minRange)) {
                throw new IllegalArgumentException("max-range must be larger than min-range");
            }
            if (minY != null && maxY != null && maxY <= minY) throw new IllegalArgumentException("max-y must be above min-y");
            if (centerX != null && !Double.isFinite(centerX) || centerZ != null && !Double.isFinite(centerZ)) {
                throw new IllegalArgumentException("center must be finite");
            }
            if (cost != null && cost.minor() < 0) throw new IllegalArgumentException("cost cannot be negative");
        }

        static Area of(String world) {
            return new Area(world, null, null, 0, null, Shape.SQUARE, null, null, null);
        }
    }

    public RtpSettings {
        defaultLocation = defaultLocation == null || defaultLocation.isBlank() ? "{world}" : defaultLocation;
        firstJoinLocation = firstJoinLocation == null || firstJoinLocation.isBlank() ? "{world}" : firstJoinLocation;
        if (findAttempts < 1 || findAttempts > 200) throw new IllegalArgumentException("find-attempts must be 1 to 200");
        if (cacheSize < 0 || cacheSize > 20) throw new IllegalArgumentException("cache-size must be 0 to 20");
        if (!Double.isFinite(defaultMaxRange) || defaultMaxRange < 16) throw new IllegalArgumentException("default-max-range must be at least 16");
        excludedBiomes = Set.copyOf(excludedBiomes);
        avoidBlocks = List.copyOf(avoidBlocks);
        disabledWorlds = Set.copyOf(disabledWorlds);
        locations = Map.copyOf(locations);
        onDeathLocation = onDeathLocation == null || onDeathLocation.isBlank() ? "{world}" : onDeathLocation;
        if (cost == null || cost.minor() < 0) throw new IllegalArgumentException("cost cannot be negative");
        cooldownTiers = Map.copyOf(cooldownTiers);
        for (var tier : cooldownTiers.entrySet()) {
            if (!tier.getKey().matches("[a-z0-9_-]{1,32}") || tier.getValue() < 0 || tier.getValue() > 86_400 * 30) {
                throw new IllegalArgumentException("invalid cooldown tier " + tier.getKey());
            }
        }
        if (biomeScan < 1 || biomeScan > 4096) throw new IllegalArgumentException("biome-scan must be 1 to 4096");
    }

    /** The price of a random teleport to {@code area}: its own cost, else the global one. */
    public Money costOf(Area area) {
        return area.cost() != null ? area.cost() : cost;
    }

    /**
     * Seconds of cooldown for a holder of these tier permissions: the shortest tier held, or -1 when none
     * is held (the teleport cooldown applies).
     */
    public long cooldownFor(java.util.function.Predicate<String> hasPermission) {
        long best = -1;
        for (var tier : cooldownTiers.entrySet()) {
            if (hasPermission.test(COOLDOWN_TIER_PERMISSION + tier.getKey()) && (best < 0 || tier.getValue() < best)) {
                best = tier.getValue();
            }
        }
        return best;
    }

    public static RtpSettings read(ConfigurationSection c) {
        Map<String, Area> locations = new LinkedHashMap<>();
        ConfigurationSection named = c.getConfigurationSection("locations");
        if (named != null) {
            for (String name : named.getKeys(false)) {
                if (!validName(name)) throw new IllegalArgumentException("invalid location name '" + name + "'");
                ConfigurationSection area = named.getConfigurationSection(name);
                if (area == null) throw new IllegalArgumentException("locations." + name + " must be a section");
                locations.put(name.toLowerCase(Locale.ROOT), readArea(name, area));
            }
        }
        Set<String> biomes = new LinkedHashSet<>();
        for (String biome : c.getStringList("excluded-biomes")) biomes.add(biomeKey(biome));
        List<String> avoid = new ArrayList<>();
        for (String block : c.getStringList("avoid-blocks")) avoid.add(block.trim().toUpperCase(Locale.ROOT));
        Set<String> disabled = new LinkedHashSet<>();
        for (String world : c.getStringList("disabled-worlds")) disabled.add(world.toLowerCase(Locale.ROOT));
        String redirect = c.getString("disabled-world-redirect", "");
        Map<String, Long> tiers = new LinkedHashMap<>();
        ConfigurationSection tierSection = c.getConfigurationSection("cooldown-tiers");
        if (tierSection != null) {
            for (String tier : tierSection.getKeys(false)) {
                if (!tierSection.isInt(tier) && !tierSection.isLong(tier)) throw new IllegalArgumentException("cooldown-tiers." + tier + " must be whole seconds");
                tiers.put(tier.toLowerCase(Locale.ROOT), tierSection.getLong(tier));
            }
        }
        return new RtpSettings(
                c.getString("default-location", "{world}"),
                c.getBoolean("per-location-permission", false),
                c.getInt("find-attempts", 24),
                c.getInt("cache-size", 3),
                c.getDouble("default-max-range", 5000),
                biomes, avoid,
                c.getBoolean("avoid-claims", true),
                disabled,
                redirect == null || redirect.isBlank() ? null : redirect,
                c.getBoolean("first-join.enabled", false),
                c.getString("first-join.location", "{world}"),
                locations,
                money(c.getString("cost", "0"), "cost"),
                tiers,
                c.getBoolean("on-death.enabled", false),
                c.getString("on-death.location", "{world}"),
                c.getInt("biome-scan", 256));
    }

    /** {@code 12.50}, {@code 12}, never negative or with more than two decimals. */
    static Money money(String text, String what) {
        try {
            return Money.parse(text == null ? "0" : text.trim(), false);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IllegalArgumentException(what + " must be an amount like 25 or 12.50");
        }
    }

    private static Area readArea(String name, ConfigurationSection c) {
        String world = c.getString("world", name);
        String shape = c.getString("shape", "square").toUpperCase(Locale.ROOT);
        Shape parsed;
        try {
            parsed = Shape.valueOf(shape);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("locations." + name + ".shape must be square or circle");
        }
        return new Area(world,
                c.contains("center.x") ? c.getDouble("center.x") : null,
                c.contains("center.z") ? c.getDouble("center.z") : null,
                c.getDouble("min-range", 0),
                c.contains("max-range") ? c.getDouble("max-range") : null,
                parsed,
                c.contains("min-y") ? c.getInt("min-y") : null,
                c.contains("max-y") ? c.getInt("max-y") : null,
                c.contains("cost") ? money(c.getString("cost"), "locations." + name + ".cost") : null);
    }

    /** Location names: letters, digits, {@code _} and {@code -}, as world names usually are. */
    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]{1,64}");
    }

    /** {@code minecraft:deep_ocean} and {@code DEEP_OCEAN} both become {@code deep_ocean}. */
    public static String biomeKey(String biome) {
        String key = biome.trim().toLowerCase(Locale.ROOT);
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    /**
     * The area {@code requested} names: a named location (any case), else a world of that name with the
     * defaults; {@code {world}} is replaced by {@code currentWorld}. Empty when neither exists.
     */
    public Optional<Resolved> resolve(@Nullable String requested, String currentWorld, java.util.function.Predicate<String> worldExists) {
        String name = (requested == null ? defaultLocation : requested).replace("{world}", currentWorld);
        Area named = locations.get(name.toLowerCase(Locale.ROOT));
        if (named != null) return Optional.of(new Resolved(name.toLowerCase(Locale.ROOT), named));
        if (worldExists.test(name)) return Optional.of(new Resolved(name, Area.of(name)));
        return Optional.empty();
    }

    public boolean disabled(String world) {
        return disabledWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    /** A resolved location: its name (for permissions and the cache) and its area. */
    public record Resolved(String name, Area area) {
    }
}
