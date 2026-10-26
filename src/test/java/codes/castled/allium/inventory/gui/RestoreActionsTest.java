package codes.castled.allium.inventory.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.inventory.InventoryManager;
import codes.castled.allium.inventory.InventorySnapshot;
import codes.castled.allium.inventory.OfflineInventoryData;
import codes.castled.allium.inventory.OfflineInventoryManager;
import codes.castled.allium.listeners.gui.GUIListener;
import codes.castled.allium.util.ItemBuilder;
import codes.castled.allium.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestoreActionsTest {
    @Test
    void restoreAppliesRemovedItemsWithoutSavingAndManualButtonCreatesANewCopy() {
        try (Window window = new Window(false)) {
            window.contents[27] = null; // Vanilla has moved the saved hotbar item to the editor.
            window.click(45);
            var applied = ArgumentCaptor.forClass(OfflineInventoryData.class);
            verify(window.offline).applyToOnlinePlayer(eq(window.player), applied.capture());
            assertNull(applied.getValue().inventory()[0], "Restore uses the panel, not the original serialized row");
            verifyNoInteractions(window.manager);

            window.click(46);
            var saved = ArgumentCaptor.forClass(InventorySnapshot.class);
            verify(window.manager).saveSnapshot(saved.capture());
            assertEquals("MANUAL", saved.getValue().getReason());
            assertEquals(window.target, saved.getValue().getPlayerId());
            assertNull(saved.getValue().getInventoryContents()[0]);
            assertEquals("Death", window.source.getReason());
            assertNotNull(window.source.getInventoryContents()[0]);

            // A new viewer of the same saved row starts from the saved contents.
            new RestoreOptionsGUI(window.player, window.source, null, window.plugin).initialize();
            assertNotNull(window.contents[27]);
            verify(window.manager, times(1)).saveSnapshot(any(InventorySnapshot.class));
        }
    }

    @Test
    void offlineTargetRestoreQueuesEditedSnapshotAndToolbarKeepsManualInSecondSlot() {
        try (Window window = new Window(true)) {
            window.contents[27] = null;
            window.click(52);
            var queued = ArgumentCaptor.forClass(InventorySnapshot.class);
            verify(window.offline).queueOfflineSnapshotRestore(eq(window.target), eq("SavedPlayer"), queued.capture(),
                eq(true), eq(true), eq(true), eq(true));
            assertNull(queued.getValue().getInventoryContents()[0]);
            assertTrue(window.gui.clickHandlers.containsKey(46));
            verifyNoInteractions(window.manager);
        }
    }

    @Test
    void enderChestNavigationKeepsBothPanelsEditsAndManualSaveIncludesBoth() {
        try (Window window = new Window(false, true)) {
            window.contents[27] = null;
            window.click(51);
            assertNotNull(window.child);
            window.enderContents[0] = null;
            InventoryClickEvent back = mock(InventoryClickEvent.class);
            window.child.clickHandlers.get(35).accept(back);
            assertNull(window.contents[27], "Reopening the same parent must not redraw saved contents over edits");
            window.click(46);
            var saved = ArgumentCaptor.forClass(InventorySnapshot.class);
            verify(window.manager).saveSnapshot(saved.capture());
            assertNull(saved.getValue().getInventoryContents()[0]);
            assertNull(saved.getValue().getEnderChestContents()[0]);
        }
    }

    private static final class Window implements AutoCloseable {
        final PluginStart plugin = mock(PluginStart.class);
        final Player player = mock(Player.class);
        final UUID target;
        final InventorySnapshot source = mock(InventorySnapshot.class);
        final Inventory top = mock(Inventory.class);
        final Inventory enderTop = mock(Inventory.class);
        final InventoryManager manager = mock(InventoryManager.class);
        final OfflineInventoryManager offline = mock(OfflineInventoryManager.class);
        final ItemStack[] contents = new ItemStack[54];
        final ItemStack[] enderContents = new ItemStack[36];
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<SchedulerAdapter> scheduler = mockStatic(SchedulerAdapter.class);
        final MockedConstruction<ItemBuilder> builders = mockConstruction(ItemBuilder.class,
            withSettings().defaultAnswer(RETURNS_SELF));
        final RestoreOptionsGUI gui;
        EnderChestRestoreGUI child;

        private Window(boolean targetOffline) {
            this(targetOffline, false);
        }

        private Window(boolean targetOffline, boolean savedEnderItem) {
            UUID viewer = UUID.randomUUID();
            target = targetOffline ? UUID.randomUUID() : viewer;
            when(player.getUniqueId()).thenReturn(viewer);
            when(player.hasPermission("allium.restore.edit")).thenReturn(true);
            when(player.getName()).thenReturn("Editor");
            ItemStack item = mock(ItemStack.class);
            when(item.clone()).thenReturn(item);
            ItemStack[] saved = new ItemStack[41];
            saved[0] = item;
            when(source.getInventoryContents()).thenReturn(saved);
            when(source.getArmorContents()).thenReturn(new ItemStack[4]);
            ItemStack[] ender = new ItemStack[27];
            ender[0] = savedEnderItem ? item : null;
            when(source.getEnderChestContents()).thenReturn(ender);
            when(source.getPlayerId()).thenReturn(target);
            when(source.getReason()).thenReturn("Death");
            when(source.getLocation()).thenReturn(new Location(null, 1, 2, 3));
            when(source.getWorldName()).thenReturn("world");
            SnapshotEditorInteractionTest.backInventory(top, contents);
            SnapshotEditorInteractionTest.backInventory(enderTop, enderContents);
            bukkit.when(() -> Bukkit.createInventory(any(), eq(54), anyString())).thenReturn(top);
            bukkit.when(() -> Bukkit.createInventory(any(), eq(36), anyString())).thenAnswer(call -> {
                child = call.getArgument(0);
                return enderTop;
            });
            if (targetOffline) {
                OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);
                when(offlinePlayer.getName()).thenReturn("SavedPlayer");
                bukkit.when(() -> Bukkit.getOfflinePlayer(target)).thenReturn(offlinePlayer);
            } else {
                bukkit.when(() -> Bukkit.getPlayer(target)).thenReturn(player);
            }
            scheduler.when(() -> SchedulerAdapter.runAtEntity(any(), any())).thenAnswer(call -> {
                ((Runnable) call.getArgument(1)).run();
                return null;
            });
            when(plugin.getInventoryManager()).thenReturn(manager);
            when(plugin.getOfflineInventoryManager()).thenReturn(offline);
            when(manager.saveSnapshot(any(InventorySnapshot.class))).thenReturn(CompletableFuture.completedFuture(true));
            when(offline.queueOfflineSnapshotRestore(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
            gui = new RestoreOptionsGUI(player, source, null, plugin);
            gui.initialize();
            when(top.getHolder()).thenReturn(gui);
        }

        private void click(int raw) {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getRawSlot()).thenReturn(raw);
            when(event.getInventory()).thenReturn(top);
            new GUIListener().onInventoryClick(event);
            verify(event).setCancelled(true);
        }

        @Override public void close() {
            builders.close();
            scheduler.close();
            bukkit.close();
        }
    }
}
