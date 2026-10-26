package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import codes.castled.allium.tradingcards.integration.QuestsBridge;
import com.leonardobishop.quests.bukkit.api.event.PlayerFinishQuestEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import su.nightexpress.quests.api.event.quest.QuestCompleteEvent;

class QuestsBridgeTest {
    public interface QuestsPlugin extends Plugin { QuestManager getQuestManager(); }
    public static class QuestManager {
        public Quest getQuestById(String id) { return new Quest(); }
    }
    public static class Quest { public String getCategoryId() { return "activities"; } }

    @Test void lmbishopCompletionContainsCategoryAndDistinctRepeatIdentity() throws Exception {
        BridgeTestContext context = new BridgeTestContext();
        context.plugins.put("Quests", BridgeTestContext.proxy(QuestsPlugin.class, (p, m, a) -> switch (m.getName()) {
            case "getQuestManager" -> new QuestManager();
            case "isEnabled" -> true;
            default -> null;
        }));
        List<QuestsBridge.Completion> completed = new ArrayList<>();
        QuestsBridge bridge = new QuestsBridge(Logger.getAnonymousLogger(), "allium");
        assertTrue(bridge.registerDetailed(context.owner, (player, completion) -> completed.add(completion)));
        Player player = BridgeTestContext.proxy(Player.class, (p, m, a) -> null);
        context.fire(PlayerFinishQuestEvent.class,
            new PlayerFinishQuestEvent(player, "activities-00-milk-4", 100));
        context.fire(PlayerFinishQuestEvent.class,
            new PlayerFinishQuestEvent(player, "activities-00-milk-4", 200));
        assertEquals(2, completed.size());
        assertEquals("activities", completed.getFirst().category());
        assertEquals(8, QuestXpRules.defaults().multiplier(completed.getFirst().questId(), completed.getFirst().category()));
        assertNotEquals(completed.get(0).claimKey(), completed.get(1).claimKey());
    }

    @Test void existingExcellentQuestsHookKeepsProviderSpecificClaims() throws Exception {
        BridgeTestContext context = new BridgeTestContext();
        context.plugins.put("ExcellentQuests", context.plugin("ExcellentQuests"));
        List<QuestsBridge.Completion> completed = new ArrayList<>();
        QuestsBridge bridge = new QuestsBridge(Logger.getAnonymousLogger(), "allium");
        assertTrue(bridge.registerDetailed(context.owner, (player, completion) -> completed.add(completion)));
        Player player = BridgeTestContext.proxy(Player.class, (p, m, a) -> null);
        context.fire(QuestCompleteEvent.class, new QuestCompleteEvent(player, "daily"));
        assertTrue(completed.getFirst().claimKey().startsWith("excellentquests:daily:"));
        assertEquals("daily", completed.getFirst().questId());
        assertNull(completed.getFirst().category());
    }
}
