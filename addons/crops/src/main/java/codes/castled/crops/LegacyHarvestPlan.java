package codes.castled.crops;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure dry-run planner that maps a {@link LegacyHarvestReader.Snapshot} onto native Crops
 * placements ({@link ModelWorld} crops &amp; sprinklers and {@link FarmInputs} soil PDC records).
 * Performs zero world or database writes and never loads unloaded chunks.
 */
final class LegacyHarvestPlan {

    enum Domain {
        CROP("crop"),
        SOIL("soil"),
        SPRINKLER("sprinkler"),
        CELL("cell");

        private final String id;
        Domain(String id) { this.id = id; }
        String id() { return id; }
    }

    enum Status {
        READY,
        PRESENT,
        CONFLICT,
        ORPHAN,
        BLOCKED,
        SKIPPED
    }

    record BlockCheck(Status status, String reason, boolean ceiling) {
        static BlockCheck ready(String reason) { return new BlockCheck(Status.READY, reason, false); }
        static BlockCheck readyCeiling(String reason) { return new BlockCheck(Status.READY, reason, true); }
        static BlockCheck present(String reason) { return new BlockCheck(Status.PRESENT, reason, false); }
        static BlockCheck conflict(String reason) { return new BlockCheck(Status.CONFLICT, reason, false); }
    }

    interface WorldInspector {
        Optional<String> worldName(UUID worldId);
        boolean isChunkLoaded(UUID worldId, int chunkX, int chunkZ);
        BlockCheck inspectCrop(UUID worldId, int x, int y, int z, Catalog.Crop crop, Catalog.Path path, int stage);
        BlockCheck inspectSprinkler(UUID worldId, int x, int y, int z, String sprinklerItem);
        BlockCheck inspectSoil(UUID worldId, int x, int y, int z, String fertilizerId, long exhaustedAt);
    }

    record CropTarget(
            String cropId,
            String pathId,
            int stage,
            long dueAt,
            String fertilizerId,
            double growthSpeed,
            @Nullable UUID legacyVisualUuid
    ) {}

    record SoilTarget(
            String fertilizerId,
            long establishedAt,
            long exhaustedAt
    ) {}

    record SprinklerTarget(
            String sprinklerItem,
            boolean ceiling,
            @Nullable UUID legacyVisualUuid
    ) {}

    record Record(
            Domain domain,
            String key,
            Status status,
            String reason,
            UUID worldUuid,
            @Nullable String worldName,
            int x,
            int y,
            int z,
            boolean chunkLoaded,
            @Nullable UUID ownerUuid,
            String legacyRef,
            @Nullable CropTarget crop,
            @Nullable SoilTarget soil,
            @Nullable SprinklerTarget sprinkler
    ) {
        int chunkX() { return x >> 4; }
        int chunkZ() { return z >> 4; }
    }

    private final String sourceSha256;
    private final String planSha256;
    private final List<Record> records;
    private final List<String> notes;

    LegacyHarvestPlan(String sourceSha256, String planSha256, List<Record> records, List<String> notes) {
        this.sourceSha256 = sourceSha256;
        this.planSha256 = planSha256;
        this.records = List.copyOf(records);
        this.notes = List.copyOf(notes);
    }

    String sourceSha256() { return sourceSha256; }
    String planSha256() { return planSha256; }
    List<Record> records() { return records; }
    List<String> notes() { return notes; }

    Map<Status, Long> counts(Domain domain) {
        Map<Status, Long> out = new EnumMap<>(Status.class);
        for (Status s : Status.values()) out.put(s, 0L);
        for (Record r : records) {
            if (r.domain() == domain) out.merge(r.status(), 1L, Long::sum);
        }
        return out;
    }

    long count(Status status) {
        return records.stream().filter(r -> r.status() == status).count();
    }

    long unloadedReadyCount() {
        return records.stream().filter(r -> r.status() == Status.READY && !r.chunkLoaded()).count();
    }

    static LegacyHarvestPlan build(
            LegacyHarvestReader.Snapshot snapshot,
            Map<String, Catalog.Crop> catalog,
            Set<String> knownFertilizers,
            Map<String, String> sprinklerLookup,
            Set<Material> validSoils,
            boolean soilExhaustionEnabled,
            long soilForgetAfterMillis,
            WorldInspector inspector,
            long nowMillis
    ) {
        List<Record> records = new ArrayList<>();
        List<String> notes = new ArrayList<>(snapshot.notes());

        // Index known crop instances for orphan cell checks and cell collision checks.
        Map<UUID, LegacyHarvestReader.LegacyCrop> cropsByInstance = new HashMap<>();
        for (LegacyHarvestReader.LegacyCrop crop : snapshot.crops()) {
            cropsByInstance.put(crop.instanceUuid(), crop);
        }

        // 1. Orphan harvest_crop_cells rows
        for (LegacyHarvestReader.LegacyCell cell : snapshot.cells()) {
            LegacyHarvestReader.LegacyCrop parent = cropsByInstance.get(cell.instanceUuid());
            String key = cellKey(cell.worldUuid(), cell.x(), cell.y(), cell.z());
            String worldName = inspector.worldName(cell.worldUuid()).orElse(null);
            boolean loaded = worldName != null && inspector.isChunkLoaded(cell.worldUuid(), cell.x() >> 4, cell.z() >> 4);
            if (parent == null) {
                records.add(new Record(Domain.CELL, key, Status.ORPHAN,
                        "orphan cell references missing harvest_crops instance " + cell.instanceUuid(),
                        cell.worldUuid(), worldName, cell.x(), cell.y(), cell.z(), loaded, null,
                        cell.instanceUuid().toString(), null, null, null));
            } else if (!parent.worldUuid().equals(cell.worldUuid())) {
                records.add(new Record(Domain.CELL, key, Status.ORPHAN,
                        "cell world does not match parent crop world " + parent.worldUuid(),
                        cell.worldUuid(), worldName, cell.x(), cell.y(), cell.z(), loaded, parent.planterUuid(),
                        cell.instanceUuid().toString(), null, null, null));
            }
        }

        // 2. Placed crops
        Set<String> claimedCells = new HashSet<>();
        for (LegacyHarvestReader.LegacyCrop row : snapshot.crops()) {
            String key = cellKey(row.worldUuid(), row.x(), row.y(), row.z());
            String legacyRef = row.cropId() + "/" + row.pathId() + "@" + row.stage();
            Optional<String> worldOpt = inspector.worldName(row.worldUuid());
            String worldName = worldOpt.orElse(null);
            boolean loaded = worldOpt.isPresent() && inspector.isChunkLoaded(row.worldUuid(), row.x() >> 4, row.z() >> 4);

            if (row.cropId().equalsIgnoreCase("weed")) {
                records.add(new Record(Domain.CROP, key, Status.SKIPPED, "weed is intentionally omitted in Crops",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, row.planterUuid(),
                        legacyRef, null, null, null));
                continue;
            }
            if ("REMOVED".equalsIgnoreCase(row.state())) {
                records.add(new Record(Domain.CROP, key, Status.SKIPPED, "already marked REMOVED in legacy database",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, row.planterUuid(),
                        legacyRef, null, null, null));
                continue;
            }
            if (worldOpt.isEmpty()) {
                records.add(new Record(Domain.CROP, key, Status.BLOCKED, "unresolved world " + row.worldUuid(),
                        row.worldUuid(), null, row.x(), row.y(), row.z(), false, row.planterUuid(),
                        legacyRef, null, null, null));
                continue;
            }
            String normCrop = row.cropId().toLowerCase(Locale.ROOT);
            Catalog.Crop def = catalog.get(normCrop);
            if (def == null) {
                records.add(new Record(Domain.CROP, key, Status.BLOCKED, "unknown crop '" + row.cropId() + "'",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, row.planterUuid(),
                        legacyRef, null, null, null));
                continue;
            }
            String rawPath = row.pathId().isBlank() ? "default" : row.pathId().toLowerCase(Locale.ROOT);
            Catalog.Path path = def.paths().get(rawPath);
            if (path == null && row.pathId().isBlank() && !def.paths().isEmpty()) {
                path = def.paths().values().iterator().next();
            }
            if (path == null) {
                records.add(new Record(Domain.CROP, key, Status.BLOCKED,
                        "unknown path '" + row.pathId() + "' for crop '" + def.id() + "'",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, row.planterUuid(),
                        legacyRef, null, null, null));
                continue;
            }
            if (!claimedCells.add(key)) {
                records.add(new Record(Domain.CROP, key, Status.CONFLICT, "duplicate legacy crop at same block cell",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, row.planterUuid(),
                        legacyRef, null, null, null));
                continue;
            }

            int maxStage = path.stages().size() - 1;
            int targetStage = "MATURE".equalsIgnoreCase(row.state()) ? maxStage : row.stage();
            if (targetStage < 0 || targetStage > maxStage) {
                int clamped = Math.max(0, Math.min(maxStage, targetStage));
                notes.add("Crop " + def.id() + " at " + worldName + " (" + row.x() + "," + row.y() + "," + row.z()
                        + ") stage " + targetStage + " clamped to " + clamped);
                targetStage = clamped;
            }

            String fert = row.fertilizerId() == null ? "" : row.fertilizerId().toLowerCase(Locale.ROOT);
            if (!fert.isEmpty() && !knownFertilizers.contains(fert)) {
                notes.add("Crop " + def.id() + " at " + worldName + " (" + row.x() + "," + row.y() + "," + row.z()
                        + ") had unknown fertilizer '" + row.fertilizerId() + "' (cleared)");
                fert = "";
            }
            double speed = row.speedMultiplier() != null && Double.isFinite(row.speedMultiplier()) && row.speedMultiplier() > 0
                    ? Math.max(.01, Math.min(100, row.speedMultiplier()))
                    : 1.0;
            long dueAt;
            if (targetStage >= maxStage) {
                dueAt = nowMillis;
            } else if (row.nextGrowthAt() != null && row.nextGrowthAt() > 0L) {
                dueAt = row.nextGrowthAt();
            } else if (row.remainingMs() != null && row.remainingMs() > 0L) {
                dueAt = nowMillis + row.remainingMs();
            } else {
                dueAt = 0L;
            }

            CropTarget target = new CropTarget(def.id(), path.id(), targetStage, dueAt, fert, speed, row.visualUuid());
            Status st;
            String reason;
            if (loaded) {
                BlockCheck check = inspector.inspectCrop(row.worldUuid(), row.x(), row.y(), row.z(), def, path, targetStage);
                st = check.status();
                reason = check.reason();
            } else {
                st = Status.READY;
                reason = "chunk unloaded during dry run; block and soil verified on chunk load";
            }
            records.add(new Record(Domain.CROP, key, st, reason, row.worldUuid(), worldName,
                    row.x(), row.y(), row.z(), loaded, row.planterUuid(), legacyRef, target, null, null));
        }

        // 3. Placed sprinklers
        for (LegacyHarvestReader.LegacySprinkler row : snapshot.sprinklers()) {
            String key = cellKey(row.worldUuid(), row.x(), row.y(), row.z());
            Optional<String> worldOpt = inspector.worldName(row.worldUuid());
            String worldName = worldOpt.orElse(null);
            boolean loaded = worldOpt.isPresent() && inspector.isChunkLoaded(row.worldUuid(), row.x() >> 4, row.z() >> 4);

            if (worldOpt.isEmpty()) {
                records.add(new Record(Domain.SPRINKLER, key, Status.BLOCKED, "unresolved world " + row.worldUuid(),
                        row.worldUuid(), null, row.x(), row.y(), row.z(), false, row.ownerUuid(),
                        row.sprinklerId(), null, null, null));
                continue;
            }
            String canonicalItem = sprinklerLookup.get(row.sprinklerId().toLowerCase(Locale.ROOT));
            if (canonicalItem == null) {
                records.add(new Record(Domain.SPRINKLER, key, Status.BLOCKED,
                        "unknown sprinkler '" + row.sprinklerId() + "'",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, row.ownerUuid(),
                        row.sprinklerId(), null, null, null));
                continue;
            }
            if (!claimedCells.add(key)) {
                records.add(new Record(Domain.SPRINKLER, key, Status.CONFLICT,
                        "cell already claimed by another legacy placement",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, row.ownerUuid(),
                        row.sprinklerId(), null, null, null));
                continue;
            }
            Status st;
            String reason;
            boolean ceiling = false;
            if (loaded) {
                BlockCheck check = inspector.inspectSprinkler(row.worldUuid(), row.x(), row.y(), row.z(), canonicalItem);
                st = check.status();
                reason = check.reason();
                ceiling = check.ceiling();
            } else {
                st = Status.READY;
                reason = "chunk unloaded during dry run; support block verified on chunk load";
            }
            SprinklerTarget target = new SprinklerTarget(canonicalItem, ceiling, row.visualUuid());
            records.add(new Record(Domain.SPRINKLER, key, st, reason, row.worldUuid(), worldName,
                    row.x(), row.y(), row.z(), loaded, row.ownerUuid(), row.sprinklerId(), null, null, target));
        }

        // 4. Tracked soil rows
        for (LegacyHarvestReader.LegacySoil row : snapshot.soils()) {
            String key = cellKey(row.worldUuid(), row.x(), row.y(), row.z());
            Optional<String> worldOpt = inspector.worldName(row.worldUuid());
            String worldName = worldOpt.orElse(null);
            boolean loaded = worldOpt.isPresent() && inspector.isChunkLoaded(row.worldUuid(), row.x() >> 4, row.z() >> 4);
            String fert = row.fertilizerId() == null ? "" : row.fertilizerId().toLowerCase(Locale.ROOT);
            String legacyRef = fert.isEmpty() ? "soil" : "soil:" + fert;

            if (worldOpt.isEmpty()) {
                records.add(new Record(Domain.SOIL, key, Status.BLOCKED, "unresolved world " + row.worldUuid(),
                        row.worldUuid(), null, row.x(), row.y(), row.z(), false, null, legacyRef, null, null, null));
                continue;
            }
            if (!fert.isEmpty() && !knownFertilizers.contains(fert)) {
                notes.add("Soil at " + worldName + " (" + row.x() + "," + row.y() + "," + row.z()
                        + ") had unknown fertilizer '" + row.fertilizerId() + "' (cleared)");
                fert = "";
            }
            if (soilExhaustionEnabled && soilForgetAfterMillis > 0L && row.exhaustedAt() > 0L
                    && nowMillis - row.exhaustedAt() >= soilForgetAfterMillis && fert.isEmpty()) {
                records.add(new Record(Domain.SOIL, key, Status.SKIPPED, "exhausted soil past forget window",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, null, legacyRef, null, null, null));
                continue;
            }
            if (!soilExhaustionEnabled && fert.isEmpty()) {
                records.add(new Record(Domain.SOIL, key, Status.SKIPPED,
                        "soil exhaustion is disabled and row has no active fertilizer",
                        row.worldUuid(), worldName, row.x(), row.y(), row.z(), loaded, null, legacyRef, null, null, null));
                continue;
            }
            SoilTarget target = new SoilTarget(fert, row.establishedAt(), row.exhaustedAt());
            Status st;
            String reason;
            if (loaded) {
                BlockCheck check = inspector.inspectSoil(row.worldUuid(), row.x(), row.y(), row.z(), fert, row.exhaustedAt());
                st = check.status();
                reason = check.reason();
            } else {
                st = Status.READY;
                reason = "chunk unloaded during dry run; soil material verified on chunk load";
            }
            records.add(new Record(Domain.SOIL, key, st, reason, row.worldUuid(), worldName,
                    row.x(), row.y(), row.z(), loaded, null, legacyRef, null, target, null));
        }

        records.sort(Comparator.comparing((Record r) -> r.domain().ordinal()).thenComparing(Record::key));
        String planSha = hashPlan(snapshot.sourceSha256(), records);
        return new LegacyHarvestPlan(snapshot.sourceSha256(), planSha, records, notes);
    }

    String renderSummary(String runId, Path sourcePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Crops legacy migration dry run ").append(runId).append('\n');
        sb.append("# Source: ").append(sourcePath).append('\n');
        sb.append("# Source SHA-256: ").append(sourceSha256).append('\n');
        sb.append("# Plan SHA-256: ").append(planSha256).append('\n');
        Set<String> chunks = new LinkedHashSet<>();
        Set<UUID> owners = new LinkedHashSet<>();
        for (Record r : records) {
            chunks.add(r.worldUuid() + ":" + r.chunkX() + "," + r.chunkZ());
            if (r.ownerUuid() != null) owners.add(r.ownerUuid());
        }
        sb.append("# Distinct chunks referenced: ").append(chunks.size())
                .append(" | Unloaded READY records (deferred to chunk load on apply): ").append(unloadedReadyCount())
                .append(" | Distinct owners preserved: ").append(owners.size()).append('\n');
        sb.append('\n');
        for (Domain d : Domain.values()) {
            var c = counts(d);
            long total = c.values().stream().mapToLong(Long::longValue).sum();
            if (total == 0) continue;
            sb.append(String.format(Locale.ROOT,
                    "%-10s ready=%d present=%d conflict=%d orphan=%d blocked=%d skipped=%d%n",
                    d.id(), c.get(Status.READY), c.get(Status.PRESENT), c.get(Status.CONFLICT),
                    c.get(Status.ORPHAN), c.get(Status.BLOCKED), c.get(Status.SKIPPED)));
        }
        if (!notes.isEmpty()) {
            sb.append("\nNotes:\n");
            for (String note : notes) sb.append("- ").append(note).append('\n');
        }
        return sb.toString();
    }

    String renderJsonl() {
        StringBuilder sb = new StringBuilder();
        for (Record r : records) {
            sb.append('{')
                    .append("\"domain\":").append(jsonString(r.domain().id()))
                    .append(",\"status\":").append(jsonString(r.status().name()))
                    .append(",\"worldUuid\":").append(jsonString(r.worldUuid().toString()))
                    .append(",\"world\":").append(r.worldName() == null ? "null" : jsonString(r.worldName()))
                    .append(",\"chunkX\":").append(r.chunkX())
                    .append(",\"chunkZ\":").append(r.chunkZ())
                    .append(",\"x\":").append(r.x())
                    .append(",\"y\":").append(r.y())
                    .append(",\"z\":").append(r.z())
                    .append(",\"chunkLoaded\":").append(r.chunkLoaded())
                    .append(",\"ownerUuid\":").append(r.ownerUuid() == null ? "null" : jsonString(r.ownerUuid().toString()))
                    .append(",\"legacy\":").append(jsonString(r.legacyRef()))
                    .append(",\"reason\":").append(jsonString(r.reason()))
                    .append("}\n");
        }
        return sb.toString();
    }

    String renderPlanJson(String runId, Path sourcePath, long plannedAt) {
        return "{"
                + "\"runId\":" + jsonString(runId)
                + ",\"sourcePath\":" + jsonString(sourcePath.toAbsolutePath().normalize().toString())
                + ",\"sourceSha256\":" + jsonString(sourceSha256)
                + ",\"planSha256\":" + jsonString(planSha256)
                + ",\"plannedAt\":" + plannedAt
                + ",\"ready\":" + count(Status.READY)
                + ",\"present\":" + count(Status.PRESENT)
                + ",\"conflict\":" + count(Status.CONFLICT)
                + ",\"orphan\":" + count(Status.ORPHAN)
                + ",\"blocked\":" + count(Status.BLOCKED)
                + ",\"skipped\":" + count(Status.SKIPPED)
                + "}\n";
    }

    static String cellKey(UUID worldUuid, int x, int y, int z) {
        return worldUuid + ":" + x + "," + y + "," + z;
    }

    private static String hashPlan(String sourceSha, List<Record> records) {
        StringBuilder sb = new StringBuilder(sourceSha);
        for (Record r : records) {
            sb.append('\n').append(r.domain().id()).append('|').append(r.key())
                    .append('|').append(r.status().name()).append('|').append(r.legacyRef());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
