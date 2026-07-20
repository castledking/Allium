package codes.castled.allium.harvest.storage;

import codes.castled.allium.harvest.crop.CropInstance;
import codes.castled.allium.harvest.crop.CropState;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous persistence for crop instances and their occupied cells.
 * Saves are coalesced per instance in a pending queue and flushed in batches;
 * deletes and inserts of new instances are written immediately so a crash
 * cannot resurrect a harvested crop or lose a planted one.
 */
public final class CropStorage {

    private final HarvestDatabase db;
    private final Map<UUID, CropInstance> pendingSaves = new LinkedHashMap<>();
    private final Object pendingLock = new Object();

    public CropStorage(HarvestDatabase db) {
        this.db = db;
    }

    // ==================== write path ====================

    /** Immediate durable write — used for planting and stage transitions. */
    public CompletableFuture<Void> saveNow(CropInstance crop) {
        CropSnapshot snapshot = snapshot(crop);
        return db.run(connection -> {
            upsert(connection, snapshot);
            writeCells(connection, snapshot);
        });
    }

    /** Queued write — coalesced until the next {@link #flush()}. */
    public void saveLater(CropInstance crop) {
        synchronized (pendingLock) {
            pendingSaves.put(crop.instanceId(), crop);
        }
    }

    public int pendingWrites() {
        synchronized (pendingLock) {
            return pendingSaves.size();
        }
    }

    public CompletableFuture<Void> flush() {
        List<CropSnapshot> batch;
        synchronized (pendingLock) {
            if (pendingSaves.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            batch = pendingSaves.values().stream().map(CropStorage::snapshot).toList();
            pendingSaves.clear();
        }
        return db.run(connection -> {
            for (CropSnapshot snapshot : batch) {
                upsert(connection, snapshot);
                writeCells(connection, snapshot);
            }
        });
    }

    public CompletableFuture<Void> delete(UUID instanceId) {
        synchronized (pendingLock) {
            pendingSaves.remove(instanceId);
        }
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("harvest_crops") + " WHERE instance_uuid = ?")) {
                ps.setString(1, instanceId.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("harvest_crop_cells") + " WHERE instance_uuid = ?")) {
                ps.setString(1, instanceId.toString());
                ps.executeUpdate();
            }
        });
    }

    // ==================== read path ====================

    /** Loads all crops anchored inside one chunk, including their cells. */
    public CompletableFuture<List<CropInstance>> loadChunk(ChunkKey chunk) {
        int minX = chunk.chunkX() << 4;
        int minZ = chunk.chunkZ() << 4;
        return db.supply(connection -> {
            List<CropInstance> crops = new ArrayList<>();
            Map<UUID, List<BlockPositionKey>> cellsByInstance = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.instance_uuid, c.world_uuid, c.x, c.y, c.z FROM "
                    + db.table("harvest_crop_cells") + " c JOIN " + db.table("harvest_crops") + " r"
                    + " ON c.instance_uuid = r.instance_uuid"
                    + " WHERE r.world_uuid = ? AND r.x BETWEEN ? AND ? AND r.z BETWEEN ? AND ?")) {
                ps.setString(1, chunk.worldId().toString());
                ps.setInt(2, minX);
                ps.setInt(3, minX + 15);
                ps.setInt(4, minZ);
                ps.setInt(5, minZ + 15);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID instance = UUID.fromString(rs.getString(1));
                        cellsByInstance.computeIfAbsent(instance, k -> new ArrayList<>())
                            .add(new BlockPositionKey(UUID.fromString(rs.getString(2)),
                                rs.getInt(3), rs.getInt(4), rs.getInt(5)));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT instance_uuid, crop_id, path_id, world_uuid, x, y, z, stage, state,"
                    + " planted_at, stage_started_at, next_growth_at, remaining_ms,"
                    + " planter_uuid, visual_uuid, fertilizer_id, speed_multiplier FROM " + db.table("harvest_crops")
                    + " WHERE world_uuid = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")) {
                ps.setString(1, chunk.worldId().toString());
                ps.setInt(2, minX);
                ps.setInt(3, minX + 15);
                ps.setInt(4, minZ);
                ps.setInt(5, minZ + 15);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        crops.add(read(rs, cellsByInstance));
                    }
                }
            }
            return crops;
        });
    }

    /** Loads the crop that occupies (owns a cell at) the given position, if any. */
    public CompletableFuture<CropInstance> loadAt(BlockPositionKey position) {
        return db.supply(connection -> {
            UUID instanceId = null;
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT instance_uuid FROM " + db.table("harvest_crop_cells")
                    + " WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, position.worldId().toString());
                ps.setInt(2, position.x());
                ps.setInt(3, position.y());
                ps.setInt(4, position.z());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        instanceId = UUID.fromString(rs.getString(1));
                    }
                }
            }
            if (instanceId == null) {
                return null;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT instance_uuid, crop_id, path_id, world_uuid, x, y, z, stage, state,"
                    + " planted_at, stage_started_at, next_growth_at, remaining_ms,"
                    + " planter_uuid, visual_uuid, fertilizer_id, speed_multiplier FROM " + db.table("harvest_crops")
                    + " WHERE instance_uuid = ?")) {
                ps.setString(1, instanceId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<UUID, List<BlockPositionKey>> cells = new HashMap<>();
                    loadCells(connection, instanceId, cells);
                    return read(rs, cells);
                }
            }
        });
    }

    public CompletableFuture<Integer> countAll() {
        return db.supply(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + db.table("harvest_crops"));
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    // ==================== SQL helpers ====================

    /** Immutable copy taken on the caller's thread before hopping to the DB thread. */
    private record CropSnapshot(
        UUID instanceId, String cropId, String pathId, BlockPositionKey position,
        int stage, String state, long plantedAt, long stageStartedAt,
        long nextGrowthAt, long remainingMs, UUID planter, UUID visual,
        String fertilizerId, double speedMultiplier, List<BlockPositionKey> cells
    ) {}

    private static CropSnapshot snapshot(CropInstance crop) {
        return new CropSnapshot(
            crop.instanceId(), crop.cropId(), crop.pathId(), crop.position(),
            crop.stage(), crop.state().name(), crop.plantedAt(), crop.stageStartedAt(),
            crop.nextGrowthAt(), crop.remainingMs(), crop.planter(), crop.visualEntityId(),
            crop.fertilizerId(), crop.speedMultiplier(), crop.cells());
    }

    private void upsert(Connection connection, CropSnapshot crop) throws SQLException {
        String table = db.table("harvest_crops");
        String update = "crop_id = ?, path_id = ?, stage = ?, state = ?, stage_started_at = ?,"
            + " next_growth_at = ?, remaining_ms = ?, visual_uuid = ?, fertilizer_id = ?,"
            + " speed_multiplier = ?";
        String sql = db.dialect() == HarvestDatabase.Dialect.SQLITE
            ? "INSERT INTO " + table + " (instance_uuid, crop_id, path_id, world_uuid, x, y, z,"
                + " stage, state, planted_at, stage_started_at, next_growth_at, remaining_ms,"
                + " planter_uuid, visual_uuid, fertilizer_id, speed_multiplier)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT(instance_uuid) DO UPDATE SET " + update
            : "INSERT INTO " + table + " (instance_uuid, crop_id, path_id, world_uuid, x, y, z,"
                + " stage, state, planted_at, stage_started_at, next_growth_at, remaining_ms,"
                + " planter_uuid, visual_uuid, fertilizer_id, speed_multiplier)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE " + update;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, crop.instanceId().toString());
            ps.setString(i++, crop.cropId());
            ps.setString(i++, crop.pathId());
            ps.setString(i++, crop.position().worldId().toString());
            ps.setInt(i++, crop.position().x());
            ps.setInt(i++, crop.position().y());
            ps.setInt(i++, crop.position().z());
            ps.setInt(i++, crop.stage());
            ps.setString(i++, crop.state());
            ps.setLong(i++, crop.plantedAt());
            ps.setLong(i++, crop.stageStartedAt());
            ps.setLong(i++, crop.nextGrowthAt());
            ps.setLong(i++, crop.remainingMs());
            ps.setString(i++, crop.planter() == null ? null : crop.planter().toString());
            ps.setString(i++, crop.visual() == null ? null : crop.visual().toString());
            ps.setString(i++, crop.fertilizerId());
            ps.setDouble(i++, crop.speedMultiplier());
            // update clause
            ps.setString(i++, crop.cropId());
            ps.setString(i++, crop.pathId());
            ps.setInt(i++, crop.stage());
            ps.setString(i++, crop.state());
            ps.setLong(i++, crop.stageStartedAt());
            ps.setLong(i++, crop.nextGrowthAt());
            ps.setLong(i++, crop.remainingMs());
            ps.setString(i++, crop.visual() == null ? null : crop.visual().toString());
            ps.setString(i++, crop.fertilizerId());
            ps.setDouble(i, crop.speedMultiplier());
            ps.executeUpdate();
        }
    }

    private void writeCells(Connection connection, CropSnapshot crop) throws SQLException {
        String table = db.table("harvest_crop_cells");
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM " + table + " WHERE instance_uuid = ?")) {
            ps.setString(1, crop.instanceId().toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO " + table + " (instance_uuid, world_uuid, x, y, z) VALUES (?,?,?,?,?)")) {
            for (BlockPositionKey cell : crop.cells()) {
                ps.setString(1, crop.instanceId().toString());
                ps.setString(2, cell.worldId().toString());
                ps.setInt(3, cell.x());
                ps.setInt(4, cell.y());
                ps.setInt(5, cell.z());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void loadCells(Connection connection, UUID instanceId,
                           Map<UUID, List<BlockPositionKey>> out) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT world_uuid, x, y, z FROM " + db.table("harvest_crop_cells")
                + " WHERE instance_uuid = ?")) {
            ps.setString(1, instanceId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(instanceId, k -> new ArrayList<>())
                        .add(new BlockPositionKey(UUID.fromString(rs.getString(1)),
                            rs.getInt(2), rs.getInt(3), rs.getInt(4)));
                }
            }
        }
    }

    private CropInstance read(ResultSet rs, Map<UUID, List<BlockPositionKey>> cells) throws SQLException {
        UUID instanceId = UUID.fromString(rs.getString("instance_uuid"));
        BlockPositionKey position = new BlockPositionKey(
            UUID.fromString(rs.getString("world_uuid")),
            rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
        String planterRaw = rs.getString("planter_uuid");
        String visualRaw = rs.getString("visual_uuid");
        CropState state;
        try {
            state = CropState.valueOf(rs.getString("state"));
        } catch (IllegalArgumentException e) {
            state = CropState.GROWING;
        }
        CropInstance crop = new CropInstance(
            instanceId,
            rs.getString("crop_id"),
            rs.getString("path_id"),
            position,
            rs.getLong("planted_at"),
            planterRaw == null ? null : UUID.fromString(planterRaw),
            rs.getInt("stage"),
            state,
            rs.getLong("stage_started_at"),
            rs.getLong("next_growth_at"),
            rs.getLong("remaining_ms"),
            visualRaw == null ? null : UUID.fromString(visualRaw),
            rs.getString("fertilizer_id"),
            cells.getOrDefault(instanceId, List.of(position))
        );
        // Rows written before the speed column existed read back as 0, which
        // setSpeedMultiplier normalises to "unmodified growth".
        crop.setSpeedMultiplier(rs.getDouble("speed_multiplier"));
        return crop;
    }
}
