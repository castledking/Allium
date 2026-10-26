package codes.castled.allium.tradingcards.xp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Combines contiguous level changes over half a second without delaying XP or boosts. */
public final class CardLevelAnnouncements implements Listener, AutoCloseable {
    private static final long WINDOW_TICKS = 10;
    @FunctionalInterface public interface ScheduleLater {
        /** Schedules on the owner's thread and returns a cancellation action. */
        Runnable schedule(Player owner, Runnable task, long delayTicks);
    }

    private final ScheduleLater scheduler;
    private final Consumer<CardXpService.LevelUp> sender;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private boolean closed;

    public CardLevelAnnouncements(ScheduleLater scheduler, Consumer<CardXpService.LevelUp> sender) {
        this.scheduler = scheduler;
        this.sender = sender;
    }

    public synchronized void queue(CardXpService.LevelUp levelUp) {
        if (closed || levelUp.owner() == null || !levelUp.owner().isOnline()
            || levelUp.current() <= levelUp.previous()) return;
        UUID owner = levelUp.owner().getUniqueId();
        Pending previous = pending.get(owner);
        if (previous != null) {
            if (previous.canMerge(levelUp)) {
                CardXpService.LevelUp first = previous.message;
                previous.message = new CardXpService.LevelUp(first.owner(), first.card(), first.previous(),
                    levelUp.current(), first.template(), first.broadcast());
                return;
            }
            previous.cancel.run();
            flush(owner, previous);
        }
        Pending next = new Pending(levelUp);
        pending.put(owner, next);
        try {
            // Keep the first deadline: a constant stream must not postpone chat forever.
            next.cancel = scheduler.schedule(levelUp.owner(), () -> flush(owner, next), WINDOW_TICKS);
        } catch (RuntimeException ex) {
            // A failed cosmetic task must not fail the XP payout or lose its announcement.
            flush(owner, next);
        }
    }

    private synchronized void flush(UUID owner, Pending expected) {
        if (!pending.remove(owner, expected)) return;
        if (!closed && expected.message.owner().isOnline()) sender.accept(expected.message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public synchronized void onQuit(PlayerQuitEvent event) {
        Pending message = pending.remove(event.getPlayer().getUniqueId());
        if (message != null) message.cancel.run();
    }

    @Override public synchronized void close() {
        closed = true;
        pending.values().forEach(message -> message.cancel.run());
        pending.clear();
    }

    private static final class Pending {
        private CardXpService.LevelUp message;
        private Runnable cancel = () -> { };
        private Pending(CardXpService.LevelUp message) { this.message = message; }
        private boolean canMerge(CardXpService.LevelUp next) {
            return message.current() == next.previous()
                && message.broadcast() == next.broadcast()
                && java.util.Objects.equals(message.template(), next.template())
                && message.card().withLevel(0).withXp(0).equals(next.card().withLevel(0).withXp(0));
        }
    }
}
