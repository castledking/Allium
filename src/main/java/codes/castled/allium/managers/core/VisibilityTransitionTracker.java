package codes.castled.allium.managers.core;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gives each viewer/target visibility transition a revision so delayed work from an
 * older transition cannot rewrite the current tab state.
 */
final class VisibilityTransitionTracker {
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<Relation, Long> revisions = new ConcurrentHashMap<>();

    long advance(UUID viewerId, UUID targetId) {
        long revision = sequence.incrementAndGet();
        revisions.put(new Relation(viewerId, targetId), revision);
        return revision;
    }

    boolean isCurrent(UUID viewerId, UUID targetId, long revision) {
        return revisions.getOrDefault(new Relation(viewerId, targetId), 0L) == revision;
    }

    void remove(UUID playerId) {
        revisions.keySet().removeIf(relation ->
                relation.viewerId().equals(playerId) || relation.targetId().equals(playerId));
    }

    private record Relation(UUID viewerId, UUID targetId) {
    }
}
