package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import codes.castled.allium.tradingcards.boost.CardProgression;
import codes.castled.allium.tradingcards.boost.EquippedCardTracker;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardXpAnnouncementTest {
    @TempDir Path folder;
    private final UUID id = UUID.randomUUID();
    private final EquippedCardTracker tracker = new EquippedCardTracker();
    private final List<String> messages = new ArrayList<>();
    private final Player player = BridgeTestContext.proxy(Player.class, (p, m, a) -> switch (m.getName()) {
        case "getUniqueId" -> id;
        case "isOnline" -> true;
        default -> null;
    });
    private final CardProgression progression = new CardProgression(null, tracker,
        new CardProgression.ProgressRules(100, 1, 1), Logger.getAnonymousLogger()) {
        @Override public void refreshEquipped(Player player, TradingCardData updated) {
            tracker.set(id, updated, List.of());
        }
        @Override public void announce(Player owner, String card, int previous, int current,
                                       String template, boolean broadcast) {
            messages.add(previous + " -> " + current);
        }
    };

    @Test void twoRapidXpSourcesAnnounceTheEntireLevelRangeOnce() {
        XpConfig config = new XpConfig(XpConfig.defaults().sources(), Map.of(100, 4.0));
        progression.curve(config::xpForLevel);
        tracker.set(id, new TradingCardData("ENDERMAN", "enderman", Tier.SIMPLE, 2, 50,
            List.of(), 0, false), List.of());
        CardXpService service = new CardXpService(config, new XpAntiFarmStore(folder.toFile()),
            progression, uuid -> Optional.ofNullable(tracker.card(uuid)), () -> 1000L);
        List<Runnable> scheduled = new ArrayList<>();
        CardLevelAnnouncements announcements = new CardLevelAnnouncements((owner, task, delay) -> {
            assertEquals(10, delay);
            scheduled.add(task);
            return () -> { };
        }, service::announceNow);
        service.announcementSink(announcements::queue);
        service.award(player, "fish-catch", 1, "COD_1", null);
        service.award(player, "kill-mob", 1, "ZOMBIE", null);
        assertEquals(4, tracker.card(id).level(), "levels must apply immediately");
        assertTrue(messages.isEmpty(), "chat waits for the burst window");
        assertEquals(1, scheduled.size(), "one deadline and one sound for the burst");
        scheduled.getFirst().run();
        assertEquals(List.of("2 -> 4"), messages);
    }
}
