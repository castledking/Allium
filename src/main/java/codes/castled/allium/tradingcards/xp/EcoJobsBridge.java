package codes.castled.allium.tradingcards.xp;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Listens for EcoJobs job experience without depending on it.
 *
 * <p>EcoJobs' API is a file of Kotlin top-level functions compiled into a
 * static class, so there is no interface to implement and no instance to
 * obtain — which means there is also nothing to compile against without pulling
 * in the plugin jar. Registered reflectively, like the other bridges.
 *
 * <h2>Why the amount is scaled rather than the count</h2>
 *
 * <p>{@code PlayerJobExpGainEvent} fires once per <i>counter acceptance</i>, not
 * once per player action. A job with several {@code xp-gain-methods} fires it
 * several times for one block broken, and a trigger such as {@code mine_block}
 * is itself driven by a listener this API cannot see. So "how many times did
 * this event fire" is not a meaningful measure of work done, and a per-event
 * award would pay more for a job with more triggers configured.
 *
 * <p>Rewarding a multiple of the event's own amount sidesteps that entirely:
 * the plugin has already decided what the work was worth, and this scales it.
 * A job with three triggers pays three times the base rate because it did three
 * counters' worth of work, which is the honest reading.
 *
 * <p>{@code isMultiply} on the event is a red herring — it is inverted, and is
 * only set when a caller asked for raw experience. The normal gameplay path
 * never sets it, so it is not consulted.
 */
public final class EcoJobsBridge {

    private static final String EVENT_CLASS =
        "com.willfp.ecojobs.api.event.PlayerJobExpGainEvent";

    private final Logger logger;
    private boolean registered;

    public EcoJobsBridge(Logger logger) {
        this.logger = logger;
    }

    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("EcoJobs");
    }

    public boolean isRegistered() {
        return registered;
    }

    /**
     * Registers the listener.
     *
     * @param onWork receives the player, the job id and the event's own amount
     * @return true when the listener is live
     */
    public boolean register(Plugin plugin, WorkConsumer onWork) {
        if (registered) {
            return true;
        }
        if (!isAvailable()) {
            return false;
        }
        Class<?> eventClass;
        try {
            eventClass = Class.forName(EVENT_CLASS);
        } catch (Throwable t) {
            logger.info("[" + codes.castled.allium.tradingcards.TradingCardsBranding.DISPLAY_NAME
                + "] EcoJobs is installed but has no " + EVENT_CLASS
                + "; job xp is unavailable.");
            return false;
        }
        try {
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method getAmount = eventClass.getMethod("getAmount");
            Method getJob = eventClass.getMethod("getJob");
            Method getId = findJobId(getJob);

            @SuppressWarnings("unchecked")
            Class<? extends Event> typed = (Class<? extends Event>) eventClass;
            EventExecutor executor = (listener, event) -> {
                try {
                    if (!typed.isInstance(event)) {
                        return;
                    }
                    Object player = getPlayer.invoke(event);
                    if (!(player instanceof Player p)) {
                        return;
                    }
                    Object amount = getAmount.invoke(event);
                    if (!(amount instanceof Number number)) {
                        return;
                    }
                    Object job = getJob.invoke(event);
                    Object id = job == null ? null : getId.invoke(job);
                    onWork.accept(p, id == null ? "" : id.toString(), number.doubleValue());
                } catch (Throwable t) {
                    // Runs inside EcoJobs' own xp dispatch; throwing would break
                    // their payout rather than just our source.
                    logger.warning("[tradingcards] EcoJobs handler failed: " + t);
                }
            };
            Bukkit.getPluginManager().registerEvent(
                typed, new org.bukkit.event.Listener() { }, EventPriority.NORMAL,
                executor, plugin, false);
            registered = true;
            logger.info("[" + codes.castled.allium.tradingcards.TradingCardsBranding.DISPLAY_NAME
                + "] Listening for EcoJobs work");
            return true;
        } catch (Throwable t) {
            logger.warning("[" + codes.castled.allium.tradingcards.TradingCardsBranding.DISPLAY_NAME
                + "] Could not register the EcoJobs listener: " + t);
            return false;
        }
    }

    /**
     * Finds {@code Job#getId}.
     *
     * <p>Resolved by name rather than by declaring {@code Job} as a parameter
     * type, because the method is declared to return the plugin's own class and
     * asking for it by name keeps this file free of any reference to it.
     */
    private static Method findJobId(Method getJob) throws NoSuchMethodException {
        Class<?> jobType = getJob.getReturnType();
        return jobType.getMethod("getId");
    }

    /** Receives job experience being awarded. */
    @FunctionalInterface
    public interface WorkConsumer {
        void accept(Player player, String jobId, double amount);
    }
}
