package codes.castled.allium.restore;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Inventory snapshots for {@code /restore} (legacy Allium's restore history), and restores queued
 * for offline players. Blocking: callers use a bounded pool, never the server thread.
 *
 * <p>Item payloads are opaque here: the plugin's versioned, data-version aware item codec writes
 * and reads them. Storage stays bounded by a per-player cap (oldest first) and an age limit.
 */
public final class SnapshotRepository {

    public static final String COMPONENT = "restore";

    /** One saved snapshot. {@code inventory} holds slots 0-40 (storage, armor, offhand); {@code enderChest} 0-26. */
    public record Snapshot(UUID id, UUID player, long createdAt, String reason, String world, double x, double y, double z,
                           float yaw, float pitch, int level, float progress, @Nullable String inventory,
                           @Nullable String enderChest, String createdBy) {
    }

    /** A restore waiting for an offline player's next join; the parts flags say what it replaces. */
    public record Pending(UUID id, UUID player, Snapshot snapshot, boolean inventory, boolean armor, boolean experience,
                          boolean enderChest, String queuedBy, long queuedAt) {
    }

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public SnapshotRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = connections;
        this.dialect = dialect;
        this.clock = clock;
    }

    static List<Migration> migrations() {
        return List.of(Migration.of(1, "inventory snapshots and pending restores", v1(SqlDialect.SQLITE), v1(SqlDialect.MYSQL)));
    }

    private static List<String> v1(SqlDialect dialect) {
        String text = dialect == SqlDialect.MYSQL ? "MEDIUMTEXT" : "TEXT";
        String columns = "player CHAR(36) NOT NULL, created_at BIGINT NOT NULL, reason VARCHAR(64) NOT NULL, world VARCHAR(128) NOT NULL, "
                + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, "
                + "level INT NOT NULL, progress FLOAT NOT NULL, inventory " + text + " NULL, ender_chest " + text + " NULL, "
                + "created_by VARCHAR(64) NOT NULL";
        return List.of(
                "CREATE TABLE IF NOT EXISTS allium_inventory_snapshots (id CHAR(36) NOT NULL PRIMARY KEY, " + columns + ")",
                "CREATE INDEX allium_inventory_snapshots_by_player ON allium_inventory_snapshots (player, created_at)",
                "CREATE INDEX allium_inventory_snapshots_by_age ON allium_inventory_snapshots (created_at)",
                "CREATE TABLE IF NOT EXISTS allium_restore_pending (id CHAR(36) NOT NULL PRIMARY KEY, " + columns + ", "
                        + "restore_inventory BOOLEAN NOT NULL, restore_armor BOOLEAN NOT NULL, restore_experience BOOLEAN NOT NULL, "
                        + "restore_ender_chest BOOLEAN NOT NULL, queued_by VARCHAR(64) NOT NULL, queued_at BIGINT NOT NULL)",
                "CREATE INDEX allium_restore_pending_by_player ON allium_restore_pending (player, queued_at)");
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, migrations());
    }

    /** Stores {@code snapshot}, then trims its player's history to the newest {@code keep}. */
    public void save(Snapshot snapshot, int keep) throws SQLException {
        try (Connection c = connections.connection()) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO allium_inventory_snapshots (id, player, created_at, reason, world, "
                    + "x, y, z, yaw, pitch, level, progress, inventory, ender_chest, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindSnapshot(s, 1, snapshot);
                s.executeUpdate();
            }
            if (keep > 0) {
                List<String> excess = new ArrayList<>();
                try (PreparedStatement s = c.prepareStatement("SELECT id FROM allium_inventory_snapshots WHERE player = ? "
                        + "ORDER BY created_at DESC, id DESC")) {
                    s.setString(1, snapshot.player().toString());
                    try (ResultSet r = s.executeQuery()) {
                        int n = 0;
                        while (r.next()) {
                            if (++n > keep) excess.add(r.getString(1));
                        }
                    }
                }
                for (String id : excess) {
                    try (PreparedStatement s = c.prepareStatement("DELETE FROM allium_inventory_snapshots WHERE id = ?")) {
                        s.setString(1, id);
                        s.executeUpdate();
                    }
                }
            }
        }
    }

    /** {@code limit} snapshots of {@code player}, newest first, skipping {@code offset}. */
    public List<Snapshot> page(UUID player, int offset, int limit) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM allium_inventory_snapshots WHERE player = ? "
                     + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")) {
            s.setString(1, player.toString());
            s.setInt(2, limit);
            s.setInt(3, offset);
            List<Snapshot> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(snapshot(r));
            }
            return out;
        }
    }

    public int count(UUID player) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM allium_inventory_snapshots WHERE player = ?")) {
            s.setString(1, player.toString());
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    public Optional<Snapshot> find(UUID id) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM allium_inventory_snapshots WHERE id = ?")) {
            s.setString(1, id.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(snapshot(r)) : Optional.empty();
            }
        }
    }

    /** Deletes snapshots older than {@code before}. */
    public int purge(long before) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("DELETE FROM allium_inventory_snapshots WHERE created_at < ?")) {
            s.setLong(1, before);
            return s.executeUpdate();
        }
    }

    /** Queues a restore for {@code pending.player()}'s next join. */
    public void queue(Pending pending) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("INSERT INTO allium_restore_pending (id, player, created_at, reason, world, x, y, z, "
                     + "yaw, pitch, level, progress, inventory, ender_chest, created_by, restore_inventory, restore_armor, "
                     + "restore_experience, restore_ender_chest, queued_by, queued_at) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            Snapshot snap = pending.snapshot();
            Snapshot row = new Snapshot(pending.id(), pending.player(), snap.createdAt(), snap.reason(), snap.world(), snap.x(), snap.y(),
                    snap.z(), snap.yaw(), snap.pitch(), snap.level(), snap.progress(), snap.inventory(), snap.enderChest(), snap.createdBy());
            int i = bindSnapshot(s, 1, row);
            s.setBoolean(i++, pending.inventory());
            s.setBoolean(i++, pending.armor());
            s.setBoolean(i++, pending.experience());
            s.setBoolean(i++, pending.enderChest());
            s.setString(i++, pending.queuedBy());
            s.setLong(i, pending.queuedAt());
            s.executeUpdate();
        }
    }

    /** Restores waiting for {@code player}, oldest first. */
    public List<Pending> pending(UUID player) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM allium_restore_pending WHERE player = ? ORDER BY queued_at, id")) {
            s.setString(1, player.toString());
            List<Pending> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new Pending(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("player")), snapshot(r),
                            r.getBoolean("restore_inventory"), r.getBoolean("restore_armor"), r.getBoolean("restore_experience"),
                            r.getBoolean("restore_ender_chest"), r.getString("queued_by"), r.getLong("queued_at")));
                }
            }
            return out;
        }
    }

    /** Removes a pending restore once it is applied (and saved) or proven applied. */
    public boolean finish(UUID pending) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement s = c.prepareStatement("DELETE FROM allium_restore_pending WHERE id = ?")) {
            s.setString(1, pending.toString());
            return s.executeUpdate() == 1;
        }
    }

    private static int bindSnapshot(PreparedStatement s, int i, Snapshot snap) throws SQLException {
        s.setString(i++, snap.id().toString());
        s.setString(i++, snap.player().toString());
        s.setLong(i++, snap.createdAt());
        s.setString(i++, truncate(snap.reason(), 64));
        s.setString(i++, truncate(snap.world(), 128));
        s.setDouble(i++, snap.x());
        s.setDouble(i++, snap.y());
        s.setDouble(i++, snap.z());
        s.setFloat(i++, snap.yaw());
        s.setFloat(i++, snap.pitch());
        s.setInt(i++, snap.level());
        s.setFloat(i++, snap.progress());
        s.setString(i++, snap.inventory());
        s.setString(i++, snap.enderChest());
        s.setString(i++, truncate(snap.createdBy(), 64));
        return i;
    }

    private static Snapshot snapshot(ResultSet r) throws SQLException {
        return new Snapshot(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("player")), r.getLong("created_at"),
                r.getString("reason"), r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"),
                r.getFloat("yaw"), r.getFloat("pitch"), r.getInt("level"), r.getFloat("progress"), r.getString("inventory"),
                r.getString("ender_chest"), r.getString("created_by"));
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
