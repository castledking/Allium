package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Listens for quest completion without depending on ExcellentQuests.
 *
 * <p>Registered reflectively against the event class rather than compiled
 * against it, for the same reason the other integrations are optional: Allium
 * has to load and run on a server without any of them. A compile-time
 * dependency would put {@code su.nightexpress.*} in Allium's jar and make the
 * trading card module fail to load wherever the plugin is absent.
 *
 * <p>Registered through {@code PluginManager#registerEvent} rather than by
 * implementing {@code Listener}, because that overload takes the event class as
 * a {@code Class} parameter. That is a documented API, not a workaround — it is
 * the only supported way to consume an event from a plugin you do not compile
 * against.
 *
 * <p>The event is a plain Bukkit event with its own {@code HandlerList}, so
 * unlike LiteFish there is no shared-listener problem here.
 */
public final class QuestsBridge {

    private static final String EVENT_CLASS =
        "su.nightexpress.quests.api.event.quest.QuestCompleteEvent";

    private final Logger logger;
    private final String namespace;
    private boolean registered;

    public QuestsBridge(Logger logger, String namespace) {
        this.logger = logger;
        this.namespace = namespace;
    }

    /** True when ExcellentQuests is present and carrying the patched event. */
    public boolean isAvailable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ExcellentQuests")) {
            return false;
        }
        try {
            Class.forName(EVENT_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Registers the listener, if the event exists.
     *
     * @param onComplete receives the player and the quest id; a null quest id
     *                   means the getter could not be read, which is reported
     *                   once and otherwise ignored rather than paying out for
     *                   an event whose identity is unknown
     * @return true when the listener is live
     */
    public boolean register(Plugin plugin, QuestConsumer onComplete) {
        if (registered) {
            return true;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("ExcellentQuests")) {
            return false;
        }
        Class<?> eventClass;
        try {
            eventClass = Class.forName(EVENT_CLASS);
        } catch (Throwable t) {
            logger.info("[" + TradingCardsBranding.DISPLAY_NAME
                + "] ExcellentQuests is installed but has no QuestCompleteEvent; "
                + "quest xp is unavailable. This build of ExcellentQuests predates "
                + "the event, or is not the patched one.");
            return false;
        }
        try {
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method getQuestId = eventClass.getMethod("getQuestId");

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
                    Object questId = getQuestId.invoke(event);
                    onComplete.accept(p, questId == null ? null : questId.toString());
                } catch (Throwable t) {
                    // A reflection failure here must not break ExcellentQuests'
                    // own reward dispatch, which runs in the same call stack.
                    logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                        + "] QuestCompleteEvent handler failed: " + t);
                }
            };
            Bukkit.getPluginManager().registerEvent(
                typed, new org.bukkit.event.Listener() { }, EventPriority.MONITOR,
                executor, plugin, false);
            registered = true;
            logger.info("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Listening for quest completions");
            return true;
        } catch (Throwable t) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Could not register the ExcellentQuests listener: " + t);
            return false;
        }
    }

    /** Receives a completion. The quest id may be null if it could not be read. */
    @FunctionalInterface
    public interface QuestConsumer {
        void accept(Player player, String questId);
    }

    public boolean isRegistered() {
        return registered;
    }

    public String namespace() {
        return namespace;
    }

    /** Exposed so a caller can key a one-shot claim without a second map. */
    public static String claimKey(UUID player, String questId, String dataId) {
        return player + ":" + questId + ":" + dataId;
    }

    static Consumer<Player> noop() {
        return p -> { };
    }
}
