package codes.castled.allium.tradingcards.xp;

import static org.junit.jupiter.api.Assertions.*;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CardLevelAnnouncementsTest {
    private final UUID id = UUID.randomUUID();
    private boolean online = true;
    private final Player player = BridgeTestContext.proxy(Player.class, (p, m, a) -> switch (m.getName()) {
        case "getUniqueId" -> id;
        case "isOnline" -> online;
        default -> null;
    });
    private final List<CardXpService.LevelUp> sent = new ArrayList<>();
    private final List<Job> jobs = new ArrayList<>();
    private final CardLevelAnnouncements announcements = new CardLevelAnnouncements((owner, task, delay) -> {
        assertEquals(10, delay);
        Job job = new Job(task);
        jobs.add(job);
        return () -> job.cancelled = true;
    }, sent::add);

    private static class Job {
        private final Runnable task;
        private boolean cancelled;
        Job(Runnable task) { this.task = task; }
    }
    private TradingCardData card(String mob, int level) {
        return new TradingCardData(mob, mob.toLowerCase(), Tier.SIMPLE, level, 50, List.of(), 0, false);
    }
    private CardXpService.LevelUp level(String mob, int from, int to) {
        return new CardXpService.LevelUp(player, card(mob, from), from, to, "<previous> → <level>", false);
    }

    @Test void contiguousChangesKeepTheFirstDeadlineAndLastLevel() {
        announcements.queue(level("ENDERMAN", 2, 3));
        announcements.queue(level("ENDERMAN", 3, 4));
        announcements.queue(level("ENDERMAN", 4, 7));
        assertEquals(1, jobs.size(), "rapid updates do not postpone the original deadline");
        assertTrue(sent.isEmpty());
        jobs.getFirst().task.run();
        assertEquals(1, sent.size());
        assertEquals(2, sent.getFirst().previous());
        assertEquals(7, sent.getFirst().current());
    }

    @Test void singleLargeAwardAlreadyUsesOneRangeAndLaterBurstsAreSeparate() {
        announcements.queue(level("ENDERMAN", 2, 4));
        jobs.getFirst().task.run();
        announcements.queue(level("ENDERMAN", 4, 6));
        jobs.get(1).task.run();
        assertEquals(List.of(2, 4), sent.stream().map(CardXpService.LevelUp::previous).toList());
        assertEquals(List.of(4, 6), sent.stream().map(CardXpService.LevelUp::current).toList());
    }

    @Test void differentCardsFlushSeparatelyAndAnOldTimerCannotFlushTheNewCard() {
        announcements.queue(level("ENDERMAN", 2, 3));
        announcements.queue(level("ZOMBIE", 3, 4));
        assertEquals(1, sent.size());
        assertTrue(jobs.getFirst().cancelled);
        jobs.getFirst().task.run(); // Simulate cancellation racing an already-running callback.
        assertEquals(1, sent.size());
        jobs.get(1).task.run();
        assertEquals(2, sent.size());
        assertEquals("ENDERMAN", sent.get(0).card().mob());
        assertEquals("ZOMBIE", sent.get(1).card().mob());
    }

    @Test void nonContiguousChangesNeverInventASingleLevelRange() {
        announcements.queue(level("ENDERMAN", 2, 3));
        announcements.queue(level("ENDERMAN", 7, 8));
        jobs.get(1).task.run();
        assertEquals(List.of(2, 7), sent.stream().map(CardXpService.LevelUp::previous).toList());
    }

    @Test void playersHaveIndependentBatches() {
        Player other = BridgeTestContext.proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> new UUID(0, 1);
            case "isOnline" -> true;
            default -> null;
        });
        announcements.queue(level("ENDERMAN", 2, 3));
        announcements.queue(new CardXpService.LevelUp(other, card("ENDERMAN", 3), 3, 4, "template", false));
        jobs.forEach(job -> job.task.run());
        assertEquals(2, sent.size());
        assertEquals(2, sent.get(0).previous());
        assertEquals(3, sent.get(1).previous());
    }

    @Test void shutdownAndDisconnectedPlayersDoNotReceiveStaleMessages() {
        announcements.queue(level("ENDERMAN", 2, 3));
        announcements.close();
        assertTrue(jobs.getFirst().cancelled);
        jobs.getFirst().task.run();
        announcements.queue(level("ENDERMAN", 3, 4));
        assertEquals(1, jobs.size());
        assertTrue(sent.isEmpty());

        CardLevelAnnouncements other = new CardLevelAnnouncements((owner, task, delay) -> {
            jobs.add(new Job(task));
            return () -> { };
        }, sent::add);
        other.queue(level("ENDERMAN", 2, 3));
        online = false;
        jobs.get(1).task.run();
        assertTrue(sent.isEmpty());
    }

    @Test void schedulerFailureDeliversTheMessageWithoutFailingTheXpPayout() {
        CardLevelAnnouncements broken = new CardLevelAnnouncements((owner, task, delay) -> {
            throw new UnsupportedOperationException("scheduler unavailable");
        }, sent::add);
        assertDoesNotThrow(() -> broken.queue(level("ENDERMAN", 2, 4)));
        assertEquals(1, sent.size());
    }
}
