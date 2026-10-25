package codes.castled.allium.tradingcards;

import codes.castled.allium.tradingcards.boost.BoostCatalog;
import codes.castled.allium.tradingcards.card.CardFactory;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.merge.MergeRules;
import codes.castled.allium.tradingcards.reroll.RerollPricing;
import codes.castled.allium.tradingcards.reroll.RerollService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TierSignaturesTest {
    @Test
    void dropsAndMergesHaveTheSameFixedTierSignatures() {
        int[] sizes = {3, 3, 4, 4, 5};
        var rules = new MergeRules(true, 100, 0);
        for (Tier tier : Tier.values()) {
            var signatures = CardFactory.startingSignatures(tier);
            assertEquals(sizes[tier.ordinal()], signatures.size(), tier.name());
            assertEquals(List.of("luck", "strength", "speed"), signatures.subList(0, 3));
            assertEquals(signatures, rules.mergedSignatures(tier));
        }
        assertEquals("health", CardFactory.startingSignatures(Tier.ULTIMATE).get(3));
        assertEquals("wisdom", CardFactory.startingSignatures(Tier.FABLED).get(4));
    }

    @Test
    void evenLegacySettingsWithAGuaranteedUnlockCannotRollASignatureOrCharge() {
        var catalog = new BoostCatalog.LoadResult(Map.of(), Map.of(),
            Map.of(Tier.SIMPLE, List.of("health")), Map.of(), 3, 1.0, 9, List.of());
        var pricing = new RerollPricing(true, 2000, 1.6, 100, 500000, Map.of(), 1.0, 9, true);
        var service = new RerollService(catalog, pricing, null, new java.util.Random(1));
        for (Tier tier : Tier.values()) {
            var card = new TradingCardData("ALLAY", "allay", tier, 0, 100,
                CardFactory.startingSignatures(tier), 0, false);
            var result = service.reroll(null, card);
            assertEquals(RerollService.Outcome.DISABLED, result.outcome());
            assertEquals(0, result.cost());
            assertEquals(card.signatures(), result.signatures());
            assertFalse(result.succeeded());
        }
    }
}
