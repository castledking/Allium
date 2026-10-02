package codes.castled.allium.tradingcards.boost;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

/**
 * Enlarges token drops and adds a chance of one, while the equipped card grants
 * either.
 *
 * <p>A drop chance and a drop size are separate requests, so they are separate
 * boosts: a player who wants more drops is not asking for bigger ones.
 *
 * <p>BeastTokens is not on the compile classpath and has no artifact to depend
 * on, so the event is registered reflectively — the same shape as the quest
 * bridge. A plain Bukkit event, so registering it by class is a supported API
 * rather than a workaround.
 *
 * <p>The event is cancellable, so an extra chance is implemented by cancelling
 * the original drop and firing one of our own at the boosted size. Cancelling
 * and re-firing rather than mutating in place, because {@code getTokens()} on
 * that event is a final field with no setter — there is no supported way to
 * change the amount of a drop that is already being paid.
 */
public class TokenDropListener implements Listener {

    private static final String EVENT_CLASS =
        "me.mraxetv.beasttokens.api.events.tokendrops.BTRewardDropEvent";
    private static final String PLUGIN = "BeastTokens";

    private final Logger logger;
    private final Function<UUID, Double> chanceFor;
    private final Function<UUID, Double> multiplierFor;

    private Class<?> eventClass;
    private Method getPlayer;
    private Method getTokens;
    private java.lang.reflect.Constructor<?> ctor;
    private Method setCancelled;

    public TokenDropListener(Logger logger,
                             Function<UUID, Double> chanceFor,
                             Function<UUID, Double> multiplierFor) {
        this.logger = logger;
        this.chanceFor = chanceFor;
        this.multiplierFor = multiplierFor;
    }

    /** True once {@link #register} has found a usable event class. */
    public boolean isReady() {
        return eventClass != null;
    }

    /** Registers the listener, or returns false when BeastTokens is absent. */
    public boolean register(org.bukkit.plugin.Plugin plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return false;
        }
        try {
            eventClass = Class.forName(EVENT_CLASS);
            getPlayer = eventClass.getMethod("getPlayer");
            getTokens = eventClass.getMethod("getTokens");
            setCancelled = eventClass.getMethod("setCancelled", boolean.class);
            // The three-argument form carries the player, the amount and the drop
            // type, which is everything needed to reproduce the drop at a new size.
            ctor = eventClass.getConstructor(Player.class, double.class, double.class,
                Class.forName("me.mraxetv.beasttokens.api.DropType"));
            // The plugin is supplied as the owning plugin and the executor does
            // the dispatch, which is the form registerEvent takes for an event
            // whose class is only known at runtime.
            EventExecutor executor = (listener, dispatched) -> onDrop((Event) dispatched);
            Bukkit.getPluginManager().registerEvent(
                eventClass.asSubclass(Event.class), new Listener() { }, EventPriority.NORMAL,
                executor, plugin, false);
            return true;
        } catch (Throwable t) {
            logger.warning("[tradingcards] BeastTokens is installed but its token-drop "
                + "event does not match this build; token drop boosts will not apply. ("
                + t + ")");
            return false;
        }
    }

    /** Invoked by the executor, so the argument is the base Event type. */
    @SuppressWarnings("unchecked")
    public void onDrop(Event event) {
        if (!eventClass.isInstance(event)) {
            return;
        }
        Object dropType;
        double tokens;
        UUID id;
        try {
            Player player = (Player) getPlayer.invoke(event);
            if (player == null) {
                return;
            }
            id = player.getUniqueId();
            tokens = ((Number) getTokens.invoke(event)).doubleValue();
            dropType = eventClass.getMethod("getDropType").invoke(event);
        } catch (Throwable t) {
            return;
        }
        if (tokens <= 0.0) {
            return;
        }
        Player player = Bukkit.getPlayer(id);

        double chance = clamp(chanceFor.apply(id));
        double multiplier = multiplierFor.apply(id);

        if (multiplier != 1.0) {
            // Size changed: cancel and re-fire at the new amount.
            try {
                setCancelled.invoke(event, true);
                fire(player, tokens * multiplier, dropType);
                return;
            } catch (Throwable t) {
                // Could not re-fire, so the original drop stands rather than the
                // player losing it entirely. Falling through is the safe failure.
                logger.fine("[tradingcards] token multiplier skipped: " + t);
            }
        }

        if (chance > 0.0 && Math.random() < chance) {
            try {
                fire(player, tokens, dropType);
            } catch (Throwable t) {
                logger.fine("[tradingcards] bonus token drop skipped: " + t);
            }
        }
    }

    /** Fires a replacement drop of the same kind at a new amount. */
    private void fire(Player player, double amount, Object dropType) throws ReflectiveOperationException {
        Object replacement = ctor.newInstance(player, amount, amount, dropType);
        Bukkit.getPluginManager().callEvent((Event) replacement);
    }

    private static double clamp(double chance) {
        return Math.max(0.0, Math.min(1.0, chance));
    }
}
