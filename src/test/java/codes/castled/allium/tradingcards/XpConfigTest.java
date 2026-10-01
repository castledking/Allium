package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.xp.XpConfig;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Reads the shipped xp block, so a curve or cooldown edited in the file cannot
 * drift away from what these assert.
 */
class XpConfigTest {

    private static XpConfig shipped() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = XpConfigTest.class
                .getResourceAsStream("/tradingcards/config.yml")) {
            assertTrue(in != null, "tradingcards/config.yml is not on the classpath");
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("could not read the shipped config", e);
        }
        return XpConfig.load(yaml).config();
    }

    @Test
    void theShippedCurveIsFlatEarlyAndSteepLate() {
        XpConfig config = shipped();
        // The whole point of the shape: the first levels arrive fast enough to
        // be interesting, the last are a project.
        assertTrue(config.xpForLevel(0) <= config.xpForLevel(40),
            "early levels should not cost more than mid levels");
        assertTrue(config.xpForLevel(90) > config.xpForLevel(10) * 10,
            "late levels should be dramatically dearer than early ones");
    }

    @Test
    void theCurveIsMonotonicAcrossEveryLevel() {
        XpConfig config = shipped();
        double previous = 0.0;
        for (int level = 0; level < 100; level++) {
            double cost = config.xpForLevel(level);
            assertTrue(cost >= previous,
                "level " + level + " costs " + cost + ", less than the previous " + previous);
            previous = cost;
        }
    }

    @Test
    void everyLevelHasAFiniteCostSoLevelsCannotStall() {
        XpConfig config = shipped();
        for (int level = 0; level < 100; level++) {
            assertTrue(config.xpForLevel(level) > 0.0,
                "level " + level + " costs nothing, so it would loop forever");
        }
    }

    @Test
    void theVanillaAndAlliumSourcesAreEnabledByDefault() {
        XpConfig config = shipped();
        // These four need no external plugin, so shipping them enabled means
        // a card levels from ordinary play on a fresh install.
        assertTrue(config.isEnabled("kill-mob"));
        assertTrue(config.isEnabled("breed-mob"));
        assertTrue(config.isEnabled("farm-crop"));
        assertTrue(config.isEnabled("advancement"));
    }

    @Test
    void thePluginBackedSourcesShipDisabled() {
        // They arrive with the phases that wire their plugins. Shipping them
        // enabled would mean a source that configures fine and never fires.
        XpConfig config = shipped();
        assertFalse(config.isEnabled("quest-complete"));
        assertFalse(config.isEnabled("fish-large"));
    }

    @Test
    void thereIsNoPlayerKillSourceAtAll() {
        // Deliberate: it is the easiest xp to farm with two accounts, and it
        // turns a collectible into a reason to start a fight. It comes back
        // scoped to a PvP arena, and only then.
        XpConfig config = shipped();
        assertFalse(config.sources().containsKey("kill-player"),
            "a player-kill xp source must not exist yet");
        assertTrue(config.sources().containsKey("kill-mob"),
            "killing MOBS is a different thing and stays");
    }

    @Test
    void breedingIsGatedPerMobType() {
        XpConfig config = shipped();
        XpConfig.Source breed = config.source("breed-mob");
        assertTrue(breed.perMobCooldown(),
            "breeding must be gated per mob type, not globally");
        assertEquals(7L * 86_400_000L, breed.perMobCooldownMillis(), 1e-9);
        assertEquals(0L, breed.cooldownMillis(),
            "a per-mob source must not also carry a plain cooldown, which would "
                + "re-introduce the farm the per-mob keying prevents");
    }

    @Test
    void theAdvancementSourceIsLargeEnoughToMatter() {
        // An advancement is a once-only, deliberate act, so it pays more than
        // a single mob kill. If it did not, nobody would notice it working.
        XpConfig config = shipped();
        assertTrue(config.source("advancement").xp() > config.source("kill-mob").xp() * 2,
            "an advancement should be worth several mob kills");
    }

    @Test
    void durationsParseFromTheHumanFormsOperatorsWrite() {
        assertEquals(7L * 86_400_000L, XpConfig.parseDuration("7d"));
        assertEquals(86_400_000L, XpConfig.parseDuration("1d"));
        assertEquals(3_600_000L, XpConfig.parseDuration("1h"));
        assertEquals(30L * 60_000L, XpConfig.parseDuration("30m"));
        assertEquals(5_000L, XpConfig.parseDuration("5s"));
        assertEquals(1_500L, XpConfig.parseDuration("1.5s"));
        assertEquals(30_000L, XpConfig.parseDuration("30"));
        assertEquals(0L, XpConfig.parseDuration("never"));
        assertEquals(0L, XpConfig.parseDuration(null));
        // A typo costs that one line rather than throwing during load.
        assertEquals(0L, XpConfig.parseDuration("soon"));
    }

    @Test
    void durationsFormatBackToSomethingReadable() {
        assertEquals("7d", XpConfig.formatDuration(7L * 86_400_000L));
        assertEquals("2h", XpConfig.formatDuration(2L * 3_600_000L));
        assertEquals("30m", XpConfig.formatDuration(30L * 60_000L));
        assertEquals("never", XpConfig.formatDuration(0L));
    }

    @Test
    void aSourceWithNothingToAwardIsDisabledRatherThanSilentlyDead() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            xp:
              sources:
                kill-mob:
                  enabled: true
                advancement:
                  enabled: true
                  xp: 0
            """);
        XpConfig.LoadResult result = XpConfig.load(yaml);
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().contains("advancement")),
            () -> "an enabled source that awards nothing should be reported: "
                + result.issues());
    }

    @Test
    void anUnknownSourceNameIsReportedRatherThanSilentlyNeverFiring() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            xp:
              sources:
                kill-monsters:
                  enabled: true
                  xp: 5
            """);
        XpConfig.LoadResult result = XpConfig.load(yaml);
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.message().contains("Unknown xp source")),
            () -> "a typo'd source name must be reported: " + result.issues());
    }

    @Test
    void aPartialFileKeepsTheDefaultsForWhatItOmits() throws Exception {
        // A partial edit should not silently disable sources the operator did
        // not mention.
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
            xp:
              sources:
                kill-mob:
                  enabled: false
            """);
        XpConfig config = XpConfig.load(yaml).config();
        assertFalse(config.isEnabled("kill-mob"));
        assertTrue(config.isEnabled("breed-mob"),
            "an unmentioned source should keep its default");
        assertTrue(config.isEnabled("advancement"));
    }

    @Test
    void sourcesWithCooldownsAreTheOnesThatMustSurviveARestart() {
        // A cooldown held only in memory is not a cooldown.
        XpConfig config = shipped();
        assertTrue(config.sourcesNeedingPersistence().contains("breed-mob"));
        assertFalse(config.sourcesNeedingPersistence().contains("kill-mob"),
            "a source with no cooldown needs nothing persisted");
    }

    @Test
    void levelForXpIsTheInverseOfTheCurve() {
        XpConfig config = shipped();
        // Enough xp for exactly three levels must land on level three.
        double three = config.xpForLevel(0) + config.xpForLevel(1) + config.xpForLevel(2);
        assertEquals(3, config.levelForXp(three));
        assertEquals(0, config.levelForXp(0));
        assertEquals(0, config.levelForXp(config.xpForLevel(0) - 1));
    }

    @Test
    void theDefaultSourceSetIsTheSameShapeAsTheShippedOne() {
        // Guards against a source being added to one and not the other.
        assertEquals(XpConfig.defaults().sources().keySet(), shipped().sources().keySet());
    }
}
