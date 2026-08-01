package codes.castled.allium.managers.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimatedTabNameWriteCoordinatorTest {

    @Test
    void tabOwnershipNeverInvokesTheCompetingBukkitWriter() {
        UUID playerId = UUID.randomUUID();
        List<String> tabFrames = new ArrayList<>();
        List<String> bukkitFrames = new ArrayList<>();
        AnimatedTabNameWriteCoordinator coordinator = new AnimatedTabNameWriteCoordinator((id, frame) -> {
            tabFrames.add(frame);
            return true;
        });

        for (String frame : List.of("frame-4", "frame-5", "frame-6")) {
            assertTrue(coordinator.write(playerId, frame, () -> bukkitFrames.add(frame)));
        }

        assertEquals(List.of("frame-4", "frame-5", "frame-6"), tabFrames);
        assertTrue(bukkitFrames.isEmpty());
        assertTrue(coordinator.isTabOwned(playerId));
    }

    @Test
    void unavailableTabWriterUsesBukkitFallback() {
        UUID playerId = UUID.randomUUID();
        List<String> bukkitFrames = new ArrayList<>();
        AnimatedTabNameWriteCoordinator coordinator = new AnimatedTabNameWriteCoordinator((id, frame) -> false);

        assertFalse(coordinator.write(playerId, "frame-5", () -> bukkitFrames.add("frame-5")));

        assertEquals(List.of("frame-5"), bukkitFrames);
        assertFalse(coordinator.isTabOwned(playerId));
    }

    @Test
    void inactivePlayersReleaseTabsTemporaryName() {
        UUID activePlayer = UUID.randomUUID();
        UUID inactivePlayer = UUID.randomUUID();
        List<String> calls = new ArrayList<>();
        AnimatedTabNameWriteCoordinator coordinator = new AnimatedTabNameWriteCoordinator((id, frame) -> {
            calls.add(id + "=" + frame);
            return true;
        });
        coordinator.write(activePlayer, "active", () -> {});
        coordinator.write(inactivePlayer, "inactive", () -> {});

        coordinator.releaseInactive(Set.of(activePlayer));

        assertTrue(coordinator.isTabOwned(activePlayer));
        assertFalse(coordinator.isTabOwned(inactivePlayer));
        assertEquals(inactivePlayer + "=null", calls.get(calls.size() - 1));
    }
}
