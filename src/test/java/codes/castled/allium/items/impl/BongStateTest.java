package codes.castled.allium.items.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The bong state machine.
 *
 * <p>The ids asserted here are the ones the Nexo config actually defines — one
 * per water state, plus a loaded variant per remaining hit. That list is the
 * contract between {@code build_bongs.py} and this class: if the config ever
 * renames an item, this test is where it should fail first.
 */
class BongStateTest {

    private static final String[] BARE = {
            "clean_bong", "slightly_dirty_bong", "dirty_bong", "filthy_bong",
    };

    @Test
    void everyBongIdParsesBackToItself() {
        for (int dirt = 0; dirt < BARE.length; dirt++) {
            final BongItem.State bare = BongItem.State.parse(BARE[dirt]);
            assertEquals(dirt, bare.dirt());
            assertFalse(bare.hasBowl(), BARE[dirt] + " has no bowl piece");
            assertEquals(0, bare.hits());
            assertEquals(BARE[dirt], bare.id());

            for (int hits = 0; hits <= BongItem.MAX_HITS; hits++) {
                final String id = BARE[dirt] + "_" + hits + "-3";
                final BongItem.State withBowl = BongItem.State.parse(id);
                assertEquals(dirt, withBowl.dirt(), id + " keeps its water");
                assertEquals(hits, withBowl.hits(), id + " keeps its hits");
                assertTrue(withBowl.hasBowl(), id + " carries a bowl piece");
                assertEquals(hits > 0, withBowl.loaded(), id + " loads only with weed left");
                assertEquals(id, withBowl.id());
            }
        }
    }

    @Test
    void threeHitsWalkTheWaterFromCleanToFilthyAndEmptyTheBowlPiece() {
        BongItem.State state = BongItem.State.parse("clean_bong_3-3");

        state = state.afterHit();
        assertEquals("slightly_dirty_bong_2-3", state.id());

        state = state.afterHit();
        assertEquals("dirty_bong_1-3", state.id());

        state = state.afterHit();
        assertEquals("filthy_bong_0-3", state.id());
        assertFalse(state.loaded(), "the last hit spends the last of the weed");
        assertTrue(state.hasBowl(), "but the bowl piece stays on the bong");
        assertTrue(state.removable(), "and an empty piece can be taken off");
        assertTrue(state.dirty(), "and the water is as dirty as it gets");

        // A spent piece has no hits left to give, so a further hit changes nothing.
        assertEquals("filthy_bong_0-3", state.afterHit().id());
    }

    @Test
    void aSpongeOnlyCleansTheWater() {
        assertEquals("clean_bong_2-3",
                BongItem.State.parse("filthy_bong_2-3").scrubbed().id());
        assertEquals("clean_bong",
                BongItem.State.parse("dirty_bong").scrubbed().id());
        // The bowl piece rides through a scrub, spent or not.
        assertEquals("clean_bong_0-3",
                BongItem.State.parse("filthy_bong_0-3").scrubbed().id());
        assertEquals("clean_bong_1-3",
                BongItem.State.parse("clean_bong_1-3").scrubbed().id());
        assertEquals(0, BongItem.State.parse("clean_bong").afterHit().hits());
    }

    @Test
    void aBowlPieceComesOffUnsmokedOrSpentOnly() {
        assertTrue(BongItem.State.parse("clean_bong_3-3").removable());
        assertTrue(BongItem.State.parse("dirty_bong_0-3").removable());
        assertFalse(BongItem.State.parse("dirty_bong_2-3").removable());
        assertFalse(BongItem.State.parse("filthy_bong_1-3").removable());
        assertFalse(BongItem.State.parse("clean_bong").removable(), "a bare bong has nothing on it");
    }

    /**
     * Every removal route gates on {@code hasBowl()}, not {@code loaded()}: a
     * spent piece is gone as far as weed goes but is still screwed to the bong
     * and still has to come off.
     */
    @Test
    void aSpentPieceIsStillAPieceOnTheBong() {
        for (final String id : new String[]{"clean_bong_3-3", "dirty_bong_2-3", "filthy_bong_0-3"}) {
            assertTrue(BongItem.State.parse(id).hasBowl(), id + " carries a piece");
        }
        assertFalse(BongItem.State.parse("clean_bong").hasBowl());
        assertFalse(BongItem.State.parse("filthy_bong").hasBowl());
    }

    @Test
    void aFullPieceLoadsOntoABareOrSpentBongOnly() {
        assertTrue(BongItem.State.parse("clean_bong").loadable());
        assertTrue(BongItem.State.parse("filthy_bong").loadable());
        assertTrue(BongItem.State.parse("filthy_bong_0-3").loadable(), "an empty piece is replaced");
        assertFalse(BongItem.State.parse("clean_bong_3-3").loadable());
        assertFalse(BongItem.State.parse("dirty_bong_1-3").loadable(), "a live one is left alone");

        // Loading keeps the water and resets the hits.
        assertEquals("filthy_bong_3-3", BongItem.State.parse("filthy_bong_0-3").filled().id());
        assertEquals("clean_bong_3-3", BongItem.State.parse("clean_bong").filled().id());
        // Taking the piece off leaves the same bong, bare.
        assertEquals("dirty_bong", BongItem.State.parse("dirty_bong_0-3").emptied().id());
    }

    @Test
    void thingsThatAreNotBongsAreNotBongs() {
        assertNull(BongItem.State.parse("clean_empty_bong"));
        assertNull(BongItem.State.parse("full_bowl_piece"));
        assertNull(BongItem.State.parse("bowl_piece"));
        assertNull(BongItem.State.parse("joint"));
        assertNull(BongItem.State.parse("clean_bong_4-3"));
        assertNull(BongItem.State.parse("clean_bong_-1-3"));
        assertNull(BongItem.State.parse("mud_bong"));
        assertNull(BongItem.State.parse(null));
    }
}