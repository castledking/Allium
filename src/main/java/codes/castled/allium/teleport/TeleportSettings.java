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
                               Map<String, Long> cooldowns) {

    public TeleportSettings {
        if (warmupMillis < 0 || warmupMillis > 300_000
                || requestExpiryMillis < 100 || requestExpiryMillis > 3_600_000
                || !Double.isFinite(movementTolerance) || movementTolerance < 0 || movementTolerance > 5) {
            throw new IllegalArgumentException("teleport warmup/expiry/movement settings out of range");
        }
        if (radius < 0 || radius > 8 || vertical < 0 || vertical > 16) {
            throw new IllegalArgumentException("teleport safety search exceeds bounded radius/height");
        }
        cooldowns = Map.copyOf(cooldowns);
        for (var entry : cooldowns.entrySet()) {
            if (!entry.getKey().matches("[a-z]+") || entry.getValue() < 0 || entry.getValue() > 86_400_000) {
                throw new IllegalArgumentException("invalid teleport cooldown " + entry.getKey());
            }
        }
    }

    public long cooldown(String command) {
        return cooldowns.getOrDefault(command, 0L);
    }

    public static TeleportSettings read(ConfigurationSection root) {
        ConfigurationSection config = section(Objects.requireNonNull(root, "root"), "teleport");
        // Reject malformed sections rather than silently treating their children as unset.
        section(config, "safety");
        section(config, "back");
        double warmup = number(config, "warmup-seconds", 3, 0, 300);
        double expiry = number(config, "request-expiry-seconds", 60, 0.1, 3600);
        double movement = number(config, "movement-tolerance", 0.1, 0, 5);
        int radius = integer(config, "safety.search-radius", 3, 8);
        int vertical = integer(config, "safety.vertical-search", 4, 16);
        Map<String, Long> cooldowns = new HashMap<>();
        ConfigurationSection commands = section(config, "cooldowns");
        for (String key : commands.getKeys(false)) {
            if (!key.matches("[a-z]+")) throw new IllegalArgumentException("invalid teleport cooldown " + key);
            cooldowns.put(key, (long) (number(commands, key, 0, 0, 86400) * 1000));
        }
        return new TeleportSettings((long) (warmup * 1000), movement, (long) (expiry * 1000),
                bool(config, "back.include-death", false), bool(config, "safety.enabled", true),
                bool(config, "safety.adjust", true), radius, vertical,
                bool(config, "safety.avoid-lava", true), bool(config, "safety.avoid-void", true),
                bool(config, "safety.avoid-suffocation", true), bool(config, "safety.avoid-falls", true),
                bool(config, "safety.load-destination-chunk", false), cooldowns);
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
        double value = number(config, path, fallback, 0, max);
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
