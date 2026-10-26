package codes.castled.allium.inventory;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotEditSessionTest {
    @Test
    void sessionEditsKeepStorageArmorOffhandAndEnderChestIndependentOfSavedSource() {
        InventorySnapshot saved = mock(InventorySnapshot.class);
        ItemStack original = item(4);
        ItemStack originalOffhand = item(1);
        ItemStack[] storedInventory = new ItemStack[41];
        storedInventory[0] = original;
        when(saved.getInventoryContents()).thenReturn(storedInventory);
        when(saved.getArmorContents()).thenReturn(new ItemStack[4]);
        when(saved.getOffhandItem()).thenReturn(originalOffhand);
        when(saved.getEnderChestContents()).thenReturn(new ItemStack[27]);
        SnapshotEditSession session = new SnapshotEditSession(saved);
        Inventory panel = mock(Inventory.class);
        ItemStack replacement = item(9);
        ItemStack boots = item(1);
        ItemStack offhand = item(2);
        when(panel.getItem(27)).thenReturn(replacement);
        when(panel.getItem(36)).thenReturn(boots);
        when(panel.getItem(40)).thenReturn(offhand);
        session.captureInventory(panel);
        Inventory enderPanel = mock(Inventory.class);
        ItemStack enderItem = item(7);
        when(enderPanel.getItem(20)).thenReturn(enderItem);
        session.captureEnderChest(enderPanel);
        OfflineInventoryData edited = session.data();
        assertEquals(9, edited.inventory()[0].getAmount());
        assertEquals(1, edited.armor()[0].getAmount());
        assertEquals(2, edited.offhand().getAmount());
        assertEquals(7, edited.enderChest()[20].getAmount());
        assertEquals(4, storedInventory[0].getAmount());
        assertEquals(1, originalOffhand.getAmount());
        assertEquals(4, new SnapshotEditSession(saved).data().inventory()[0].getAmount(),
            "Reopening a saved snapshot creates a fresh working copy");
        verify(saved, never()).getInventoryData();
    }

    @Test
    void manualCopyGetsNewTimeAndReasonButPreservesTargetLocationAndExperience() {
        UUID target = UUID.randomUUID();
        InventorySnapshot saved = new InventorySnapshot(target, 1000, "Death",
            InventorySnapshot.toBase64(new ItemStack[41]),
            InventorySnapshot.toBase64(new ItemStack[4]),
            InventorySnapshot.toBase64(new ItemStack[1]),
            InventorySnapshot.toBase64(new ItemStack[27]), 77, new Location(null, 7, 8, 9), "saved-world");
        SnapshotEditSession session = new SnapshotEditSession(saved);
        InventorySnapshot restored = session.snapshot();
        InventorySnapshot manual = session.manualSnapshot();
        assertEquals(target, manual.getPlayerId());
        assertEquals("MANUAL", manual.getReason());
        assertTrue(manual.getTimestamp() > saved.getTimestamp());
        assertEquals(1000, restored.getTimestamp());
        assertEquals("Death", restored.getReason());
        assertEquals("saved-world", manual.getWorldName());
        assertEquals(77, manual.getExperience());
        assertEquals(saved.getLocation(), manual.getLocation());
        assertNotSame(saved.getLocation(), manual.getLocation());
        assertEquals(41, manual.getInventoryContents().length);
        assertEquals(4, manual.getArmorContents().length);
        assertEquals(27, manual.getEnderChestContents().length);
    }

    private static ItemStack item(int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(amount);
        when(item.clone()).thenAnswer(call -> item(amount));
        return item;
    }
}
