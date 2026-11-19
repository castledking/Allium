package codes.castled.allium.placeholder;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.module.TravelModule;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.travel.TravelService;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlliumPlaceholdersTest {

    private final UUID steveId = UUID.randomUUID();
    private PluginStart plugin;
    private TaskScheduler scheduler;
    private Player steve;
    private World world;
    private TravelService travel;
    private AlliumPlaceholders placeholders;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        scheduler = mock(TaskScheduler.class);
        when(plugin.getScheduler()).thenReturn(scheduler);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(steveId);
        when(steve.getName()).thenReturn("Steve");
        when(steve.getPlayer()).thenReturn(steve);
        when(steve.getPing()).thenReturn(42);
        when(steve.getAllowFlight()).thenReturn(true);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getTime()).thenReturn(6_000L);
        when(world.getFullTime()).thenReturn(31L * 24_000 + 6_000);
        when(steve.getWorld()).thenReturn(world);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(steve));

        ModuleManager modules = mock(ModuleManager.class);
        when(plugin.getModuleManager()).thenReturn(modules);
        EconomyModule economyModule = mock(EconomyModule.class);
        EconomyRuntime economy = mock(EconomyRuntime.class);
        when(economyModule.isEnabled()).thenReturn(true);
        when(economyModule.runtime()).thenReturn(economy);
        UUID alexId = UUID.randomUUID();
        when(economy.placeholders()).thenReturn(new EconomyRuntime.PlaceholderSnapshot(
                Map.of(steveId, new BigDecimal("12345.60")),
                List.of(new EconomyRuntime.TopEntry(alexId, "&aAlex", new BigDecimal("2500000.00")),
                        new EconomyRuntime.TopEntry(steveId, "Steve", new BigDecimal("12345.60")))));
        when(economy.formatAmount(any(BigDecimal.class))).thenAnswer(inv -> "$" + PlaceholderFormats.commas(inv.getArgument(0)));
        TravelModule travelModule = mock(TravelModule.class);
        travel = mock(TravelService.class);
        when(travelModule.isEnabled()).thenReturn(true);
        when(travelModule.travel()).thenReturn(travel);
        when(travel.homeCount(steveId)).thenReturn(3);
        when(travel.limit(steve)).thenReturn(5);
        when(modules.getModule(anyString())).thenReturn(null);
        when(modules.getModule("economy")).thenAnswer(inv -> economyModule);
        when(modules.getModule("travel")).thenAnswer(inv -> travelModule);
        placeholders = new AlliumPlaceholders(plugin);
    }

    @Test
    void theServerThreadIsAnsweredLiveWithLegacyNamesAndFormats() {
        when(scheduler.isServerThread()).thenReturn(true);
        assertEquals("42", placeholders.request(steve, "ping"));
        assertEquals("yes", placeholders.request(steve, "fly"));
        assertEquals("", placeholders.request(steve, "god"), "a source that fails (no god-mode provider here) answers empty, never throws");
        assertEquals("Steve", placeholders.request(steve, "nickname"), "without chat, the player's name");
        assertEquals("12:00 PM", placeholders.request(steve, "world_time"));
        assertEquals("12:00", placeholders.request(steve, "world_time_24h"));
        assertEquals("Feb 1, 0", placeholders.request(steve, "world_date"));
        assertEquals("3", placeholders.request(steve, "homes_set"));
        assertEquals("5", placeholders.request(steve, "home_max"), "legacy's singular spelling works too");
        assertEquals("12345.60", placeholders.request(steve, "balance"));
        assertEquals("12,345.6", placeholders.request(steve, "balance_commas"));
        assertEquals("12.3k", placeholders.request(steve, "BALANCE_FORMATTED"));
        assertEquals("$12,345.6", placeholders.request(steve, "balance_display"));
        assertEquals("2", placeholders.request(steve, "baltop_rank"));
        assertEquals("2nd", placeholders.request(steve, "baltop_rank_formatted"));
        assertEquals("0", placeholders.request(steve, "mail_unread"), "without mail, zero");
        assertNull(placeholders.request(steve, "nonsense"), "PlaceholderAPI leaves unknown placeholders as written");
    }

    @Test
    void baltopEntriesComeFromTheEconomySnapshotOnAnyThread() {
        when(scheduler.isServerThread()).thenReturn(false);
        assertEquals("&aAlex", placeholders.request(null, "baltop_player_1"));
        assertEquals("Alex", placeholders.request(null, "baltop_player_stripped_1"));
        assertEquals("2500000.00", placeholders.request(null, "baltop_balance_1"));
        assertEquals("2.5M", placeholders.request(null, "baltop_balance_formatted_1"));
        assertEquals("12345", placeholders.request(null, "baltop_balance_fixed_2"));
        assertEquals("", placeholders.request(null, "baltop_player_3"), "past the end is empty");
        assertNull(placeholders.request(null, "baltop_player_x"));
        assertNull(placeholders.request(null, "baltop_balance_weird_1"));
    }

    @Test
    void otherThreadsGetTheSecondlySnapshotNeverLiveState() {
        when(scheduler.isServerThread()).thenReturn(false);
        assertEquals("", placeholders.request(steve, "ping"), "nothing until the first snapshot");
        placeholders.refreshSnapshots();
        when(steve.getPing()).thenReturn(99);
        assertEquals("42", placeholders.request(steve, "ping"));
        assertEquals("3", placeholders.request(steve, "homes_set"));
        when(scheduler.isServerThread()).thenReturn(true);
        placeholders.register("fly_time", p -> "10m 30s");
        assertEquals("10m 30s", placeholders.request(steve, "fly_time"), "a registered value replaces the built-in one");
        assertEquals("", placeholders.request(mock(org.bukkit.OfflinePlayer.class), "ping"), "offline players get nothing");
    }
}
