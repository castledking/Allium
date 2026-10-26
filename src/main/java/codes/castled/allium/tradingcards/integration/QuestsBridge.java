package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/** Optional LMBishop Quests and ExcellentQuests completion events, resolved in their own loaders. */
public final class QuestsBridge {
    private static final String LMB_EVENT =
        "com.leonardobishop.quests.bukkit.api.event.PlayerFinishQuestEvent";
    private static final String EXCELLENT_EVENT =
        "su.nightexpress.quests.api.event.quest.QuestCompleteEvent";
    private final Logger logger;
    private final String namespace;
    private final Set<String> registered = new HashSet<>();

    public QuestsBridge(Logger logger, String namespace) {
        this.logger = logger;
        this.namespace = namespace;
    }

    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Quests")
            || Bukkit.getPluginManager().isPluginEnabled("ExcellentQuests");
    }

    /** Backwards-compatible completion callback. */
    public boolean register(Plugin plugin, QuestConsumer onComplete) {
        return registerDetailed(plugin, (player, completion) -> onComplete.accept(player, completion.questId()));
    }

    public boolean registerDetailed(Plugin plugin, DetailedQuestConsumer onComplete) {
        registerProvider(plugin, "Quests", LMB_EVENT, true, onComplete);
        registerProvider(plugin, "ExcellentQuests", EXCELLENT_EVENT, false, onComplete);
        return !registered.isEmpty();
    }

    private void registerProvider(Plugin owner, String name, String eventName, boolean lmb,
                                  DetailedQuestConsumer consumer) {
        if (registered.contains(name)) return;
        Plugin dependency = owner.getServer().getPluginManager().getPlugin(name);
        if (dependency == null || !dependency.isEnabled()) return;
        try {
            Class<? extends Event> eventClass = Class.forName(eventName, true,
                dependency.getClass().getClassLoader()).asSubclass(Event.class);
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method getProgress = lmb ? eventClass.getMethod("getQuestProgress") : null;
            Class<?> progressType = lmb ? getProgress.getReturnType() : eventClass;
            Method getId = progressType.getMethod("getQuestId");
            Method getDate = lmb ? progressType.getMethod("getCompletionDate") : null;
            Method getManager = lmb ? dependency.getClass().getMethod("getQuestManager") : null;
            Object manager = lmb ? getManager.invoke(dependency) : null;
            Method getQuest = lmb ? getManager.getReturnType().getMethod("getQuestById", String.class) : null;
            Method getCategory = lmb ? getQuest.getReturnType().getMethod("getCategoryId") : null;
            EventExecutor executor = (listener, event) -> {
                if (!eventClass.isInstance(event)) return;
                try {
                    if (!(getPlayer.invoke(event) instanceof Player player)) return;
                    Object progress = lmb ? getProgress.invoke(event) : event;
                    Object id = getId.invoke(progress);
                    if (id == null || id.toString().isBlank()) return;
                    String questId = id.toString();
                    String category = null;
                    String completionId;
                    if (lmb) {
                        Object quest = getQuest.invoke(manager, questId);
                        Object categoryId = quest == null ? null : getCategory.invoke(quest);
                        category = categoryId == null ? null : categoryId.toString();
                        long date = ((Number) getDate.invoke(progress)).longValue();
                        if (date <= 0) return;
                        completionId = Long.toString(date);
                    } else {
                        // ExcellentQuests' patched event does not expose a completion timestamp.
                        completionId = LocalDate.now().toString();
                    }
                    consumer.accept(player, new Completion(questId, category,
                        name.toLowerCase(java.util.Locale.ROOT) + ":" + questId + ":" + completionId));
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    logger.warning("[tradingcards] " + name + " completion handler failed: " + ex);
                }
            };
            owner.getServer().getPluginManager().registerEvent(eventClass,
                new org.bukkit.event.Listener() { }, EventPriority.MONITOR, executor, owner, false);
            registered.add(name);
            logger.info("[" + TradingCardsBranding.DISPLAY_NAME + "] Listening for " + name + " completions");
        } catch (ReflectiveOperationException | LinkageError ex) {
            logger.warning("[tradingcards] Could not register " + name + " completion event: " + ex);
        }
    }

    public record Completion(String questId, String category, String claimKey) { }
    @FunctionalInterface public interface QuestConsumer {
        void accept(Player player, String questId);
    }
    @FunctionalInterface public interface DetailedQuestConsumer {
        void accept(Player player, Completion completion);
    }
    public boolean isRegistered() { return !registered.isEmpty(); }
    public String namespace() { return namespace; }
    public static String claimKey(UUID player, String questId, String dataId) {
        return player + ":" + questId + ":" + dataId;
    }
}
