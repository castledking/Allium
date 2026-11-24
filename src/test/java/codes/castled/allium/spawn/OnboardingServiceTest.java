package codes.castled.allium.spawn;

import codes.castled.allium.PluginStart;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.moderation.DisciplineService;
import codes.castled.allium.rtp.RtpService;
import codes.castled.allium.rtp.RtpSettings;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.TeleportService;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OnboardingServiceTest {

    @TempDir Path dir;

    private PluginStart plugin;
    private Server server;
    private ConsoleCommandSender console;
    private TaskScheduler scheduler;
    private YamlConfiguration config;
    private World world;
    private Location spawnLocation;
    private TeleportService teleports;
    private KitService kits;
    private RtpService rtp;
    private DisciplineService discipline;
    private OnboardingRepository repository;
    private ArrayDeque<Runnable> ioQueue;
    private ExecutorService io;
    private OnboardingService service;

    @BeforeEach
    void setUp() throws Exception {
        ConnectionSource cs = () -> DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("onboarding.db"));
        repository = new OnboardingRepository(cs, SqlDialect.SQLITE, System::currentTimeMillis);
        repository.migrate();

        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        console = mock(ConsoleCommandSender.class);
        scheduler = mock(TaskScheduler.class);
        config = new YamlConfiguration();
        world = mock(World.class);
        spawnLocation = new Location(world, 0.5, 64.0, 0.5);
        teleports = mock(TeleportService.class);
        kits = mock(KitService.class);
        rtp = mock(RtpService.class);
        discipline = mock(DisciplineService.class);

        when(world.getName()).thenReturn("world");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getConsoleSender()).thenReturn(console);
        when(scheduler.isServerThread()).thenReturn(true);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return TaskHandle.NONE;
        });

        RtpSettings rtpSettings = RtpSettings.read(new YamlConfiguration());
        when(rtp.settings()).thenReturn(rtpSettings);
        when(teleports.defaultSpawn()).thenReturn(spawnLocation);
        when(teleports.ready(any())).thenReturn(true);
        doAnswer(inv -> {
            inv.<Consumer<Boolean>>getArgument(4).accept(true);
            return null;
        }).when(teleports).forceLocation(any(), any(), any(), eq("spawn"), any());

        when(kits.readyForOnboarding(any())).thenReturn(true);
        doAnswer(inv -> {
            inv.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(kits).grantOnboardingKit(any(), anyString(), any());

        ioQueue = new ArrayDeque<>();
        io = mock(ExecutorService.class);
        doAnswer(inv -> {
            ioQueue.addLast(inv.getArgument(0));
            return null;
        }).when(io).execute(any(Runnable.class));

        service = new OnboardingService(plugin, repository, teleports, io, kits, rtp, discipline);
    }

    private Player mockPlayer(UUID id, String name, boolean playedBefore) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getDisplayName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPlayedBefore()).thenReturn(playedBefore);
        when(player.getWorld()).thenReturn(world);
        when(server.getPlayer(id)).thenReturn(player);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(player));
        return player;
    }

    private void drainIo() {
        while (!ioQueue.isEmpty()) {
            ioQueue.removeFirst().run();
        }
    }

    @Test
    void optInOffDoesNothingOnJoin() throws Exception {
        UUID id = UUID.randomUUID();
        Player newcomer = mockPlayer(id, "Alex", false);
        service.onJoin(new PlayerJoinEvent(newcomer, "joined"));
        drainIo();

        assertTrue(repository.load(id).isEmpty());
        verifyNoInteractions(kits);
        verify(teleports, never()).forceLocation(any(), any(), any(), anyString(), any());
        verify(newcomer, never()).sendMessage(anyString());
    }

    @Test
    void firstJoinAnnouncesPlacesAndGrantsKitExactlyOnceAcrossRepeatJoins() throws Exception {
        config.set("join-and-quit-messages.first-join.enabled", true);
        config.set("join-and-quit-messages.first-join.format", "&dWelcome &f%name%&d!");
        config.set("teleport.first-join-spawn", true);
        config.set("kits.first-join-kit", "starter");

        UUID id = UUID.randomUUID();
        Player newcomer = mockPlayer(id, "Alex", false);

        // 1. Brand-new first join: announces, places at spawn, grants starter kit, marks complete.
        service.onJoin(new PlayerJoinEvent(newcomer, "joined"));
        drainIo();

        verify(console, times(1)).sendMessage(anyString());
        verify(teleports, times(1)).forceLocation(eq(console), eq(newcomer), any(Location.class), eq("spawn"), any());
        verify(kits, times(1)).grantOnboardingKit(eq(newcomer), eq("starter"), any());
        OnboardingRepository.State state = repository.load(id).orElseThrow();
        assertTrue(state.announced());
        assertTrue(state.placed());
        assertTrue(state.kitGranted());
        assertTrue(state.completed());

        // 2. Returning join (hasPlayedBefore = true): zero duplicate actions.
        Player returning = mockPlayer(id, "Alex", true);
        service.onJoin(new PlayerJoinEvent(returning, "joined"));
        drainIo();
        verify(console, times(1)).sendMessage(anyString());
        verify(teleports, times(1)).forceLocation(any(), any(), any(), eq("spawn"), any());
        verify(kits, times(1)).grantOnboardingKit(any(), anyString(), any());

        // 3. Even if playerdata was wiped (hasPlayedBefore = false again), durable ledger blocks duplicates.
        Player wiped = mockPlayer(id, "Alex", false);
        service.onJoin(new PlayerJoinEvent(wiped, "joined"));
        drainIo();
        verify(console, times(1)).sendMessage(anyString());
        verify(teleports, times(1)).forceLocation(any(), any(), any(), eq("spawn"), any());
        verify(kits, times(1)).grantOnboardingKit(any(), anyString(), any());
    }

    @Test
    void kitOutageOnFirstJoinResumesOnlyKitOnNextJoinWithoutDuplicatingAnnounceOrSpawn() throws Exception {
        config.set("join-and-quit-messages.first-join.enabled", true);
        config.set("join-and-quit-messages.first-join.format", "&dWelcome &f%name%&d!");
        config.set("teleport.first-join-spawn", true);
        config.set("kits.first-join-kit", "starter");

        AtomicBoolean kitHealthy = new AtomicBoolean(false);
        doAnswer(inv -> {
            inv.<Consumer<Boolean>>getArgument(2).accept(kitHealthy.get());
            return null;
        }).when(kits).grantOnboardingKit(any(), anyString(), any());

        UUID id = UUID.randomUUID();
        Player first = mockPlayer(id, "Sam", false);

        // 1. First join during kit outage: announce + placement succeed, kit fails and releases its claim.
        service.onJoin(new PlayerJoinEvent(first, "joined"));
        drainIo();

        verify(console, times(1)).sendMessage(anyString());
        verify(teleports, times(1)).forceLocation(eq(console), eq(first), any(Location.class), eq("spawn"), any());
        verify(kits, times(1)).grantOnboardingKit(eq(first), eq("starter"), any());
        OnboardingRepository.State afterOutage = repository.load(id).orElseThrow();
        assertTrue(afterOutage.announced());
        assertTrue(afterOutage.placed());
        assertFalse(afterOutage.kitGranted(), "failed kit claim must be released for retry");
        assertFalse(afterOutage.completed());

        // 2. Kit service recovers; player rejoins (hasPlayedBefore = true): ONLY the starter kit is retried!
        kitHealthy.set(true);
        Player second = mockPlayer(id, "Sam", true);
        service.onJoin(new PlayerJoinEvent(second, "joined"));
        drainIo();

        verify(console, times(1)).sendMessage(anyString());
        verify(teleports, times(1)).forceLocation(any(), any(), any(), eq("spawn"), any());
        verify(kits, times(2)).grantOnboardingKit(any(), eq("starter"), any());
        OnboardingRepository.State afterRecovery = repository.load(id).orElseThrow();
        assertTrue(afterRecovery.kitGranted());
        assertTrue(afterRecovery.completed());

        // 3. Third join does nothing at all.
        Player third = mockPlayer(id, "Sam", true);
        service.onJoin(new PlayerJoinEvent(third, "joined"));
        drainIo();
        verify(kits, times(2)).grantOnboardingKit(any(), eq("starter"), any());
    }

    @Test
    void databaseOutageOnFirstJoinFailsClosedWithoutUnrecordedSideEffects() {
        config.set("join-and-quit-messages.first-join.enabled", true);
        config.set("teleport.first-join-spawn", true);
        config.set("kits.first-join-kit", "starter");

        OnboardingRepository brokenRepo = new OnboardingRepository(
                () -> { throw new SQLException("storage offline"); },
                SqlDialect.SQLITE,
                System::currentTimeMillis
        );
        OnboardingService outageService = new OnboardingService(plugin, brokenRepo, teleports, io, kits, rtp, discipline);

        Player newcomer = mockPlayer(UUID.randomUUID(), "OfflineDb", false);
        outageService.onJoin(new PlayerJoinEvent(newcomer, "joined"));
        drainIo();

        verify(console, never()).sendMessage(anyString());
        verify(teleports, never()).forceLocation(any(), any(), any(), eq("spawn"), any());
        verify(kits, never()).grantOnboardingKit(any(), anyString(), any());
    }

    @Test
    void jailedFirstJoinSuppressesAllOnboardingSteps() throws Exception {
        config.set("join-and-quit-messages.first-join.enabled", true);
        config.set("teleport.first-join-spawn", true);
        config.set("kits.first-join-kit", "starter");

        UUID id = UUID.randomUUID();
        when(discipline.jailed(id)).thenReturn(true);
        Player prisoner = mockPlayer(id, "Prisoner", false);

        service.onJoin(new PlayerJoinEvent(prisoner, "joined"));
        drainIo();

        assertTrue(repository.load(id).isEmpty());
        verify(console, never()).sendMessage(anyString());
        verify(teleports, never()).forceLocation(any(), any(), any(), anyString(), any());
        verify(kits, never()).grantOnboardingKit(any(), anyString(), any());
    }

    @Test
    void returningPlayerWithSpawnOnJoinTeleportsWithoutFirstJoinAnnounceOrKit() {
        config.set("join-and-quit-messages.first-join.enabled", true);
        config.set("teleport.spawn-on-join", true);
        config.set("kits.first-join-kit", "starter");

        Player veteran = mockPlayer(UUID.randomUUID(), "Veteran", true);
        service.onJoin(new PlayerJoinEvent(veteran, "joined"));
        drainIo();

        verify(teleports, times(1)).forceLocation(eq(console), eq(veteran), any(Location.class), eq("spawn"), any());
        verify(console, never()).sendMessage(anyString());
        verify(kits, never()).grantOnboardingKit(any(), anyString(), any());
    }
}
