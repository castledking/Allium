package codes.castled.allium.restart;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Set;

record RestartSettings(boolean enabled, ZoneId zone, List<LocalTime> times, List<String> preCommands,
                       int commandDelay, Set<Integer> countdown, boolean save, boolean finalMessage,
                       boolean relay, int discordWarning) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    static RestartSettings read(ConfigurationSection config) {
        return new RestartSettings(config.getBoolean("enabled"), ZoneId.of(config.getString("timezone", "UTC")),
                config.getStringList("times").stream().map(t -> LocalTime.parse(t.strip(), TIME)).distinct().toList(),
                config.getStringList("pre-commands"), seconds(config, "command-delay", 30),
                Set.copyOf(config.getIntegerList("countdown-times")), config.getBoolean("save-before-restart", true),
                config.getBoolean("send-final-server-stopped-message", true), config.getBoolean("relay-to-discord", true),
                seconds(config, "discord-warning-seconds", 60));
    }

    private static int seconds(ConfigurationSection config, String key, int fallback) {
        int value = config.getInt(key, fallback);
        if (value < 0) throw new IllegalArgumentException(key + " must be nonnegative");
        return value;
    }

    Instant next(Instant now) {
        if (!enabled || times.isEmpty()) return null;
        var date = now.atZone(zone).toLocalDate();
        return times.stream().map(time -> {
            var next = date.atTime(time).atZone(zone).toInstant();
            return next.isAfter(now) ? next : date.plusDays(1).atTime(time).atZone(zone).toInstant();
        }).min(Instant::compareTo).orElseThrow();
    }
}
