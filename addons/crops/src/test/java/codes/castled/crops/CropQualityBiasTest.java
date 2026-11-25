package codes.castled.crops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CropQualityBiasTest {

    @Test
    void rarityMultipliersBiasRarerEntriesLinearlyAndKeepZeroWeightEntriesOff() {
        List<Catalog.Entry> entries = List.of(
                new Catalog.Entry("common", 70.0, 1.0, List.of()),
                new Catalog.Entry("silver", 20.0, 1.0, List.of()),
                new Catalog.Entry("gold", 10.0, 1.0, List.of()),
                new Catalog.Entry("disabled", 0.0, 1.0, List.of())
        );

        Map<String, Double> mults = Catalog.rarityMultipliers(entries, 0.40);
        // Sorted ascending by weight: disabled (0), gold (10), silver (20), common (70)
        assertFalse(mults.containsKey("disabled"), "zero-weight entry must never be unlocked");
        assertTrue(mults.get("gold") > mults.get("silver"));
        assertTrue(mults.get("silver") > mults.get("common"));
        assertEquals(1.0, mults.get("common"), 1e-6);
    }

    @Test
    void rarityMultipliersIgnoreNonPositiveBiasOrSingleEntryTables() {
        List<Catalog.Entry> one = List.of(new Catalog.Entry("only", 10.0, 1.0, List.of()));
        assertTrue(Catalog.rarityMultipliers(one, 0.25).isEmpty());
        assertTrue(Catalog.rarityMultipliers(List.of(
                new Catalog.Entry("a", 10.0, 1.0, List.of()),
                new Catalog.Entry("b", 5.0, 1.0, List.of())
        ), 0.0).isEmpty());
    }
}
