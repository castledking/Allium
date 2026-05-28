package codes.castled.allium.listeners.world;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;

import org.bukkit.Bukkit;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.GradientNameManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens to WorldEdit / FastAsyncWorldEdit {@link EditSessionEvent} signals and pauses the
 * gradient name animation while edits are actively flowing through the engine. Uses a debounce
 * window because WorldEdit does not fire a corresponding "end" event for an edit session.
 *
 * <p>FAWE callbacks can fire off the main server thread, so this class only mutates atomics in
 * the event handler and bounces any Bukkit API interaction onto the main thread via the scheduler.
 */
public final class FaweActivityListener {

    /** How long after the last edit signal we wait before unpausing the animation. */
    private static final long QUIET_THRESHOLD_MILLIS = 750L;

    /** Delay (in ticks) between scheduling the quiet-check after we mark FAWE active. */
    private static final long QUIET_CHECK_DELAY_TICKS = 20L;

    private final PluginStart plugin;
    private final GradientNameManager gradientNameManager;

    private final AtomicLong lastSignalNanos = new AtomicLong(0L);

    public FaweActivityListener(PluginStart plugin, GradientNameManager gradientNameManager) {
        this.plugin = plugin;
        this.gradientNameManager = gradientNameManager;
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        markFaweActive();
    }

    private void markFaweActive() {
        lastSignalNanos.set(System.nanoTime());

        Bukkit.getScheduler().runTask(plugin, () -> gradientNameManager.setPaused(true));

        Bukkit.getScheduler().runTaskLater(plugin, this::tryResume, QUIET_CHECK_DELAY_TICKS);
    }

    private void tryResume() {
        long quietForMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastSignalNanos.get());
        if (quietForMillis >= QUIET_THRESHOLD_MILLIS) {
            gradientNameManager.setPaused(false);
        }
    }
}
