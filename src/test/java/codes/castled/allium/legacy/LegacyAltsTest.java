package codes.castled.allium.legacy;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyAltsTest {
    @TempDir
    Path folder;

    private LegacyAlts alts(LegacyMessageManager messages, OfflinePlayer resolved) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager plugins = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        LegacyAlts alts = new LegacyAlts(plugin, messages, name -> resolved);
        alts.config = YamlConfiguration::new;
        return alts;
    }

    @Test
    void theNewerAccountOnASharedIpIsTheAlt() {
        LegacyAlts alts = alts(mock(LegacyMessageManager.class), null);
        UUID main = UUID.randomUUID(), alt = UUID.randomUUID(), stranger = UUID.randomUUID();
        alts.record(main, "1.2.3.4", 1000);
        alts.record(alt, "1.2.3.4", 2000);
        alts.record(stranger, "5.6.7.8", 500);
        assertFalse(alts.isAlt(main, "1.2.3.4"));
        assertTrue(alts.isAlt(alt, "1.2.3.4"));
        assertFalse(alts.isAlt(stranger, "5.6.7.8"));
        assertEquals(Arrays.asList(main), alts.linked(alt, 10));
        assertEquals(Arrays.asList(alt), alts.linked(main, 10));
        assertTrue(alts.linked(stranger, 10).isEmpty());

        // A later join from the same IP keeps the first-seen time, so the order holds.
        alts.record(main, "1.2.3.4", 9000);
        assertFalse(alts.isAlt(main, "1.2.3.4"));
        assertTrue(alts.save());
        alts.load();
        assertTrue(alts.isAlt(alt, "1.2.3.4"));
    }

    @Test
    void exemptionsAreAddedCheckedAndRemoved() {
        LegacyMessageManager messages = mock(LegacyMessageManager.class);
        OfflinePlayer bob = mock(OfflinePlayer.class);
        when(bob.getUniqueId()).thenReturn(UUID.randomUUID());
        when(bob.getName()).thenReturn("Bob");
        LegacyAlts alts = alts(messages, bob);
        CommandSender staff = mock(CommandSender.class);
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(staff.getName()).thenReturn("Console");

        assertFalse(alts.command(staff, new String[]{"add"}), "missing arguments show the usage card");
        assertTrue(alts.command(staff, new String[]{"add", "Bob", "same", "household"}));
        verify(messages).send(eq(staff), eq("alts.exempt-added"), anyString(), any(String[].class));
        alts.load();
        assertEquals("same household", alts.exemptions.get(bob.getUniqueId()).reason);
        assertTrue(alts.command(staff, new String[]{"check", "Bob"}));
        verify(messages).send(eq(staff), eq("alts.exempt-status"), anyString(), any(String[].class));
        assertTrue(alts.command(staff, new String[]{"remove", "Bob"}));
        assertTrue(alts.command(staff, new String[]{"remove", "Bob"}));
        verify(messages).send(eq(staff), eq("alts.exempt-removed"), anyString(), any(String[].class));
        verify(messages).send(eq(staff), eq("alts.exempt-none"), anyString(), any(String[].class));
    }
}
