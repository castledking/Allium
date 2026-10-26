package codes.castled.allium.inventory.gui;

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotEditorInteractionTest {
    @Test
    void repeatedShiftClicksMoveOneStackOnceAndMergeIntoOwnInventory() {
        try (Window window = new Window()) {
            window.topItems[0] = stack(Material.DIAMOND, 63);
            window.ownItems[0] = stack(Material.DIAMOND, 63);
            window.gui.handleClick(window.click(0, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            window.gui.handleClick(window.click(0, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            window.drain();
            assertNull(window.topItems[0]);
            assertEquals(64, window.ownItems[0].getAmount());
            assertEquals(62, window.ownItems[1].getAmount());
            assertNull(window.ownItems[2]);
        }
    }

    @Test
    void shiftFromOwnInventoryNeverFillsArmorOrToolbarAndKeepsRemainder() {
        try (Window window = new Window()) {
            for (int slot = 0; slot < 36; slot++) {
                window.topItems[slot] = stack(Material.STONE, 64);
            }
            window.ownItems[0] = stack(Material.DIRT, 12);
            window.gui.handleClick(window.click(54, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            window.drain();
            assertEquals(12, window.ownItems[0].getAmount());
            assertNull(window.topItems[36]);
            assertNull(window.topItems[46]);
            window.topItems[10] = null;
            window.gui.handleClick(window.click(54, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            window.drain();
            assertNull(window.ownItems[0]);
            assertEquals(Material.DIRT, window.topItems[10].getType());
            assertEquals(12, window.topItems[10].getAmount());
        }
    }

    @Test
    void doubleClickCollectsContentsAndOwnItemsButLeavesToolbarIntact() {
        try (Window window = new Window()) {
            ItemStack cursor = stack(Material.BOOK, 1);
            when(window.player.getItemOnCursor()).thenReturn(cursor);
            window.topItems[0] = stack(Material.BOOK, 3);
            window.topItems[46] = stack(Material.BOOK, 1);
            window.ownItems[0] = stack(Material.BOOK, 4);
            window.gui.handleClick(window.click(54, InventoryAction.COLLECT_TO_CURSOR));
            window.drain();
            var savedCursor = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
            verify(window.player).setItemOnCursor(savedCursor.capture());
            assertEquals(8, savedCursor.getValue().getAmount());
            assertEquals(1, window.topItems[46].getAmount());
            assertNull(window.topItems[0]);
            assertNull(window.ownItems[0]);
        }
    }

    @Test
    void closingBeforeQueuedTransferDoesNotMoveItems() {
        try (Window window = new Window()) {
            window.topItems[0] = stack(Material.DIAMOND, 8);
            window.gui.handleClick(window.click(0, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            when(window.view.getTopInventory()).thenReturn(mock(Inventory.class));
            window.drain();
            assertEquals(8, window.topItems[0].getAmount());
            assertNull(window.ownItems[0]);
        }
    }

    @Test
    void viewPermissionAloneNeverAllowsTakingContentsOrToolbarItems() {
        try (Window window = new Window()) {
            when(window.player.hasPermission("allium.restore.edit")).thenReturn(false);
            InventoryClickEvent content = window.click(0, InventoryAction.PICKUP_ALL);
            window.gui.handleClick(content);
            verify(content).setCancelled(true);
            InventoryClickEvent own = window.click(54, InventoryAction.PICKUP_ALL);
            window.gui.handleClick(own);
            verify(own).setCancelled(true);
            when(window.player.hasPermission("allium.restore.edit")).thenReturn(true);
            InventoryClickEvent toolbar = window.click(46, InventoryAction.HOTBAR_SWAP);
            window.gui.handleClick(toolbar);
            verify(toolbar).setCancelled(true);
        }
    }

    static ItemStack stack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        AtomicInteger count = new AtomicInteger(amount);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(call -> count.get());
        doAnswer(call -> { count.set(call.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        when(stack.getMaxStackSize()).thenReturn(64);
        when(stack.clone()).thenAnswer(call -> stack(material, count.get()));
        when(stack.isSimilar(any())).thenAnswer(call -> {
            ItemStack other = call.getArgument(0);
            return other != null && other.getType() == material;
        });
        return stack;
    }

    static void backInventory(Inventory inventory, ItemStack[] items) {
        when(inventory.getItem(anyInt())).thenAnswer(call -> items[(int) call.getArgument(0)]);
        doAnswer(call -> { items[(int) call.getArgument(0)] = call.getArgument(1); return null; })
            .when(inventory).setItem(anyInt(), any());
        when(inventory.getMaxStackSize()).thenReturn(64);
    }

    private static final class Editor extends SnapshotEditorGUI {
        private Editor(Player player, PluginStart plugin) {
            super(player, "Snapshot", 6, plugin);
        }
        @Override public void initialize() {}
        @Override protected int storageSlots() { return 36; }
        @Override protected boolean isSnapshotSlot(int rawSlot) { return rawSlot >= 0 && rawSlot <= 40; }
    }

    private static final class Window implements AutoCloseable {
        final Player player = mock(Player.class);
        final Inventory top = mock(Inventory.class);
        final PlayerInventory own = mock(PlayerInventory.class);
        final InventoryView view = mock(InventoryView.class);
        final ItemStack[] topItems = new ItemStack[54];
        final ItemStack[] ownItems = new ItemStack[36];
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<SchedulerAdapter> scheduler = mockStatic(SchedulerAdapter.class);
        final Editor gui;

        private Window() {
            backInventory(top, topItems);
            backInventory(own, ownItems);
            when(player.getInventory()).thenReturn(own);
            when(player.getOpenInventory()).thenReturn(view);
            when(view.getTopInventory()).thenReturn(top);
            when(player.hasPermission("allium.restore.edit")).thenReturn(true);
            bukkit.when(() -> Bukkit.createInventory(any(), eq(54), anyString())).thenReturn(top);
            scheduler.when(() -> SchedulerAdapter.runAtEntity(eq(player), any())).thenAnswer(call -> {
                tasks.addLast(call.getArgument(1));
                return null;
            });
            gui = new Editor(player, mock(PluginStart.class));
        }

        private InventoryClickEvent click(int raw, InventoryAction action) {
            InventoryClickEvent click = mock(InventoryClickEvent.class);
            when(click.getRawSlot()).thenReturn(raw);
            when(click.getSlot()).thenReturn(raw < 54 ? raw : raw - 54);
            when(click.getClickedInventory()).thenReturn(raw < 54 ? top : own);
            when(click.getAction()).thenReturn(action);
            return click;
        }

        private void drain() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }

        @Override public void close() {
            scheduler.close();
            bukkit.close();
        }
    }
}
