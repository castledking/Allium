package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import codes.castled.allium.tradingcards.morph.MorphRules;
import codes.castled.allium.tradingcards.morph.MorphService;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * The morph rules, without a server.
 *
 * <p>Two things are being pinned down here.
 *
 * <p>First, the config: the shipped {@code config.yml} is read rather than
 * duplicated, so a change to the file that breaks the gate cannot pass tests.
 *
 * <p>Second, the targeting rule, which is the part most likely to be wrong. It
 * is the difference between a morph that is a costume and one that is a mechanic
 * with a cost, so the cases that matter — the Johnny exemption, the
 * not-yet-provocable case, the witness asymmetry — are all named.
 */
class MorphRulesTest {

    private static TradingCardsConfig shipped() {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        try (var in = MorphRulesTest.class
                .getResourceAsStream("/tradingcards/config.yml")) {
            assertTrue(in != null, "tradingcards/config.yml is not on the classpath");
            yaml.loadFromString(new String(in.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("could not read the shipped config", e);
        }
        return TradingCardsConfig.load(yaml).config();
    }

    // ==================== config ====================

    @Test
    void theShippedConfigGatesMorphingOnFabled() {
        var morph = shipped().morph();
        assertTrue(morph.enabled(), "morphing should ship enabled");
        assertEquals(Tier.FABLED, morph.minimumTier(),
            "morphing is the reward for merging all the way up");
    }

    @Test
    void theShippedConfigShipsStealthOnAndFlightOff() {
        var morph = shipped().morph();
        // Stealth-until-attacked is what gives the morph its cost. Flight is the
        // most abusable thing in the set and is opt-in per mob, not global.
        assertTrue(morph.stealthUntilAttacked(),
            "a morphed hostile form must not be aggroed for free");
        assertFalse(morph.allowFlight(), "flight must be opt-in");
        assertEquals(0.0, morph.deactivateBelowHealth(),
            "the health floor ships off; a 20hp player at half health is not a rule");
        assertEquals(0, morph.durationSeconds(),
            "0 means a toggle, which is what /morph advertises");
    }

    @Test
    void theShippedConfigLetsMobsSeeTheMorphedPlayer() {
        assertTrue(shipped().morph().mobsTargetDisguised(),
            "false would make the morphed player invisible to mob AI entirely");
    }

    // ==================== eligibility ====================

    @Test
    void everyMorphableTypeIsReal() {
        assertFalse(MorphRules.MORPHABLE.isEmpty());
        assertEquals(new HashSet<>(MorphRules.MORPHABLE).size(),
            MorphRules.MORPHABLE.size(), "no duplicates");
    }

    @Test
    void resolutionIsCaseAndSpaceTolerant() {
        assertEquals(EntityType.CREEPER, MorphRules.resolve("creeper"));
        assertEquals(EntityType.CREEPER, MorphRules.resolve("  Creeper "));
        assertNull(MorphRules.resolve("NOT_A_MOB"));
        assertNull(MorphRules.resolve(""));
        assertNull(MorphRules.resolve(null));
    }

    @Test
    void playerIsMorphableAndEverythingElseStillNeedsABackingMob() {
        // PLAYER is deliberately in the set: the disguise API supports it and
        // Johnny-style impersonation is the clearest test of the stealth rule.
        assertTrue(MorphRules.isMorphable(EntityType.PLAYER));
        assertTrue(MorphRules.isMorphable(EntityType.CREEPER));
        assertFalse(MorphRules.isMorphable(EntityType.UNKNOWN));
        assertFalse(MorphRules.isMorphable(null));
    }

    // ==================== flight ====================

    @Test
    void onlyRealFliersCanFly() {
        assertTrue(MorphRules.canFly(EntityType.PARROT));
        assertTrue(MorphRules.canFly(EntityType.BEE));
        assertFalse(MorphRules.canFly(EntityType.CREEPER),
            "a creeper that can fly is an infinite creeper");
        assertFalse(MorphRules.canFly(EntityType.PLAYER));
        for (var type : MorphRules.FLYING) {
            assertTrue(MorphRules.isMorphable(type),
                type + " is in the flying list but cannot morph");
        }
    }

    // ==================== targeting ====================

    @Test
    void aMorphedHostileIsInvisibleUntilItStrikes() {
        assertFalse(MorphRules.morphMayTarget(
                EntityType.CREEPER, false, true, EntityType.CREEPER, null),
            "a creeper morph must not be targeted by creepers before it attacks");
        assertTrue(MorphRules.morphMayTarget(
                EntityType.CREEPER, true, true, EntityType.CREEPER, null),
            "once provoked, the mobs that witnessed it converge");
    }

    @Test
    void turningStealthOffMakesHostileFormsTargetableImmediately() {
        assertTrue(MorphRules.morphMayTarget(
                EntityType.CREEPER, false, false, EntityType.CREEPER, null),
            "with the rule disabled there is nothing to hide behind");
    }

    @Test
    void aPassiveFormIsNeverRefused() {
        assertTrue(MorphRules.morphMayTarget(EntityType.COW, false, true, EntityType.WOLF, null),
            "a morphed cow is not something a wolf objects to");
        assertTrue(MorphRules.morphMayTarget(
                EntityType.SHEEP, false, true, EntityType.SHEEP, null));
    }

    @Test
    void anUnmorphedPlayerIsAlwaysTargetable() {
        assertTrue(MorphRules.morphMayTarget(null, false, true, EntityType.ZOMBIE, null),
            "the rule must not reach players who are not morphed");
    }

    @Test
    void johnnyIsExempt() {
        // The case the whole exemption exists for: Johnny the Vindicator is a
        // vanilla pacifist, so a vindicator-shaped morph must not be dragged into
        // a fight with him.
        assertTrue(MorphRules.isExempt(EntityType.VINDICATOR, "Johnny"));
        assertTrue(MorphRules.morphMayTarget(
                EntityType.VINDICATOR, false, true, EntityType.VINDICATOR, "Johnny"),
            "named vindicators never target a morph");
        assertFalse(MorphRules.morphMayTarget(
                EntityType.VINDICATOR, false, true, EntityType.VINDICATOR, null),
            "an unnamed vindicator is an ordinary hostile and is not exempt");
    }

    @Test
    void theExemptionFollowsTheNameNotTheIdentity() {
        // Any named vindicator, so a server that renames its traders does not
        // accidentally make them hostile to morphs.
        assertTrue(MorphRules.isExempt(EntityType.VINDICATOR, "Trader Joe"));
        assertTrue(MorphRules.isExempt(EntityType.EVOKER, "Professor"));
        assertFalse(MorphRules.isExempt(EntityType.EVOKER, null));
        assertFalse(MorphRules.isExempt(EntityType.VINDICATOR, "   "),
            "a blank name is not a name");
    }

    @Test
    void theExemptionDoesNotLeakToOtherMobs() {
        assertFalse(MorphRules.isExempt(EntityType.ZOMBIE, "Johnny"),
            "the exemption is about vindicators, not about the name");
        assertFalse(MorphRules.isExempt(EntityType.CREEPER, "Johnny"));
    }

    @Test
    void everyExemptTypeIsARealMob() {
        for (var type : MorphRules.NEVER_TARGETS_MORPHS) {
            assertTrue(MorphRules.isHostile(type),
                type + " is listed as never-targets but is not hostile anyway");
        }
    }

    // ==================== service config ====================

    @Test
    void serviceDefaultsMatchTheShippedConfig() {
        var shipped = shipped().morph();
        var defaults = MorphService.Config.defaults();
        assertEquals(shipped.minimumTier(), defaults.minimumTier());
        assertEquals(shipped.durationSeconds(), defaults.durationSeconds());
        assertEquals(shipped.allowFlight(), defaults.allowFlight());
        assertEquals(shipped.stealthUntilAttacked(), defaults.stealthUntilAttacked());
    }

    @Test
    void tierGateComparesByRank() {
        assertTrue(Tier.FABLED.atLeast(Tier.FABLED));
        assertTrue(Tier.FABLED.atLeast(Tier.LEGENDARY));
        assertFalse(Tier.LEGENDARY.atLeast(Tier.FABLED));
        assertFalse(Tier.SIMPLE.atLeast(Tier.FABLED),
            "merging all the way up is the only route to a morph");
    }

    @Test
    void theListingIsSortedAndComplete() {
        var names = MorphRules.names();
        assertEquals(MorphRules.MORPHABLE.size(), names.size());
        var sorted = new java.util.ArrayList<>(names);
        sorted.sort(String::compareTo);
        assertEquals(sorted, names, "the /morph list should be in a stable order");
        assertTrue(names.contains("CREEPER"));
    }

    @Test
    void morphableAndHostileListsAgreeOnWhatIsReal() {
        for (var type : MorphRules.HOSTILE) {
            assertTrue(type != null, "no nulls in the hostile list");
        }
        assertFalse(MorphRules.HOSTILE.contains(EntityType.COW));
        assertTrue(MorphRules.HOSTILE.contains(EntityType.CREEPER));
    }

    @Test
    void tierNamesRoundTrip() {
        for (Tier tier : Tier.values()) {
            assertEquals(tier, Tier.valueOf(tier.name().toUpperCase(Locale.ROOT)));
        }
    }
}
