package codes.castled.allium.harvest.storage;

import codes.castled.allium.harvest.sprinkler.PlacedSprinkler;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence for placed sprinklers. */
public final class SprinklerStorage {

    private final HarvestDatabase db;

    public SprinklerStorage(HarvestDatabase db) {
        this.db = db;
    }

    public CompletableFuture<Void> save(PlacedSprinkler sprinkler) {
        BlockPositionKey pos = sprinkler.position();
        String id = sprinkler.sprinklerId();
        UUID visual = sprinkler.visualEntityId();
        long placedAt = sprinkler.placedAt();
        UUID owner = sprinkler.owner();
        String table = db.table("harvest_sprinklers");
        String update = "sprinkler_id = ?, visual_uuid = ?";
        String sql = db.dialect() == HarvestDatabase.Dialect.SQLITE
            ? "INSERT INTO " + table + " (world_uuid, x, y, z, sprinkler_id, visual_uuid, placed_at, owner_uuid)"
                + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(world_uuid, x, y, z) DO UPDATE SET " + update
            : "INSERT INTO " + table + " (world_uuid, x, y, z, sprinkler_id, visual_uuid, placed_at, owner_uuid)"
                + " VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " + update;
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, pos.worldId().toString());
                ps.setInt(i++, pos.x());
                ps.setInt(i++, pos.y());
                ps.setInt(i++, pos.z());
                ps.setString(i++, id);
                ps.setString(i++, visual == null ? null : visual.toString());
                ps.setLong(i++, placedAt);
                ps.setString(i++, owner == null ? null : owner.toString());
                ps.setString(i++, id);
                ps.setString(i, visual == null ? null : visual.toString());
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> delete(BlockPositionKey position) {
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("harvest_sprinklers")
                    + " WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, position.worldId().toString());
                ps.setInt(2, position.x());
                ps.setInt(3, position.y());
                ps.setInt(4, position.z());
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<PlacedSprinkler>> loadChunk(ChunkKey chunk) {
        int minX = chunk.chunkX() << 4;
        int minZ = chunk.chunkZ() << 4;
        return db.supply(connection -> {
            List<PlacedSprinkler> sprinklers = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world_uuid, x, y, z, sprinkler_id, visual_uuid, placed_at, owner_uuid FROM "
                    + db.table("harvest_sprinklers")
                    + " WHERE world_uuid = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")) {
                ps.setString(1, chunk.worldId().toString());
                ps.setInt(2, minX);
                ps.setInt(3, minX + 15);
                ps.setInt(4, minZ);
                ps.setInt(5, minZ + 15);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String visualRaw = rs.getString("visual_uuid");
                        String ownerRaw = rs.getString("owner_uuid");
                        sprinklers.add(new PlacedSprinkler(
                            new BlockPositionKey(UUID.fromString(rs.getString("world_uuid")),
                                rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                            rs.getString("sprinkler_id"),
                            rs.getLong("placed_at"),
                            ownerRaw == null ? null : UUID.fromString(ownerRaw),
                            visualRaw == null ? null : UUID.fromString(visualRaw)));
                    }
                }
            }
            return sprinklers;
        });
    }

    public CompletableFuture<Integer> countAll() {
        return db.supply(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + db.table("harvest_sprinklers"));
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
