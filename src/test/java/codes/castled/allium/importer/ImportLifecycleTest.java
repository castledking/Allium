package codes.castled.allium.importer;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ImportLifecycleTest {

    @TempDir Path temp;

    @Test
    void legacyConfigSourcesOrderActiveKeptThenNewestArchivesThenCurrentConfig() throws Exception {
        Path kept = temp.resolve("config.legacy.yml");
        Path older = temp.resolve("import/backups/tidy-20260101-010000/config.legacy.yml");
        Path newer = temp.resolve("import/backups/tidy-20260201-010000/config.legacy.yml");
        Path ignoredDir = temp.resolve("import/backups/other-backup/config.legacy.yml");
        Path active = temp.resolve("config.yml");

        Files.createDirectories(older.getParent());
        Files.createDirectories(newer.getParent());
        Files.createDirectories(ignoredDir.getParent());
        Files.writeString(kept, "kept: true\n");
        Files.writeString(older, "older: true\n");
        Files.writeString(newer, "newer: true\n");
        Files.writeString(ignoredDir, "ignored: true\n");
        Files.writeString(active, "active: true\n");

        List<Path> sources = ImportLifecycle.legacyConfigSources(temp);
        assertEquals(List.of(kept, newer, older, active), sources);
    }

    @Test
    void reviewedPlanVerificationEnforcesSingleApplyAndFingerprintParity() throws Exception {
        ConnectionSource db = () -> DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("ledger.db"));
        ImportLedger ledger = new ImportLedger(db, SqlDialect.SQLITE, System::currentTimeMillis);
        ledger.migrate();

        Path missing = temp.resolve("missing/plan.json");
        assertInstanceOf(ImportLifecycle.ReviewCheck.Refused.class,
                ImportLifecycle.loadReviewedPlan(missing, "missing/plan.json", "legacy-20260101-000000", ledger));

        Path planFile = temp.resolve("plan.json");
        Files.writeString(planFile, """
                {"sourceSha256":"src-abc","planSha256":"plan-xyz","plannedAt":123456}
                """);
        var valid = assertInstanceOf(ImportLifecycle.ReviewCheck.Valid.class,
                ImportLifecycle.loadReviewedPlan(planFile, "plan.json", "legacy-20260101-000000", ledger));
        assertEquals(123456L, valid.plan().plannedAt());

        assertTrue(ImportLifecycle.verifyFingerprints(valid.plan(), "src-abc", "plan-xyz",
                "legacy-20260101-000000", "src changed", "plan changed").isEmpty());
        assertEquals("src changed", ImportLifecycle.verifyFingerprints(valid.plan(), "src-diff", "plan-xyz",
                "legacy-20260101-000000", "src changed", "plan changed").orElseThrow());
        assertEquals("plan changed", ImportLifecycle.verifyFingerprints(valid.plan(), "src-abc", "plan-diff",
                "legacy-20260101-000000", "src changed", "plan changed").orElseThrow());

        // Mark run as already applied -> refused on retry
        ledger.startRun("legacy-20260101-000000", "legacy", "src-abc", "plan-xyz");
        ledger.finishRun("legacy-20260101-000000", "applied");
        assertInstanceOf(ImportLifecycle.ReviewCheck.Refused.class,
                ImportLifecycle.loadReviewedPlan(planFile, "plan.json", "legacy-20260101-000000", ledger));
    }

    @Test
    void loadedWorldsCaptureAndWorldMappingResolveByMappedNameAndUuid() {
        UUID survivalId = UUID.randomUUID();
        World survival = mock(World.class);
        when(survival.getUID()).thenReturn(survivalId);
        when(survival.getName()).thenReturn("survival");
        Server server = mock(Server.class);
        when(server.getWorlds()).thenReturn(List.of(survival));

        ImportLifecycle.LoadedWorlds loaded = ImportLifecycle.LoadedWorlds.capture(server);
        assertEquals("survival", loaded.resolve(Map.of("old_world", "survival"), "old_world").orElseThrow().name());
        assertEquals(survivalId, loaded.resolve(Map.of(), "missing", survivalId).orElseThrow().id());
        assertTrue(loaded.resolve(Map.of(), "unknown", UUID.randomUUID()).isEmpty());
    }

    @Test
    void publishAndReleaseKeepsLeaseHeldUntilServerPublicationFinishes() {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ArrayDeque<Runnable> mainTasks = new ArrayDeque<>();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(scheduler.runOnServer(any())).thenAnswer(call -> {
            mainTasks.add(call.getArgument(0));
            return mock(TaskHandle.class);
        });

        ImportApplyCoordinator coordinator = new ImportApplyCoordinator(plugin);
        var lease = assertInstanceOf(ImportApplyCoordinator.Acquired.class,
                coordinator.tryAcquireApply("legacy", "run-1")).lease();
        AtomicBoolean published = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean notified = new AtomicBoolean();

        assertTrue(ImportLifecycle.publishAndRelease(plugin, lease, () -> finished.set(true),
                () -> {
                    assertTrue(coordinator.isApplying(), "lease must remain active during publication");
                    published.set(true);
                },
                () -> {
                    assertFalse(coordinator.isApplying(), "lease must be released before post-publish notify");
                    notified.set(true);
                }));
        assertTrue(coordinator.isApplying());
        mainTasks.remove().run();
        assertTrue(published.get());
        assertTrue(finished.get());
        assertTrue(notified.get());
        assertFalse(coordinator.isApplying());
    }
}
