package codes.castled.allium.tfly;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.DB.Database;
import codes.castled.allium.util.SchedulerAdapter;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TFlyReloadTest {
    private final PluginStart plugin = mock(PluginStart.class);
    private final Database database = mock(Database.class);
    private final Player player = mock(Player.class);
    private final UUID id = UUID.randomUUID();
    private final Map<UUID, Database.PlayerTFlyData> saved = new HashMap<>();

    private void scenario(Runnable check) {
        when(plugin.getDatabase()).thenReturn(database);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("ReloadTest");
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getAllowFlight()).thenReturn(true);
        when(player.isFlying()).thenReturn(true);
        when(database.getPlayerTFlyStatus(any())).thenAnswer(i -> saved.get(i.getArgument(0)));
        doAnswer(i -> {
            saved.put(i.getArgument(0), new Database.PlayerTFlyData(i.getArgument(2), i.getArgument(3)));
            return null;
        }).when(database).savePlayerTFlyState(any(), anyString(), anyLong(), anyBoolean());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<SchedulerAdapter> scheduler = mockStatic(SchedulerAdapter.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            scheduler.when(() -> SchedulerAdapter.runTimer(any(), anyLong(), anyLong()))
                .thenReturn(mock(SchedulerAdapter.TaskHandle.class));
            check.run();
        }
    }

    @Test void connectedPlayersKeepTheirBalanceAndActiveToggleAfterReload() {
        scenario(() -> {
            TFlyManager outgoing = new TFlyManager(plugin);
            outgoing.setTFlyTime(id, 5432);
            outgoing.setTFlyEnabled(id, true);
            outgoing.savePlayerState(player);
            outgoing.stop();

            TFlyManager incoming = new TFlyManager(plugin);
            incoming.start(); // Same entry point used by Allium.onEnable; no join event.
            assertEquals(5432, incoming.getTFlyTime(id));
            assertTrue(incoming.isTFlyEnabled(id));
        });
    }

    @Test void repeatedReloadsDoNotOverwriteStoredTimeWithAnUninitializedZero() {
        scenario(() -> {
            saved.put(id, new Database.PlayerTFlyData(5432, false));
            for (int i = 0; i < 3; i++) {
                TFlyManager incoming = new TFlyManager(plugin);
                incoming.start();
                incoming.savePlayerState(player);
                incoming.stop();
            }
            assertEquals(5432, saved.get(id).timeSeconds());
        });
    }

    @Test void realReconnectKeepsTimeButRequiresEnablingFlightAgain() {
        scenario(() -> {
            saved.put(id, new Database.PlayerTFlyData(5432, true));
            TFlyManager manager = new TFlyManager(plugin);
            manager.loadPlayerState(player);
            assertEquals(5432, manager.getTFlyTime(id));
            assertFalse(manager.isTFlyEnabled(id));
        });
    }

    @Test void unloadThenLoadSavesBeforeDiscardingTheManager() {
        scenario(() -> {
            TFlyManager outgoing = new TFlyManager(plugin);
            outgoing.setTFlyTime(id, 1234);
            outgoing.setTFlyEnabled(id, true);
            outgoing.stop();
            TFlyManager incoming = new TFlyManager(plugin);
            incoming.start();
            assertEquals(1234, incoming.getTFlyTime(id));
            assertTrue(incoming.isTFlyEnabled(id));
        });
    }

    @Test void reloadDoesNotReenableFlightResetByAnotherPlugin() {
        scenario(() -> {
            saved.put(id, new Database.PlayerTFlyData(5432, true));
            when(player.getAllowFlight()).thenReturn(false);
            TFlyManager manager = new TFlyManager(plugin);
            manager.start();
            assertEquals(5432, manager.getTFlyTime(id));
            assertFalse(manager.isTFlyEnabled(id));
            verify(player, never()).setAllowFlight(anyBoolean());
            verify(player, never()).setFlying(anyBoolean());
        });
    }

    @Test void missingOrFailedLoadCannotSaveAnUninitializedZero() {
        scenario(() -> {
            TFlyManager manager = new TFlyManager(plugin);
            manager.start();
            manager.stop();
            verify(database, never()).savePlayerTFlyState(any(), anyString(), anyLong(), anyBoolean());
        });
    }

    @Test void restartingTheTimerDoesNotReplaceMoreRecentInMemoryTime() {
        scenario(() -> {
            saved.put(id, new Database.PlayerTFlyData(5432, true));
            TFlyManager manager = new TFlyManager(plugin);
            manager.start();
            manager.setTFlyTime(id, 5430);
            manager.start();
            assertEquals(5430, manager.getTFlyTime(id));
        });
    }
}
