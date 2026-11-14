package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.messaging.IgnoreService;
import codes.castled.allium.messaging.MessagePolicy;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeleportServiceTest {
    private final Map<UUID, Player> online = new HashMap<>();
    private final Deque<Runnable> worker = new ArrayDeque<>();
    private final Deque<Runnable> later = new ArrayDeque<>();
    private final YamlConfiguration config = new YamlConfiguration();
    private PluginStart plugin;
    private Server server;
    private TeleportRepository repository;
    private ThreadPoolExecutor io;
    private TaskScheduler scheduler;
    private DestinationSafety safety;
    private PreferencesModule preferences;
    private ModuleManager modules;
    private MessagingModule messagingModule;
    private IgnoreService ignores;
    private World world;
    private Player mover;
    private Player target;
    private Player staff;
    private TeleportService service;
    private BooleanSupplier tick;
    private boolean rejecting;
    private boolean disabled;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        repository = mock(TeleportRepository.class);
        io = mock(ThreadPoolExecutor.class);
        scheduler = mock(TaskScheduler.class);
        safety = mock(DestinationSafety.class);
        modules = mock(ModuleManager.class);
        preferences = mock(PreferencesModule.class);
        messagingModule = mock(MessagingModule.class);
        MessagingService messaging = mock(MessagingService.class);
        ignores = mock(IgnoreService.class);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        when(server.getWorld(world.getUID())).thenReturn(world);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getPlayer(any(UUID.class))).thenAnswer(call -> online.get(call.getArgument(0)));
        when(server.getOnlinePlayers()).thenAnswer(call -> List.copyOf(online.values()));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(mock(MessageManager.class));
        when(plugin.getModuleManager()).thenReturn(modules);
        when(modules.getModule("preferences")).thenReturn(preferences);
        when(modules.getModule("messaging")).thenReturn(messagingModule);
        when(preferences.isEnabled()).thenReturn(true);
        when(preferences.effective(any(UUID.class)))
                .thenReturn(Optional.of(new EffectivePreferences(true, true, true, 1, false)));
        when(preferences.acceptance(any(UUID.class), eq(PreferenceKey.ACCEPT_TELEPORT_REQUESTS)))
                .thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
        when(messagingModule.isEnabled()).thenReturn(true);
        when(messagingModule.messaging()).thenReturn(messaging);
        when(messagingModule.ignores()).thenReturn(ignores);
        when(messaging.visibleTo(any(), any(Player.class))).thenReturn(true);
        when(ignores.ignores(any(UUID.class), any(UUID.class))).thenReturn(MessagePolicy.Ignore.NO);
        when(repository.load(any(UUID.class), anyString())).thenReturn(Optional.empty());
        when(repository.loadCooldowns(any(UUID.class))).thenReturn(Map.of());
        when(safety.resolve(any(Location.class), any(TeleportSettings.class)))
                .thenAnswer(call -> Optional.of(((Location) call.getArgument(0)).clone()));
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return TaskHandle.NONE;
        });
        when(scheduler.runOnServerLater(any(Runnable.class), anyLong())).thenAnswer(call -> {
            later.addLast(call.getArgument(0));
            return TaskHandle.NONE;
        });
        when(scheduler.repeatOnServer(any(BooleanSupplier.class), anyLong(), anyLong())).thenAnswer(call -> {
            tick = call.getArgument(0);
            return mock(TaskHandle.class);
        });
        doAnswer(call -> {
            if (rejecting) throw new RejectedExecutionException("test worker full");
            worker.addLast(call.getArgument(0));
            return null;
        }).when(io).execute(any(Runnable.class));
        when(io.awaitTermination(anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
            drainWorker();
            return true;
        });
        when(io.isTerminated()).thenReturn(true);
        config.set("teleport.warmup-seconds", 0);
        config.set("teleport.cooldowns.home", 600);
        mover = player("Mover", UUID.randomUUID());
        target = player("Target", UUID.randomUUID());
        staff = player("Staff", UUID.randomUUID());
    }

    @Test
    void vanishedRequesterLooksOfflineOnAcceptDenyAndCancelInvisibleRequests() throws Exception {
        enable();
        when(target.hasPermission("allium.tpaccept")).thenReturn(true);
        when(target.hasPermission("allium.tpdeny")).thenReturn(true);
        when(mover.hasPermission("allium.tpa")).thenReturn(true);

        // 1. Mover requests TP to target while visible
        service.request(mover, target, RequestTracker.Kind.TO_TARGET);
        assertEquals(1, service.pendingRequests());

        // Mover vanishes from target -> accept reports TELEPORT_NO_PENDING (not PLAYER_NOT_FOUND to mover)
        MessagingService messaging = messagingModule.messaging();
        when(messaging.visibleTo(target, mover)).thenReturn(false);
        service.accept(target, null);
        verify(plugin.getMessageManager()).send(target, codes.castled.allium.message.Messages.TELEPORT_NO_PENDING);
        assertEquals(0, service.pendingRequests());

        // 2. Same for deny when requester becomes invisible
        when(messaging.visibleTo(target, mover)).thenReturn(true);
        service.request(mover, target, RequestTracker.Kind.TO_TARGET);
        when(messaging.visibleTo(target, mover)).thenReturn(false);
        service.deny(target, null);
        verify(plugin.getMessageManager(), times(2)).send(target, codes.castled.allium.message.Messages.TELEPORT_NO_PENDING);
        assertEquals(0, service.pendingRequests());

        // 3. cancelInvisibleRequests clears normal-player pending requests without messaging, keeping staff observer request
        when(messaging.visibleTo(target, mover)).thenReturn(true);
        service.request(mover, target, RequestTracker.Kind.TO_TARGET);
        when(staff.hasPermission("allium.tpa")).thenReturn(true);
        service.request(staff, target, RequestTracker.Kind.TO_TARGET);
        assertEquals(2, service.pendingRequests());
        when(messaging.visibleTo(mover, target)).thenReturn(false);
        when(messaging.visibleTo(staff, target)).thenReturn(true);
        service.cancelInvisibleRequests(target);
        assertEquals(1, service.pendingRequests());
    }

    @AfterEach
    void close() throws Exception {
        if (service != null && !disabled) {
            // A failed assertion must not make fixture cleanup repeat a simulated SQL outage.
            doNothing().when(repository).saveHistory(any(UUID.class), any(SavedLocation.class), anyMap());
            rejecting = false;
            service.disable();
        }
    }

    @Test
    void successfulTeleportPersistsTheOriginAndCooldownAfterBukkitSuccess() throws Exception {
        enable();
        Location origin = mover.getLocation();
        long before = System.currentTimeMillis();

        assertTrue(service.teleport(mover, destination(), "home"));

        verify(mover).teleport(eq(destination()), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(repository, never()).saveHistory(any(), any(), anyMap());
        assertTrue(service.historyPending(mover.getUniqueId()));
        assertFalse(service.ready(mover.getUniqueId()));
        drainWorker();

        ArgumentCaptor<SavedLocation> saved = ArgumentCaptor.forClass(SavedLocation.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Long>> cooldowns = ArgumentCaptor.forClass(Map.class);
        verify(repository).saveHistory(eq(mover.getUniqueId()), saved.capture(), cooldowns.capture());
        assertEquals(snapshot(origin), saved.getValue());
        assertTrue(cooldowns.getValue().get("home") >= before + 600_000);
        assertFalse(service.historyPending(mover.getUniqueId()));
        assertTrue(service.ready(mover.getUniqueId()));
        assertFalse(service.teleport(mover, destination(), "home"), "success starts the cooldown");
    }

    @Test
    void cancelledBukkitTeleportWritesNeitherHistoryNorCooldownAndPreservesBack() throws Exception {
        Location previousBack = new Location(world, -20, 70, 4);
        SavedLocation savedBack = snapshot(previousBack);
        when(repository.load(mover.getUniqueId(), "back")).thenReturn(Optional.of(savedBack));
        enable();
        when(mover.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN)))
                .thenReturn(false, true, true);

        assertTrue(service.teleport(mover, destination(), "home"));
        drainWorker();

        verify(repository, never()).saveHistory(any(), any(), anyMap());
        assertFalse(service.historyPending(mover.getUniqueId()));
        assertTrue(service.ready(mover.getUniqueId()));
        service.back(mover);
        verify(mover).teleport(eq(previousBack), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        drainWorker();
        assertTrue(service.teleport(mover, destination(), "home"), "a cancelled home must not start its cooldown");
        verify(mover, times(2)).teleport(eq(destination()), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    @Test
    void acceptRechecksRecipientPolicyInsteadOfTrustingTheOriginalRequest() throws Exception {
        enable();
        service.request(mover, target, RequestTracker.Kind.TO_TARGET);
        assertEquals(1, service.pendingRequests());
        when(preferences.acceptance(target.getUniqueId(), PreferenceKey.ACCEPT_TELEPORT_REQUESTS))
                .thenReturn(PreferenceSessions.Acceptance.REFUSES);

        service.accept(target, mover);
        drainWorker();

        assertEquals(0, service.pendingRequests());
        verify(mover, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(repository, never()).saveHistory(any(), any(), anyMap());
    }

    @Test
    void separateIssuingStaffQuitCancelsTheMoversWarmup() throws Exception {
        warmup();
        enable();
        service.force(staff, mover, target, "tp");
        assertTrue(service.warming(mover.getUniqueId()));
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(staff);

        service.quit(quit);
        assertFalse(service.warming(mover.getUniqueId()));
        tickAfterWarmup();
        drainWorker();

        verify(mover, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(repository, never()).saveHistory(any(), any(), anyMap());
    }

    @Test
    void rejectedHistoryRemainsABarrierAcrossReconnectUntilItIsSaved() throws Exception {
        enable();
        clearInvocations(repository);
        rejecting = true;
        assertTrue(service.teleport(mover, destination(), "home"));
        assertTrue(service.historyPending(mover.getUniqueId()));
        assertFalse(service.ready(mover.getUniqueId()));
        Player reconnect = player("Mover", mover.getUniqueId());
        join(reconnect);

        assertTrue(service.historyPending(mover.getUniqueId()));
        assertFalse(service.ready(reconnect.getUniqueId()));
        assertFalse(service.teleport(reconnect, destination(), "spawn"));
        verify(repository, never()).load(eq(mover.getUniqueId()), eq("back"));
        verify(repository, never()).loadCooldowns(mover.getUniqueId());

        rejecting = false;
        join(reconnect);
        assertEquals(1, worker.size(), "retry history before submitting a SQL load");
        drainWorker();
        assertFalse(service.historyPending(reconnect.getUniqueId()));
        assertFalse(service.ready(reconnect.getUniqueId()), "the new session still needs its load");
        assertFalse(later.isEmpty());
        later.removeFirst().run();
        drainWorker();
        assertTrue(service.ready(reconnect.getUniqueId()));
        var order = inOrder(repository);
        order.verify(repository).saveHistory(eq(reconnect.getUniqueId()), any(), anyMap());
        order.verify(repository).load(reconnect.getUniqueId(), "back");
        order.verify(repository).loadCooldowns(reconnect.getUniqueId());
    }

    private Player player(String name, UUID id) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenAnswer(call -> !((String) call.getArgument(0)).contains(".bypass.")
                && !"allium.vanish.see".equals(call.getArgument(0)));
        when(player.canSee(any(Player.class))).thenReturn(true);
        when(player.getMetadata(anyString())).thenReturn(List.of());
        when(player.getLocation()).thenAnswer(call -> new Location(world, 1, 64, 2, 35, -10));
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        online.put(id, player);
        return player;
    }

    private void enable() {
        service = new TeleportService(plugin, repository, io, safety);
        service.enable();
        drainWorker();
        assertTrue(service.ready(mover.getUniqueId()));
        assertTrue(service.ready(target.getUniqueId()));
        assertTrue(service.ready(staff.getUniqueId()));
    }

    private void warmup() {
        config.set("teleport.warmup-seconds", 0.001);
    }

    private void tickAfterWarmup() throws InterruptedException {
        // Only the public warmup timer observes wall-clock time; storage retries are driven separately.
        Thread.sleep(10);
        assertTrue(tick.getAsBoolean());
    }

    private void drainWorker() {
        for (int i = 0; i < 50 && !worker.isEmpty(); i++) worker.removeFirst().run();
        assertTrue(worker.isEmpty(), "worker work must remain bounded");
    }

    private void join(Player player) {
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);
        service.join(event);
    }

    private Location destination() {
        return new Location(world, 100, 75, -30, -50, 15);
    }

    private SavedLocation snapshot(Location location) {
        return new SavedLocation(world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }
}
