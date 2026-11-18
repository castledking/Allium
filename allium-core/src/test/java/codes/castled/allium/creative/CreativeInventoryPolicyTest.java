package codes.castled.allium.creative;

import codes.castled.allium.creative.CreativeInventoryPolicy.Group;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreativeInventoryPolicyTest {

    @Test
    void survivalAndAdventureShareAnInventoryAndSpectatorHasNone() {
        assertEquals(Optional.of(Group.SURVIVAL), CreativeInventoryPolicy.groupOf("SURVIVAL"));
        assertEquals(Optional.of(Group.SURVIVAL), CreativeInventoryPolicy.groupOf("adventure"));
        assertEquals(Optional.of(Group.CREATIVE), CreativeInventoryPolicy.groupOf("CREATIVE"));
        assertEquals(Optional.empty(), CreativeInventoryPolicy.groupOf("SPECTATOR"));
    }

    @Test
    void aSwapHappensOnlyWhenTheHeldInventoryBelongsToTheOtherGroup() {
        assertEquals(Optional.of(Group.CREATIVE), CreativeInventoryPolicy.swapTo(Group.SURVIVAL, "SURVIVAL", "CREATIVE"));
        assertEquals(Optional.of(Group.SURVIVAL), CreativeInventoryPolicy.swapTo(Group.CREATIVE, "CREATIVE", "ADVENTURE"));
        assertEquals(Optional.empty(), CreativeInventoryPolicy.swapTo(Group.SURVIVAL, "SURVIVAL", "ADVENTURE"));
        assertEquals(Optional.empty(), CreativeInventoryPolicy.swapTo(Group.SURVIVAL, "SURVIVAL", "SPECTATOR"),
                "spectator keeps the held inventory");
        // survival -> spectator -> creative swaps once, from the recorded group, not the previous mode
        assertEquals(Optional.of(Group.CREATIVE), CreativeInventoryPolicy.swapTo(Group.SURVIVAL, "SPECTATOR", "CREATIVE"));
        assertEquals(Optional.empty(), CreativeInventoryPolicy.swapTo(Group.CREATIVE, "SPECTATOR", "CREATIVE"));
    }

    @Test
    void anUnrecordedPlayerHoldsTheInventoryOfTheirCurrentMode() {
        assertEquals(Optional.of(Group.CREATIVE), CreativeInventoryPolicy.swapTo(null, "SURVIVAL", "CREATIVE"));
        assertEquals(Optional.empty(), CreativeInventoryPolicy.swapTo(null, "SPECTATOR", "CREATIVE"),
                "nothing is known about a spectator's inventory, so nothing moves");
        assertEquals(Optional.of(Group.CREATIVE), Group.parse("creative"));
        assertEquals(Optional.empty(), Group.parse("bogus"));
    }
}
