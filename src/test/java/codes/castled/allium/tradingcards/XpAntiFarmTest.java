package codes.castled.allium.tradingcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.xp.XpAntiFarmStore;
import codes.castled.allium.tradingcards.xp.XpConfig;
import java.io.File;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The anti-farm gates, without a server.
 *
 * <p>These are the tests that matter most in this phase, because every one of
 * them describes a way xp becomes infinite if the gate is missing — and none
 * of them is visible in normal play. A cooldown that resets on reconnect and an
 * advancement that pays every time it fires both look completely correct in a
 * five-minute test session.
 */
class XpAntiFarmTest {

    @TempDir
    File dataFolder;

    private static final long WEEK = 7L * 86_400_000L;

    // ==================== advancements ====================

    @Test
    void anAdvancementPaysOnceAndOnlyOnce() {
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        assertTrue(store.claimAdvancement(player, "minecraft:story/root"));
        assertFalse(store.claimAdvancement(player, "minecraft:story/root"),
            "re-triggering an advancement's steps must not pay again");
    }

    @Test
    void differentAdvancementsEachPay() {
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        assertTrue(store.claimAdvancement(player, "minecraft:story/root"));
        assertTrue(store.claimAdvancement(player, "minecraft:story/mine_stone"),
            "a second, different advancement is a separate one-off");
    }

    @Test
    void advancementsAreTrackedPerPlayer() {
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(store.claimAdvancement(a, "x:y"));
        assertTrue(store.claimAdvancement(b, "x:y"),
            "one player completing it must not consume another's claim");
    }

    @Test
    void anAdvancementClaimSurvivesACleanRestart() {
        // The save here is what module disable does. Without persistence the
        // gate re-opens on every restart, which is the failure it exists to
        // prevent.
        UUID player = UUID.randomUUID();
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        store.claimAdvancement(player, "x:y");
        store.save();
        assertFalse(new XpAntiFarmStore(dataFolder).claimAdvancement(player, "x:y"));
    }

    @Test
    void anUnflushedClaimIsLostToACrash() {
        // Documented, not defended against: claims are batched and flushed on a
        // timer, so a hard crash can lose the last few seconds of gates. That
        // is the deliberate trade — saving on every mob kill would be a disk
        // write per kill — and it bounds the exploit to the crash window rather
        // than making every kill expensive forever.
        UUID player = UUID.randomUUID();
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        store.claimAdvancement(player, "x:y");
        assertTrue(new XpAntiFarmStore(dataFolder).claimAdvancement(player, "x:y"),
            "an unflushed claim is expected to be lost; see the save interval");
    }

    // ==================== cooldowns ====================

    @Test
    void aCooldownBlocksTheSecondClaimUntilItElapses() {
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        assertTrue(store.claimCooldown(player, "kill-mob", WEEK, 0L));
        assertFalse(store.claimCooldown(player, "kill-mob", WEEK, 1000L));
        assertTrue(store.claimCooldown(player, "kill-mob", WEEK, WEEK + 1L));
    }

    @Test
    void aZeroCooldownNeverBlocks() {
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            assertTrue(store.claimCooldown(player, "kill-mob", 0L, 0L));
        }
    }

    @Test
    void aCooldownIsTrackedPerSource() {
        // A crop cooldown must not gate mob kills.
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        assertTrue(store.claimCooldown(player, "farm-crop", WEEK, 0L));
        assertTrue(store.claimCooldown(player, "kill-mob", WEEK, 0L),
            "a different source is a different gate");
    }

    @Test
    void theRemainingCooldownIsReadableForAMessage() {
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        store.claimCooldown(player, "kill-mob", WEEK, 0L);
        assertEquals(WEEK, store.cooldownRemaining(player, "kill-mob", WEEK, 0L));
        assertEquals(WEEK / 2, store.cooldownRemaining(player, "kill-mob", WEEK, WEEK / 2));
        assertEquals(0L, store.cooldownRemaining(player, "kill-mob", WEEK, WEEK));
    }

    // ==================== per-mob cooldowns ====================

    @Test
    void aPerMobCooldownIsKeyedByMobType() {
        // "Once per mob type" is the rule: breeding a chicken must not put
        // cows on cooldown, nor the reverse.
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        assertTrue(store.claimMobCooldown(player, "chicken", WEEK, 0L));
        assertFalse(store.claimMobCooldown(player, "chicken", WEEK, 1000L));
        assertTrue(store.claimMobCooldown(player, "cow", WEEK, 1000L),
            "a different mob type is a different gate");
    }

    @Test
    void aPerMobCooldownIsCaseInsensitive() {
        // The store is keyed from an entity type name and from a lower-cased
        // config id; both must reach the same record.
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        assertTrue(store.claimMobCooldown(player, "MOOSHROOM", WEEK, 0L));
        assertFalse(store.claimMobCooldown(player, "mooshroom", WEEK, 0L));
    }

    @Test
    void aPerMobCooldownSurvivesACleanRestart() {
        UUID player = UUID.randomUUID();
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        store.claimMobCooldown(player, "chicken", WEEK, 0L);
        store.save();
        assertFalse(new XpAntiFarmStore(dataFolder).claimMobCooldown(player, "chicken", WEEK, 0L),
            "a breeding cooldown that resets on restart is not a cooldown");
    }

    // ==================== persistence ====================

    @Test
    void aSaveIsSkippedWhenNothingChanged() {
        // A burst of twenty advancements must not be twenty disk writes.
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        assertFalse(store.isDirty(), "a freshly loaded store has nothing to save");
        store.claimAdvancement(UUID.randomUUID(), "x:y");
        assertTrue(store.isDirty());
        store.save();
        assertFalse(store.isDirty());
    }

    @Test
    void everyGateIsReloadedTogether() {
        UUID player = UUID.randomUUID();
        XpAntiFarmStore first = new XpAntiFarmStore(dataFolder);
        first.claimAdvancement(player, "x:y");
        first.claimCooldown(player, "kill-mob", WEEK, 1234L);
        first.claimMobCooldown(player, "chicken", WEEK, 4321L);
        first.save();

        XpAntiFarmStore reloaded = new XpAntiFarmStore(dataFolder);
        assertFalse(reloaded.claimAdvancement(player, "x:y"));
        assertFalse(reloaded.claimCooldown(player, "kill-mob", WEEK, 2000L));
        assertFalse(reloaded.claimMobCooldown(player, "chicken", WEEK, 5000L));
    }

    @Test
    void aCorruptPlayerKeyIsSkippedWithoutFailingTheLoad() throws Exception {
        UUID player = UUID.randomUUID();
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        store.claimAdvancement(player, "x:y");
        store.save();

        java.nio.file.Path path = store.file().toPath();
        String contents = java.nio.file.Files.readString(path);
        java.nio.file.Files.writeString(path, contents + "\n  not-a-uuid:\n    - x:y\n");

        XpAntiFarmStore reloaded = new XpAntiFarmStore(dataFolder);
        assertFalse(reloaded.claimAdvancement(player, "x:y"),
            "the valid player's gate must still be closed after a corrupt row");
    }

    @Test
    void forgettingAPlayerClosesTheirFile() {
        XpAntiFarmStore store = new XpAntiFarmStore(dataFolder);
        UUID player = UUID.randomUUID();
        store.claimAdvancement(player, "x:y");
        store.claimCooldown(player, "kill-mob", WEEK, 0L);
        assertTrue(store.trackedPlayers() > 0);
        store.forget(player);
        assertEquals(0, store.trackedPlayers());
        assertTrue(store.claimAdvancement(player, "x:y"),
            "a forgotten player starts from a clean slate, as a data reset intends");
    }
}
