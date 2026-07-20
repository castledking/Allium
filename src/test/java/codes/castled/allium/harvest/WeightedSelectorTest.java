package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.random.WeightedEntry;
import codes.castled.allium.harvest.random.WeightedSelector;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WeightedSelectorTest {

    private record Entry(String id, double weight) implements WeightedEntry {
        @Override public double weight() { return weight; }
    }

    private static final List<Entry> TOMATO_PATHS = List.of(
        new Entry("normal", 85),
        new Entry("golden", 5),
        new Entry("gigantic", 10));

    @Test
    void millionRollsMatchConfiguredDistribution() {
        Random random = new Random(42);
        Map<String, Integer> counts = new HashMap<>();
        int rolls = 1_000_000;
        for (int i = 0; i < rolls; i++) {
            Optional<Entry> selected = WeightedSelector.selectOne(TOMATO_PATHS, random);
            assertTrue(selected.isPresent(), "every roll must select exactly one path");
            counts.merge(selected.get().id(), 1, Integer::sum);
        }
        // Exactly one path per roll: counts sum to the number of rolls.
        assertEquals(rolls, counts.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(0.85, counts.get("normal") / (double) rolls, 0.005);
        assertEquals(0.05, counts.get("golden") / (double) rolls, 0.005);
        assertEquals(0.10, counts.get("gigantic") / (double) rolls, 0.005);
    }

    @Test
    void relativeWeightsAreEquivalent() {
        List<Entry> scaled = List.of(
            new Entry("normal", 17), new Entry("golden", 1), new Entry("gigantic", 2));
        Random a = new Random(7);
        Random b = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            assertEquals(
                WeightedSelector.selectOne(TOMATO_PATHS, a).orElseThrow().id(),
                WeightedSelector.selectOne(scaled, b).orElseThrow().id(),
                "17/1/2 must behave identically to 85/5/10");
        }
    }

    @Test
    void zeroAndNegativeWeightsAreNeverSelected() {
        List<Entry> entries = List.of(
            new Entry("valid", 10), new Entry("zero", 0), new Entry("negative", -5));
        Random random = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            assertEquals("valid", WeightedSelector.selectOne(entries, random).orElseThrow().id());
        }
    }

    @Test
    void allNonPositiveWeightsSelectNothing() {
        List<Entry> entries = List.of(new Entry("zero", 0), new Entry("negative", -1));
        assertTrue(WeightedSelector.selectOne(entries, new Random(1)).isEmpty());
        assertTrue(WeightedSelector.selectOne(List.<Entry>of(), new Random(1)).isEmpty());
    }

    @Test
    void withoutReplacementNeverRepeats() {
        Random random = new Random(9);
        for (int i = 0; i < 10_000; i++) {
            List<Entry> picked = WeightedSelector.selectWithoutReplacement(TOMATO_PATHS, 2, random);
            assertEquals(2, picked.size());
            Set<String> ids = new HashSet<>();
            picked.forEach(entry -> ids.add(entry.id()));
            assertEquals(2, ids.size(), "picks must be unique");
        }
        // Requesting more than available yields each entry exactly once.
        List<Entry> all = WeightedSelector.selectWithoutReplacement(TOMATO_PATHS, 10, random);
        assertEquals(3, all.size());
    }
}
