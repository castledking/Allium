package codes.castled.allium.config;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.CommandManager;
import codes.castled.allium.rtp.RtpService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReloadCoordinatorTest {

    private static final String VALID_RTP = """
            enabled: true
            default-location: wild
            locations:
              wild:
                world: world
                min-range: 100
                max-range: 1000
                shape: square
            """;

    private static final String UPDATED_VALID_RTP = """
            enabled: true
            default-location: wild
            locations:
              wild:
                world: world
                min-range: 250
                max-range: 2500
                shape: circle
            """;

    private static final String INVALID_RTP = """
            enabled: true
            default-location: wild
            locations:
              wild:
                world: world
                min-range: 5000
                max-range: 100
                shape: square
            """;

    @Test
    void invalidRtpRejectsWholeReloadWithoutApplyingDisabledCommandsAndReportsTruthfully(@TempDir Path dir) throws Exception {
        Path dataFolder = Files.createDirectory(dir.resolve("Allium"));
        Path configFile = dataFolder.resolve("config.yml");
        Path rtpFile = dataFolder.resolve("rtp.yml");

        Files.writeString(configFile, """
                disabled-commands: []
                database:
                  type: sqlite
                modules:
                  economy: true
                addons:
                  enabled:
                    curios: false
                """, StandardCharsets.UTF_8);
        Files.writeString(rtpFile, VALID_RTP, StandardCharsets.UTF_8);

        YamlConfiguration initialConfig = YamlConfiguration.loadConfiguration(configFile.toFile());
        AtomicReference<FileConfiguration> liveConfig = new AtomicReference<>(initialConfig);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

        try {
            PluginStart plugin = mockPlugin(dataFolder, liveConfig, pool);
            CommandManager commandManager = new CommandManager(plugin);
            RtpService rtpService = new RtpService(plugin, () -> null);
            rtpService.enable();

            assertFalse(commandManager.getFamilyPolicy().isDisabled("fly"));
            assertEquals(100, rtpService.settings().locations().get("wild").minRange());

            // Valid disabled-commands change in config.yml + invalid rtp.yml together.
            Files.writeString(configFile, """
                    disabled-commands:
                      - fly
                    database:
                      type: mysql
                    modules:
                      economy: false
                    addons:
                      enabled:
                        curios: true
                    """, StandardCharsets.UTF_8);
            Files.writeString(rtpFile, INVALID_RTP, StandardCharsets.UTF_8);

            AtomicBoolean rootPublished = new AtomicBoolean(false);
            ConfigManager configManager = new ConfigManager(plugin);
            ReloadCoordinator.RootConfigSource rootSource = new ReloadCoordinator.RootConfigSource() {
                @Override
                public FileConfiguration prepareRoot() throws Exception {
                    return configManager.prepareRootConfig();
                }

                @Override
                public List<ReloadReport.Entry> publishRoot(FileConfiguration candidateRoot) {
                    rootPublished.set(true);
                    FileConfiguration previous = liveConfig.getAndSet(candidateRoot);
                    return ConfigManager.detectRestartOnlyChanges(previous, candidateRoot);
                }
            };

            List<ReloadCoordinator.Participant> participants = List.of(
                    ReloadCoordinator.Participant.of("commands", commandManager::prepareReload),
                    ReloadCoordinator.Participant.of("rtp", ignored -> rtpService.prepareReload())
            );

            List<String> rejectedReplies = new ArrayList<>();
            ReloadReport failed = ReloadCoordinator.execute(rootSource, participants, rejectedReplies::add, m -> {});

            // Nothing was published: disabled-commands stays unchanged, root config stays unchanged, rtp stays unchanged.
            assertFalse(failed.success());
            assertTrue(failed.applied().isEmpty(), "no changes may be reported applied when preparation fails");
            assertEquals(1, failed.rejected().size());
            assertEquals("rtp", failed.rejected().get(0).target());
            assertTrue(failed.restartRequired().isEmpty());
            assertFalse(rootPublished.get(), "root config must not be published when a late candidate fails");
            assertFalse(commandManager.getFamilyPolicy().isDisabled("fly"),
                    "valid disabled-commands change must not become active when rtp.yml fails validation");
            assertEquals(100, rtpService.settings().locations().get("wild").minRange());
            assertTrue(rejectedReplies.stream().anyMatch(line -> line.contains("Rejected rtp")));
            assertTrue(rejectedReplies.stream().anyMatch(line -> line.contains("No configuration changes were applied")));

            // Fix rtp.yml and re-run reload: everything publishes atomically and restart-only changes are reported.
            Files.writeString(rtpFile, UPDATED_VALID_RTP, StandardCharsets.UTF_8);
            List<String> successReplies = new ArrayList<>();
            ReloadReport succeeded = ReloadCoordinator.execute(rootSource, participants, successReplies::add, m -> {});

            assertTrue(succeeded.success());
            assertTrue(rootPublished.get());
            assertEquals(List.of("config", "commands", "rtp"), succeeded.applied());
            assertTrue(succeeded.rejected().isEmpty());
            assertTrue(commandManager.getFamilyPolicy().isDisabled("fly"));
            assertEquals(250, rtpService.settings().locations().get("wild").minRange());
            assertEquals(2500, rtpService.settings().locations().get("wild").maxRange());

            List<String> restartTargets = succeeded.restartRequired().stream().map(ReloadReport.Entry::target).toList();
            assertTrue(restartTargets.containsAll(List.of("database", "modules", "addons", "commands")),
                    "expected restart-required notices for database, modules, addons, and commands, got " + restartTargets);
            assertTrue(successReplies.stream().anyMatch(line -> line.contains("Applied: config, commands, rtp")));
            assertTrue(successReplies.stream().anyMatch(line -> line.contains("Requires restart (commands)")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void malformedRootYamlAndMultipleInvalidParticipantsReportAllRejectionsWithoutPublishing(@TempDir Path dir) throws Exception {
        Path dataFolder = Files.createDirectory(dir.resolve("AlliumBadRoot"));
        Path configFile = dataFolder.resolve("config.yml");
        Files.writeString(configFile, "disabled-commands: [\n  unclosed", StandardCharsets.UTF_8);

        PluginStart plugin = mock(PluginStart.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        ConfigManager configManager = new ConfigManager(plugin);
        AtomicBoolean participantPrepared = new AtomicBoolean(false);
        ReloadReport badRoot = ReloadCoordinator.execute(
                new ReloadCoordinator.RootConfigSource() {
                    @Override public FileConfiguration prepareRoot() throws Exception { return configManager.prepareRootConfig(); }
                    @Override public List<ReloadReport.Entry> publishRoot(FileConfiguration c) { fail("must not publish"); return List.of(); }
                },
                List.of(ReloadCoordinator.Participant.of("commands", c -> {
                    participantPrepared.set(true);
                    return ReloadCoordinator.Prepared.of(() -> {});
                })),
                line -> {},
                warn -> {}
        );
        assertFalse(badRoot.success());
        assertFalse(participantPrepared.get());
        assertEquals("config.yml", badRoot.rejected().get(0).target());

        // Multiple failing participants are all reported together before aborting.
        ReloadReport multiFail = ReloadCoordinator.execute(
                new ReloadCoordinator.RootConfigSource() {
                    @Override public FileConfiguration prepareRoot() { return new YamlConfiguration(); }
                    @Override public List<ReloadReport.Entry> publishRoot(FileConfiguration c) { fail("must not publish"); return List.of(); }
                },
                List.of(
                        ReloadCoordinator.Participant.of("rtp", c -> { throw new IllegalArgumentException("bad rtp range"); }),
                        ReloadCoordinator.Participant.of("worth", c -> { throw new IllegalArgumentException("bad worth price"); })
                ),
                line -> {},
                warn -> {}
        );
        assertFalse(multiFail.success());
        assertTrue(multiFail.applied().isEmpty());
        assertEquals(List.of("rtp", "worth"), multiFail.rejected().stream().map(ReloadReport.Entry::target).toList());
    }

    @Test
    void participantsPublishInDeclaredReloadOrderAndTargetedReloadIsolatesTarget() {
        List<String> publishOrder = new ArrayList<>();
        ReloadCoordinator.RootConfigSource root = new ReloadCoordinator.RootConfigSource() {
            @Override public FileConfiguration prepareRoot() { return new YamlConfiguration(); }
            @Override public List<ReloadReport.Entry> publishRoot(FileConfiguration c) {
                publishOrder.add("config");
                return List.of();
            }
        };
        List<ReloadCoordinator.Participant> participants = List.of(
                ReloadCoordinator.Participant.of("worth", 130, c -> ReloadCoordinator.Prepared.of(() -> publishOrder.add("worth"))),
                ReloadCoordinator.Participant.of("economy", 20, c -> ReloadCoordinator.Prepared.of(() -> publishOrder.add("economy"))),
                ReloadCoordinator.Participant.of("rtp", 120, c -> ReloadCoordinator.Prepared.of(() -> publishOrder.add("rtp")))
        );

        ReloadReport full = ReloadCoordinator.execute(root, participants, line -> {}, warn -> {});
        assertTrue(full.success());
        assertEquals(List.of("config", "economy", "rtp", "worth"), publishOrder);

        // Targeted reload runs only the selected participant + root config
        publishOrder.clear();
        ReloadReport targeted = ReloadCoordinator.executeTargeted(root, participants, "rtp", line -> {}, warn -> {});
        assertTrue(targeted.success());
        assertEquals(List.of("config", "rtp"), publishOrder);
        assertEquals(List.of("config", "rtp"), targeted.applied());

        // Unknown targeted reload is rejected without publishing root
        publishOrder.clear();
        ReloadReport unknown = ReloadCoordinator.executeTargeted(root, participants, "nonexistent", line -> {}, warn -> {});
        assertFalse(unknown.success());
        assertTrue(publishOrder.isEmpty());
        assertEquals("nonexistent", unknown.rejected().get(0).target());
    }

    private static PluginStart mockPlugin(Path dataFolder, AtomicReference<FileConfiguration> liveConfig, ThreadPoolExecutor pool) {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        TaskScheduler scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return true; }
            @Override public TaskHandle runOnServer(Runnable task) { task.run(); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) { return pool; }
        };
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getConfig()).thenAnswer(inv -> liveConfig.get());
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pm);
        when(plugin.logger()).thenReturn(logger);
        when(plugin.getScheduler()).thenReturn(scheduler);
        return plugin;
    }
}
