package codes.castled.allium.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.DB.Database;
import codes.castled.allium.managers.lang.Lang;
import codes.castled.allium.util.SchedulerAdapter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class TeleportToggleReloadTest {
    @Test void commandPersistsToggleAndTheNextManagerReadsItBeforeToggling() {
        PluginStart plugin = mock(PluginStart.class);
        Database database = mock(Database.class);
        Lang lang = mock(Lang.class);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDatabase()).thenReturn(database);
        when(plugin.getLangManager()).thenReturn(lang);
        when(lang.get(anyString())).thenReturn("");
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission("allium.tptoggle")).thenReturn(true);
        AtomicBoolean stored = new AtomicBoolean(false);
        when(database.getTeleportToggleState(id)).thenAnswer(i -> stored.get());
        doAnswer(i -> { stored.set(i.getArgument(1)); return null; })
            .when(database).saveTeleportToggleState(eq(id), anyBoolean());
        try (var scheduler = mockStatic(SchedulerAdapter.class)) {
            TP first = new TP(plugin);
            assertTrue(first.onCommand(player, mock(Command.class), "tptoggle", new String[0]));
            assertTrue(stored.get(), "The command must save its preference before a reload");
            TP reloaded = new TP(plugin);
            assertTrue(reloaded.onCommand(player, mock(Command.class), "tptoggle", new String[0]));
            assertFalse(stored.get(), "First click after reload must enable requests rather than disable them again");
        }
    }
}
