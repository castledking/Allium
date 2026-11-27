package codes.castled.allium.legacy;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyChequesTest {
    @TempDir
    Path folder;

    /** Note items as mocks: each remembers its cheque id; a copy carries the same id. */
    static final class FakeNotes implements LegacyCheques.Notes {
        final java.util.Map<ItemStack, String> ids = new java.util.IdentityHashMap<>();

        @Override
        public ItemStack create(String id, String value, String signer) {
            ItemStack item = mock(ItemStack.class);
            when(item.getAmount()).thenReturn(1);
            when(item.clone()).thenAnswer(inv -> {
                ItemStack copy = create(id, value, signer);
                return copy;
            });
            ids.put(item, id);
            return item;
        }

        @Override
        public String idOf(ItemStack item) {
            return ids.get(item);
        }
    }

    @Test
    void aFundedChequePaysOnceAndACopyOrForgeryPaysNothing() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        LegacyMessageManager messages = mock(LegacyMessageManager.class);
        var economy = new LegacyEconomyModule(plugin, null, messages);
        economy.enable();
        Player alice = player("Alice");
        assertTrue(economy.adjust(alice.getUniqueId(), "Alice", new BigDecimal("1000")));

        FakeNotes notes = new FakeNotes();
        LegacyCheques cheques = economy.cheques();
        cheques.notes = notes;
        assertTrue(cheques.command(alice, new String[]{"200"}));
        assertEquals(new BigDecimal("800.00"), economy.getBalance(alice.getUniqueId()));
        ArgumentCaptor<ItemStack> given = ArgumentCaptor.forClass(ItemStack.class);
        verify(alice.getInventory()).addItem(given.capture());
        ItemStack note = given.getValue();
        assertNotNull(notes.idOf(note));

        // The ledger, not the item, holds the amount: it survives a reload.
        economy.reload();
        ItemStack copy = note.clone();
        cheques.onInteract(new PlayerInteractEvent(alice, Action.RIGHT_CLICK_AIR, note, null, null));
        assertEquals(new BigDecimal("1000.00"), economy.getBalance(alice.getUniqueId()));
        cheques.onInteract(new PlayerInteractEvent(alice, Action.RIGHT_CLICK_AIR, copy, null, null));
        assertEquals(new BigDecimal("1000.00"), economy.getBalance(alice.getUniqueId()));
        verify(messages).send(eq(alice), eq("economy.cheque-spent"), anyString());

        ItemStack forged = cheques.note("0123456789abcdef0123456789abcdef", new BigDecimal("99999"), "Alice");
        cheques.onInteract(new PlayerInteractEvent(alice, Action.RIGHT_CLICK_AIR, forged, null, null));
        assertEquals(new BigDecimal("1000.00"), economy.getBalance(alice.getUniqueId()));
        verify(messages).send(eq(alice), eq("economy.cheque-invalid"), anyString());

        // Writing more than the balance is refused.
        assertTrue(cheques.command(alice, new String[]{"5000"}));
        verify(messages).send(eq(alice), eq("economy.cheque-funds"), anyString(), eq("{amount}"), anyString());
        assertEquals(new BigDecimal("1000.00"), economy.getBalance(alice.getUniqueId()));
    }

    @Test
    void theNoteIdLineRoundTripsAndRejectsOtherLines() {
        String id = "0123456789abcdef0123456789abcdef";
        assertEquals(id, LegacyCheques.revealed(LegacyCheques.hidden(id)));
        assertNull(LegacyCheques.revealed("§8#not-a-cheque-id-not-a-cheque-xx"));
        assertNull(LegacyCheques.revealed("§fSigner: §eAlice"));
    }

    @Test
    void adminChequesNeedThePermissionAndRollRanges() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        LegacyMessageManager messages = mock(LegacyMessageManager.class);
        var economy = new LegacyEconomyModule(plugin, null, messages);
        economy.enable();
        Player staff = player("Staff");
        Player bob = player("Bob");
        when(server.getPlayerExact("Bob")).thenReturn(bob);
        when(staff.canSee(bob)).thenReturn(true);

        economy.cheques().notes = new FakeNotes();
        when(staff.hasPermission("allium.cheque.others")).thenReturn(false);
        assertTrue(economy.cheques().command(staff, new String[]{"500", "Bob"}));
        verify(bob.getInventory(), never()).addItem(any(ItemStack.class));

        when(staff.hasPermission("allium.cheque.others")).thenReturn(true);
        assertTrue(economy.cheques().command(staff, new String[]{"500-1000", "Bob"}));
        ArgumentCaptor<ItemStack> given = ArgumentCaptor.forClass(ItemStack.class);
        verify(bob.getInventory()).addItem(given.capture());
        assertEquals(BigDecimal.ZERO.setScale(2), economy.getBalance(staff.getUniqueId()), "admin cheques cost the issuer nothing");
        economy.cheques().onInteract(new PlayerInteractEvent(bob, Action.RIGHT_CLICK_AIR, given.getValue(), null, null));
        BigDecimal paid = economy.getBalance(bob.getUniqueId());
        assertTrue(paid.compareTo(new BigDecimal("500")) >= 0 && paid.compareTo(new BigDecimal("1000")) <= 0, paid.toString());
        assertNull(LegacyCheques.rolled("1000-500"));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(anyString())).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[36]);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }
}
