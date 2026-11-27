package codes.castled.allium.restart;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class RestartSettings {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    private final boolean enabled;
    private final ZoneId zone;
    private final List<LocalTime> times;
    private final List<String> preCommands;
    private final int commandDelay;
    private final Set<Integer> countdown;
    private final boolean save;
    private final boolean finalMessage;
    private final boolean relay;
    private final int discordWarning;

    private RestartSettings(ConfigurationSection config) {
        enabled = config.getBoolean("enabled");
        zone = ZoneId.of(config.getString("timezone", "UTC"));
        times = config.getStringList("times").stream()
                .map(value -> LocalTime.parse(value.trim(), TIME)).distinct().collect(Collectors.toList());
        preCommands = Collections.unmodifiableList(config.getStringList("pre-commands"));
        commandDelay = seconds(config, "command-delay", 30);
        countdown = Collections.unmodifiableSet(new HashSet<>(config.getIntegerList("countdown-times")));
        save = config.getBoolean("save-before-restart", true);
        finalMessage = config.getBoolean("send-final-server-stopped-message", true);
        relay = config.getBoolean("relay-to-discord", true);
        discordWarning = seconds(config, "discord-warning-seconds", 60);
    }

    public static RestartSettings read(ConfigurationSection config) {
        return new RestartSettings(config);
    }

    private static int seconds(ConfigurationSection config, String key, int fallback) {
        int value = config.getInt(key, fallback);
        if (value < 0) throw new IllegalArgumentException(key + " must be nonnegative");
        return value;
    }

    public Instant next(Instant now) {
        if (!enabled || times.isEmpty()) return null;
        java.time.LocalDate date = now.atZone(zone).toLocalDate();
        return times.stream().map(time -> {
            Instant next = date.atTime(time).atZone(zone).toInstant();
            return next.isAfter(now) ? next : date.plusDays(1).atTime(time).atZone(zone).toInstant();
        }).min(Instant::compareTo).orElse(null);
    }

    public List<String> preCommands() { return preCommands; }
    public int commandDelay() { return commandDelay; }
    public Set<Integer> countdown() { return countdown; }
    public boolean save() { return save; }
    public boolean finalMessage() { return finalMessage; }
    boolean relay() { return relay; }
    int discordWarning() { return discordWarning; }
}
