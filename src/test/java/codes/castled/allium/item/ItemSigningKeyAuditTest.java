package codes.castled.allium.item;

import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemSigningKeyAuditTest {
    @Test void unknownLoadedKeyIsLoudBoundedAndReadOnly() {
        var signer = ItemHmacSigner.randomEphemeral();
        List<String> logs = new ArrayList<>();
        var audit = new ItemSigningKeyAudit(signer, logs::add);
        var stack = mock(ItemStack.class);
        var meta = mock(ItemMeta.class);
        var pdc = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(Material.STONE);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING)).thenReturn("v2:unknown:invalid");
        audit.auditStack(stack);
        audit.auditStack(stack);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("CRITICAL HMAC KEY MISMATCH"));
        assertTrue(logs.get(0).contains("unknown"));
        verify(stack, never()).setItemMeta(any());
        verify(pdc, never()).set(any(), any(), any());
        when(pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING)).thenReturn("v2:k1:invalid");
        audit.auditStack(stack);
        assertEquals(1, logs.size());
    }

    @Test void lateInstallAuditsEachInventoryOnItsOwnRegion() {
        var audit = new ItemSigningKeyAudit(ItemHmacSigner.randomEphemeral(), ignored -> {});
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(TaskScheduler.class);
        var first = mock(Player.class);
        var second = mock(Player.class);
        var firstOwner = mock(ServerThread.class);
        var secondOwner = mock(ServerThread.class);
        var firstInventory = mock(PlayerInventory.class);
        var secondInventory = mock(PlayerInventory.class);
        for (Player player : List.of(first, second)) {
            when(player.isOnline()).thenReturn(true);
            var ender = mock(Inventory.class);
            when(ender.getContents()).thenReturn(new ItemStack[0]);
            when(player.getEnderChest()).thenReturn(ender);
        }
        when(first.getInventory()).thenReturn(firstInventory);
        when(second.getInventory()).thenReturn(secondInventory);
        when(firstInventory.getContents()).thenReturn(new ItemStack[0]);
        when(secondInventory.getContents()).thenReturn(new ItemStack[0]);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        doReturn(List.of(first, second)).when(server).getOnlinePlayers();
        var tick = org.mockito.ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);
        var firstScan = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        var secondScan = org.mockito.ArgumentCaptor.forClass(Runnable.class);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, first)).thenReturn(firstOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, second)).thenReturn(secondOwner);
            audit.install(plugin, scheduler);
            verify(scheduler).repeatOnServer(tick.capture(), eq(50L), eq(50L));
            assertTrue(tick.getValue().getAsBoolean());
            assertFalse(tick.getValue().getAsBoolean());
            verify(scheduler).runOn(eq(firstOwner), firstScan.capture(), any(), eq(0L));
            verify(scheduler).runOn(eq(secondOwner), secondScan.capture(), any(), eq(0L));
            verifyNoInteractions(firstInventory, secondInventory);
            firstScan.getValue().run();
            verify(firstInventory).getContents();
            verifyNoInteractions(secondInventory);
            secondScan.getValue().run();
            verify(secondInventory).getContents();
        }
    }
}
