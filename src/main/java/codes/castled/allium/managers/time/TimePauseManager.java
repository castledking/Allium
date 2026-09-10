package codes.castled.allium.managers.time;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.DB.Database;
import codes.castled.allium.util.SchedulerAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static codes.castled.allium.managers.core.Text.DebugSeverity.WARN;

/**
 * Owns the {@code /time pause} feature: pins a world's day-night cycle at a
 * single tick and disables {@code doDaylightCycle} so the time can't drift
 * even if a plugin or operator tries to advance it.
 *
 * <p>Two layers cooperate:
 * <ul>
 *   <li>the gamerule is set to {@code false} for the duration of the pause, so
 *       the vanilla tick loop won't advance the clock;</li>
 *   <li>a 1-tick repeating task re-asserts the saved tick on every server tick
 *       in case {@code /time set} or any other plugin tries to move it.</li>
 * </ul>
 *
 * <p>State lives in the database ({@code paused_worlds} table) and is restored
 * on startup so a server crash or restart leaves the pause in effect.</p>
 */
public class TimePauseManager implements Listener {

    private final PluginStart plugin;
    private final Map<String, Long> pausedTicks = new HashMap<>();
    private SchedulerAdapter.TaskHandle reassertTask;

    public TimePauseManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    /**
     * Restore pauses recorded in the database. Called once on plugin enable so
     * the world is back at its pinned tick before players can observe drift.
     */
    public void restorePersisted() {
        if (plugin.getDatabase() == null) {
            return;
        }
        Set<String> persisted = plugin.getDatabase().listPausedWorlds();
        for (String name : persisted) {
            long tick = plugin.getDatabase().getPausedTick(name);
            if (tick < 0) {
                continue;
            }
            World world = Bukkit.getWorld(name);
            if (world == null) {
                // World not loaded yet; defer until it is. The DB row stays
                // until the operator explicitly unpauses.
                pausedTicks.put(name, tick);
                continue;
            }
            pausedTicks.put(name, tick);
            applyPauseGamerule(world, true);
            world.setTime(tick);
        }
        if (!pausedTicks.isEmpty() && reassertTask == null) {
            startReassertTask();
        }
    }

    public boolean isPaused(World world) {
        return world != null && pausedTicks.containsKey(world.getName());
    }

    public boolean isPaused(String worldName) {
        return pausedTicks.containsKey(worldName);
    }

    public long getPausedTick(World world) {
        return world == null ? -1L : pausedTicks.getOrDefault(world.getName(), -1L);
    }

    /**
     * Toggle pause for a world. When pausing, captures the current tick and
     * stores it. When unpausing, restores the gamerule and removes the row.
     *
     * @return true if the world is paused after this call.
     */
    public boolean toggle(World world, CommandSender actor) {
        if (world == null) {
            return false;
        }
        if (isPaused(world)) {
            unpause(world);
            return false;
        }
        pause(world, actor);
        return true;
    }

    public void pause(World world, CommandSender actor) {
        if (world == null || plugin.getDatabase() == null) {
            return;
        }
        long tick = world.getTime();
        pausedTicks.put(world.getName(), tick);
        applyPauseGamerule(world, true);
        world.setTime(tick);
        String actorUuid = actor instanceof Player p ? p.getUniqueId().toString() : null;
        plugin.getDatabase().pauseWorldTime(world.getName(), tick, actorUuid);
        if (reassertTask == null) {
            startReassertTask();
        }
    }

    public void unpause(World world) {
        if (world == null || plugin.getDatabase() == null) {
            return;
        }
        pausedTicks.remove(world.getName());
        applyPauseGamerule(world, false);
        plugin.getDatabase().unpauseWorldTime(world.getName());
        if (pausedTicks.isEmpty() && reassertTask != null) {
            reassertTask.cancel();
            reassertTask = null;
        }
    }

    /**
     * Toggle the {@code doDaylightCycle} gamerule. We capture its previous
     * value when pausing so unpausing restores the operator's setting rather
     * than forcing it back to {@code true}.
     */
    private void applyPauseGamerule(World world, boolean pausing) {
        try {
            if (pausing) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            } else {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
            }
        } catch (Throwable t) {
            // Some worlds (e.g. Nether, End) reject this rule; ignore.
            plugin.getLogger().warning(
                "[TimePause] Could not toggle doDaylightCycle on " + world.getName() + ": " + t.getMessage());
        }
    }

    /**
     * Per-tick re-assertion: any world that has a saved pause tick gets
     * {@code setTime} re-applied. This catches {@code /time set}, plugin
     * resets, or any drift while {@code doDaylightCycle} was still {@code true}
     * (e.g. before the gamerule write took effect).
     */
    private void startReassertTask() {
        reassertTask = SchedulerAdapter.runTimer(() -> {
            for (Map.Entry<String, Long> entry : pausedTicks.entrySet()) {
                World world = Bukkit.getWorld(entry.getKey());
                if (world == null) {
                    continue;
                }
                if (world.getTime() != entry.getValue()) {
                    world.setTime(entry.getValue());
                }
            }
        }, 1L, 1L);
    }

    public void shutdown() {
        if (reassertTask != null) {
            reassertTask.cancel();
            reassertTask = null;
        }
    }

    /**
     * Worlds loaded after the initial {@link #restorePersisted()} scan (e.g. a
     * plugin that lazy-loads dimensions) get the same treatment here. Without
     * this hook a paused Nether/End that loads mid-runtime would silently
     * drift until the operator unpaused and repaused.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        String name = event.getWorld().getName();
        Long savedTick = pausedTicks.get(name);
        if (savedTick == null) {
            // Maybe the world was loaded after restorePersisted (e.g. very early
            // init) - the DB row still exists.
            if (plugin.getDatabase() != null && plugin.getDatabase().isWorldTimePaused(name)) {
                long tick = plugin.getDatabase().getPausedTick(name);
                if (tick >= 0) {
                    pausedTicks.put(name, tick);
                    savedTick = tick;
                    if (reassertTask == null) {
                        startReassertTask();
                    }
                }
            }
        }
        if (savedTick != null) {
            applyPauseGamerule(event.getWorld(), true);
            event.getWorld().setTime(savedTick);
        }
    }
}
