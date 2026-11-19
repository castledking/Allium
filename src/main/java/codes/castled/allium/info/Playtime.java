package codes.castled.allium.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.module.PlayerInfoModule;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Playtime and first join for {@code /playtime} and the placeholders. Playtime is Minecraft's own
 * play-time statistic; an offline player's is read from their stats file. First join is the
 * player-info record /seen shows, else Bukkit's first-played time.
 */
public final class Playtime {

    private Playtime() {
    }

    /** An online player's playtime in seconds. */
    public static long seconds(Player player) {
        return player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
    }

    /**
     * An offline player's playtime in seconds from {@code <world>/stats/<uuid>.json}, empty when
     * there is no such file. Blocking file read: call it off the server thread.
     */
    public static OptionalLong offlineSeconds(Path worldFolder, UUID player) throws IOException {
        Path file = worldFolder.resolve("stats").resolve(player + ".json");
        if (!Files.isRegularFile(file)) return OptionalLong.empty();
        return OptionalLong.of(statSeconds(Files.readString(file)));
    }

    /** The play time in a stats file: {@code minecraft:play_time} (1.17+) or the older play_one_minute, in ticks. */
    static long statSeconds(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return 0;
        JsonObject stats = root.getAsJsonObject().getAsJsonObject("stats");
        JsonObject custom = stats == null ? null : stats.getAsJsonObject("minecraft:custom");
        if (custom == null) return 0;
        for (String key : new String[]{"minecraft:play_time", "minecraft:play_one_minute"}) {
            if (custom.has(key)) return custom.get(key).getAsLong() / 20L;
        }
        return 0;
    }

    /** When an online player first joined, in epoch milliseconds; 0 when unknown. */
    public static long firstJoin(PluginStart plugin, Player player) {
        if (plugin.getModuleManager() != null && plugin.getModuleManager().getModule("info") instanceof PlayerInfoModule info
                && info.isEnabled() && info.info() != null) {
            long recorded = info.info().firstJoin(player.getUniqueId());
            if (recorded > 0) return recorded;
        }
        return player.getFirstPlayed();
    }

    /** A first-join date in {@code playtime.date-format} (default {@code MMM d, yyyy}), in the server's time zone. */
    public static String date(PluginStart plugin, long epochMillis) {
        if (epochMillis <= 0) return "";
        return formatter(plugin.getConfig().getString("playtime.date-format", "MMM d, yyyy")).format(Instant.ofEpochMilli(epochMillis));
    }

    static DateTimeFormatter formatter(@Nullable String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern == null || pattern.isBlank() ? "MMM d, yyyy" : pattern, Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault());
        } catch (IllegalArgumentException invalid) {
            return DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());
        }
    }
}
