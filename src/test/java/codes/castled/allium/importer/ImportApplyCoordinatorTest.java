package codes.castled.allium.importer;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.database.DatabaseManager;
import codes.castled.allium.importer.essentials.EssentialsImportPlan;
import codes.castled.allium.importer.essentials.EssentialsImportService;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan;
import codes.castled.allium.util.AlliumLogger;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.DisciplineService;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.module.TeleportModule;
import codes.castled.allium.module.TravelModule;
import codes.castled.allium.pack.Json;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.TeleportService;
import codes.castled.allium.travel.TravelService;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ImportApplyCoordinatorTest {

    @Test
    void inFlightLoginBlocksApplyUntilJoinCompletesOrHandshakesExpire() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        PluginStart plugin = mockPlugin(List.of(), null);
        ImportApplyCoordinator coordinator = new ImportApplyCoordinator(plugin, clock::get, 30_000L);

        UUID joiningId = UUID.randomUUID();
        var preLogin = new AsyncPlayerPreLoginEvent("Alex", InetAddress.getLoopbackAddress(), joiningId);
        coordinator.onAsyncPreLogin(preLogin);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin.getLoginResult());
        assertTrue(coordinator.hasInFlightLogins());

        // Online player list is still empty, yet apply MUST be refused while the login is in flight.
        var refused = coordinator.tryAcquireApply(EssentialsImportPlan.SOURCE, "essentials-20261123-120000");
        assertInstanceOf(ImportApplyCoordinator.Refused.class, refused);
        assertTrue(((ImportApplyCoordinator.Refused) refused).reason().contains("login is in progress"));

        // If another plugin later disallows the login, MONITOR clears the in-flight entry immediately.
        preLogin.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "banned");
        coordinator.onAsyncPreLoginMonitor(preLogin);
        assertFalse(coordinator.hasInFlightLogins());

        // A second handshake that stalls past TTL is pruned automatically.
        var stalled = new AsyncPlayerPreLoginEvent("Stalled", InetAddress.getLoopbackAddress(), UUID.randomUUID());
        coordinator.onAsyncPreLogin(stalled);
        assertTrue(coordinator.hasInFlightLogins());
        clock.addAndGet(30_000L);
        var acquired = coordinator.tryAcquireApply(EssentialsImportPlan.SOURCE, "essentials-20261123-120000");
        assertInstanceOf(ImportApplyCoordinator.Acquired.class, acquired);
        var lease = ((ImportApplyCoordinator.Acquired) acquired).lease();

        // While the lease is held, both AsyncPlayerPreLoginEvent and PlayerLoginEvent are fenced,
        // and a PlayerJoinEvent reaching the server is kicked defensively.
        var blockedPre = new AsyncPlayerPreLoginEvent("Late", InetAddress.getLoopbackAddress(), UUID.randomUUID());
        coordinator.onAsyncPreLogin(blockedPre);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, blockedPre.getLoginResult());

        Player latePlayer = mock(Player.class);
        when(latePlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        var blockedLogin = new PlayerLoginEvent(latePlayer, "localhost", InetAddress.getLoopbackAddress());
        coordinator.onLogin(blockedLogin);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, blockedLogin.getResult());

        coordinator.onJoin(new PlayerJoinEvent(latePlayer, "joined"));
        verify(latePlayer).kickPlayer(contains("import"));

        // Second source cannot acquire the lease while the first holds it.
        var secondSource = coordinator.tryAcquireApply(LegacyDestinationPlan.SOURCE, "legacy-20261123-120000");
        assertInstanceOf(ImportApplyCoordinator.Refused.class, secondSource);

        lease.close();
        assertFalse(coordinator.isApplying());
        var afterRelease = coordinator.tryAcquireApply(LegacyDestinationPlan.SOURCE, "legacy-20261123-120000");
        assertInstanceOf(ImportApplyCoordinator.Acquired.class, afterRelease);
        ((ImportApplyCoordinator.Acquired) afterRelease).lease().close();
    }

    @Test
    void pausedEssentialsApplyBlocksLegacySourceAndHoldsFenceThroughStatePublication(@TempDir Path root) throws Exception {
        Path pluginData = Files.createDirectory(root.resolve("Allium"));
        Path essentialsDir = Files.createDirectory(root.resolve("Essentials"));
        Path warpsDir = Files.createDirectory(essentialsDir.resolve("warps"));
        Files.createDirectory(essentialsDir.resolve("userdata"));
        Files.writeString(warpsDir.resolve("hub.yml"), "world: world\nx: 10.0\ny: 64.0\nz: -5.0\nyaw: 0.0\npitch: 0.0\n");
        Files.writeString(essentialsDir.resolve("spawn.yml"), """
                spawns:
                  default:
                    world: world
                    x: 0.5
                    y: 65.0
                    z: 0.5
                    yaw: 0.0
                    pitch: 0.0
                """);

        UUID worldId = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(worldId);

        List<Runnable> serverQueue = new CopyOnWriteArrayList<>();
        TaskScheduler scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return true; }
            @Override public TaskHandle runOnServer(Runnable task) { serverQueue.add(task); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { serverQueue.add(task); return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
                throw new UnsupportedOperationException();
            }
        };

        ConnectionSource dbSource = () -> DriverManager.getConnection("jdbc:sqlite:" + root.resolve("allium.db"));
        DatabaseManager db = mock(DatabaseManager.class);
        when(db.connectionSource()).thenReturn(dbSource);
        when(db.dialect()).thenReturn(SqlDialect.SQLITE);

        TravelService travelService = mock(TravelService.class);
        TravelModule travelModule = mock(TravelModule.class);
        when(travelModule.isEnabled()).thenReturn(true);
        when(travelModule.travel()).thenReturn(travelService);

        TeleportService teleportService = mock(TeleportService.class);
        TeleportModule teleportModule = mock(TeleportModule.class);
        when(teleportModule.isEnabled()).thenReturn(true);
        when(teleportModule.teleports()).thenReturn(teleportService);

        ModerationService moderationService = mock(ModerationService.class);
        DisciplineService disciplineService = mock(DisciplineService.class);
        ModerationModule moderationModule = mock(ModerationModule.class);
        when(moderationModule.isEnabled()).thenReturn(true);
        when(moderationModule.moderation()).thenReturn(moderationService);
        when(moderationModule.discipline()).thenReturn(disciplineService);

        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("travel")).thenReturn(travelModule);
        when(modules.getModule("teleport")).thenReturn(teleportModule);
        when(modules.getModule("moderation")).thenReturn(moderationModule);

        PluginStart plugin = mockPlugin(List.of(world), scheduler);
        when(plugin.getDataFolder()).thenReturn(pluginData.toFile());
        when(plugin.getDatabaseManager()).thenReturn(db);
        when(plugin.getModuleManager()).thenReturn(modules);

        ImportApplyCoordinator coordinator = new ImportApplyCoordinator(plugin);
        when(plugin.getImportApplyCoordinator()).thenReturn(coordinator);

        // Step 1: run an Essentials dry run synchronously to produce a reviewed runId.
        CommandSender console = mock(CommandSender.class);
        EssentialsImportService essDry = new EssentialsImportService(plugin, Runnable::run, coordinator);
        essDry.dryRun(console);
        String essRunId;
        try (var dirs = Files.list(pluginData.resolve("import/essentials"))) {
            essRunId = dirs.findFirst().orElseThrow().getFileName().toString();
        }
        assertTrue(Files.isRegularFile(pluginData.resolve("import/essentials").resolve(essRunId).resolve("plan.json")));

        // Step 2: launch Essentials apply on a pausable worker executor.
        CountDownLatch enteredWorker = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerDone = new CountDownLatch(1);
        var bg = Executors.newSingleThreadExecutor();
        Executor pausingExecutor = task -> bg.execute(() -> {
            enteredWorker.countDown();
            try {
                releaseWorker.await(10, TimeUnit.SECONDS);
                task.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                workerDone.countDown();
            }
        });

        try {
            EssentialsImportService essentialsService = new EssentialsImportService(plugin, pausingExecutor, coordinator);
            LegacyImportService legacyService = new LegacyImportService(plugin, Runnable::run, coordinator);

            serverQueue.clear();
            essentialsService.apply(console, essRunId);
            assertTrue(enteredWorker.await(5, TimeUnit.SECONDS));
            assertTrue(coordinator.isApplying());

            // Attempt a second source (Legacy apply AND dryRun) while Essentials apply is paused.
            CommandSender secondSender = mock(CommandSender.class);
            legacyService.apply(secondSender, "legacy-20261123-120000");
            verify(plugin.getMessageManager()).send(eq(secondSender), eq(Messages.IMPORT_LEGACY_REFUSED),
                    eq("{reason}"), contains("another import apply"));

            legacyService.dryRun(secondSender);
            verify(plugin.getMessageManager()).send(eq(secondSender), eq(Messages.IMPORT_LEGACY_REFUSED),
                    eq("{reason}"), contains("an import apply"));

            // Attempt a login while apply is paused.
            var midApplyPreLogin = new AsyncPlayerPreLoginEvent("Bob", InetAddress.getLoopbackAddress(), UUID.randomUUID());
            coordinator.onAsyncPreLogin(midApplyPreLogin);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, midApplyPreLogin.getLoginResult());

            // Resume worker: SQLite writes complete and server-thread publication is queued, NOT yet run.
            releaseWorker.countDown();
            assertTrue(workerDone.await(10, TimeUnit.SECONDS));

            // Fence and lease MUST remain held until server-thread publication executes!
            assertTrue(coordinator.isApplying());
            assertTrue(essentialsService.running());
            var prePublishLogin = new AsyncPlayerPreLoginEvent("Carol", InetAddress.getLoopbackAddress(), UUID.randomUUID());
            coordinator.onAsyncPreLogin(prePublishLogin);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, prePublishLogin.getLoginResult());

            // Verify publication callback actually publishes refreshed warps/spawns/mutes/jails BEFORE releasing the fence.
            AtomicBoolean fenceHeldDuringPublish = new AtomicBoolean(false);
            AtomicBoolean fenceHeldDuringSpawnPublish = new AtomicBoolean(false);
            doAnswer(inv -> {
                fenceHeldDuringPublish.set(coordinator.isApplying());
                return null;
            }).when(travelService).publishWarps(anyMap());
            doAnswer(inv -> {
                fenceHeldDuringSpawnPublish.set(coordinator.isApplying());
                return null;
            }).when(teleportService).publishImportedSpawns(any(), anyMap());

            assertFalse(serverQueue.isEmpty());
            List<Runnable> pending = new ArrayList<>(serverQueue);
            serverQueue.clear();
            pending.forEach(Runnable::run);

            assertTrue(fenceHeldDuringPublish.get(), "login fence must stay active while publishing refreshed warps");
            assertTrue(fenceHeldDuringSpawnPublish.get(), "login fence must stay active while publishing refreshed spawns");
            verify(travelService).publishWarps(anyMap());
            verify(teleportService).publishImportedSpawns(any(), anyMap());
            verify(moderationService).publishImportedMutes(anyList());
            verify(disciplineService).publishImportedJails(anyList(), anyList());

            // After publication completes, the lease is released and logins are admitted again.
            assertFalse(coordinator.isApplying());
            assertFalse(essentialsService.running());
            var postPublishLogin = new AsyncPlayerPreLoginEvent("Dave", InetAddress.getLoopbackAddress(), UUID.randomUUID());
            coordinator.onAsyncPreLogin(postPublishLogin);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, postPublishLogin.getLoginResult());

            // Confirm plan.json is intact and the apply wrote apply.jsonl.
            Map<String, Object> planDoc = Json.parseObject(
                    Files.readString(pluginData.resolve("import/essentials").resolve(essRunId).resolve("plan.json"), StandardCharsets.UTF_8));
            assertEquals(essRunId, planDoc.get("runId"));
            assertTrue(Files.isRegularFile(pluginData.resolve("import/essentials").resolve(essRunId).resolve("apply.jsonl")));
        } finally {
            bg.shutdownNow();
        }
    }

    @Test
    void pausedLegacyApplyBlocksEssentialsSourceAndReleasesLeaseOnRefusal(@TempDir Path root) throws Exception {
        Path pluginData = Files.createDirectory(root.resolve("Allium"));
        TaskScheduler scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return true; }
            @Override public TaskHandle runOnServer(Runnable task) { task.run(); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { task.run(); return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
                throw new UnsupportedOperationException();
            }
        };

        PluginStart plugin = mockPlugin(List.of(), scheduler);
        when(plugin.getDataFolder()).thenReturn(pluginData.toFile());
        ImportApplyCoordinator coordinator = new ImportApplyCoordinator(plugin);
        when(plugin.getImportApplyCoordinator()).thenReturn(coordinator);

        CountDownLatch enteredWorker = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerDone = new CountDownLatch(1);
        var bg = Executors.newSingleThreadExecutor();
        Executor pausingExecutor = task -> bg.execute(() -> {
            enteredWorker.countDown();
            try {
                releaseWorker.await(10, TimeUnit.SECONDS);
                task.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                workerDone.countDown();
            }
        });

        try {
            LegacyImportService legacyService = new LegacyImportService(plugin, pausingExecutor, coordinator);
            EssentialsImportService essentialsService = new EssentialsImportService(plugin, Runnable::run, coordinator);
            CommandSender console = mock(CommandSender.class);
            CommandSender secondSender = mock(CommandSender.class);

            legacyService.apply(console, "legacy-20261123-120000");
            assertTrue(enteredWorker.await(5, TimeUnit.SECONDS));
            assertTrue(coordinator.isApplying());

            // While Legacy apply holds the lease, Essentials apply and dry run are both refused.
            essentialsService.apply(secondSender, "essentials-20261123-120000");
            verify(plugin.getMessageManager()).send(eq(secondSender), eq(Messages.IMPORT_ESSENTIALS_REFUSED),
                    eq("{reason}"), contains("another import apply"));

            essentialsService.dryRun(secondSender);
            verify(plugin.getMessageManager()).send(eq(secondSender), eq(Messages.IMPORT_ESSENTIALS_REFUSED),
                    eq("{reason}"), contains("an import apply"));

            // Logins are fenced while Legacy apply holds the lease.
            var midLogin = new AsyncPlayerPreLoginEvent("Eve", InetAddress.getLoopbackAddress(), UUID.randomUUID());
            coordinator.onAsyncPreLogin(midLogin);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, midLogin.getLoginResult());

            // Resuming causes Legacy apply to refuse (missing plan.json) and release the lease cleanly.
            releaseWorker.countDown();
            assertTrue(workerDone.await(10, TimeUnit.SECONDS));
            assertFalse(coordinator.isApplying());
            assertFalse(legacyService.running());

            var afterLogin = new AsyncPlayerPreLoginEvent("Frank", InetAddress.getLoopbackAddress(), UUID.randomUUID());
            coordinator.onAsyncPreLogin(afterLogin);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, afterLogin.getLoginResult());
        } finally {
            bg.shutdownNow();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PluginStart mockPlugin(List<World> worlds, TaskScheduler scheduler) {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        MessageManager messages = mock(MessageManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.logger()).thenReturn(logger);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of());
        when(server.getWorlds()).thenReturn(worlds);
        when(messages.resolve(Messages.IMPORT_LEGACY_FENCE)).thenReturn("Server import in progress");
        return plugin;
    }
}
