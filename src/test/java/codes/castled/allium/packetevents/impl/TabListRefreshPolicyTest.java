package codes.castled.allium.packetevents.impl;

import org.junit.jupiter.api.Test;

import static codes.castled.allium.packetevents.impl.TabListRefreshPolicy.EntryOperation.ADD_ENTRY;
import static codes.castled.allium.packetevents.impl.TabListRefreshPolicy.EntryOperation.UPDATE_LISTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabListRefreshPolicyTest {

    @Test
    void existingAnimatedEntriesPreserveTabsAuthoritativeFrameWhenRelisting() {
        var plan = TabListRefreshPolicy.entryPlan(true, true);

        assertEquals(UPDATE_LISTED, plan.operation());
        assertFalse(plan.includeDisplayName());
    }

    @Test
    void existingStaticEntriesKeepTabsFormattingOwnership() {
        var plan = TabListRefreshPolicy.entryPlan(true, false);

        assertEquals(UPDATE_LISTED, plan.operation());
        assertFalse(plan.includeDisplayName());
    }

    @Test
    void missingAnimatedEntriesDoNotInstallATabCachedDisplayName() {
        var plan = TabListRefreshPolicy.entryPlan(false, true);

        assertEquals(ADD_ENTRY, plan.operation());
        assertFalse(plan.includeDisplayName());
    }

    @Test
    void missingStaticEntriesMayCarryTheirTabFormattingInTheAddPacket() {
        var plan = TabListRefreshPolicy.entryPlan(false, false);

        assertEquals(ADD_ENTRY, plan.operation());
        assertTrue(plan.includeDisplayName());
    }

    @Test
    void visibilityTransitionsScheduleOneBoundedRetry() {
        assertArrayEquals(new long[] { 1L }, TabListRefreshPolicy.retryDelays(false));
        assertArrayEquals(new long[] { 1L }, TabListRefreshPolicy.retryDelays(true));
    }
}
