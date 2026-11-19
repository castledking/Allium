package codes.castled.allium.broadcast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastRotationTest {

    @Test
    void sequentialFollowsTheFileAndWrapsAndCopesWithEdits() {
        BroadcastRotation rotation = new BroadcastRotation(BroadcastRotation.Order.SEQUENTIAL, new Random(1));
        List<String> names = List.of("a", "b", "c");
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) seen.add(rotation.next(names).orElseThrow());
        assertEquals(List.of("a", "b", "c", "a"), seen);
        assertEquals("x", rotation.next(List.of("a", "x", "b")).orElseThrow(), "the broadcast after the last one sent");
        assertEquals("b", rotation.next(List.of("b", "c")).orElseThrow(), "with x removed, the one that followed it goes next");
        assertTrue(rotation.next(List.of()).isEmpty());
    }

    @Test
    void randomNeverRepeatsThePreviousOneWhenThereIsAChoice() {
        BroadcastRotation rotation = new BroadcastRotation(BroadcastRotation.Order.RANDOM, new Random(7));
        List<String> names = List.of("a", "b", "c");
        String last = rotation.next(names).orElseThrow();
        for (int i = 0; i < 200; i++) {
            String next = rotation.next(names).orElseThrow();
            assertNotEquals(last, next);
            last = next;
        }
        assertEquals("only", rotation.next(List.of("only")).orElseThrow());
        assertEquals("only", rotation.next(List.of("only")).orElseThrow());
    }

    @Test
    void ordersParseLeniently() {
        assertEquals(BroadcastRotation.Order.RANDOM, BroadcastRotation.Order.parse(" Random ").orElseThrow());
        assertEquals(BroadcastRotation.Order.SEQUENTIAL, BroadcastRotation.Order.parse("in-order").orElseThrow());
        assertTrue(BroadcastRotation.Order.parse("sometimes").isEmpty());
    }
}
