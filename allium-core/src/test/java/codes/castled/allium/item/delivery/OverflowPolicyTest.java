package codes.castled.allium.item.delivery;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OverflowPolicyTest {

    private static InventoryFit.Stack<String> s(String key, int count, int max) {
        return new InventoryFit.Stack<>(key, count, max);
    }

    @Test
    void planTopsUpSimilarStacksFirstThenFillsEmptySlotsLikeAddItem() {
        List<InventoryFit.Stack<String>> slots = new ArrayList<>(Arrays.asList(s("stone", 60, 64), null, s("dirt", 64, 64), null));
        InventoryFit.Plan plan = InventoryFit.plan(slots, List.of(s("stone", 70, 64), s("pearl", 20, 16)), String::equals);
        // 4 top up the partial stone stack, 64 fill slot 1 and the last 2 take slot 3; no room is left for pearls.
        assertArrayEquals(new int[]{70, 0}, plan.inserted());
        assertArrayEquals(new int[]{0, 20}, plan.leftovers());
        assertEquals(20, plan.totalLeftover());
        // Pearls first: one 16-stack per empty slot (max 16), so 20 pearls take both empty slots and stone tops up only.
        InventoryFit.Plan pearlsFirst = InventoryFit.plan(slots, List.of(s("pearl", 20, 16), s("stone", 70, 64)), String::equals);
        assertArrayEquals(new int[]{20, 4}, pearlsFirst.inserted());
        assertArrayEquals(new int[]{0, 66}, pearlsFirst.leftovers());
        assertFalse(plan.fitsAll());

        InventoryFit.Plan fits = InventoryFit.plan(slots, List.of(s("stone", 4, 64), s("sword", 1, 1)), String::equals);
        assertTrue(fits.fitsAll());
    }

    @Test
    void policiesDecideFromThePlan() {
        InventoryFit.Plan full = InventoryFit.plan(Arrays.asList(s("dirt", 64, 64)), List.of(s("diamond", 5, 64)), String::equals);
        assertEquals(5, full.totalLeftover());
        assertEquals(OverflowPolicy.Decision.INSERT_THEN_DROP, OverflowPolicy.DROP.decide(full));
        assertEquals(OverflowPolicy.Decision.REFUSE, OverflowPolicy.REFUSE.decide(full));
        assertEquals(OverflowPolicy.Decision.INSERT_PARTIAL, OverflowPolicy.PARTIAL.decide(full));
        InventoryFit.Plan room = InventoryFit.plan(Arrays.asList((InventoryFit.Stack<String>) null), List.of(s("diamond", 5, 64)), String::equals);
        for (OverflowPolicy policy : OverflowPolicy.values()) {
            assertEquals(OverflowPolicy.Decision.INSERT, policy.decide(room), policy.name());
        }
        assertEquals(Optional.of(OverflowPolicy.PARTIAL), OverflowPolicy.fromConfig(" Partial "));
        assertTrue(OverflowPolicy.fromConfig("queue").isEmpty());
    }
}
