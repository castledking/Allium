package codes.castled.allium.harvest.storage;

import codes.castled.allium.harvest.spawner.TrackedSpawner;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence for tracked spawner locations. */
public final class SpawnerStorage {

    private final HarvestDatabase db;

    public SpawnerStorage(HarvestDatabase db) {
        this.db = db;
    }

    public CompletableFuture<Void> save(TrackedSpawner spawner) {
        BlockPositionKey pos = spawner.position();
        String entityType = spawner.entityType();
        UUID visual = spawner.visualEntityId();
        long updatedAt = spawner.updatedAt();
        String table = db.table("harvest_tracked_spawners");
        String update = "entity_type = ?, visual_uuid = ?, updated_at = ?";
        String sql = db.dialect() == HarvestDatabase.Dialect.SQLITE
            ? "INSERT INTO " + table + " (world_uuid, x, y, z, entity_type, visual_uuid, updated_at)"
                + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(world_uuid, x, y, z) DO UPDATE SET " + update
            : "INSERT INTO " + table + " (world_uuid, x, y, z, entity_type, visual_uuid, updated_at)"
                + " VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " + update;
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, pos.worldId().toString());
                ps.setInt(i++, pos.x());
                ps.setInt(i++, pos.y());
                ps.setInt(i++, pos.z());
                ps.setString(i++, entityType);
                ps.setString(i++, visual == null ? null : visual.toString());
                ps.setLong(i++, updatedAt);
                ps.setString(i++, entityType);
                ps.setString(i++, visual == null ? null : visual.toString());
                ps.setLong(i, updatedAt);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> delete(BlockPositionKey position) {
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("harvest_tracked_spawners")
                    + " WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, position.worldId().toString());
                ps.setInt(2, position.x());
                ps.setInt(3, position.y());
                ps.setInt(4, position.z());
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<TrackedSpawner>> loadChunk(ChunkKey chunk) {
        int minX = chunk.chunkX() << 4;
        int minZ = chunk.chunkZ() << 4;
        return db.supply(connection -> {
            List<TrackedSpawner> spawners = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world_uuid, x, y, z, entity_type, visual_uuid, updated_at FROM "
                    + db.table("harvest_tracked_spawners")
                    + " WHERE world_uuid = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")) {
                ps.setString(1, chunk.worldId().toString());
                ps.setInt(2, minX);
                ps.setInt(3, minX + 15);
                ps.setInt(4, minZ);
                ps.setInt(5, minZ + 15);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String visualRaw = rs.getString("visual_uuid");
                        spawners.add(new TrackedSpawner(
                            new BlockPositionKey(UUID.fromString(rs.getString("world_uuid")),
                                rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                            rs.getString("entity_type"),
                            visualRaw == null ? null : UUID.fromString(visualRaw),
                            rs.getLong("updated_at")));
                    }
                }
            }
            return spawners;
        });
    }

    public CompletableFuture<Integer> countAll() {
        return db.supply(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + db.table("harvest_tracked_spawners"));
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
