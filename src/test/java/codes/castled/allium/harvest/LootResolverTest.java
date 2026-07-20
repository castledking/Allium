package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.crop.LootResolver;
import codes.castled.allium.harvest.crop.def.HarvestOutcome;
import codes.castled.allium.harvest.crop.def.HarvestTable;
import codes.castled.allium.harvest.crop.def.LootMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LootResolverTest {

    private static HarvestOutcome outcome(String id, double weight, double chance) {
        return new HarvestOutcome(id, weight, chance, List.of(), List.of());
    }

    private static HarvestTable qualityTable() {
        Map<String, HarvestOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("regular", outcome("regular", 65, 1.0));
        outcomes.put("silver", outcome("silver", 25, 1.0));
        outcomes.put("golden-star", outcome("golden-star", 10, 1.0));
        return new HarvestTable(LootMode.WEIGHTED_ONE, 1, true, outcomes);
    }

    @Test
    void weightedOneIsMutuallyExclusive() {
        Random random = new Random(1234);
        Map<String, Integer> counts = new HashMap<>();
        int rolls = 500_000;
        for (int i = 0; i < rolls; i++) {
            List<HarvestOutcome> fired = LootResolver.resolve(qualityTable(), null, random);
            assertEquals(1, fired.size(), "exactly one quality per harvest, never a collision");
            counts.merge(fired.get(0).id(), 1, Integer::sum);
        }
        assertEquals(0.65, counts.get("regular") / (double) rolls, 0.01);
        assertEquals(0.25, counts.get("silver") / (double) rolls, 0.01);
        assertEquals(0.10, counts.get("golden-star") / (double) rolls, 0.01);
    }

    @Test
    void weightModifiersShiftTheDistribution() {
        Random random = new Random(99);
        Map<String, Integer> counts = new HashMap<>();
        int rolls = 500_000;
        for (int i = 0; i < rolls; i++) {
            // Deluxe fertilizer: regular ×0.5, silver ×1.5, golden ×2.0
            List<HarvestOutcome> fired = LootResolver.resolve(qualityTable(), table -> {
                table.multiplyWeight("regular", 0.5);
                table.multiplyWeight("silver", 1.5);
                table.multiplyWeight("golden-star", 2.0);
            }, random);
            assertEquals(1, fired.size());
            counts.merge(fired.get(0).id(), 1, Integer::sum);
        }
        double total = 32.5 + 37.5 + 20.0;
        assertEquals(32.5 / total, counts.get("regular") / (double) rolls, 0.01);
        assertEquals(37.5 / total, counts.get("silver") / (double) rolls, 0.01);
        assertEquals(20.0 / total, counts.get("golden-star") / (double) rolls, 0.01);
    }

    @Test
    void independentModeCanFireSeveralEntries() {
        Map<String, HarvestOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("a", outcome("a", 1, 0.9));
        outcomes.put("b", outcome("b", 1, 0.9));
        HarvestTable table = new HarvestTable(LootMode.INDEPENDENT, 1, true, outcomes);
        Random random = new Random(5);
        boolean sawBoth = false;
        for (int i = 0; i < 1_000 && !sawBoth; i++) {
            sawBoth = LootResolver.resolve(table, null, random).size() == 2;
        }
        assertTrue(sawBoth, "INDEPENDENT must be able to fire multiple entries at once");
    }

    @Test
    void guaranteedAllFiresEverything() {
        Map<String, HarvestOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("crop", outcome("crop", 1, 1.0));
        outcomes.put("bonus", outcome("bonus", 1, 1.0));
        HarvestTable table = new HarvestTable(LootMode.GUARANTEED_ALL, 1, true, outcomes);
        assertEquals(2, LootResolver.resolve(table, null, new Random(1)).size());
    }

    @Test
    void sequenceStopsAtFirstFailureWhenConfigured() {
        Map<String, HarvestOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("first", outcome("first", 1, 0.0000001)); // effectively never
        outcomes.put("second", outcome("second", 1, 1.0));
        HarvestTable stopping = new HarvestTable(LootMode.SEQUENCE, 1, true, outcomes);
        assertEquals(0, LootResolver.resolve(stopping, null, new Random(3)).size(),
            "stop-on-fail sequence must not reach the second entry");
        HarvestTable continuing = new HarvestTable(LootMode.SEQUENCE, 1, false, outcomes);
        assertEquals(1, LootResolver.resolve(continuing, null, new Random(3)).size());
    }

    @Test
    void weightedMultipleWithoutReplacementRespectsPickCount() {
        Map<String, HarvestOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("a", outcome("a", 10, 1.0));
        outcomes.put("b", outcome("b", 10, 1.0));
        outcomes.put("c", outcome("c", 10, 1.0));
        HarvestTable table = new HarvestTable(
            LootMode.WEIGHTED_MULTIPLE_WITHOUT_REPLACEMENT, 2, true, outcomes);
        Random random = new Random(11);
        for (int i = 0; i < 1_000; i++) {
            List<HarvestOutcome> fired = LootResolver.resolve(table, null, random);
            assertEquals(2, fired.size());
            assertTrue(!fired.get(0).id().equals(fired.get(1).id()), "no repeats");
        }
    }

    @Test
    void emptyTableResolvesToNothing() {
        assertTrue(LootResolver.resolve(HarvestTable.empty(), null, new Random(1)).isEmpty());
        assertTrue(LootResolver.resolve(null, null, new Random(1)).isEmpty());
    }
}
