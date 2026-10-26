package codes.castled.allium.listeners.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.DB.Database;
import codes.castled.allium.managers.UnknownCommandLangPack;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.permissions.command.DefaultCommandPermissionResolver;
import codes.castled.allium.permissions.command.PermissionResult;
import codes.castled.allium.permissions.command.ResolutionType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandManagerPreprocessTest {
    @TempDir Path dataFolder;

    // ItemTag command actions fire the event with the bare command line ("ce"), not "/ce".
    @ParameterizedTest
    @ValueSource(strings = {"/ce", "ce"})
    void checksTheCommandBeingRunWhetherOrNotTheMessageHasASlash(String message) throws Exception {
        Files.writeString(dataFolder.resolve("hide.yml"), "groups:\n  default:\n    whitelist: true\n    commands: [ce]\n");
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(mock(ServicesManager.class));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getDatabase()).thenReturn(mock(Database.class));
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        Player player = mock(Player.class);
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(message);
        PermissionResult allowed = new PermissionResult(true, ResolutionType.BUKKIT, "craftengine.command", mock(Command.class));
        try (var text = mockStatic(Text.class);
             var langPack = mockStatic(UnknownCommandLangPack.class);
             var resolvers = mockConstruction(DefaultCommandPermissionResolver.class,
                     (resolver, context) -> when(resolver.resolve(any(), any())).thenReturn(allowed))) {
            new CommandManager(plugin).onPlayerCommand(event);

            verify(event, never()).setCancelled(true);
            verify(resolvers.constructed().get(0)).resolve(player, "ce");
        }
    }
}
