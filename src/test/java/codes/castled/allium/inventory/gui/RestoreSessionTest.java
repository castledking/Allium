package codes.castled.allium.inventory.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.inventory.InventorySnapshot;
import codes.castled.allium.util.ItemBuilder;
import codes.castled.allium.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RestoreSessionTest {
    @Test
    void editorCanClickOwnInventoryAndDragAcrossStorageButNotControls() {
        Player player = mock(Player.class);
        Inventory top = mock(Inventory.class);
        PluginStart plugin = mock(PluginStart.class);
        InventorySnapshot snapshot = mock(InventorySnapshot.class);
        when(player.hasPermission("allium.restore.edit")).thenReturn(true);
        when(snapshot.getPlayerId()).thenReturn(UUID.randomUUID());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(), eq(54), anyString())).thenReturn(top);
            RestoreOptionsGUI gui = new RestoreOptionsGUI(player, snapshot, null, plugin);
            InventoryClickEvent ownInventory = mock(InventoryClickEvent.class);
            when(ownInventory.getRawSlot()).thenReturn(54);
            assertFalse(gui.shouldCancelClick(ownInventory), "Editors must be able to pick up their own items");
            InventoryClickEvent hotbar = mock(InventoryClickEvent.class);
            when(hotbar.getRawSlot()).thenReturn(30);
            assertFalse(gui.shouldCancelClick(hotbar), "The entire saved storage, including hotbar, is editable");
            InventoryClickEvent outside = mock(InventoryClickEvent.class);
            when(outside.getRawSlot()).thenReturn(-999);
            assertTrue(gui.shouldCancelClick(outside), "Outside clicks are not saved content slots");

            InventoryDragEvent contentsDrag = mock(InventoryDragEvent.class);
            when(contentsDrag.getRawSlots()).thenReturn(Set.of(5, 28, 55));
            gui.handleDrag(contentsDrag);
            verify(contentsDrag, never()).setCancelled(true);
            InventoryDragEvent buttonDrag = mock(InventoryDragEvent.class);
            when(buttonDrag.getRawSlots()).thenReturn(Set.of(5, 46));
            gui.handleDrag(buttonDrag);
            verify(buttonDrag).setCancelled(true);
        }
    }

    @Test
    void rendersAllStorageAndSeparateOffhandAndReservesSecondToolbarSlotForManualCopy() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission("allium.restore.edit")).thenReturn(true);
        Inventory top = mock(Inventory.class);
        InventorySnapshot snapshot = mock(InventorySnapshot.class);
        ItemStack hotbar = mock(ItemStack.class);
        ItemStack lastStorage = mock(ItemStack.class);
        ItemStack offhand = mock(ItemStack.class);
        when(hotbar.clone()).thenReturn(hotbar);
        when(lastStorage.clone()).thenReturn(lastStorage);
        when(offhand.clone()).thenReturn(offhand);
        ItemStack[] storage = new ItemStack[41];
        storage[0] = hotbar;
        storage[35] = lastStorage;
        when(snapshot.getInventoryContents()).thenReturn(storage);
        when(snapshot.getArmorContents()).thenReturn(new ItemStack[4]);
        when(snapshot.getEnderChestContents()).thenReturn(new ItemStack[27]);
        when(snapshot.getOffhandItem()).thenReturn(offhand);
        when(snapshot.getLocation()).thenReturn(new Location(null, 1, 2, 3));
        when(snapshot.getPlayerId()).thenReturn(id);
        when(snapshot.getReason()).thenReturn("Death");
        try (var bukkit = mockStatic(Bukkit.class);
             var scheduler = mockStatic(SchedulerAdapter.class);
             var builders = mockConstruction(ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            bukkit.when(() -> Bukkit.createInventory(any(), eq(54), anyString())).thenReturn(top);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            scheduler.when(() -> SchedulerAdapter.runAsync(any())).thenAnswer(call -> {
                ((Runnable) call.getArgument(0)).run();
                return null;
            });
            scheduler.when(() -> SchedulerAdapter.runAtEntity(eq(player), any())).thenAnswer(call -> {
                ((Runnable) call.getArgument(1)).run();
                return null;
            });
            RestoreOptionsGUI gui = new RestoreOptionsGUI(player, snapshot, null, mock(PluginStart.class));
            gui.initialize();
            verify(top).setItem(27, hotbar);
            verify(top).setItem(26, lastStorage);
            verify(top).setItem(40, offhand);
            assertNotNull(gui.clickHandlers.get(46), "Second toolbar slot saves a new Manual copy");
        }
    }
}
