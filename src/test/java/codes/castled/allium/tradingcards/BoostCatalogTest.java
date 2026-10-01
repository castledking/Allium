package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.boost.BoostCatalog;
import codes.castled.allium.tradingcards.boost.BoostDefinition;
import codes.castled.allium.tradingcards.boost.BoostMechanism;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Reads the shipped catalogue, not a fixture.
 *
 * <p>These assertions are about the numbers and mechanisms an operator will
 * actually run, so a fixture would prove nothing: a boost moved into the wrong
 * pool, or a non-incrementable boost listed as a signature, has to fail the
 * build here rather than show up as a meaningless "(+0.1)" on a live card.
 */
class BoostCatalogTest {

    private static BoostCatalog.LoadResult shipped() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = BoostCatalogTest.class
                .getResourceAsStream("/tradingcards/boosts.yml")) {
            assertNotNull(in, "tradingcards/boosts.yml is not on the classpath");
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("could not read the shipped catalogue", e);
        }
        BoostCatalog.LoadResult result = BoostCatalog.load(yaml, 3, 0.35, 6);
        assertFalse(result.hasErrors(),
            () -> "the shipped catalogue must load cleanly: " + result.issues());
        return result;
    }

    @Test
    void theShippedCatalogueLoadsWithoutErrors() {
        assertFalse(shipped().boosts().isEmpty());
    }

    @Test
    void allNineAuraSkillsStatsAreReachable() {
        // AuraSkills' enum is exactly nine constants. If any is missing from
        // the catalogue, a stat is quietly unreachable through cards — which
        // was the original complaint about the design.
        var stats = shipped().boosts().values().stream()
            .filter(b -> b.mechanism() == BoostMechanism.AURASKILL_STAT)
            .map(b -> b.option("stat", "").toUpperCase())
            .collect(java.util.stream.Collectors.toSet());
        for (String expected : new String[] {"STRENGTH", "HEALTH", "REGENERATION", "LUCK",
                                            "WISDOM", "TOUGHNESS", "CRIT_CHANCE",
                                            "CRIT_DAMAGE", "SPEED"}) {
            assertTrue(stats.contains(expected),
                expected + " is not reachable through any card boost");
        }
    }

    @Test
    void everySignatureIsAuraSkillsBackedAndThereforeIncrementable() {
        // The contract: a signature's "+n" must mean something, which requires
        // levelling's +1 to be addable to its unit. Only AURASKILL_STAT is.
        for (var tier : codes.castled.allium.tradingcards.card.Tier.values()) {
            for (String id : shipped().signaturePool()
                    .getOrDefault(tier, java.util.List.of())) {
                BoostDefinition boost = shipped().boosts().get(id);
                assertNotNull(boost, id + " is in a signature pool but not in the catalogue");
                assertTrue(boost.mechanism().canBeSignature(),
                    id + " uses " + boost.mechanism()
                        + ", which cannot be a signature — levelling could not add +1 to it");
            }
        }
    }

    @Test
    void scaleIsNeverASignatureBecauseItIsNotIncrementable() {
        // "+0.1x player scale" is not a quantity, and its 0.6-1.9 clamp means
        // nothing under flat addition.
        for (var tier : codes.castled.allium.tradingcards.card.Tier.values()) {
            assertFalse(shipped().signaturePool()
                    .getOrDefault(tier, java.util.List.of()).contains("scale"),
                "scale must stay a bonus boost");
        }
        assertFalse(shipped().boosts().get("scale").isSignature());
    }

    @Test
    void theSignaturePoolsGrowWithTier() {
        var result = shipped();
        int simple = result.signaturePool()
            .get(codes.castled.allium.tradingcards.card.Tier.SIMPLE).size();
        int fabled = result.signaturePool()
            .get(codes.castled.allium.tradingcards.card.Tier.FABLED).size();
        assertTrue(fabled > simple,
            "a FABLED card should be able to unlock more than a SIMPLE one");
    }

    @Test
    void everyBoostNamedInAPoolExistsInTheCatalogue() {
        var result = shipped();
        for (var tier : codes.castled.allium.tradingcards.card.Tier.values()) {
            for (String id : result.bonusPool().getOrDefault(tier, java.util.List.of())) {
                assertNotNull(result.boosts().get(id),
                    "bonus " + tier + " names '" + id + "', which is not in the catalogue");
            }
        }
    }

    @Test
    void everySignatureHasAnAmountConfigured() {
        var result = shipped();
        for (var tier : codes.castled.allium.tradingcards.card.Tier.values()) {
            for (String id : result.signaturePool().getOrDefault(tier, java.util.List.of())) {
                assertTrue(result.signatureAmounts().containsKey(id),
                    id + " can be a signature but has no amount, so its lore would show nothing");
            }
        }
    }

    @Test
    void everyMechanismHasAnExplicitCleanupPath() {
        // A boost whose state lives in another plugin must say so, because
        // that is what decides whether unequip has to undo it.
        for (BoostMechanism mechanism : BoostMechanism.values()) {
            if (mechanism.name().startsWith("CARD_") || mechanism == BoostMechanism.ATTRIBUTE
                || mechanism == BoostMechanism.AURASKILL_TRAIT) {
                continue;
            }
            assertFalse(mechanism.needsExplicitCleanup() && !mechanism.canBeSignature(),
                mechanism + " needs cleanup but cannot be a signature");
        }
        assertTrue(BoostMechanism.AURASKILL_STAT.needsExplicitCleanup());
        assertTrue(BoostMechanism.CARD_PERMISSION.needsExplicitCleanup());
        assertFalse(BoostMechanism.ATTRIBUTE.needsExplicitCleanup(),
            "a Bukkit attribute modifier is engine-owned and drops with the entity");
    }

    @Test
    void aSignatureTotalGrowsWithLevelButABonusTotalDoesNot() {
        BoostCatalog.LoadResult result = shipped();
        BoostDefinition luck = result.boosts().get("luck");
        BoostDefinition armor = result.boosts().get("armor");

        // AURASKILL_STAT is auto-incrementable; ATTRIBUTE is not, because a
        // flat attribute bonus is not a signature.
        assertTrue(luck.isSignature());
        assertEquals(5.0, luck.totalAt(0, 1.0, 0.6, 1.9), 1e-9);
        assertEquals(15.0, luck.totalAt(10, 1.0, 0.6, 1.9), 1e-9);

        assertFalse(armor.isSignature());
        assertEquals(2.0, armor.totalAt(0, 1.0, 0.6, 1.9), 1e-9);
        assertEquals(2.0, armor.totalAt(50, 1.0, 0.6, 1.9), 1e-9,
            "levelling must never grow a bonus boost — the drop did not award it");
    }

    @Test
    void scaleIsClampedRatherThanRejected() {
        // A card that rolled a large factor grants the strongest legal version
        // of itself, rather than nothing at all.
        BoostDefinition scale = new BoostDefinition("scale", "Scale",
            BoostMechanism.CARD_SCALE, 2.4, 0, false, java.util.Map.of());
        assertEquals(1.9, scale.totalAt(0, 1.0, 0.6, 1.9), 1e-9);
        assertEquals(1.9, scale.totalAt(100, 1.0, 0.6, 1.9), 1e-9,
            "levelling must not move scale either way");

        BoostDefinition tiny = new BoostDefinition("scale", "Scale",
            BoostMechanism.CARD_SCALE, 0.1, 0, false, java.util.Map.of());
        assertEquals(0.6, tiny.totalAt(0, 1.0, 0.6, 1.9), 1e-9);
    }

    @Test
    void everyAttributeBoostNamesAResolvableAttribute() {
        for (var boost : shipped().boosts().values()) {
            if (boost.mechanism() != BoostMechanism.ATTRIBUTE) continue;
            assertFalse(boost.option("attribute", "").isBlank(),
                boost.id() + " is an attribute boost but names no attribute");
        }
    }

    @Test
    void aNonIncrementableBoostInASignaturePoolIsRejectedWithAReason() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            boosts:
              speed:
                mechanism: AURASKILL_STAT
                stat: SPEED
                amount: 3.0
              scale:
                mechanism: CARD_SCALE
                amount: 1.0
            signature-pool:
              SIMPLE: [ scale ]
            bonus:
              SIMPLE: [ speed ]
            """);
        BoostCatalog.LoadResult result = BoostCatalog.load(yaml, 3, 0.35, 6);
        assertTrue(result.hasErrors());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("scale")
                && i.message().contains("not incrementable")),
            () -> "expected an explanatory error: " + result.issues());
    }

    @Test
    void anUnknownBoostInAPoolIsReported() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            boosts:
              speed:
                mechanism: AURASKILL_STAT
                stat: SPEED
                amount: 3.0
            signature-pool:
              SIMPLE: [ ]
            bonus:
              SIMPLE: [ nonexistent-boost ]
            """);
        BoostCatalog.LoadResult result = BoostCatalog.load(yaml, 3, 0.35, 6);
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("nonexistent-boost")),
            () -> "expected the unknown-boost error: " + result.issues());
    }

    @Test
    void anUnknownMechanismIsRejectedByName() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            boosts:
              mystery:
                mechanism: AURASKILL_MYSTERY
                amount: 1.0
            """);
        BoostCatalog.LoadResult result = BoostCatalog.load(yaml, 3, 0.35, 6);
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("Unknown mechanism")),
            () -> "expected an unknown-mechanism error: " + result.issues());
    }

    @Test
    void maximumSignaturesIsFlooredAtTheThreeEveryCardStartsWith() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("maximum-signatures: 1\n");
        BoostCatalog.LoadResult result = BoostCatalog.load(yaml, 3, 0.35, 1);
        assertEquals(3, result.maximumSignatures(),
            "below 3 a card would have to lose a signature to equip");
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().contains("maximum-signatures")),
            () -> "expected a warning pinned to maximum-signatures: " + result.issues());
    }
}
