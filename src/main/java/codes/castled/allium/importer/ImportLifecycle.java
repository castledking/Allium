package codes.castled.allium.importer;

import codes.castled.allium.PluginStart;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import codes.castled.allium.pack.Json;
import codes.castled.allium.scheduler.AlliumScheduler;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.storage.ConnectionSource;
import org.bukkit.Server;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Shared import lifecycle module for reviewed dry-run/apply workflows (legacy H2, Essentials YAML,
 * and addon legacy-config discovery).
 *
 * <p>Owns source/plan fingerprint verification, loaded-world capture and world remapping,
 * destination table capability checks, server-thread publication with lease handoff, and
 * active/archived legacy input discovery (keeping file archival separate from transformation).
 */
public final class ImportLifecycle {

    private ImportLifecycle() {}

    /** Snapshot of loaded worlds captured on the server thread for worker-side world mapping. */
    public record LoadedWorlds(Map<String, ResolvedWorld> byName, Map<UUID, ResolvedWorld> byUuid) {
        public static LoadedWorlds capture(Server server) {
            Map<String, ResolvedWorld> names = new LinkedHashMap<>();
            Map<UUID, ResolvedWorld> uuids = new LinkedHashMap<>();
            for (World world : server.getWorlds()) {
                ResolvedWorld resolved = new ResolvedWorld(world.getUID(), world.getName());
                names.put(world.getName(), resolved);
                uuids.put(world.getUID(), resolved);
            }
            return new LoadedWorlds(Map.copyOf(names), Map.copyOf(uuids));
        }

        public Optional<ResolvedWorld> resolve(Map<String, String> worldMap, @Nullable String name) {
            return resolve(worldMap, name, null);
        }

        public Optional<ResolvedWorld> resolve(Map<String, String> worldMap, @Nullable String name, @Nullable UUID uuid) {
            if (name != null && !name.isBlank()) {
                String target = worldMap.getOrDefault(name, name);
                ResolvedWorld byMappedName = byName.get(target);
                if (byMappedName != null) return Optional.of(byMappedName);
            }
            if (uuid != null) {
                ResolvedWorld direct = byUuid.get(uuid);
                if (direct != null) return Optional.of(direct);
            }
            return Optional.empty();
        }
    }

    /** Parsed metadata from a reviewed dry-run {@code plan.json}. */
    public record ReviewedPlan(String sourceSha256, String planSha256, long plannedAt, Map<String, Object> raw) {}

    public sealed interface ReviewCheck permits ReviewCheck.Valid, ReviewCheck.Refused {
        record Valid(ReviewedPlan plan) implements ReviewCheck {}
        record Refused(String reason) implements ReviewCheck {}
    }

    /**
     * Loads and verifies a reviewed dry-run {@code plan.json} and confirms {@code runId} has not
     * already been applied in {@code ledger}.
     */
    public static ReviewCheck loadReviewedPlan(Path planFile, String displayPath, String runId,
                                               ImportLedger ledger) throws IOException, SQLException {
        if (!Files.isRegularFile(planFile)) {
            return new ReviewCheck.Refused("no dry run " + runId + " (expected " + displayPath + ")");
        }
        Map<String, Object> reviewed = Json.parseObject(Files.readString(planFile, StandardCharsets.UTF_8));
        if (ledger.ran(runId)) {
            return new ReviewCheck.Refused(runId + " was already applied; run a new dry run");
        }
        String sourceSha = reviewed.get("sourceSha256") instanceof String s ? s : "";
        String planSha = reviewed.get("planSha256") instanceof String s ? s : "";
        long plannedAt = reviewed.get("plannedAt") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        return new ReviewCheck.Valid(new ReviewedPlan(sourceSha, planSha, plannedAt, reviewed));
    }

    /** Verifies that live source and plan fingerprints still match the reviewed dry run. */
    public static Optional<String> verifyFingerprints(ReviewedPlan reviewed, String liveSourceSha,
                                                      String livePlanSha, String runId,
                                                      String sourceChangedReason, String planChangedReason) {
        if (!Objects.equals(liveSourceSha, reviewed.sourceSha256())) {
            return Optional.of(sourceChangedReason);
        }
        if (!Objects.equals(livePlanSha, reviewed.planSha256())) {
            return Optional.of(planChangedReason);
        }
        return Optional.empty();
    }

    /** Checks whether a destination table exists without creating it (safe for dry-run capability checks). */
    public static boolean tableExists(ConnectionSource source, String table) throws SQLException {
        try (Connection c = source.connection()) {
            for (String name : List.of(table, table.toUpperCase(Locale.ROOT))) {
                try (ResultSet r = c.getMetaData().getTables(null, null, name, null)) {
                    if (r.next()) return true;
                }
            }
            return false;
        }
    }

    /**
     * Hands off post-apply in-memory publication to the server thread while keeping the apply
     * lease and login fence active until publication completes.
     *
     * @return {@code true} if the server-thread task now owns releasing {@code lease}; {@code false}
     *         if scheduling failed and the caller must release {@code lease} immediately.
     */
    public static boolean publishAndRelease(PluginStart plugin,
                                            @Nullable ImportApplyCoordinator.Lease lease,
                                            Runnable finish,
                                            Runnable publishState,
                                            Runnable afterPublished) {
        var scheduler = plugin.getScheduler();
        TaskHandle handle = scheduler.runOnServer(() -> {
            try {
                publishState.run();
            } finally {
                if (lease != null) lease.close();
                finish.run();
            }
            afterPublished.run();
        });
        return !(scheduler instanceof AlliumScheduler && handle == TaskHandle.NONE);
    }

    /**
     * Returns candidate legacy Allium configuration files in precedence order (newest first):
     * <ol>
     *   <li>{@code config.legacy.yml} kept in {@code alliumDataDir} after carry-over,</li>
     *   <li>{@code import/backups/tidy-*\/config.legacy.yml} archived by {@code /allium import tidy}, newest first,</li>
     *   <li>{@code config.yml} in {@code alliumDataDir} if never migrated.</li>
     * </ol>
     */
    public static List<Path> legacyConfigSources(Path alliumDataDir) {
        if (alliumDataDir == null) return List.of();
        List<Path> found = new ArrayList<>();
        Path kept = alliumDataDir.resolve("config.legacy.yml");
        if (Files.isRegularFile(kept)) found.add(kept);
        Path backups = alliumDataDir.resolve("import").resolve("backups");
        if (Files.isDirectory(backups)) {
            try (Stream<Path> dirs = Files.list(backups)) {
                dirs.filter(Files::isDirectory)
                        .filter(dir -> dir.getFileName().toString().startsWith("tidy-"))
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                        .map(dir -> dir.resolve("config.legacy.yml"))
                        .filter(Files::isRegularFile)
                        .forEach(found::add);
            } catch (IOException ignored) {
                // Unreadable archive directory; continue with remaining candidates.
            }
        }
        Path config = alliumDataDir.resolve("config.yml");
        if (Files.isRegularFile(config)) found.add(config);
        return List.copyOf(found);
    }
}
