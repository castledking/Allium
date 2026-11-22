package codes.castled.allium.chat;
import codes.castled.allium.PluginStart;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatDecorationsTest {
    @Test void vaultDecorationsUsePrimaryGroupWithoutEvaluatingPlayerText(){
        var plugin=mock(PluginStart.class);var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);var peers=mock(PluginManager.class);when(server.getPluginManager()).thenReturn(peers);when(peers.isPluginEnabled("Vault")).thenReturn(true);
        var services=mock(ServicesManager.class);when(server.getServicesManager()).thenReturn(services);var chat=mock(Chat.class);var permissions=mock(Permission.class);when(services.load(Chat.class)).thenReturn(chat);when(services.load(Permission.class)).thenReturn(permissions);var player=mock(Player.class);
        when(chat.getPlayerPrefix(player)).thenReturn("&a[Member]");when(chat.getPlayerSuffix(player)).thenReturn("&7suffix");when(permissions.hasGroupSupport()).thenReturn(true);when(permissions.getPrimaryGroup(player)).thenReturn("member");
        var result=new ChatDecorations(plugin).query(player);assertEquals("&a[Member]",result.prefix());assertEquals("&7suffix",result.suffix());assertEquals("member",result.group());assertTrue(result.colors().isEmpty());
    }
    @Test void missingOptionalServicesLeavePlainSafeDefaults(){
        var plugin=mock(PluginStart.class);var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);var peers=mock(PluginManager.class);when(server.getPluginManager()).thenReturn(peers);
        assertEquals(ChatDecorations.Value.EMPTY,new ChatDecorations(plugin).query(mock(Player.class)));verify(server,never()).getServicesManager();
    }
}
