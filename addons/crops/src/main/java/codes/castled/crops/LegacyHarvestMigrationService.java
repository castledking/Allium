package codes.castled.crops;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dry-run planner and chunk-safe converter for legacy Allium Harvest databases
 * ({@code /crops migrate [path]}, {@code /crops migrate apply <runId>}, {@code /crops migrate status}).
 *
 * <p>Preserves the legacy source database untouched (copies before reading read-only), never
 * force-loads unloaded chunks during dry run, and defers unloaded chunks cleanly to
 * {@link ChunkLoadEvent} on apply so both Spigot/Paper and Folia region ownership are respected.
 */
final class LegacyHarvestMigrationService implements Listener, AutoCloseable {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"([a-zA-Z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_LONG_FIELD = Pattern.compile("\"([a-zA-Z0-9_]+)\"\\s*:\\s*(-?[0-9]+)");

    record ChunkKey(UUID worldUuid, int chunkX, int chunkZ) {
        String id() { return worldUuid + "_" + chunkX + "_" + chunkZ; }
    }

    record PendingPlacement(
            String runId,
            LegacyHarvestPlan.Domain domain,
            UUID worldUuid,
            int x,
            int y,
            int z,
            @Nullable UUID ownerUuid,
            @Nullable LegacyHarvestPlan.CropTarget crop,
            @Nullable LegacyHarvestPlan.SoilTarget soil,
            @Nullable LegacyHarvestPlan.SprinklerTarget sprinkler
    ) {
        ChunkKey chunkKey() { return new ChunkKey(worldUuid, x >> 4, z >> 4); }
    }

    record ApplyChunkStats(int applied, int present, int conflict, int skipped) {}

    private final CropsPlugin plugin;
    private final ModelWorld modelWorld;
    private final Planting planting;
    private final FarmInputs farm;
    private final ExecutorService io;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<ChunkKey, List<PendingPlacement>> pendingByChunk = new ConcurrentHashMap<>();
    private final Object pendingFileLock = new Object();

    LegacyHarvestMigrationService(CropsPlugin plugin, ModelWorld modelWorld, Planting planting, FarmInputs farm) {
        this.plugin = plugin;
        this.modelWorld = modelWorld;
        this.planting = planting;
        this.farm = farm;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Allium-Crops-Migration");
            t.setDaemon(true);
            return t;
        });
        loadPendingFile();
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (!pendingByChunk.isEmpty()) {
            plugin.getLogger().info("Crops migration: " + pendingEntryCount()
                    + " deferred placement(s) across " + pendingByChunk.size()
                    + " unloaded chunk(s) waiting for chunk load.");
        }
    }

    int pendingChunkCount() { return pendingByChunk.size(); }

    int pendingEntryCount() {
        int total = 0;
        for (List<PendingPlacement> list : pendingByChunk.values()) total += list.size();
        return total;
    }

    List<String> recentRuns() {
        Path root = migrationRoot();
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("crops-"))
                    .sorted(Comparator.reverseOrder())
                    .limit(20)
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    void dryRun(CommandSender sender, @Nullable String explicitPath) {
        if (!busy.compareAndSet(false, true)) {
            tell(sender, "§cAnother Crops migration task is already running.");
            return;
        }
        tell(sender, "§7Starting Crops legacy migration dry run (read-only)...");
        io.execute(() -> {
            try {
                LegacyHarvestReader.DiscoveredSource source = resolveSource(explicitPath);
                String runId = newRunId();
                Path runDir = migrationRoot().resolve(runId);
                Files.createDirectories(runDir);
                String ext = source.databaseFile().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mv.db")
                        ? "source.mv.db" : "source.db";
                LegacyHarvestReader.Snapshot snapshot = LegacyHarvestReader.copyAndRead(
                        source.databaseFile(), runDir.resolve(ext), source.tablePrefix(), h2DriverDir());
                long now = System.currentTimeMillis();
                LegacyHarvestPlan plan = buildPlanWithLiveInspection(snapshot, now);
                Files.writeString(runDir.resolve("plan.json"), plan.renderPlanJson(runId, source.databaseFile(), now), StandardCharsets.UTF_8);
                Files.writeString(runDir.resolve("records.jsonl"), plan.renderJsonl(), StandardCharsets.UTF_8);
                Files.writeString(runDir.resolve("summary.txt"), plan.renderSummary(runId, source.databaseFile()), StandardCharsets.UTF_8);

                String rel = relative(runDir.resolve("summary.txt"));
                plugin.getLogger().info("Crops migration dry run " + runId + ": ready=" + plan.count(LegacyHarvestPlan.Status.READY)
                        + " present=" + plan.count(LegacyHarvestPlan.Status.PRESENT)
                        + " conflict=" + plan.count(LegacyHarvestPlan.Status.CONFLICT)
                        + " orphan=" + plan.count(LegacyHarvestPlan.Status.ORPHAN)
                        + " blocked=" + plan.count(LegacyHarvestPlan.Status.BLOCKED)
                        + " skipped=" + plan.count(LegacyHarvestPlan.Status.SKIPPED)
                        + " (" + rel + ")");
                tell(sender, "§aCrops dry run §f" + runId + " §acomplete:"
                        + " §eready=" + plan.count(LegacyHarvestPlan.Status.READY)
                        + " §7present=" + plan.count(LegacyHarvestPlan.Status.PRESENT)
                        + " §cconflict=" + plan.count(LegacyHarvestPlan.Status.CONFLICT)
                        + " §6orphan=" + plan.count(LegacyHarvestPlan.Status.ORPHAN)
                        + " §cblocked=" + plan.count(LegacyHarvestPlan.Status.BLOCKED)
                        + " §7skipped=" + plan.count(LegacyHarvestPlan.Status.SKIPPED)
                        + (plan.unloadedReadyCount() > 0 ? " §8(" + plan.unloadedReadyCount() + " in unloaded chunks)" : "")
                        + " §7Report: §f" + rel
                        + " §7Apply with §f/crops migrate apply " + runId);
            } catch (Exception failure) {
                String msg = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                plugin.getLogger().warning("Crops migration dry run failed: " + msg);
                tell(sender, "§cCrops dry run failed: " + msg);
            } finally {
                busy.set(false);
            }
        });
    }

    void apply(CommandSender sender, String runId) {
        if (runId == null || !runId.matches("crops-[0-9]{8}-[0-9]{6}(-[0-9]{1,3})?")) {
            tell(sender, "§cUnknown migration run ID '" + runId + "'. Run /crops migrate first.");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            tell(sender, "§cAnother Crops migration task is already running.");
            return;
        }
        tell(sender, "§7Verifying and applying Crops migration §f" + runId + "§7...");
        io.execute(() -> {
            try {
                Path runDir = migrationRoot().resolve(runId);
                Path planFile = runDir.resolve("plan.json");
                if (!Files.isRegularFile(planFile)) {
                    tell(sender, "§cRun '" + runId + "' has no plan.json; run /crops migrate first.");
                    return;
                }
                Map<String, String> planMeta = parsePlanMeta(Files.readString(planFile, StandardCharsets.UTF_8));
                String expectedSourcePath = planMeta.get("sourcePath");
                String expectedSourceSha = planMeta.get("sourceSha256");
                String expectedPlanSha = planMeta.get("planSha256");
                long plannedAt = Long.parseLong(planMeta.getOrDefault("plannedAt", Long.toString(System.currentTimeMillis())));
                if (expectedSourcePath == null || expectedSourceSha == null || expectedPlanSha == null) {
                    tell(sender, "§cMalformed plan.json in run '" + runId + "'; run a fresh /crops migrate.");
                    return;
                }
                Path sourcePath = Path.of(expectedSourcePath);
                String prefix = LegacyHarvestReader.readTablePrefix(sourcePath.getParent() == null
                        ? null : sourcePath.getParent().resolve("config.yml"));
                String ext = sourcePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mv.db")
                        ? "apply-source.mv.db" : "apply-source.db";
                LegacyHarvestReader.Snapshot snapshot = LegacyHarvestReader.copyAndRead(
                        sourcePath, runDir.resolve(ext), prefix, h2DriverDir());
                if (!expectedSourceSha.equals(snapshot.sourceSha256())) {
                    tell(sender, "§cSource database changed since " + runId + "; run a fresh /crops migrate.");
                    return;
                }
                LegacyHarvestPlan plan = buildPlanWithLiveInspection(snapshot, plannedAt);
                if (!expectedPlanSha.equals(plan.planSha256())) {
                    tell(sender, "§cWorld or chunk state changed since " + runId + "; run a fresh /crops migrate.");
                    return;
                }

                Map<ChunkKey, List<PendingPlacement>> byChunk = new LinkedHashMap<>();
                for (LegacyHarvestPlan.Record r : plan.records()) {
                    if (r.status() != LegacyHarvestPlan.Status.READY) continue;
                    if (r.domain() == LegacyHarvestPlan.Domain.CELL) continue;
                    PendingPlacement p = new PendingPlacement(runId, r.domain(), r.worldUuid(),
                            r.x(), r.y(), r.z(), r.ownerUuid(), r.crop(), r.soil(), r.sprinkler());
                    byChunk.computeIfAbsent(p.chunkKey(), ignored -> new ArrayList<>()).add(p);
                }

                AtomicInteger applied = new AtomicInteger();
                AtomicInteger present = new AtomicInteger();
                AtomicInteger conflicts = new AtomicInteger();
                AtomicInteger skipped = new AtomicInteger();
                AtomicInteger deferred = new AtomicInteger();
                AtomicInteger deferredChunks = new AtomicInteger();
                List<String> applyLog = new ArrayList<>();

                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (Map.Entry<ChunkKey, List<PendingPlacement>> entry : byChunk.entrySet()) {
                    ChunkKey ck = entry.getKey();
                    List<PendingPlacement> items = entry.getValue();
                    World world = Bukkit.getWorld(ck.worldUuid());
                    if (world == null) {
                        skipped.addAndGet(items.size());
                        continue;
                    }
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    plugin.connection().onRegion(world, ck.chunkX(), ck.chunkZ(), () -> {
                        try {
                            if (world.isChunkLoaded(ck.chunkX(), ck.chunkZ())) {
                                ApplyChunkStats stats = applyChunkOnRegion(world, items, applyLog);
                                applied.addAndGet(stats.applied());
                                present.addAndGet(stats.present());
                                conflicts.addAndGet(stats.conflict());
                                skipped.addAndGet(stats.skipped());
                            } else {
                                queueDeferredChunk(ck, items);
                                deferred.addAndGet(items.size());
                                deferredChunks.incrementAndGet();
                            }
                            done.complete(null);
                        } catch (Throwable err) {
                            done.completeExceptionally(err);
                        }
                    });
                    futures.add(done);
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
                if (deferred.get() > 0) savePendingFile();

                String summary = "# Crops migration apply " + runId + "\n"
                        + "# Source (preserved read-only): " + sourcePath + "\n"
                        + "applied=" + applied.get() + " present=" + present.get()
                        + " conflict=" + conflicts.get() + " skipped=" + skipped.get()
                        + " deferred=" + deferred.get() + " deferredChunks=" + deferredChunks.get() + "\n";
                Files.writeString(runDir.resolve("apply-summary.txt"), summary, StandardCharsets.UTF_8);
                synchronized (applyLog) {
                    Files.writeString(runDir.resolve("apply.jsonl"), String.join("\n", applyLog)
                            + (applyLog.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
                }

                plugin.getLogger().info("Crops migration " + runId + " applied: applied=" + applied.get()
                        + " present=" + present.get() + " conflict=" + conflicts.get()
                        + " deferred=" + deferred.get() + " across " + deferredChunks.get() + " unloaded chunk(s).");
                tell(sender, "§aCrops migration §f" + runId + " §aapplied:"
                        + " §aapplied=" + applied.get()
                        + " §7present=" + present.get()
                        + " §cconflict=" + conflicts.get()
                        + (deferred.get() > 0 ? " §edeferred=" + deferred.get() + " (" + deferredChunks.get()
                        + " unloaded chunks; will convert automatically on chunk load)" : "")
                        + " §7Source DB left untouched.");
            } catch (Exception failure) {
                String msg = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                plugin.getLogger().warning("Crops migration apply failed: " + msg);
                tell(sender, "§cCrops migration apply failed: " + msg);
            } finally {
                busy.set(false);
            }
        });
    }

    void status(CommandSender sender) {
        int chunks = pendingChunkCount();
        int entries = pendingEntryCount();
        List<String> runs = recentRuns();
        tell(sender, "§6Crops Migration Status§7: pending=§e" + entries + " §7placement(s) across §e"
                + chunks + " §7unloaded chunk(s); recent runs=§f"
                + (runs.isEmpty() ? "(none)" : String.join(", ", runs.subList(0, Math.min(5, runs.size())))));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (pendingByChunk.isEmpty()) return;
        Chunk chunk = event.getChunk();
        ChunkKey ck = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        List<PendingPlacement> items = pendingByChunk.remove(ck);
        if (items == null || items.isEmpty()) return;
        World world = chunk.getWorld();
        plugin.connection().onRegion(world, ck.chunkX(), ck.chunkZ(), () -> {
            if (!world.isChunkLoaded(ck.chunkX(), ck.chunkZ())) {
                queueDeferredChunk(ck, items);
                return;
            }
            List<String> log = new ArrayList<>();
            ApplyChunkStats stats = applyChunkOnRegion(world, items, log);
            plugin.getLogger().info("Crops lazy migration chunk (" + world.getName() + " " + ck.chunkX() + "," + ck.chunkZ()
                    + "): applied=" + stats.applied() + " present=" + stats.present() + " conflict=" + stats.conflict());
            io.execute(() -> {
                savePendingFile();
                appendDeferredLog(log);
            });
        });
    }

    private ApplyChunkStats applyChunkOnRegion(World world, List<PendingPlacement> items, List<String> logSink) {
        int applied = 0, present = 0, conflict = 0, skipped = 0;
        long now = System.currentTimeMillis();
        // Apply soil rows first so newly placed crops inherit migrated soil records cleanly.
        List<PendingPlacement> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingInt(p -> p.domain() == LegacyHarvestPlan.Domain.SOIL ? 0
                : p.domain() == LegacyHarvestPlan.Domain.SPRINKLER ? 1 : 2));

        for (PendingPlacement item : ordered) {
            String outcome;
            String detail;
            switch (item.domain()) {
                case SOIL -> {
                    Block soil = world.getBlockAt(item.x(), item.y(), item.z());
                    if (item.soil() == null) {
                        skipped++;
                        outcome = "SKIPPED";
                        detail = "missing soil payload";
                        break;
                    }
                    var check = inspectSoilBlock(soil, item.soil().fertilizerId(), item.soil().exhaustedAt(), now);
                    if (check.status() == LegacyHarvestPlan.Status.PRESENT) {
                        present++;
                        outcome = "PRESENT";
                        detail = check.reason();
                    } else if (check.status() != LegacyHarvestPlan.Status.READY) {
                        conflict++;
                        outcome = "CONFLICT";
                        detail = check.reason();
                    } else if (farm.writeMigratedSoil(soil, item.soil().fertilizerId(), item.soil().exhaustedAt())) {
                        applied++;
                        outcome = "APPLIED";
                        detail = "soil PDC written";
                    } else {
                        conflict++;
                        outcome = "CONFLICT";
                        detail = "soil block rejected write";
                    }
                }
                case SPRINKLER -> {
                    Block cell = world.getBlockAt(item.x(), item.y(), item.z());
                    if (item.sprinkler() == null) {
                        skipped++;
                        outcome = "SKIPPED";
                        detail = "missing sprinkler payload";
                        break;
                    }
                    var check = inspectSprinklerBlock(cell, item.sprinkler().sprinklerItem());
                    if (check.status() == LegacyHarvestPlan.Status.PRESENT) {
                        present++;
                        outcome = "PRESENT";
                        detail = check.reason();
                    } else if (check.status() != LegacyHarvestPlan.Status.READY) {
                        conflict++;
                        outcome = "CONFLICT";
                        detail = check.reason();
                    } else if (farm.placeMigratedSprinkler(cell, item.sprinkler().sprinklerItem(), check.ceiling())) {
                        removeLegacyVisual(cell.getChunk(), item.sprinkler().legacyVisualUuid());
                        applied++;
                        outcome = "APPLIED";
                        detail = check.ceiling() ? "ceiling sprinkler placed" : "floor sprinkler placed";
                    } else {
                        conflict++;
                        outcome = "CONFLICT";
                        detail = "sprinkler placement refused";
                    }
                }
                case CROP -> {
                    Block anchor = world.getBlockAt(item.x(), item.y(), item.z());
                    if (item.crop() == null) {
                        skipped++;
                        outcome = "SKIPPED";
                        detail = "missing crop payload";
                        break;
                    }
                    Catalog.Crop def = planting.crop(item.crop().cropId());
                    Catalog.Path path = def == null ? null : def.paths().get(item.crop().pathId());
                    if (def == null || path == null) {
                        skipped++;
                        outcome = "SKIPPED";
                        detail = "crop or path unavailable at apply time";
                        break;
                    }
                    var check = inspectCropBlock(anchor, def, path, item.crop().stage());
                    if (check.status() == LegacyHarvestPlan.Status.PRESENT) {
                        present++;
                        outcome = "PRESENT";
                        detail = check.reason();
                    } else if (check.status() != LegacyHarvestPlan.Status.READY) {
                        conflict++;
                        outcome = "CONFLICT";
                        detail = check.reason();
                    } else if (planting.placeMigrated(anchor, def, path, item.crop().stage(),
                            item.crop().dueAt(), item.crop().fertilizerId(), item.crop().growthSpeed())) {
                        removeLegacyVisual(anchor.getChunk(), item.crop().legacyVisualUuid());
                        applied++;
                        outcome = "APPLIED";
                        detail = "crop placed";
                    } else {
                        conflict++;
                        outcome = "CONFLICT";
                        detail = "crop placement refused";
                    }
                }
                default -> {
                    skipped++;
                    outcome = "SKIPPED";
                    detail = "non-placeable domain";
                }
            }
            String line = "{\"runId\":" + LegacyHarvestPlan.jsonString(item.runId())
                    + ",\"domain\":" + LegacyHarvestPlan.jsonString(item.domain().id())
                    + ",\"world\":" + LegacyHarvestPlan.jsonString(world.getName())
                    + ",\"x\":" + item.x() + ",\"y\":" + item.y() + ",\"z\":" + item.z()
                    + ",\"outcome\":" + LegacyHarvestPlan.jsonString(outcome)
                    + ",\"detail\":" + LegacyHarvestPlan.jsonString(detail) + "}";
            synchronized (logSink) {
                logSink.add(line);
            }
        }
        return new ApplyChunkStats(applied, present, conflict, skipped);
    }

    private void removeLegacyVisual(Chunk chunk, @Nullable UUID legacyVisualUuid) {
        if (legacyVisualUuid == null) return;
        for (Entity entity : chunk.getEntities()) {
            if (legacyVisualUuid.equals(entity.getUniqueId()) && modelWorld.get(entity) == null) {
                entity.remove();
                return;
            }
        }
    }

    private LegacyHarvestPlan buildPlanWithLiveInspection(LegacyHarvestReader.Snapshot snapshot, long nowMillis) throws Exception {
        // Capture loaded world metadata and inspect loaded chunks safely on their owning regions.
        Map<UUID, World> worldsById = new LinkedHashMap<>();
        Map<UUID, String> worldNames = new LinkedHashMap<>();
        CompletableFuture<Void> worldsFuture = new CompletableFuture<>();
        plugin.connection().scheduler().runOnServer(() -> {
            try {
                for (World w : Bukkit.getWorlds()) {
                    worldsById.put(w.getUID(), w);
                    worldNames.put(w.getUID(), w.getName());
                }
                worldsFuture.complete(null);
            } catch (Throwable t) {
                worldsFuture.completeExceptionally(t);
            }
        });
        worldsFuture.get(15, TimeUnit.SECONDS);

        Map<ChunkKey, Boolean> loadedChunks = new ConcurrentHashMap<>();
        Map<String, LegacyHarvestPlan.BlockCheck> cropChecks = new ConcurrentHashMap<>();
        Map<String, LegacyHarvestPlan.BlockCheck> sprinklerChecks = new ConcurrentHashMap<>();
        Map<String, LegacyHarvestPlan.BlockCheck> soilChecks = new ConcurrentHashMap<>();

        Map<ChunkKey, List<Runnable>> chunkTasks = new LinkedHashMap<>();
        var catalog = planting.catalog();
        var sprinklerMap = farm.sprinklerLookup();

        for (LegacyHarvestReader.LegacyCrop row : snapshot.crops()) {
            World w = worldsById.get(row.worldUuid());
            if (w == null || row.cropId().equalsIgnoreCase("weed") || "REMOVED".equalsIgnoreCase(row.state())) continue;
            Catalog.Crop def = catalog.get(row.cropId().toLowerCase(Locale.ROOT));
            if (def == null) continue;
            String rawPath = row.pathId().isBlank() ? "default" : row.pathId().toLowerCase(Locale.ROOT);
            Catalog.Path path = def.paths().get(rawPath);
            if (path == null && row.pathId().isBlank() && !def.paths().isEmpty()) path = def.paths().values().iterator().next();
            if (path == null) continue;
            int maxStage = path.stages().size() - 1;
            int stage = Math.max(0, Math.min(maxStage, "MATURE".equalsIgnoreCase(row.state()) ? maxStage : row.stage()));
            final Catalog.Path resolvedPath = path;
            String key = LegacyHarvestPlan.cellKey(row.worldUuid(), row.x(), row.y(), row.z());
            ChunkKey ck = new ChunkKey(row.worldUuid(), row.x() >> 4, row.z() >> 4);
            chunkTasks.computeIfAbsent(ck, ignored -> new ArrayList<>()).add(() ->
                    cropChecks.put(key, inspectCropBlock(w.getBlockAt(row.x(), row.y(), row.z()), def, resolvedPath, stage)));
        }

        for (LegacyHarvestReader.LegacySprinkler row : snapshot.sprinklers()) {
            World w = worldsById.get(row.worldUuid());
            if (w == null) continue;
            String canonical = sprinklerMap.get(row.sprinklerId().toLowerCase(Locale.ROOT));
            if (canonical == null) continue;
            String key = LegacyHarvestPlan.cellKey(row.worldUuid(), row.x(), row.y(), row.z());
            ChunkKey ck = new ChunkKey(row.worldUuid(), row.x() >> 4, row.z() >> 4);
            chunkTasks.computeIfAbsent(ck, ignored -> new ArrayList<>()).add(() ->
                    sprinklerChecks.put(key, inspectSprinklerBlock(w.getBlockAt(row.x(), row.y(), row.z()), canonical)));
        }

        for (LegacyHarvestReader.LegacySoil row : snapshot.soils()) {
            World w = worldsById.get(row.worldUuid());
            if (w == null) continue;
            String fert = row.fertilizerId() == null ? "" : row.fertilizerId().toLowerCase(Locale.ROOT);
            String key = LegacyHarvestPlan.cellKey(row.worldUuid(), row.x(), row.y(), row.z());
            ChunkKey ck = new ChunkKey(row.worldUuid(), row.x() >> 4, row.z() >> 4);
            chunkTasks.computeIfAbsent(ck, ignored -> new ArrayList<>()).add(() ->
                    soilChecks.put(key, inspectSoilBlock(w.getBlockAt(row.x(), row.y(), row.z()), fert, row.exhaustedAt(), nowMillis)));
        }

        List<CompletableFuture<Void>> inspections = new ArrayList<>();
        for (Map.Entry<ChunkKey, List<Runnable>> entry : chunkTasks.entrySet()) {
            ChunkKey ck = entry.getKey();
            World w = worldsById.get(ck.worldUuid());
            if (w == null) continue;
            CompletableFuture<Void> f = new CompletableFuture<>();
            plugin.connection().onRegion(w, ck.chunkX(), ck.chunkZ(), () -> {
                try {
                    boolean loaded = w.isChunkLoaded(ck.chunkX(), ck.chunkZ());
                    loadedChunks.put(ck, loaded);
                    if (loaded) {
                        for (Runnable r : entry.getValue()) r.run();
                    }
                    f.complete(null);
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
            inspections.add(f);
        }
        CompletableFuture.allOf(inspections.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);

        LegacyHarvestPlan.WorldInspector inspector = new LegacyHarvestPlan.WorldInspector() {
            @Override
            public Optional<String> worldName(UUID worldId) {
                return Optional.ofNullable(worldNames.get(worldId));
            }

            @Override
            public boolean isChunkLoaded(UUID worldId, int chunkX, int chunkZ) {
                return Boolean.TRUE.equals(loadedChunks.get(new ChunkKey(worldId, chunkX, chunkZ)));
            }

            @Override
            public LegacyHarvestPlan.BlockCheck inspectCrop(UUID worldId, int x, int y, int z,
                                                            Catalog.Crop crop, Catalog.Path path, int stage) {
                return cropChecks.getOrDefault(LegacyHarvestPlan.cellKey(worldId, x, y, z),
                        LegacyHarvestPlan.BlockCheck.ready("ready"));
            }

            @Override
            public LegacyHarvestPlan.BlockCheck inspectSprinkler(UUID worldId, int x, int y, int z, String sprinklerItem) {
                return sprinklerChecks.getOrDefault(LegacyHarvestPlan.cellKey(worldId, x, y, z),
                        LegacyHarvestPlan.BlockCheck.ready("ready"));
            }

            @Override
            public LegacyHarvestPlan.BlockCheck inspectSoil(UUID worldId, int x, int y, int z, String fertilizerId, long exhaustedAt) {
                return soilChecks.getOrDefault(LegacyHarvestPlan.cellKey(worldId, x, y, z),
                        LegacyHarvestPlan.BlockCheck.ready("ready"));
            }
        };

        return LegacyHarvestPlan.build(
                snapshot,
                catalog,
                farm.fertilizerIds(),
                sprinklerMap,
                farm.validSoils(),
                farm.exhaustionEnabled(),
                farm.forgetAfterMillis(),
                inspector,
                nowMillis
        );
    }

    private LegacyHarvestPlan.BlockCheck inspectCropBlock(Block anchor, Catalog.Crop crop, Catalog.Path path, int stage) {
        ModelWorld.Placed existing = modelWorld.at(anchor);
        if (existing != null) {
            if ("crop".equals(existing.kind) && crop.id().equals(existing.type)
                    && path.id().equals(existing.path) && existing.stage == stage) {
                return LegacyHarvestPlan.BlockCheck.present("matching native crop already placed");
            }
            return LegacyHarvestPlan.BlockCheck.conflict("native " + existing.kind + " (" + existing.type + ") already placed here");
        }
        if (!anchor.getType().isAir()) {
            return LegacyHarvestPlan.BlockCheck.conflict("conflicting world block " + anchor.getType().name());
        }
        Block soil = anchor.getRelative(BlockFace.DOWN);
        if (!crop.soils().contains(soil.getType())) {
            return LegacyHarvestPlan.BlockCheck.conflict("incompatible soil block " + soil.getType().name());
        }
        return LegacyHarvestPlan.BlockCheck.ready("ready on " + soil.getType().name().toLowerCase(Locale.ROOT));
    }

    private LegacyHarvestPlan.BlockCheck inspectSprinklerBlock(Block cell, String sprinklerItem) {
        ModelWorld.Placed existing = modelWorld.at(cell);
        if (existing != null) {
            if ("sprinkler".equals(existing.kind) && sprinklerItem.equals(existing.type)) {
                return LegacyHarvestPlan.BlockCheck.present("matching native sprinkler already placed");
            }
            return LegacyHarvestPlan.BlockCheck.conflict("native " + existing.kind + " (" + existing.type + ") already placed here");
        }
        if (!cell.getType().isAir()) {
            return LegacyHarvestPlan.BlockCheck.conflict("conflicting world block " + cell.getType().name());
        }
        if (cell.getRelative(BlockFace.DOWN).getType().isSolid()) {
            return LegacyHarvestPlan.BlockCheck.ready("ready on floor support");
        }
        if (cell.getRelative(BlockFace.UP).getType().isSolid()) {
            return LegacyHarvestPlan.BlockCheck.readyCeiling("ready on ceiling support");
        }
        return LegacyHarvestPlan.BlockCheck.conflict("no solid floor or ceiling support block");
    }

    private LegacyHarvestPlan.BlockCheck inspectSoilBlock(Block soil, String fertilizerId, long exhaustedAt, long now) {
        if (!farm.validSoils().contains(soil.getType())) {
            return LegacyHarvestPlan.BlockCheck.conflict("block is " + soil.getType().name() + ", not valid crop soil");
        }
        var existing = farm.existingSoil(soil, now);
        if (existing.isPresent()) {
            var snap = existing.get();
            if (snap.fertilizer().equals(fertilizerId)
                    && (exhaustedAt <= 0L || Math.abs(snap.expires() - exhaustedAt) < 1000L)) {
                return LegacyHarvestPlan.BlockCheck.present("matching soil PDC already present");
            }
            return LegacyHarvestPlan.BlockCheck.conflict("soil already has different native PDC state");
        }
        return LegacyHarvestPlan.BlockCheck.ready("ready to write soil PDC");
    }

    private void queueDeferredChunk(ChunkKey ck, List<PendingPlacement> items) {
        pendingByChunk.compute(ck, (k, existing) -> {
            List<PendingPlacement> merged = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            Set<String> seen = new java.util.HashSet<>();
            for (PendingPlacement p : merged) seen.add(p.domain().id() + ":" + p.x() + "," + p.y() + "," + p.z());
            for (PendingPlacement p : items) {
                if (seen.add(p.domain().id() + ":" + p.x() + "," + p.y() + "," + p.z())) merged.add(p);
            }
            return merged;
        });
    }

    private void loadPendingFile() {
        Path file = pendingFile();
        if (!Files.isRegularFile(file)) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            ConfigurationSection chunks = yaml.getConfigurationSection("chunks");
            if (chunks == null) return;
            for (String chunkId : chunks.getKeys(false)) {
                ConfigurationSection cSec = chunks.getConfigurationSection(chunkId);
                if (cSec == null) continue;
                UUID worldId = UUID.fromString(cSec.getString("world", ""));
                int cx = cSec.getInt("cx");
                int cz = cSec.getInt("cz");
                ChunkKey ck = new ChunkKey(worldId, cx, cz);
                List<PendingPlacement> list = new ArrayList<>();
                for (Map<?, ?> raw : cSec.getMapList("items")) {
                    PendingPlacement p = deserializePending(worldId, raw);
                    if (p != null) list.add(p);
                }
                if (!list.isEmpty()) pendingByChunk.put(ck, list);
            }
        } catch (Exception bad) {
            plugin.getLogger().warning("Could not read Crops pending migration queue: " + bad.getMessage());
        }
    }

    private void savePendingFile() {
        synchronized (pendingFileLock) {
            Path file = pendingFile();
            try {
                if (pendingByChunk.isEmpty()) {
                    Files.deleteIfExists(file);
                    return;
                }
                Files.createDirectories(file.getParent());
                YamlConfiguration yaml = new YamlConfiguration();
                ConfigurationSection chunks = yaml.createSection("chunks");
                for (Map.Entry<ChunkKey, List<PendingPlacement>> entry : pendingByChunk.entrySet()) {
                    ChunkKey ck = entry.getKey();
                    ConfigurationSection cSec = chunks.createSection(ck.id());
                    cSec.set("world", ck.worldUuid().toString());
                    cSec.set("cx", ck.chunkX());
                    cSec.set("cz", ck.chunkZ());
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (PendingPlacement p : entry.getValue()) {
                        rows.add(serializePending(p));
                    }
                    cSec.set("items", rows);
                }
                yaml.save(file.toFile());
            } catch (IOException ioErr) {
                plugin.getLogger().warning("Could not save Crops pending migration queue: " + ioErr.getMessage());
            }
        }
    }

    private void appendDeferredLog(List<String> lines) {
        if (lines.isEmpty()) return;
        synchronized (pendingFileLock) {
            Path logFile = migrationRoot().resolve("deferred-applied.jsonl");
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    private static Map<String, Object> serializePending(PendingPlacement p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", p.runId());
        m.put("domain", p.domain().name());
        m.put("x", p.x());
        m.put("y", p.y());
        m.put("z", p.z());
        if (p.ownerUuid() != null) m.put("owner", p.ownerUuid().toString());
        if (p.crop() != null) {
            m.put("cropId", p.crop().cropId());
            m.put("pathId", p.crop().pathId());
            m.put("stage", p.crop().stage());
            m.put("dueAt", p.crop().dueAt());
            m.put("fertilizer", p.crop().fertilizerId());
            m.put("speed", p.crop().growthSpeed());
            if (p.crop().legacyVisualUuid() != null) m.put("visual", p.crop().legacyVisualUuid().toString());
        }
        if (p.soil() != null) {
            m.put("fertilizer", p.soil().fertilizerId());
            m.put("establishedAt", p.soil().establishedAt());
            m.put("exhaustedAt", p.soil().exhaustedAt());
        }
        if (p.sprinkler() != null) {
            m.put("sprinklerItem", p.sprinkler().sprinklerItem());
            m.put("ceiling", p.sprinkler().ceiling());
            if (p.sprinkler().legacyVisualUuid() != null) m.put("visual", p.sprinkler().legacyVisualUuid().toString());
        }
        return m;
    }

    @Nullable
    private static PendingPlacement deserializePending(UUID worldId, Map<?, ?> m) {
        try {
            String runId = String.valueOf(m.get("runId"));
            LegacyHarvestPlan.Domain domain = LegacyHarvestPlan.Domain.valueOf(String.valueOf(m.get("domain")));
            int x = ((Number) m.get("x")).intValue();
            int y = ((Number) m.get("y")).intValue();
            int z = ((Number) m.get("z")).intValue();
            UUID owner = m.get("owner") == null ? null : UUID.fromString(String.valueOf(m.get("owner")));
            UUID visual = m.get("visual") == null ? null : UUID.fromString(String.valueOf(m.get("visual")));
            LegacyHarvestPlan.CropTarget crop = null;
            LegacyHarvestPlan.SoilTarget soil = null;
            LegacyHarvestPlan.SprinklerTarget sprinkler = null;
            switch (domain) {
                case CROP -> crop = new LegacyHarvestPlan.CropTarget(
                        String.valueOf(m.get("cropId")),
                        String.valueOf(m.get("pathId")),
                        ((Number) m.get("stage")).intValue(),
                        ((Number) m.get("dueAt")).longValue(),
                        m.get("fertilizer") == null ? "" : String.valueOf(m.get("fertilizer")),
                        m.get("speed") instanceof Number n ? n.doubleValue() : 1.0,
                        visual);
                case SOIL -> soil = new LegacyHarvestPlan.SoilTarget(
                        m.get("fertilizer") == null ? "" : String.valueOf(m.get("fertilizer")),
                        m.get("establishedAt") instanceof Number n ? n.longValue() : 0L,
                        m.get("exhaustedAt") instanceof Number n ? n.longValue() : 0L);
                case SPRINKLER -> sprinkler = new LegacyHarvestPlan.SprinklerTarget(
                        String.valueOf(m.get("sprinklerItem")),
                        Boolean.TRUE.equals(m.get("ceiling")),
                        visual);
                default -> { return null; }
            }
            return new PendingPlacement(runId, domain, worldId, x, y, z, owner, crop, soil, sprinkler);
        } catch (RuntimeException bad) {
            return null;
        }
    }

    private LegacyHarvestReader.DiscoveredSource resolveSource(@Nullable String explicitPath) throws IOException {
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path p = Path.of(explicitPath.trim());
            if (!p.isAbsolute()) p = plugin.getDataFolder().toPath().resolve(p).normalize();
            String prefix = LegacyHarvestReader.readTablePrefix(p.getParent() == null ? null : p.getParent().resolve("config.yml"));
            return new LegacyHarvestReader.DiscoveredSource(p, prefix);
        }
        Path alliumData = alliumDataFolder();
        return LegacyHarvestReader.discover(alliumData).orElseThrow(() -> new IOException(
                "No legacy harvest database found under " + alliumData.resolve("harvest")
                        + " or " + alliumData.resolve("import/backups")
                        + ". Pass an explicit file path: /crops migrate <path-to-harvest.mv.db|harvest.db>"));
    }

    private Path alliumDataFolder() {
        var alliumPlugin = plugin.connection().plugin();
        if (alliumPlugin != null) return alliumPlugin.getDataFolder().toPath();
        Path parent = plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent();
        return parent == null ? plugin.getDataFolder().toPath() : parent.resolve("Allium");
    }

    private Path h2DriverDir() {
        return alliumDataFolder().resolve("import");
    }

    private Path migrationRoot() {
        return plugin.getDataFolder().toPath().resolve("migration");
    }

    private Path pendingFile() {
        return migrationRoot().resolve("pending.yml");
    }

    private String newRunId() throws IOException {
        String base = "crops-" + RUN_ID.format(Instant.now());
        Path root = migrationRoot();
        Files.createDirectories(root);
        if (!Files.exists(root.resolve(base))) return base;
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (!Files.exists(root.resolve(candidate))) return candidate;
        }
        throw new IOException("Too many migration runs in the same second");
    }

    private String relative(Path path) {
        return plugin.getDataFolder().toPath().toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize()).toString();
    }

    private void tell(CommandSender sender, String message) {
        plugin.connection().onOwner(sender, () -> sender.sendMessage(message));
    }

    private static Map<String, String> parsePlanMeta(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher sm = JSON_STRING_FIELD.matcher(json);
        while (sm.find()) {
            out.put(sm.group(1), sm.group(2).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        Matcher lm = JSON_LONG_FIELD.matcher(json);
        while (lm.find()) {
            out.putIfAbsent(lm.group(1), lm.group(2));
        }
        return out;
    }

    @Override
    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
