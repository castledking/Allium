package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.GameRule;
import org.bukkit.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates paused world clocks across {@code /time pause}, {@code /time set}, {@code /day}, and
 * {@code /night}, preserving and restoring {@link GameRule#DO_DAYLIGHT_CYCLE} via Allium's
 * {@code TaskScheduler}.
 */
public final class TimePauseCoordinator implements AutoCloseable {

    private record PausedWorld(String worldName, long fixedTick, Boolean previousDaylightRule) {
    }

    private final PluginStart plugin;
    private final Map<UUID, PausedWorld> pausedWorlds = new ConcurrentHashMap<>();
    private TaskHandle tickTask = TaskHandle.NONE;

    public TimePauseCoordinator(PluginStart plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean isPaused(World world) {
        return world != null && pausedWorlds.containsKey(world.getUID());
    }

    public synchronized boolean toggle(World world) {
        if (isPaused(world)) {
            unpause(world);
            return false;
        }
        pause(world);
        return true;
    }

    public synchronized void pause(World world) {
        if (world == null) {
            return;
        }
        UUID id = world.getUID();
        PausedWorld existing = pausedWorlds.get(id);
        Boolean prevRule = existing != null
                ? existing.previousDaylightRule()
                : world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
        long fixed = normalize(world.getTime());
        pausedWorlds.put(id, new PausedWorld(world.getName(), fixed, prevRule));
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, Boolean.FALSE);
        ensureTaskRunning();
    }

    public synchronized void unpause(World world) {
        if (world == null) {
            return;
        }
        PausedWorld removed = pausedWorlds.remove(world.getUID());
        boolean restore = removed == null || removed.previousDaylightRule() == null
                ? true
                : removed.previousDaylightRule();
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, restore);
        if (pausedWorlds.isEmpty()) {
            stopTask();
        }
    }

    /** If {@code world} is currently paused, updates its pinned time to the new tick. */
    public synchronized void updatePinnedTime(World world, long newTick) {
        if (world == null) {
            return;
        }
        PausedWorld existing = pausedWorlds.get(world.getUID());
        if (existing != null) {
            pausedWorlds.put(world.getUID(),
                    new PausedWorld(existing.worldName(), normalize(newTick), existing.previousDaylightRule()));
        }
    }

    private void ensureTaskRunning() {
        if (tickTask != TaskHandle.NONE || plugin.getScheduler() == null || plugin.getServer() == null) {
            return;
        }
        TaskHandle handle = plugin.getScheduler().repeatOnServer(this::reassertPausedWorlds, 250L, 250L);
        tickTask = handle != null ? handle : TaskHandle.NONE;
    }

    private synchronized boolean reassertPausedWorlds() {
        if (plugin.getServer() == null || pausedWorlds.isEmpty()) {
            tickTask = TaskHandle.NONE;
            return false;
        }
        for (Map.Entry<UUID, PausedWorld> entry : pausedWorlds.entrySet()) {
            World world = plugin.getServer().getWorld(entry.getKey());
            if (world != null) {
                long fixed = entry.getValue().fixedTick();
                if (normalize(world.getTime()) != fixed) {
                    world.setTime(fixed);
                }
            }
        }
        return true;
    }

    private void stopTask() {
        if (tickTask != TaskHandle.NONE) {
            tickTask.cancel();
            tickTask = TaskHandle.NONE;
        }
    }

    private static long normalize(long time) {
        return ((time % 24_000L) + 24_000L) % 24_000L;
    }

    @Override
    public synchronized void close() {
        stopTask();
        if (plugin.getServer() != null) {
            for (Map.Entry<UUID, PausedWorld> entry : pausedWorlds.entrySet()) {
                World world = plugin.getServer().getWorld(entry.getKey());
                if (world != null) {
                    boolean restore = entry.getValue().previousDaylightRule() == null
                            ? true
                            : entry.getValue().previousDaylightRule();
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, restore);
                }
            }
        }
        pausedWorlds.clear();
    }
}
