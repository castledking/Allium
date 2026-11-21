package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerTimeAndWeatherCommandTest {

    private PluginStart plugin;
    private Server server;
    private World world;
    private PlayerTimeCommand ptime;
    private PlayerWeatherCommand pweather;
    private List<Player> online;
    private List<String> sent;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        world = mock(World.class);
        online = new ArrayList<>();
        sent = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(world.getTime()).thenReturn(5000L);
        when(server.getOnlinePlayers()).thenAnswer(inv -> new ArrayList<>(online));
        when(server.getPlayer(anyString())).thenAnswer(inv -> {
            String query = inv.getArgument(0);
            return online.stream().filter(p -> p.getName().equalsIgnoreCase(query)).findFirst().orElse(null);
        });

        LanguageManager lang = mock(LanguageManager.class);
        for (Messages m : Messages.values()) {
            when(lang.get(m)).thenReturn(m.getDefault());
            when(lang.get(m.getKey(), m.getDefault())).thenReturn(m.getDefault());
        }
        MessageManager messages = new MessageManager(plugin, lang);
        when(plugin.getMessageManager()).thenReturn(messages);

        ptime = new PlayerTimeCommand(plugin);
        pweather = new PlayerWeatherCommand(plugin);
    }

    @AfterEach
    void tearDown() {
        PlayerNameVisibility.setVanishHooks(null, null);
    }

    private Player mockPlayer(String name, Set<String> perms) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.getWorld()).thenReturn(world);
        when(p.isOnline()).thenReturn(true);
        when(p.canSee(org.mockito.ArgumentMatchers.any(Player.class))).thenReturn(true);
        when(p.hasPermission(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
        when(p.getPlayerTime()).thenReturn(5000L);
        when(p.isPlayerTimeRelative()).thenReturn(true);
        when(p.getPlayerTimeOffset()).thenReturn(0L);
        when(p.getPlayerWeather()).thenReturn(null);
        doAnswer(inv -> {
            sent.add(name + ":" + inv.getArgument(0));
            return null;
        }).when(p).sendMessage(anyString());
        online.add(p);
        return p;
    }

    @Test
    void ptimeSpecHasPlayertimeAliasAndNeverPt() {
        assertEquals(List.of("playertime"), CoreCommandSpecs.PTIME.aliases());
        assertFalse(CoreCommandSpecs.PTIME.aliases().contains("pt"));
        assertEquals(List.of("playerweather"), CoreCommandSpecs.PWEATHER.aliases());
    }

    @Test
    void ptimeSetsRelativeAndFixedTimesAndResets() {
        Player alice = mockPlayer("Alice", Set.of("allium.ptime"));

        // Relative 4pm -> 10000 ticks; world time is 5000 -> offset 5000, relative = true
        ptime.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PTIME, alice, "ptime", new String[]{"4pm"}));
        verify(alice).setPlayerTime(5000L, true);

        // Fixed @4000ticks -> 4000 ticks, relative = false
        ptime.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PTIME, alice, "ptime", new String[]{"@4000ticks"}));
        verify(alice).setPlayerTime(4000L, false);

        // Fixed @17:30 -> 11500 ticks, relative = false
        ptime.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PTIME, alice, "ptime", new String[]{"@17:30"}));
        verify(alice).setPlayerTime(11500L, false);

        // Reset
        ptime.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PTIME, alice, "ptime", new String[]{"reset"}));
        verify(alice).resetPlayerTime();
    }

    @Test
    void ptimeWildcardAndOthersPermissionAndVanishRespect() {
        Player staff = mockPlayer("Staff", Set.of("allium.ptime", "allium.ptime.others"));
        Player bob = mockPlayer("Bob", Set.of());
        Player hidden = mockPlayer("Ghost", Set.of());
        Player plain = mockPlayer("Plain", Set.of("allium.ptime"));
        PlayerNameVisibility.setVanishHooks(p -> p.equals(hidden), null);

        // Plain player without .others cannot target Bob
        ptime.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PTIME, plain, "ptime", new String[]{"day", "Bob"}));
        verify(bob, never()).setPlayerTime(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());

        // Staff with .others targeting * updates visible players (Staff, Bob, Plain) and skips vanished Ghost
        ptime.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PTIME, staff, "ptime", new String[]{"@night", "*"}));
        verify(staff).setPlayerTime(13000L, false);
        verify(bob).setPlayerTime(13000L, false);
        verify(plain).setPlayerTime(13000L, false);
        verify(hidden, never()).setPlayerTime(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void ptimeQueryShowsNormalRelativeAndFixedStatesAndCompletes() {
        Player alice = mockPlayer("Alice", Set.of("allium.ptime", "allium.ptime.others"));
        Player bob = mockPlayer("Bob", Set.of());
        when(bob.isPlayerTimeRelative()).thenReturn(false);
        when(bob.getPlayerTime()).thenReturn(13000L);

        ptime.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PTIME, alice, "ptime", new String[]{"list"}));
        assertTrue(sent.stream().anyMatch(s -> s.contains("Alice") && s.contains("synced")));
        assertTrue(sent.stream().anyMatch(s -> s.contains("Bob") && s.contains("fixed")));

        List<String> firstCompletions = ptime.complete(alice, new String[]{"@n"});
        assertEquals(List.of("@noon", "@night"), firstCompletions);
        List<String> secondCompletions = ptime.complete(alice, new String[]{"day", ""});
        assertTrue(secondCompletions.contains("*"));
        assertTrue(secondCompletions.contains("Bob"));
    }

    @Test
    void pweatherSetsClearsResetsListsAndRespectsOthersPermission() {
        Player staff = mockPlayer("Staff", Set.of("allium.pweather", "allium.pweather.others"));
        Player bob = mockPlayer("Bob", Set.of("allium.pweather"));

        // Self sun/storm/reset
        pweather.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PWEATHER, bob, "pweather", new String[]{"storm"}));
        verify(bob).setPlayerWeather(WeatherType.DOWNFALL);

        pweather.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PWEATHER, bob, "pweather", new String[]{"sun"}));
        verify(bob).setPlayerWeather(WeatherType.CLEAR);

        pweather.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PWEATHER, bob, "pweather", new String[]{"reset"}));
        verify(bob).resetPlayerWeather();

        // Bob without .others cannot target Staff
        pweather.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PWEATHER, bob, "pweather", new String[]{"sun", "Staff"}));
        verify(staff, never()).setPlayerWeather(org.mockito.ArgumentMatchers.any());

        // Console can target *
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        pweather.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PWEATHER, console, "pweather", new String[]{"sun", "*"}));
        verify(staff).setPlayerWeather(WeatherType.CLEAR);

        // Query shows clear/storm/normal
        when(bob.getPlayerWeather()).thenReturn(WeatherType.DOWNFALL);
        sent.clear();
        pweather.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.PWEATHER, staff, "pweather", new String[]{"list"}));
        assertTrue(sent.stream().anyMatch(s -> s.contains("Bob") && s.contains("storm")));
        assertTrue(sent.stream().anyMatch(s -> s.contains("Staff") && s.contains("synced")));

        // Tab completions
        assertTrue(pweather.complete(staff, new String[]{"s"}).containsAll(List.of("sun", "storm")));
        assertTrue(pweather.complete(staff, new String[]{"sun", "*"}).contains("*"));
        assertTrue(pweather.complete(bob, new String[]{"sun", ""}).isEmpty());
    }
}
