package codes.castled.allium.legacy;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyChatHistoryTest {
    @Test
    void deletingAMessageReSyncsOnlyThoseWhoSawItWithoutIt() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        LegacyConfigManager config = mock(LegacyConfigManager.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("chat.deletion_resend.clear_lines", 100);
        yaml.set("chat.replay-packets-per-tick", 256);
        when(config.raw()).thenReturn(yaml);
        LegacyMessageManager messages = mock(LegacyMessageManager.class);
        LegacyChatHistory history = new LegacyChatHistory(plugin, config, messages);

        Player staff = player("Staff", true), alice = player("Alice", false), bob = player("Bob", false);
        when(server.getPlayer(alice.getUniqueId())).thenReturn(alice);
        when(server.getPlayer(staff.getUniqueId())).thenReturn(staff);
        String first = history.nextId(), second = history.nextId(), whisper = history.nextId();
        history.deliver(first, alice.getUniqueId(), "<Alice> hello", Arrays.asList(staff, alice), 1000);
        history.deliver(second, alice.getUniqueId(), "<Alice> bad word", Arrays.asList(staff, alice), 2000);
        history.deliver(whisper, staff.getUniqueId(), "<Staff> local", Collections.singletonList(staff), 3000);

        assertTrue(history.command(staff, new String[]{second}));
        verify(messages).send(eq(staff), eq("chat.delete.success"), anyString());
        ArgumentCaptor<Runnable> flush = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), flush.capture(), anyLong(), anyLong());
        clearInvocations(alice, staff);
        flush.getValue().run();

        // Alice: 100 blank lines, then only the surviving message.
        verify(alice, times(101)).sendMessage(anyString());
        verify(alice).sendMessage("<Alice> hello");
        verify(alice, never()).sendMessage("<Alice> bad word");
        assertEquals(Arrays.asList(first), history.visibleIds(alice.getUniqueId()));
        assertEquals(Arrays.asList(first, whisper), history.visibleIds(staff.getUniqueId()));
        // Bob never saw it and is left alone.
        verify(bob, never()).sendMessage(anyString());

        assertTrue(history.command(staff, new String[]{second}));
        verify(messages).send(eq(staff), eq("chat.delete.not-found"), anyString());
        assertTrue(history.command(alice, new String[]{first}));
        verify(messages).send(eq(alice), eq("error.no-permission"), anyString());
    }

    private static Player player(String name, boolean staff) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(staff);
        return player;
    }
}
