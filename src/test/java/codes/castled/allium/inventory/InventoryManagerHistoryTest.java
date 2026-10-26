package codes.castled.allium.inventory;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.DB.Database;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryManagerHistoryTest {
    @Test
    void savesAfterAnEmptyLoadAreVisibleAndFailedLoadsCanBeRetried() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Database database = mock(Database.class);
        UUID playerId = UUID.randomUUID();
        when(plugin.getDatabase()).thenReturn(database);
        when(database.getPlayerInventorySnapshots(playerId))
            .thenThrow(new IllegalStateException("database unavailable"))
            .thenReturn(List.of());
        InventoryManager manager = new InventoryManager(plugin);
        try {
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> manager.getPlayerSnapshots(playerId, 0).get(5, TimeUnit.SECONDS));
            assertEquals(0, manager.getTotalPages(playerId).get(5, TimeUnit.SECONDS));
            InventorySnapshot manual = new InventorySnapshot(playerId, 2000, "MANUAL", "", "", "", "", 5,
                new Location(null, 1, 2, 3), "world");
            when(database.saveInventorySnapshot(manual)).thenReturn(true);
            assertTrue(manager.saveSnapshot(manual).get(5, TimeUnit.SECONDS));
            assertSame(manual, manager.getLatestSnapshot(playerId).get(5, TimeUnit.SECONDS));
            assertEquals(1, manager.getTotalPages(playerId).get(5, TimeUnit.SECONDS));
            verify(database, times(2)).getPlayerInventorySnapshots(playerId);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void aQueuedLoadAndSaveKeepOldHistoryAndManualCopyExactlyOnce() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Database database = mock(Database.class);
        when(plugin.getDatabase()).thenReturn(database);
        UUID playerId = UUID.randomUUID();
        InventorySnapshot old = new InventorySnapshot(playerId, 1000, "Death", "", "", "", "", 5,
            new Location(null, 1, 2, 3), "world");
        InventorySnapshot manual = new InventorySnapshot(playerId, 2000, "MANUAL", "", "", "", "", 5,
            new Location(null, 1, 2, 3), "world");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(database.getPlayerInventorySnapshots(playerId)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(old);
        });
        when(database.saveInventorySnapshot(manual)).thenReturn(true);
        InventoryManager manager = new InventoryManager(plugin);
        try {
            var loading = manager.getPlayerSnapshots(playerId, 0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var alsoLoading = manager.getTotalPages(playerId);
            var saving = manager.saveSnapshot(manual);
            release.countDown();
            loading.get(5, TimeUnit.SECONDS);
            assertEquals(1, alsoLoading.get(5, TimeUnit.SECONDS));
            assertTrue(saving.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(manual, old), manager.getPlayerSnapshots(playerId, 0).get(5, TimeUnit.SECONDS));
            verify(database).getPlayerInventorySnapshots(playerId);
        } finally {
            release.countDown();
            manager.shutdown();
        }
    }

    @Test
    void loginSnapshotBeforeFirstReadDoesNotHideHistorySavedBeforeStartup() throws Exception {
        UUID playerId = UUID.randomUUID();
        PluginStart plugin = mock(PluginStart.class);
        Database database = mock(Database.class);
        when(plugin.getDatabase()).thenReturn(database);
        List<InventorySnapshot> stored = new ArrayList<>();
        for (int i = 0; i < 46; i++) {
            stored.add(new InventorySnapshot(playerId, 1000 - i, "AUTOSAVE", "", "", "", "", 0,
                new Location(null, 1, 2, 3), "world"));
        }
        when(database.saveInventorySnapshot(any())).thenAnswer(call -> {
            stored.add(0, call.getArgument(0));
            return true;
        });
        when(database.getPlayerInventorySnapshots(playerId)).thenAnswer(call -> new ArrayList<>(stored));

        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Inventory ender = mock(Inventory.class);
        World world = mock(World.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(ender);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 1, 2, 3));
        when(world.getName()).thenReturn("world");
        when(inventory.getContents()).thenReturn(new ItemStack[41]);
        when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);
        when(ender.getContents()).thenReturn(new ItemStack[27]);

        InventoryManager startup = new InventoryManager(plugin);
        try {
            assertTrue(startup.saveSnapshot(player, "Login").get(5, TimeUnit.SECONDS));
            assertEquals(2, startup.getTotalPages(playerId).get(5, TimeUnit.SECONDS),
                "A new login must not turn an incomplete cache into the whole history");
            assertEquals(45, startup.getPlayerSnapshots(playerId, 0).get(5, TimeUnit.SECONDS).size());
            assertEquals(2, startup.getPlayerSnapshots(playerId, 1).get(5, TimeUnit.SECONDS).size());
        } finally {
            startup.shutdown();
        }
        InventoryManager reload = new InventoryManager(plugin);
        try {
            assertEquals(2, reload.getTotalPages(playerId).get(5, TimeUnit.SECONDS));
        } finally {
            reload.shutdown();
        }
    }
}
