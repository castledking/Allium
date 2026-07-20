package codes.castled.allium.harvest.storage;

import codes.castled.allium.harvest.soil.SoilState;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.ChunkKey;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence for tracked soil blocks. */
public final class SoilStorage {

    private final HarvestDatabase db;

    public SoilStorage(HarvestDatabase db) {
        this.db = db;
    }

    public CompletableFuture<Void> save(SoilState soil) {
        BlockPositionKey pos = soil.position();
        long established = soil.establishedAt();
        long exhausted = soil.exhaustedAt();
        String fertilizer = soil.fertilizerId();
        String table = db.table("harvest_soil");
        String update = "established_at = ?, exhausted_at = ?, fertilizer_id = ?";
        String sql = db.dialect() == HarvestDatabase.Dialect.SQLITE
            ? "INSERT INTO " + table + " (world_uuid, x, y, z, established_at, exhausted_at, fertilizer_id)"
                + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(world_uuid, x, y, z) DO UPDATE SET " + update
            : "INSERT INTO " + table + " (world_uuid, x, y, z, established_at, exhausted_at, fertilizer_id)"
                + " VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " + update;
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, pos.worldId().toString());
                ps.setInt(i++, pos.x());
                ps.setInt(i++, pos.y());
                ps.setInt(i++, pos.z());
                ps.setLong(i++, established);
                ps.setLong(i++, exhausted);
                ps.setString(i++, fertilizer);
                ps.setLong(i++, established);
                ps.setLong(i++, exhausted);
                ps.setString(i, fertilizer);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> delete(BlockPositionKey position) {
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("harvest_soil")
                    + " WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, position.worldId().toString());
                ps.setInt(2, position.x());
                ps.setInt(3, position.y());
                ps.setInt(4, position.z());
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<SoilState>> loadChunk(ChunkKey chunk) {
        int minX = chunk.chunkX() << 4;
        int minZ = chunk.chunkZ() << 4;
        return db.supply(connection -> {
            List<SoilState> soils = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world_uuid, x, y, z, established_at, exhausted_at, fertilizer_id FROM "
                    + db.table("harvest_soil")
                    + " WHERE world_uuid = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")) {
                ps.setString(1, chunk.worldId().toString());
                ps.setInt(2, minX);
                ps.setInt(3, minX + 15);
                ps.setInt(4, minZ);
                ps.setInt(5, minZ + 15);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        soils.add(new SoilState(
                            new BlockPositionKey(UUID.fromString(rs.getString("world_uuid")),
                                rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                            rs.getLong("established_at"),
                            rs.getLong("exhausted_at"),
                            rs.getString("fertilizer_id")));
                    }
                }
            }
            return soils;
        });
    }

    /**
     * Deletes soil records that wore out longer than {@code graceMillis} ago.
     *
     * <p>Records are kept for a while past exhaustion on purpose — that is what
     * makes breaking and replacing farmland fail to reset the timer. Past the
     * grace window the spot is genuinely forgotten and behaves like new ground,
     * which also stops the table from growing without bound.
     */
    public CompletableFuture<Integer> purgeExhausted(long now, long graceMillis) {
        long cutoff = now - Math.max(0L, graceMillis);
        return db.supply(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("harvest_soil")
                    + " WHERE exhausted_at > 0 AND exhausted_at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Integer> countAll() {
        return db.supply(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + db.table("harvest_soil"));
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
