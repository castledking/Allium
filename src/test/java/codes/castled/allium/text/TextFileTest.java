package codes.castled.allium.text;

import codes.castled.allium.PluginStart;
import codes.castled.allium.placeholder.AlliumPlaceholders;
import codes.castled.allium.util.AlliumLogger;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextFileTest {

    @TempDir
    Path dir;
    private PluginStart plugin;
    private Server server;
    private AlliumPlaceholders placeholders;
    private Permission permissions;
    private Chat chat;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        PluginManager plugins = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugins.isPluginEnabled("Vault")).thenReturn(true);
        Plugin vault = mock(Plugin.class);
        when(vault.getName()).thenReturn("Vault");
        when(vault.isEnabled()).thenReturn(true);
        Plugin off = mock(Plugin.class);
        when(off.getName()).thenReturn("Zed");
        when(plugins.getPlugins()).thenReturn(new Plugin[]{off, vault});
        ServicesManager services = mock(ServicesManager.class);
        when(server.getServicesManager()).thenReturn(services);
        permissions = mock(Permission.class);
        chat = mock(Chat.class);
        when(services.load(Permission.class)).thenReturn(permissions);
        when(services.load(Chat.class)).thenReturn(chat);
        when(permissions.hasGroupSupport()).thenReturn(true);
        when(server.getName()).thenReturn("Paper");
        when(server.getMaxPlayers()).thenReturn(20);
        when(server.getVersion()).thenReturn("git-Paper-196 (MC: 1.20.1)");
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[3]);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        World nether = mock(World.class);
        when(nether.getName()).thenReturn("world_nether");
        when(server.getWorlds()).thenReturn(List.of(world, nether));
        placeholders = mock(AlliumPlaceholders.class);
        when(plugin.getPlaceholders()).thenReturn(placeholders);
    }

    private Player player(String name, String display, String group) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getDisplayName()).thenReturn(display);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.canSee(any(Player.class))).thenReturn(true);
        when(permissions.getPrimaryGroup(player)).thenReturn(group);
        return player;
    }

    @Test
    void essentialsKeywordsFillForTheReaderInAnyCase() throws Exception {
        Player steve = player("Steve", "§bSteve", "admin");
        Player alex = player("Alex", "Alex", "default");
        doReturn(List.of(steve, alex)).when(server).getOnlinePlayers();
        World world = server.getWorlds().get(0);
        when(steve.getWorld()).thenReturn(world);
        when(steve.getLocation()).thenReturn(new Location(world, 10.5, 64, -3.2));
        when(steve.getAddress()).thenReturn(new InetSocketAddress(InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 7}), 50123));
        when(chat.getPlayerPrefix(steve)).thenReturn("&c[Admin] ");
        when(placeholders.request(any(), anyString())).thenReturn("");
        when(placeholders.request(steve, "balance_display")).thenReturn("$5.00");
        when(placeholders.request(steve, "mail_unread")).thenReturn("2");
        when(placeholders.request(steve, "playtime")).thenReturn("1h 5m");
        when(placeholders.request(steve, "world_time")).thenReturn("6:00 AM");
        when(placeholders.request(steve, "world_time_24")).thenReturn("06:00");
        when(placeholders.request(steve, "world_date")).thenReturn("Day 3");
        TextFile file = new TextFile(plugin, "info.txt");

        assertEquals("Steve Steve §bSteve §bSteve &c[Admin] admin| $5.00 2 1h 5m world world 10, 64, -4 6:00 AM 06:00 Day 3",
                file.fill(steve, "{PLAYER} {username} {DisplayName} {nickname} {prefix}{group}|{suffix} {balance} {mails} {playtime} "
                        + "{world} {worldname} {coords} {worldtime12} {worldtime24} {worlddate}"));
        assertEquals("2/20 3 world, world_nether Alex, Steve [Steve] [nobody] [] 203.0.113.7 203.0.113.7 Vault git-Paper-196 (MC: 1.20.1) Paper N/A",
                file.fill(steve, "{online}/{max} {unique} {worlds} {playerlist} [{playerlist:admin}] [{playerlist:mod:nobody}] "
                        + "[{playerlist:mod}] {ip} {address} {plugins} {version} {server} {tps}"));
        assertEquals(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date()), file.fill(steve, "{date}"));
        assertFalse(file.fill(steve, "{uptime}").isEmpty());
        assertEquals("{nope} {player:x} {\"text\":\"hi\"} { player}", file.fill(steve, "{nope} {player:x} {\"text\":\"hi\"} { player}"));
    }

    @Test
    void playerControlledValuesCannotOpenTagsAndTheConsoleGetsItsName() {
        Player bob = player("Bob", "<click:run_command:/op Bob>Bob", "default");
        when(placeholders.request(bob, "nickname")).thenReturn("<hover:show_text:'x'>Bobby");
        TextFile file = new TextFile(plugin, "info.txt");
        assertEquals("click:run_command:/op BobBob hover:show_text:'x'Bobby", file.fill(bob, "{displayname} {nickname}"));

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        assertEquals("CONSOLE|CONSOLE|CONSOLE|||||", file.fill(console, "{player}|{displayname}|{nickname}|{world}|{coords}|{balance}|{group}|{ip}"));
    }

    @Test
    void perPlayerAndGroupFileNamesAreLowerCaseAndSafe() {
        TextFile file = new TextFile(plugin, "info.txt");
        assertEquals("info_steve.txt", file.variant("Steve"));
        assertEquals("info_vip_.._x.txt".replace("..", "__"), file.variant("VIP/../x"));
        assertEquals("info_.bedrock".replace(".", "_") + ".txt", file.variant(".Bedrock"));
        assertNull(file.variant(" "));
        assertNull(file.variant(null));
    }
}
