package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import codes.castled.allium.tradingcards.boost.BoostService;
import codes.castled.allium.tradingcards.boost.CardProgression;
import codes.castled.allium.tradingcards.boost.EquippedCardTracker;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardXpSourcesTest {
    @TempDir Path folder;
    private final UUID id = UUID.randomUUID();
    private final EquippedCardTracker tracker = new EquippedCardTracker();
    private final Player player = BridgeTestContext.proxy(Player.class, (p, m, a) -> switch (m.getName()) {
        case "getUniqueId" -> id;
        case "isOnline" -> true;
        default -> null;
    });

    private CardXpService service() {
        XpConfig config = new XpConfig(XpConfig.defaults().sources(), Map.of(100, 1000.0));
        Logger logger = Logger.getAnonymousLogger();
        CardProgression progression = new CardProgression(new BoostService(logger, null), tracker,
            new CardProgression.ProgressRules(100, 1, 1), logger);
        progression.curve(config::xpForLevel);
        tracker.set(id, new TradingCardData("PIG", "pig", Tier.SIMPLE, 0, 50,
            List.of(), List.of(), 0, 0, false), List.of());
        return new CardXpService(config, new XpAntiFarmStore(folder.toFile()), progression,
            uuid -> Optional.ofNullable(tracker.card(uuid)), () -> 1000L);
    }

    @Test void ordinaryCatchLargeBonusMobKillAndAcceptedJobXpAllBank() {
        CardXpService service = service();
        service.award(player, "fish-catch", 1, "COD_1", null);
        service.award(player, "fish-large", 1, "COD_1", null);
        service.award(player, "kill-mob", 1, "ZOMBIE", null);
        service.award(player, "ecojobs-work", 20, "fisherman", null);
        assertEquals(4 + 12 + 4 + 30, tracker.card(id).xp());
    }

    @Test void repeatableQuestPaysPerCompletionButDuplicateCompletionIsRejected() {
        CardXpService service = service();
        double difficulty = QuestXpRules.defaults().multiplier("activities-00-milk-4", "activities");
        assertTrue(service.awardScaled(player, "quest-complete", 1, null, "quests:milk:100", difficulty).paid());
        assertFalse(service.awardScaled(player, "quest-complete", 1, null, "quests:milk:100", difficulty).paid());
        assertTrue(service.awardScaled(player, "quest-complete", 1, null, "quests:milk:200", difficulty).paid());
        assertEquals(400, tracker.card(id).xp());
    }

    @Test void invalidAmountsDoNotConsumeACompletionClaimOrCorruptTheCard() {
        CardXpService service = service();
        assertFalse(service.awardScaled(player, "quest-complete", Double.NaN, null, "completion", 1).paid());
        assertFalse(service.awardScaled(player, "quest-complete", 1, null, "completion", Double.POSITIVE_INFINITY).paid());
        assertTrue(service.awardScaled(player, "quest-complete", 1, null, "completion", 1).paid());
        assertEquals(25, tracker.card(id).xp());
    }
}
