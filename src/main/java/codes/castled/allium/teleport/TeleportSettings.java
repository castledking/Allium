package codes.castled.allium.teleport;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable validated operation settings; a reload never changes an active warmup. */
public record TeleportSettings(long warmupMillis, double movementTolerance, long requestExpiryMillis,
                               boolean deathBack, boolean safety, boolean adjust, int radius, int vertical,
                               boolean lava, boolean voids, boolean suffocation, boolean falls, boolean loadChunk,
                               Map<String, Long> cooldowns,
                               int tppetMaxEntities, int tpeMaxEntities, int tphereMaxEntities,
                               long selectorConfirmMillis,
                               Map<String, Long> warmups) {

    public static final int DEFAULT_TPPET_MAX = 10;
    public static final int DEFAULT_TPE_MAX = 50;
    public static final int DEFAULT_TPHERE_MAX = 50;
    public static final long DEFAULT_SELECTOR_CONFIRM_MILLIS = 15_000L;

    public TeleportSettings(long warmupMillis, double movementTolerance, long requestExpiryMillis,
                            boolean deathBack, boolean safety, boolean adjust, int radius, int vertical,
                            boolean lava, boolean voids, boolean suffocation, boolean falls, boolean loadChunk,
                            Map<String, Long> cooldowns) {
        this(warmupMillis, movementTolerance, requestExpiryMillis, deathBack, safety, adjust, radius, vertical,
                lava, voids, suffocation, falls, loadChunk, cooldowns,
                DEFAULT_TPPET_MAX, DEFAULT_TPE_MAX, DEFAULT_TPHERE_MAX, DEFAULT_SELECTOR_CONFIRM_MILLIS, Map.of());
    }

    public TeleportSettings(long warmupMillis, double movementTolerance, long requestExpiryMillis,
                            boolean deathBack, boolean safety, boolean adjust, int radius, int vertical,
                            boolean lava, boolean voids, boolean suffocation, boolean falls, boolean loadChunk,
                            Map<String, Long> cooldowns,
                            int tppetMaxEntities, int tpeMaxEntities, int tphereMaxEntities,
                            long selectorConfirmMillis) {
        this(warmupMillis, movementTolerance, requestExpiryMillis, deathBack, safety, adjust, radius, vertical,
                lava, voids, suffocation, falls, loadChunk, cooldowns,
                tppetMaxEntities, tpeMaxEntities, tphereMaxEntities, selectorConfirmMillis, Map.of());
    }

    public TeleportSettings {
        if (warmupMillis < 0 || warmupMillis > 300_000
                || requestExpiryMillis < 100 || requestExpiryMillis > 3_600_000
                || !Double.isFinite(movementTolerance) || movementTolerance < 0 || movementTolerance > 5) {
            throw new IllegalArgumentException("teleport warmup/expiry/movement settings out of range");
        }
        if (radius < 0 || radius > 8 || vertical < 0 || vertical > 16) {
            throw new IllegalArgumentException("teleport safety search exceeds bounded radius/height");
        }
        if (tppetMaxEntities < 1 || tppetMaxEntities > 1000
                || tpeMaxEntities < 1 || tpeMaxEntities > 1000
                || tphereMaxEntities < 1 || tphereMaxEntities > 1000
                || selectorConfirmMillis < 0 || selectorConfirmMillis > 300_000) {
            throw new IllegalArgumentException("teleport entity-limits settings out of range");
        }
        cooldowns = Map.copyOf(cooldowns);
        for (var entry : cooldowns.entrySet()) {
            if (!entry.getKey().matches("[a-z]+") || entry.getValue() < 0 || entry.getValue() > 86_400_000) {
                throw new IllegalArgumentException("invalid teleport cooldown " + entry.getKey());
            }
        }
        warmups = Map.copyOf(warmups);
        for (var entry : warmups.entrySet()) {
            if (!entry.getKey().matches("[a-z]+") || entry.getValue() < 0 || entry.getValue() > 300_000) {
                throw new IllegalArgumentException("invalid teleport warmup " + entry.getKey());
            }
        }
    }

    /** Flight removes landing requirements; an explicit unsafe grant removes hazard checks too. */
    public TeleportSettings forMover(boolean flight, boolean unsafe) {
        if (!flight && !unsafe) return this;
        return new TeleportSettings(warmupMillis, movementTolerance, requestExpiryMillis, deathBack,
                safety && !unsafe, adjust, radius, vertical, lava, voids && !flight,
                suffocation, falls && !flight, loadChunk, cooldowns,
                tppetMaxEntities, tpeMaxEntities, tphereMaxEntities, selectorConfirmMillis, warmups);
    }

    public long cooldown(String command) {
        return cooldowns.getOrDefault(command, 0L);
    }

    /** Per-type warmup in milliseconds, checking exact command, group/alias fallback, then {@link #warmupMillis()}. */
    public long warmup(String command) {
        if (command != null) {
            Long direct = warmups.get(command);
            if (direct != null) return direct;
            String group = switch (command) {
                case "home" -> "homes";
                case "warp" -> "warps";
                case "tpa", "tpahere" -> "requests";
                case "tpo" -> "tp";
                case "tpohere" -> "tphere";
                default -> null;
            };
            if (group != null) {
                Long grouped = warmups.get(group);
                if (grouped != null) return grouped;
            }
        }
        return warmupMillis;
    }

    public static TeleportSettings read(ConfigurationSection root) {
        ConfigurationSection config = section(Objects.requireNonNull(root, "root"), "teleport");
        // Reject malformed sections rather than silently treating their children as unset.
        section(config, "safety");
        section(config, "back");
        ConfigurationSection limits = section(config, "entity-limits");
        double warmup = number(config, "warmup-seconds", 3, 0, 300);
        double expiry = number(config, "request-expiry-seconds", 60, 0.1, 3600);
        double movement = number(config, "movement-tolerance", 0.1, 0, 5);
        int radius = integer(config, "safety.search-radius", 3, 8);
        int vertical = integer(config, "safety.vertical-search", 4, 16);
        int tppetMax = integer(limits, "tppet-max", DEFAULT_TPPET_MAX, 1, 1000);
        int tpeMax = integer(limits, "tpe-max", DEFAULT_TPE_MAX, 1, 1000);
        int tphereMax = integer(limits, "tphere-max", DEFAULT_TPHERE_MAX, 1, 1000);
        double confirmSec = number(limits, "confirm-seconds", 15, 0, 300);
        Map<String, Long> cooldowns = new HashMap<>();
        ConfigurationSection commands = section(config, "cooldowns");
        for (String key : commands.getKeys(false)) {
            if (!key.matches("[a-z]+")) throw new IllegalArgumentException("invalid teleport cooldown " + key);
            cooldowns.put(key, (long) (number(commands, key, 0, 0, 86400) * 1000));
        }
        Map<String, Long> warmups = new HashMap<>();
        ConfigurationSection warmupCommands = section(config, "warmups");
        for (String key : warmupCommands.getKeys(false)) {
            if (!key.matches("[a-z]+")) throw new IllegalArgumentException("invalid teleport warmup " + key);
            Object raw = warmupCommands.get(key);
            if (raw == null) continue;
            if (raw instanceof Number number) {
                double seconds = number.doubleValue();
                if (seconds == -1.0) continue;
                if (Double.isFinite(seconds) && seconds >= 0 && seconds <= 300) {
                    warmups.put(key, (long) (seconds * 1000));
                    continue;
                }
            }
            throw new IllegalArgumentException("teleport warmups." + key + " must be -1 (default) or a finite number from 0 to 300");
        }
        return new TeleportSettings((long) (warmup * 1000), movement, (long) (expiry * 1000),
                bool(config, "back.include-death", false), bool(config, "safety.enabled", true),
                bool(config, "safety.adjust", true), radius, vertical,
                bool(config, "safety.avoid-lava", true), bool(config, "safety.avoid-void", true),
                bool(config, "safety.avoid-suffocation", true), bool(config, "safety.avoid-falls", true),
                bool(config, "safety.load-destination-chunk", false), cooldowns,
                tppetMax, tpeMax, tphereMax, (long) (confirmSec * 1000), warmups);
    }

    private static ConfigurationSection section(ConfigurationSection root, String path) {
        Object value = root.get(path);
        if (value == null) return new MemoryConfiguration();
        if (value instanceof ConfigurationSection section) return section;
        throw new IllegalArgumentException("teleport " + path + " must be a configuration section");
    }

    private static double number(ConfigurationSection config, String path, double fallback, double min, double max) {
        Object value = config.get(path);
        if (value == null) return fallback;
        if (value instanceof Number number) {
            double result = number.doubleValue();
            if (Double.isFinite(result) && result >= min && result <= max) return result;
        }
        throw new IllegalArgumentException("teleport " + path + " must be a finite number from " + min + " to " + max);
    }

    private static int integer(ConfigurationSection config, String path, int fallback, int max) {
        return integer(config, path, fallback, 0, max);
    }

    private static int integer(ConfigurationSection config, String path, int fallback, int min, int max) {
        double value = number(config, path, fallback, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException("teleport " + path + " must be an integer");
        return (int) value;
    }

    private static boolean bool(ConfigurationSection config, String path, boolean fallback) {
        Object value = config.get(path);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        throw new IllegalArgumentException("teleport " + path + " must be a boolean");
    }
}
