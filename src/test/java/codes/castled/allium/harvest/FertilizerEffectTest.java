package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.crop.GrowthSpeed;
import codes.castled.allium.harvest.crop.def.FertilizerDefinition;
import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.crop.def.FootprintDefinition;
import codes.castled.allium.harvest.crop.def.HarvestDefinition;
import codes.castled.allium.harvest.crop.def.RegrowthDefinition;
import codes.castled.allium.harvest.crop.def.StageDefinition;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.random.MutableWeightedTable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Covers the yield, speed and variation fertilizer effects. */
class FertilizerEffectTest {

    // ==================== yield ====================

    @Test
    void yieldMultipliesAndAddsBonus() {
        FertilizerDefinition.YieldEffect effect =
            new FertilizerDefinition.YieldEffect(1.5D, 1, 0);
        assertEquals(7, effect.apply(4), "round(4 * 1.5) + 1");
        assertEquals(1, effect.apply(0), "bonus applies even to a zero roll");
    }

    @Test
    void yieldRespectsTheMaximum() {
        FertilizerDefinition.YieldEffect capped =
            new FertilizerDefinition.YieldEffect(3.0D, 5, 8);
        assertEquals(8, capped.apply(10));
        assertEquals(8, capped.apply(4));
        assertEquals(8, capped.apply(1), "round(1*3)+5 = 8, exactly at the cap");
    }

    @Test
    void yieldNeverProducesNegativeAmounts() {
        FertilizerDefinition.YieldEffect punishing =
            new FertilizerDefinition.YieldEffect(0.0D, -5, 0);
        assertEquals(0, punishing.apply(3));
    }

    @Test
    void noYieldEffectLeavesAmountsAlone() {
        for (int amount = 0; amount < 64; amount++) {
            assertEquals(amount, FertilizerDefinition.YieldEffect.NONE.apply(amount));
        }
    }

    // ==================== speed ====================

    @Test
    void speedMultipliersStackMultiplicatively() {
        assertEquals(0.64D, GrowthSpeed.combine(0.8D, 0.8D), 1e-9);
        assertEquals(0.6D, GrowthSpeed.combine(1.0D, 0.6D), 1e-9);
        assertEquals(1.0D, GrowthSpeed.combine(), 1e-9);
    }

    @Test
    void speedIsClampedSoStagesCannotCollapse() {
        double extreme = GrowthSpeed.combine(0.01D, 0.01D, 0.01D);
        assertEquals(GrowthSpeed.MINIMUM_MULTIPLIER, extreme, 1e-9);
        // A stage with a real duration always keeps a non-zero one, otherwise
        // the growth engine would advance the crop every single pass.
        assertTrue(GrowthSpeed.apply(10L, extreme) >= 1L);
    }

    @Test
    void nonPositiveMultipliersAreTreatedAsUnmodified() {
        assertEquals(1.0D, GrowthSpeed.clamp(0.0D), 1e-9);
        assertEquals(1.0D, GrowthSpeed.clamp(-2.0D), 1e-9);
        assertEquals(1.0D, GrowthSpeed.clamp(Double.NaN), 1e-9);
        assertEquals(60_000L, GrowthSpeed.apply(60_000L, 1.0D));
    }

    @Test
    void speedScalesStageDurations() {
        assertEquals(30_000L, GrowthSpeed.apply(60_000L, 0.5D));
        assertEquals(120_000L, GrowthSpeed.apply(60_000L, 2.0D));
        assertEquals(0L, GrowthSpeed.apply(0L, 0.5D), "a mature stage stays zero");
    }

    @Test
    void catchUpHonoursTheSpeedMultiplier() {
        ItemRef model = ItemRef.parse("minecraft:wheat_seeds");
        CropPathDefinition path = new CropPathDefinition("normal", 85, List.of(
            new StageDefinition(0, model, 10_000, FootprintDefinition.SINGLE),
            new StageDefinition(1, model, 10_000, FootprintDefinition.SINGLE),
            new StageDefinition(2, model, 0, FootprintDefinition.SINGLE)),
            HarvestDefinition.empty(), RegrowthDefinition.DISABLED);

        // 12s elapsed: at normal speed only stage 0 (10s) is earned.
        assertEquals(1, codes.castled.allium.harvest.crop.CatchUp
            .advance(path, 0, 0, 12_000, 10, 1.0D).stage());
        // At half duration both 5s stages are earned and the crop is mature.
        var fast = codes.castled.allium.harvest.crop.CatchUp
            .advance(path, 0, 0, 12_000, 10, 0.5D);
        assertEquals(2, fast.stage());
        assertTrue(fast.mature());
    }

    // ==================== variation ====================

    /**
     * Mirrors the weighting the placement service performs, so the effect's
     * arithmetic is verified independently of Bukkit.
     */
    private static Map<String, Integer> rollPaths(
        Map<String, Double> baseWeights, String commonPath,
        FertilizerDefinition.VariationEffect variation, int rolls
    ) {
        Random random = new Random(2024);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < rolls; i++) {
            MutableWeightedTable<String> table = new MutableWeightedTable<>();
            baseWeights.forEach((id, weight) -> table.put(id, weight, id));
            if (variation.specialMultiplier() != 1.0D) {
                baseWeights.keySet().stream()
                    .filter(id -> !id.equals(commonPath))
                    .forEach(id -> table.multiplyWeight(id, variation.specialMultiplier()));
            }
            variation.pathMultipliers().forEach(table::multiplyWeight);
            var picked = table.selectOne(random);
            assertTrue(picked.isPresent(), "one path must always be selected");
            counts.merge(picked.get().value(), 1, Integer::sum);
        }
        assertEquals(rolls, counts.values().stream().mapToInt(Integer::intValue).sum(),
            "variation must never yield more than one path per plant");
        return counts;
    }

    @Test
    void specialMultiplierRaisesRarePathsOnly() {
        Map<String, Double> base = Map.of("normal", 85.0, "golden", 5.0, "gigantic", 10.0);
        int rolls = 200_000;
        Map<String, Integer> counts = rollPaths(base, "normal",
            new FertilizerDefinition.VariationEffect(Map.of(), 3.0D), rolls);

        // Weights become 85 / 15 / 30 = 130 total.
        assertEquals(85.0 / 130.0, counts.get("normal") / (double) rolls, 0.01);
        assertEquals(15.0 / 130.0, counts.get("golden") / (double) rolls, 0.01);
        assertEquals(30.0 / 130.0, counts.get("gigantic") / (double) rolls, 0.01);
    }

    @Test
    void perPathMultipliersStackOnTopOfTheSpecialMultiplier() {
        Map<String, Double> base = Map.of("normal", 85.0, "golden", 5.0, "gigantic", 10.0);
        int rolls = 200_000;
        Map<String, Integer> counts = rollPaths(base, "normal",
            new FertilizerDefinition.VariationEffect(Map.of("golden", 2.0D), 4.0D), rolls);

        // normal 85, golden 5*4*2 = 40, gigantic 10*4 = 40 -> 165 total.
        assertEquals(85.0 / 165.0, counts.get("normal") / (double) rolls, 0.01);
        assertEquals(40.0 / 165.0, counts.get("golden") / (double) rolls, 0.01);
        assertEquals(40.0 / 165.0, counts.get("gigantic") / (double) rolls, 0.01);
    }

    @Test
    void noVariationLeavesTheBaseDistributionIntact() {
        Map<String, Double> base = Map.of("normal", 85.0, "golden", 5.0, "gigantic", 10.0);
        int rolls = 200_000;
        Map<String, Integer> counts =
            rollPaths(base, "normal", FertilizerDefinition.VariationEffect.NONE, rolls);
        assertEquals(0.85, counts.get("normal") / (double) rolls, 0.01);
        assertEquals(0.05, counts.get("golden") / (double) rolls, 0.01);
        assertEquals(0.10, counts.get("gigantic") / (double) rolls, 0.01);
    }
}
