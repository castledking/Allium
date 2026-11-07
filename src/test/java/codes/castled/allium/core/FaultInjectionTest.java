package codes.castled.allium.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FaultInjectionTest {

    @Test
    void offByDefaultAndCannotBeArmed() {
        assertFalse(FaultInjection.enabled(), "the test JVM must not set -D" + FaultInjection.PROPERTY);
        assertThrows(IllegalStateException.class, () -> FaultInjection.arm(FaultInjection.ITEM_STATE_WRITE, 1));
        assertFalse(FaultInjection.trip(FaultInjection.ITEM_STATE_WRITE));
        assertEquals(0, FaultInjection.tripped(FaultInjection.ITEM_STATE_WRITE));
    }

    @Test
    void armedPointFailsExactlyItsCountThenPasses() {
        FaultInjection faults = new FaultInjection(true);
        assertFalse(faults.tripPoint("p"), "unarmed points pass");
        faults.armPoint("p", 2);
        assertTrue(faults.tripPoint("p"));
        assertFalse(faults.tripPoint("other"));
        assertTrue(faults.tripPoint("p"));
        assertFalse(faults.tripPoint("p"));
        assertEquals(2, faults.trippedCount("p"));
        assertEquals(0, faults.armedCount("p"));
        faults.armPoint("p", 1);
        assertEquals(0, faults.trippedCount("p"), "arming resets the trip count");
        faults.resetAll();
        assertFalse(faults.tripPoint("p"));
    }
}
