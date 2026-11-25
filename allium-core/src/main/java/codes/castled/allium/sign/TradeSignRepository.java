package codes.castled.allium.sign;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Authoritative SQLite/MySQL storage for player-owned {@code [Trade]} sign stock and accumulated
 * earnings. Every stock/earnings mutation runs atomically inside SQL so concurrent clicks or
 * crashes never duplicate items or produce negative stock.
 */
public final class TradeSignRepository {

    public static final String COMPONENT = "trade_signs";
    public static final String TABLE = "allium_trade_signs";

    private static final String CREATE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "sign_id CHAR(36) NOT NULL PRIMARY KEY, "
            + "owner_uuid CHAR(36) NOT NULL, "
            + "owner_name VARCHAR(32) NOT NULL, "
            + "world_name VARCHAR(64) NOT NULL, "
            + "x INTEGER NOT NULL, "
            + "y INTEGER NOT NULL, "
            + "z INTEGER NOT NULL, "
            + "side VARCHAR(8) NOT NULL, "
            + "pay_kind VARCHAR(8) NOT NULL, "
            + "pay_item VARCHAR(128) NOT NULL, "
            + "pay_amount BIGINT NOT NULL, "
            + "give_kind VARCHAR(8) NOT NULL, "
            + "give_item VARCHAR(128) NOT NULL, "
            + "give_amount BIGINT NOT NULL, "
            + "stock_amount BIGINT NOT NULL, "
            + "earned_amount BIGINT NOT NULL, "
            + "version BIGINT NOT NULL, "
            + "updated_at BIGINT NOT NULL)";

    private static final String INDEX_OWNER = "CREATE INDEX IF NOT EXISTS idx_allium_trade_signs_owner "
            + "ON " + TABLE + " (owner_uuid)";

    public static final List<Migration> MIGRATIONS = List.of(
            Migration.of(1, "create " + TABLE, List.of(CREATE, INDEX_OWNER), List.of(CREATE, INDEX_OWNER))
    );

    public record TradeSignRecord(
            UUID signId,
            UUID ownerUuid,
            String ownerName,
            String worldName,
            int x,
            int y,
            int z,
            String side,
            TradeSignSpec.Kind payKind,
            String payItem,
            long payAmount,
            TradeSignSpec.Kind giveKind,
            String giveItem,
            long giveAmount,
            long stockAmount,
            long earnedAmount,
            long version,
            long updatedAt
    ) {
        public TradeSignSpec.Offer payOffer() {
            return new TradeSignSpec.Offer(payKind, payItem, payAmount);
        }

        public TradeSignSpec.Offer giveOffer() {
            return new TradeSignSpec.Offer(giveKind, giveItem, giveAmount);
        }
    }

    public record CollectResult(long collected, TradeSignRecord after) {}

    public record ReclaimResult(long stockAmount, long earnedAmount, TradeSignRecord snapshot) {}

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public TradeSignRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, MIGRATIONS);
    }

    public void create(TradeSignRecord record) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO " + TABLE + " (sign_id, owner_uuid, owner_name, world_name, x, y, z, side, "
                             + "pay_kind, pay_item, pay_amount, give_kind, give_item, give_amount, "
                             + "stock_amount, earned_amount, version, updated_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)")) {
            ps.setString(1, record.signId().toString());
            ps.setString(2, record.ownerUuid().toString());
            ps.setString(3, record.ownerName());
            ps.setString(4, record.worldName());
            ps.setInt(5, record.x());
            ps.setInt(6, record.y());
            ps.setInt(7, record.z());
            ps.setString(8, record.side());
            ps.setString(9, record.payKind().name());
            ps.setString(10, record.payItem());
            ps.setLong(11, record.payAmount());
            ps.setString(12, record.giveKind().name());
            ps.setString(13, record.giveItem());
            ps.setLong(14, record.giveAmount());
            ps.setLong(15, record.stockAmount());
            ps.setLong(16, record.earnedAmount());
            ps.setLong(17, clock.getAsLong());
            ps.executeUpdate();
        }
    }

    public Optional<TradeSignRecord> load(UUID signId) throws SQLException {
        try (Connection c = connections.connection()) {
            return load(c, signId);
        }
    }

    private Optional<TradeSignRecord> load(Connection c, UUID signId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sign_id, owner_uuid, owner_name, world_name, x, y, z, side, "
                        + "pay_kind, pay_item, pay_amount, give_kind, give_item, give_amount, "
                        + "stock_amount, earned_amount, version, updated_at "
                        + "FROM " + TABLE + " WHERE sign_id = ?")) {
            ps.setString(1, signId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TradeSignRecord(
                        UUID.fromString(rs.getString(1)),
                        UUID.fromString(rs.getString(2)),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getInt(6),
                        rs.getInt(7),
                        rs.getString(8),
                        TradeSignSpec.Kind.fromCode(rs.getString(9)),
                        rs.getString(10),
                        rs.getLong(11),
                        TradeSignSpec.Kind.fromCode(rs.getString(12)),
                        rs.getString(13),
                        rs.getLong(14),
                        rs.getLong(15),
                        rs.getLong(16),
                        rs.getLong(17),
                        rs.getLong(18)
                ));
            }
        }
    }

    /**
     * Atomically applies {@code stockDelta} and {@code earnedDelta} only if both resulting balances
     * remain non-negative. Returns the updated record, or empty if the sign does not exist or would
     * underflow.
     */
    public Optional<TradeSignRecord> applyDelta(UUID signId, long stockDelta, long earnedDelta) throws SQLException {
        try (Connection c = connections.connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + TABLE + " SET stock_amount = stock_amount + ?, earned_amount = earned_amount + ?, "
                                + "version = version + 1, updated_at = ? "
                                + "WHERE sign_id = ? AND stock_amount + ? >= 0 AND earned_amount + ? >= 0")) {
                    ps.setLong(1, stockDelta);
                    ps.setLong(2, earnedDelta);
                    ps.setLong(3, clock.getAsLong());
                    ps.setString(4, signId.toString());
                    ps.setLong(5, stockDelta);
                    ps.setLong(6, earnedDelta);
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        return Optional.empty();
                    }
                }
                Optional<TradeSignRecord> updated = load(c, signId);
                c.commit();
                return updated;
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /**
     * Atomically deducts up to {@code maxAmount} from {@code earned_amount} and returns the exact
     * amount collected alongside the updated record.
     */
    public Optional<CollectResult> collectEarned(UUID signId, long maxAmount) throws SQLException {
        if (maxAmount <= 0) {
            return load(signId).map(rec -> new CollectResult(0L, rec));
        }
        try (Connection c = connections.connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Optional<TradeSignRecord> currentOpt = load(c, signId);
                if (currentOpt.isEmpty()) {
                    c.rollback();
                    return Optional.empty();
                }
                TradeSignRecord current = currentOpt.get();
                long collected = Math.min(current.earnedAmount(), maxAmount);
                if (collected <= 0) {
                    c.commit();
                    return Optional.of(new CollectResult(0L, current));
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + TABLE + " SET earned_amount = earned_amount - ?, "
                                + "version = version + 1, updated_at = ? "
                                + "WHERE sign_id = ? AND version = ? AND earned_amount >= ?")) {
                    ps.setLong(1, collected);
                    ps.setLong(2, clock.getAsLong());
                    ps.setString(3, signId.toString());
                    ps.setLong(4, current.version());
                    ps.setLong(5, collected);
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        return Optional.empty();
                    }
                }
                TradeSignRecord after = load(c, signId).orElseThrow(() -> new SQLException("trade sign vanished"));
                c.commit();
                return Optional.of(new CollectResult(collected, after));
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /** Atomically removes the trade sign row and returns the stock and earnings it held. */
    public Optional<ReclaimResult> deleteAndReclaim(UUID signId) throws SQLException {
        try (Connection c = connections.connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Optional<TradeSignRecord> currentOpt = load(c, signId);
                if (currentOpt.isEmpty()) {
                    c.rollback();
                    return Optional.empty();
                }
                TradeSignRecord current = currentOpt.get();
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + TABLE + " WHERE sign_id = ? AND version = ?")) {
                    ps.setString(1, signId.toString());
                    ps.setLong(2, current.version());
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        return Optional.empty();
                    }
                }
                c.commit();
                return Optional.of(new ReclaimResult(current.stockAmount(), current.earnedAmount(), current));
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    public boolean delete(UUID signId) throws SQLException {
        try (Connection c = connections.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE sign_id = ?")) {
            ps.setString(1, signId.toString());
            return ps.executeUpdate() > 0;
        }
    }
}
