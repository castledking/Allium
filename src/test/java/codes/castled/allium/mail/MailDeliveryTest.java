package codes.castled.allium.mail;

import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.delivery.ItemGiveEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MailDeliveryTest {
    private final AtomicReference<ItemStack[]> storage = new AtomicReference<>();
    private Player player;
    private PlayerInventory inventory;
    private PluginManager events;
    private ItemDeliveryService service;
    @BeforeEach void setup() {
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        events = mock(PluginManager.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.isOnline()).thenReturn(true);
        when(inventory.getMaxStackSize()).thenReturn(64);
        when(inventory.getStorageContents()).thenAnswer(c -> storage.get());
        doAnswer(c -> { storage.set(c.getArgument(0)); return null; }).when(inventory).setStorageContents(any());
        service = new ItemDeliveryService(Runnable::run, () -> true, id -> player, events);
    }
    @Test void partialDeliveryJournalsTheExactRemnantAndDoesNotDrop() {
        storage.set(new ItemStack[]{new Stack(Material.DIAMOND, 60, "signature"), new Stack(Material.DIRT,64, "")});
        var plan = service.planMail(player, player, List.of(new Stack(Material.DIAMOND, 7, "signature")));
        assertNotNull(plan);
        assertEquals(4, plan.given());
        assertEquals(List.of(4), plan.delivered());
        assertEquals(3, plan.remaining().get(0).getAmount());
        assertEquals(60, storage.get()[0].getAmount(), "planning does not mutate storage");
        assertTrue(service.applyMail(player, plan));
        assertEquals(64, storage.get()[0].getAmount());
        assertEquals("signature", ((Stack) storage.get()[0]).data);
        verify(inventory, never()).addItem(any(ItemStack[].class));
    }
    @Test void aCancellationOrInventoryMutationInTheEventPreventsDelivery() {
        storage.set(new ItemStack[]{null});
        doAnswer(c -> { ((ItemGiveEvent)c.getArgument(0)).setCancelled(true); return null; }).when(events).callEvent(any());
        assertNull(service.planMail(player, player, List.of(new Stack(Material.DIAMOND, 7, "mac"))));
        assertNull(storage.get()[0]);
        doAnswer(c -> { storage.set(new ItemStack[]{new Stack(Material.DIRT, 1, "")}); return null; }).when(events).callEvent(any());
        assertNull(service.planMail(player, player, List.of(new Stack(Material.DIAMOND, 7, "mac"))));
        assertEquals(Material.DIRT, storage.get()[0].getType());
    }
    @Test void changingTheHeldInventoryOrDisconnectingRejectsAStalePlan() {
        storage.set(new ItemStack[]{null});
        var plan = service.planMail(player, player, List.of(new Stack(Material.DIAMOND, 7, "mac")));
        storage.set(new ItemStack[]{new Stack(Material.EMERALD, 3, "other")});
        assertFalse(service.applyMail(player, plan));
        assertEquals(Material.EMERALD, storage.get()[0].getType());
        storage.set(new ItemStack[]{null});
        when(player.isOnline()).thenReturn(false);
        assertFalse(service.applyMail(player, plan));
    }
    @Test void plansHaveDefensiveCopiesAndFullInventoriesHaveZeroDelivery() {
        storage.set(new ItemStack[]{new Stack(Material.DIRT, 64, "")});
        var full = service.planMail(player, player, List.of(new Stack(Material.DIAMOND, 7, "mac")));
        assertEquals(0, full.given());
        assertEquals(7, full.remaining().get(0).getAmount());
        full.remaining().get(0).setAmount(100);
        assertEquals(7, full.remaining().get(0).getAmount());
        full.before()[0].setAmount(1);
        assertEquals(64, full.before()[0].getAmount());
    }
    private static class Stack extends ItemStack {
        private final String data;
        private final Material material;
        private int count;
        Stack(Material material, int count, String data) { this.material = material; this.count = count; this.data = data; }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return count; }
        @Override public void setAmount(int amount) { count = amount; }
        @Override public int getMaxStackSize() { return 64; }
        @Override public Stack clone() { return new Stack(material,count,data); }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof Stack s && material == s.material && data.equals(s.data); }
        @Override public boolean equals(Object other) { return other instanceof Stack s && count == s.count && isSimilar(s); }
        @Override public int hashCode() { return java.util.Objects.hash(material,count,data); }
    }
}
