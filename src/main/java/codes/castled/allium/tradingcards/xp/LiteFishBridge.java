package codes.castled.allium.tradingcards.xp;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Listens for LiteFish catches without depending on it.
 *
 * <p>Everything here is either a string or reflection, which is deliberate on
 * two counts. Allium has to load on a server without LiteFish, so there is no
 * compile-time dependency. And LiteFish is premium, so there is no api artifact
 * to depend on even if we wanted one — a `provided` jar would tie the build to
 * a file in another repository.
 *
 * <p>Two keys are all that is read. {@code litefish:weight} and
 * {@code litefish:id} are written onto the caught stack by LiteFish itself, and
 * {@code FishStats} in the LiteFishContests plugin reads them the same way with
 * no dependency at all.
 *
 * <h2>The shared-listener problem</h2>
 *
 * <p>All of LiteFish's events extend {@code LiteEvent}, which declares a single
 * {@code static final HandlerList} that no subclass overrides. Bukkit resolves
 * a listener's registration class by looking for {@code getHandlerList} and
 * recursing to the superclass when it is absent — so a listener registered for
 * {@code CatchEvent} is filed under {@code LiteEvent}, and firing <i>any</i>
 * LiteFish event invokes it with that event.
 *
 * <p>Consequence: this handler also receives {@code SellEvent} and {@code
 * StartFishingEvent}. {@link #getReason()} is called first, so the
 * {@code ClassCastException} that a naive handler throws on every fishing event
 * is avoided by not casting at all — the value is compared as a string. Only
 * then is the drop list read, and only for a successful catch.
 */
public final class LiteFishBridge {

    private static final String CATCH_EVENT = "dev.nekomadev.liteFish.api.CatchEvent";
    private static final String SUCCESS = "SUCCESS";

    /** LiteFish's own namespace, which is its plugin name lowercased. */
    private static final NamespacedKey WEIGHT = new NamespacedKey("litefish", "weight");
    private static final NamespacedKey DROP_ID = new NamespacedKey("litefish", "id");

    private final Logger logger;
    private boolean registered;

    public LiteFishBridge(Logger logger) {
        this.logger = logger;
    }

    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("LiteFish");
    }

    public boolean isRegistered() {
        return registered;
    }

    /**
     * Registers the catch listener.
     *
     * @param onCatch receives the player, the species id and the weight
     * @return true when the listener is live
     */
    public boolean register(Plugin plugin, CatchConsumer onCatch) {
        if (registered) {
            return true;
        }
        if (!isAvailable()) {
            return false;
        }
        Class<?> eventClass;
        try {
            eventClass = Class.forName(CATCH_EVENT);
        } catch (Throwable t) {
            logger.warning("[" + codes.castled.allium.tradingcards.TradingCardsBranding.DISPLAY_NAME
                + "] LiteFish is installed but has no " + CATCH_EVENT
                + "; fishing xp is unavailable.");
            return false;
        }
        try {
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method getDrop = eventClass.getMethod("getDrop");
            Method getReason = eventClass.getMethod("getReason");

            @SuppressWarnings("unchecked")
            Class<? extends Event> typed = (Class<? extends Event>) eventClass;
            EventExecutor executor = (listener, event) -> {
                try {
                    // The class check matters: the shared HandlerList means this
                    // runs for every LiteFish event, not just this one.
                    if (!typed.isInstance(event)) {
                        return;
                    }
                    // Read as a string, never cast: with the shared list this
                    // can be handed a SellEvent or StartFishingEvent, and a cast
                    // to CatchReason would throw on every fishing event.
                    Object reason = getReason.invoke(event);
                    if (reason == null || !SUCCESS.equals(reason.toString())) {
                        return;
                    }
                    Object player = getPlayer.invoke(event);
                    if (!(player instanceof Player p)) {
                        return;
                    }
                    if (!(getDrop.invoke(event) instanceof List<?> drops)) {
                        return;
                    }
                    for (Object drop : drops) {
                        if (drop instanceof ItemStack stack) {
                            emit(p, stack, onCatch);
                        }
                    }
                } catch (Throwable t) {
                    // Runs inside LiteFish's own payout path; throwing here
                    // would break their economy, not just our source.
                    logger.warning("[tradingcards] LiteFish catch handler failed: " + t);
                }
            };
            Bukkit.getPluginManager().registerEvent(
                typed, new org.bukkit.event.Listener() { }, EventPriority.MONITOR,
                executor, plugin, false);
            registered = true;
            logger.info("[" + codes.castled.allium.tradingcards.TradingCardsBranding.DISPLAY_NAME
                + "] Listening for LiteFish catches");
            return true;
        } catch (Throwable t) {
            logger.warning("[" + codes.castled.allium.tradingcards.TradingCardsBranding.DISPLAY_NAME
                + "] Could not register the LiteFish listener: " + t);
            return false;
        }
    }

    private static void emit(Player player, ItemStack stack, CatchConsumer onCatch) {
        var meta = stack.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        Double weight = pdc.get(WEIGHT, PersistentDataType.DOUBLE);
        if (weight == null || weight <= 0.0) {
            // Not a LiteFish fish, or a species with no weight range. Either
            // way there is nothing to size.
            return;
        }
        String species = pdc.get(DROP_ID, PersistentDataType.STRING);
        onCatch.accept(player, species == null ? "unknown" : species, weight);
    }

    /** Receives a landed fish. */
    @FunctionalInterface
    public interface CatchConsumer {
        void accept(Player player, String species, double weight);
    }

    /** The weight LiteFish recorded on a stack, for tests and diagnostics. */
    public static Double weightOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        var meta = stack.getItemMeta();
        return meta == null ? null
            : meta.getPersistentDataContainer().get(WEIGHT, PersistentDataType.DOUBLE);
    }

    /** The species id LiteFish recorded on a stack. */
    public static String speciesOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        var meta = stack.getItemMeta();
        return meta == null ? null
            : meta.getPersistentDataContainer().get(DROP_ID, PersistentDataType.STRING);
    }

    static UUID noop() {
        return null;
    }
}
