package codes.castled.allium.tfly;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.DB.Database;
import codes.castled.allium.util.SchedulerAdapter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manages temporary fly time and state per player. Ticks every second when flying.
 */
public class TFlyManager {

    private final Plugin plugin;
    private final PluginStart alliumPlugin;
    private final Map<UUID, Long> tflyTime = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> tflyEnabled = new ConcurrentHashMap<>();
    private SchedulerAdapter.TaskHandle tickTask;

    public TFlyManager(Plugin plugin) {
        this.plugin = plugin;
        this.alliumPlugin = plugin instanceof PluginStart
            ? (PluginStart) plugin
            : null;
    }

    public void start() {
        if (tickTask != null) tickTask.cancel();
        tickTask = SchedulerAdapter.runTimer(this::tickFlightTime, 20L, 20L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public long getTFlyTime(UUID uuid) {
        return tflyTime.getOrDefault(uuid, 0L);
    }

    public void setTFlyTime(UUID uuid, long seconds) {
        if (seconds <= 0) tflyTime.remove(uuid);
        else tflyTime.put(uuid, seconds);
    }

    public boolean isTFlyEnabled(UUID uuid) {
        return Boolean.TRUE.equals(tflyEnabled.get(uuid));
    }

    public void setTFlyEnabled(UUID uuid, boolean enabled) {
        if (enabled) tflyEnabled.put(uuid, true);
        else tflyEnabled.remove(uuid);
    }

    private void tickFlightTime() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!isTFlyEnabled(uuid)) continue;
            if (!player.isFlying()) continue;

            long timeRemaining = getTFlyTime(uuid);
            if (timeRemaining <= 0) {
                setTFlyEnabled(uuid, false);
                disableFlight(player);
                player.sendMessage("§cYour temporary fly time has expired.");
                continue;
            }

            timeRemaining--;
            setTFlyTime(uuid, timeRemaining);

            if (timeRemaining <= 0) {
                setTFlyEnabled(uuid, false);
                disableFlight(player);
                player.sendMessage("§cYour temporary fly time has expired.");
            } else if (timeRemaining == 60) {
                player.sendMessage("§eTfly warning: 1 minute remaining.");
            } else if (timeRemaining == 30) {
                player.sendMessage("§eTfly warning: 30 seconds remaining.");
            } else if (timeRemaining == 10) {
                player.sendMessage("§eTfly warning: 10 seconds remaining.");
            } else if (timeRemaining <= 5 && timeRemaining > 0) {
                player.sendMessage(
                    "§eTfly warning: " + timeRemaining + " seconds remaining."
                );
            }
        }
    }

    public void disableFlight(Player player) {
        if (player == null) return;
        if (!player.hasPermission("allium.fly")) {
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setFallDistance(0f);
        }
    }

    public void savePlayerState(Player player) {
        if (player == null || alliumPlugin == null) return;
        Database database = alliumPlugin.getDatabase();
        if (database == null) return;
        UUID uuid = player.getUniqueId();
        database.savePlayerTFlyState(
            uuid,
            player.getName(),
            getTFlyTime(uuid),
            isTFlyEnabled(uuid)
        );
    }

    public void loadPlayerState(Player player) {
        if (player == null || alliumPlugin == null) return;
        Database database = alliumPlugin.getDatabase();
        if (database == null) return;
        Database.PlayerTFlyData tflyData = database.getPlayerTFlyStatus(
            player.getUniqueId()
        );
        if (tflyData == null) return;

        long timeSeconds = Math.max(0L, tflyData.timeSeconds());
        if (timeSeconds > 0L) {
            tflyTime.put(player.getUniqueId(), timeSeconds);
        } else {
            tflyTime.remove(player.getUniqueId());
        }

        // Never restore tflyEnabled on join — the player must re-enable with /fly.
        // Another plugin (e.g. Multiverse) may forcibly set allowFlight=false on join,
        // which would desync the command state from the actual flight state.
        tflyEnabled.remove(player.getUniqueId());
    }

    /**
     * Format seconds as e.g. "1d 2h 30m 45s" for placeholders and messages.
     */
    public static String formatTime(long seconds) {
        if (seconds <= 0) return "0s";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0 || sb.length() == 0) sb.append(secs).append("s");
        return sb.toString().trim();
    }
}
