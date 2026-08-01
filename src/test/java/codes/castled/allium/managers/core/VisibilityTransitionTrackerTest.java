package codes.castled.allium.managers.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityTransitionTrackerTest {

    @Test
    void newerTransitionInvalidatesDelayedWorkFromThePreviousState() {
        var tracker = new VisibilityTransitionTracker();
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        long hiddenRevision = tracker.advance(viewer, target);
        long visibleRevision = tracker.advance(viewer, target);

        assertFalse(tracker.isCurrent(viewer, target, hiddenRevision));
        assertTrue(tracker.isCurrent(viewer, target, visibleRevision));
    }

    @Test
    void quittingPlayerInvalidatesBothDirectionsOfPendingWork() {
        var tracker = new VisibilityTransitionTracker();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        long outgoing = tracker.advance(player, other);
        long incoming = tracker.advance(other, player);

        tracker.remove(player);

        assertFalse(tracker.isCurrent(player, other, outgoing));
        assertFalse(tracker.isCurrent(other, player, incoming));
    }
}
