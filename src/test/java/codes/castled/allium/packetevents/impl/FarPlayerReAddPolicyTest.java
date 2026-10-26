package codes.castled.allium.packetevents.impl;

import static com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

class FarPlayerReAddPolicyTest {

    @Test
    void extendedHorizonsFarPlayerReAddKeepsTabsNameAndOrder() {
        var trimmed = FarPlayerReAddPolicy.trim(EnumSet.of(
                ADD_PLAYER, UPDATE_GAME_MODE, UPDATE_LATENCY, UPDATE_DISPLAY_NAME, UPDATE_HAT, UPDATE_LIST_ORDER));

        assertEquals(EnumSet.of(ADD_PLAYER, UPDATE_GAME_MODE, UPDATE_LATENCY, UPDATE_HAT), trimmed);
    }

    @Test
    void serverJoinEntryIsLeftAlone() {
        assertNull(FarPlayerReAddPolicy.trim(EnumSet.of(
                ADD_PLAYER, INITIALIZE_CHAT, UPDATE_GAME_MODE, UPDATE_LISTED, UPDATE_LATENCY,
                UPDATE_DISPLAY_NAME, UPDATE_HAT, UPDATE_LIST_ORDER)));
    }

    @Test
    void listedReAddIsLeftAlone() {
        assertNull(FarPlayerReAddPolicy.trim(EnumSet.of(ADD_PLAYER, UPDATE_LISTED, UPDATE_DISPLAY_NAME)));
    }

    @Test
    void displayNameUpdatesWithoutAddAreLeftAlone() {
        assertNull(FarPlayerReAddPolicy.trim(EnumSet.of(UPDATE_DISPLAY_NAME)));
    }

    @Test
    void reAddWithNothingToDropIsLeftAlone() {
        assertNull(FarPlayerReAddPolicy.trim(EnumSet.of(ADD_PLAYER, UPDATE_GAME_MODE, UPDATE_LATENCY)));
    }
}
