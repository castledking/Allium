package net.survivalfun.core.tfly;

import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages temporary fly time and state per player. Ticks every second when flying.
 */
public class TFlyManager {

    private final Plugin plugin;
    private final Map<UUID, Long> tflyTime = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> tflyEnabled = new ConcurrentHashMap<>();
    private SchedulerAdapter.TaskHandle tickTask;

    public TFlyManager(Plugin plugin) {
        this.plugin = plugin;
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
                player.sendMessage("§eTfly warning: " + timeRemaining + " seconds remaining.");
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
