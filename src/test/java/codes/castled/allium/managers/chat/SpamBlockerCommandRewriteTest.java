package codes.castled.allium.managers.chat;

import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpamBlockerCommandRewriteTest {
    @TempDir Path dataFolder;

    // ItemTag dispatches the event's message as-is, and Paper's dispatchCommand doesn't strip a slash.
    @ParameterizedTest
    @CsvSource({"/say heyyyyyyyyyy, /say heyy", "say heyyyyyyyyyy, say heyy"})
    void normalizedCommandsKeepTheSlashConventionTheyArrivedWith(String message, String rewritten) throws Exception {
        Path modules = Files.createDirectories(dataFolder.resolve("chat/modules"));
        Files.writeString(dataFolder.resolve("chat/modules.yml"), "modules:\n  spam-blocker:\n    enabled: true\n");
        Files.writeString(modules.resolve("spam-blocker.yml"),
                "apply:\n  player-commands: true\nrepeat-collapse:\n  enabled: true\n");
        PluginStart plugin = mock(PluginStart.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(mock(Player.class));
        when(event.getMessage()).thenReturn(message);

        new SpamBlockerManager(plugin).onCommand(event);

        verify(event).setMessage(rewritten);
        verify(event, never()).setCancelled(true);
    }
}
