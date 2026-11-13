package codes.castled.allium.teleport;

import codes.castled.allium.teleport.RequestTracker.Kind;
import codes.castled.allium.teleport.RequestTracker.Request;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestTrackerTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CAROL = new UUID(0, 3);
    private static final UUID DAVE = new UUID(0, 4);

    @Test
    void multipleInboundRequestsCanBeSelectedByRequesterOrLatestPut() {
        RequestTracker tracker = new RequestTracker();
        Request alice = new Request(ALICE, BOB, Kind.TO_TARGET, 500);
        Request carol = new Request(CAROL, BOB, Kind.HERE, 400);
        tracker.put(alice);
        tracker.put(carol);
        assertEquals(alice, tracker.incoming(BOB, ALICE, 100).orElseThrow());
        assertEquals(carol, tracker.incoming(BOB, CAROL, 100).orElseThrow());
        assertEquals(carol, tracker.incoming(BOB, null, 100).orElseThrow(), "latest put, not latest expiration");
        assertTrue(tracker.incoming(CAROL, ALICE, 100).isEmpty());
        assertTrue(tracker.incoming(BOB, DAVE, 100).isEmpty());
        assertEquals(carol, tracker.remove(CAROL).orElseThrow());
        assertEquals(alice, tracker.incoming(BOB, null, 100).orElseThrow());
        assertTrue(tracker.remove(CAROL).isEmpty());
    }

    @Test
    void replacementMovesTheRequesterAndMakesThemTheNewest() {
        RequestTracker tracker = new RequestTracker();
        tracker.put(new Request(ALICE, CAROL, Kind.TO_TARGET, 500));
        Request carol = new Request(CAROL, BOB, Kind.HERE, 500);
        tracker.put(carol);
        Request replacement = new Request(ALICE, BOB, Kind.HERE, 400);
        tracker.put(replacement);
        assertTrue(tracker.incoming(CAROL, ALICE, 100).isEmpty());
        assertEquals(replacement, tracker.incoming(BOB, null, 100).orElseThrow());
        assertEquals(List.of(carol, replacement), tracker.cancelIncoming(BOB));
        assertTrue(tracker.remove(ALICE).isEmpty());
    }

    @Test
    void exactDeadlineIsExpiredAndExpiryNotificationsAreReturnedOnce() {
        RequestTracker tracker = new RequestTracker();
        Request early = new Request(ALICE, BOB, Kind.TO_TARGET, 10);
        Request later = new Request(CAROL, BOB, Kind.HERE, 20);
        tracker.put(early);
        tracker.put(later);
        assertEquals(early, tracker.incoming(BOB, ALICE, 9).orElseThrow());
        assertTrue(tracker.incoming(BOB, ALICE, 10).isEmpty());
        assertEquals(later, tracker.incoming(BOB, null, 10).orElseThrow());
        assertTrue(tracker.incoming(BOB, null, 20).isEmpty());
        assertEquals(List.of(), tracker.expire(9));
        assertEquals(List.of(early), tracker.expire(10));
        assertEquals(List.of(), tracker.expire(10));
        assertEquals(List.of(later), tracker.expire(20));
        assertEquals(List.of(), tracker.expire(Long.MAX_VALUE));
    }

    @Test
    void anExpiredNewestRequestDoesNotHideAnOlderLiveRequest() {
        RequestTracker tracker = new RequestTracker();
        Request live = new Request(ALICE, BOB, Kind.TO_TARGET, Long.MAX_VALUE);
        Request expired = new Request(CAROL, BOB, Kind.HERE, Long.MIN_VALUE);
        tracker.put(live);
        tracker.put(expired);
        assertEquals(live, tracker.incoming(BOB, null, 0).orElseThrow());
        assertEquals(List.of(expired), tracker.expire(0));
        assertEquals(List.of(live), tracker.expire(Long.MAX_VALUE), "deadline comparison cannot overflow");
    }

    @Test
    void cancelIncomingPreservesOutgoingAndUnrelatedRequests() {
        RequestTracker tracker = new RequestTracker();
        Request alice = new Request(ALICE, BOB, Kind.TO_TARGET, 100);
        Request carol = new Request(CAROL, BOB, Kind.HERE, 100);
        Request bob = new Request(BOB, CAROL, Kind.TO_TARGET, 100);
        Request unrelated = new Request(DAVE, ALICE, Kind.HERE, 100);
        for (Request request : List.of(alice, carol, bob, unrelated)) tracker.put(request);
        assertEquals(List.of(alice, carol), tracker.cancelIncoming(BOB));
        assertEquals(List.of(), tracker.cancelIncoming(BOB));
        assertEquals(bob, tracker.incoming(CAROL, BOB, 0).orElseThrow());
        assertEquals(unrelated, tracker.incoming(ALICE, DAVE, 0).orElseThrow());
    }

    @Test
    void cancelPlayerRemovesBothDirectionsAndPreservesUnrelatedRequests() {
        RequestTracker tracker = new RequestTracker();
        Request incoming = new Request(ALICE, BOB, Kind.TO_TARGET, 100);
        Request outgoing = new Request(BOB, CAROL, Kind.HERE, 100);
        Request unrelated = new Request(CAROL, DAVE, Kind.TO_TARGET, 100);
        tracker.put(incoming);
        tracker.put(outgoing);
        tracker.put(unrelated);
        assertEquals(List.of(incoming, outgoing), tracker.cancelPlayer(BOB));
        assertEquals(List.of(), tracker.cancelPlayer(BOB));
        assertTrue(tracker.remove(ALICE).isEmpty());
        assertTrue(tracker.remove(BOB).isEmpty());
        assertEquals(unrelated, tracker.incoming(DAVE, CAROL, 0).orElseThrow());
    }

    @Test
    void selfRequestsAndNullIdentitiesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Request(ALICE, ALICE, Kind.TO_TARGET, 100));
        assertThrows(IllegalArgumentException.class, () -> new Request(ALICE, ALICE, Kind.HERE, 100));
        assertThrows(NullPointerException.class, () -> new Request(null, BOB, Kind.HERE, 100));
        assertThrows(NullPointerException.class, () -> new Request(ALICE, null, Kind.HERE, 100));
        assertThrows(NullPointerException.class, () -> new Request(ALICE, BOB, null, 100));
        RequestTracker tracker = new RequestTracker();
        assertThrows(NullPointerException.class, () -> tracker.put(null));
    }

    @Test
    void rejectsOverflowAtomicallyButAllowsReplacementAtCapacity() {
        RequestTracker tracker = new RequestTracker(2);
        Request alice = new Request(ALICE, BOB, Kind.TO_TARGET, 100);
        Request carol = new Request(CAROL, BOB, Kind.HERE, 100);
        Request overflow = new Request(DAVE, BOB, Kind.TO_TARGET, 100);
        tracker.put(alice);
        tracker.put(carol);
        assertThrows(IllegalStateException.class, () -> tracker.put(overflow));
        assertEquals(carol, tracker.incoming(BOB, null, 0).orElseThrow());
        assertEquals(alice, tracker.incoming(BOB, ALICE, 0).orElseThrow());
        assertTrue(tracker.remove(DAVE).isEmpty());
        Request replacement = new Request(ALICE, DAVE, Kind.HERE, 200);
        tracker.put(replacement);
        assertTrue(tracker.incoming(BOB, ALICE, 0).isEmpty());
        assertEquals(replacement, tracker.incoming(DAVE, ALICE, 0).orElseThrow());
        tracker.remove(CAROL);
        tracker.put(overflow);
        assertEquals(overflow, tracker.incoming(BOB, null, 0).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new RequestTracker(0));
        assertThrows(IllegalArgumentException.class, () -> new RequestTracker(-1));
    }

    @Test
    void defaultBoundIs4096AndExpiredRequestsReleaseCapacityWhenReaped() {
        RequestTracker tracker = new RequestTracker();
        for (int i = 1; i <= 4096; i++) {
            tracker.put(new Request(new UUID(1, i), BOB, Kind.TO_TARGET, 0));
        }
        Request next = new Request(ALICE, BOB, Kind.HERE, 10);
        assertThrows(IllegalStateException.class, () -> tracker.put(next));
        assertEquals(4096, tracker.expire(0).size());
        tracker.put(next);
        assertEquals(next, tracker.incoming(BOB, null, 0).orElseThrow());
    }
}
