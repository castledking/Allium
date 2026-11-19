package codes.castled.allium.economy;

import codes.castled.allium.storage.ConnectionSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQLite persistence for {@code /tax impose}, {@code /tax history} and {@code /tax refund}. */
public final class TaxHistoryRepository {

    public record AccountBalance(UUID accountUuid, Money available) {}

    private final ConnectionSource connections;

    public TaxHistoryRepository(ConnectionSource connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Inserts an imposed-tax history entry inside the TRANSFER transaction. */
    public boolean insertImpose(Connection c, UUID operationId, UUID playerUuid, String playerName,
                                Money amount, int percentageBps, Money balanceBefore, String imposer,
                                long createdAt) throws SQLException {
        String sql = "INSERT INTO " + EconomySchema.TAX_HISTORY
                + " (operation_id,player_uuid,player_name,amount_minor,percentage_bps,balance_before_minor,imposer,created_at) "
                + "VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, operationId.toString());
            s.setString(2, playerUuid.toString());
            s.setString(3, playerName);
            s.setLong(4, amount.minor());
            s.setInt(5, percentageBps);
            s.setLong(6, balanceBefore.minor());
            s.setString(7, imposer);
            s.setLong(8, createdAt);
            return s.executeUpdate() == 1;
        }
    }

    /** Atomically marks an unrefunded history row refunded inside the refund TRANSFER transaction. */
    public boolean markRefunded(Connection c, long taxId, Money refundAmount, int refundPercentageBps,
                                String refunder, UUID refundOperationId, long refundedAt) throws SQLException {
        String sql = "UPDATE " + EconomySchema.TAX_HISTORY
                + " SET refunded=1, refund_amount_minor=?, refund_percentage_bps=?, refunder=?, "
                + "refund_operation_id=?, refunded_at=? WHERE tax_id=? AND refunded=0";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, refundAmount.minor());
            s.setInt(2, refundPercentageBps);
            s.setString(3, refunder);
            s.setString(4, refundOperationId.toString());
            s.setLong(5, refundedAt);
            s.setLong(6, taxId);
            return s.executeUpdate() == 1;
        }
    }

    public Optional<TaxHistoryRecord> findById(long taxId) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + EconomySchema.TAX_HISTORY + " WHERE tax_id=?";
        try (var c = connections.connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, taxId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    public Optional<TaxHistoryRecord> findByOperation(UUID operationId) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + EconomySchema.TAX_HISTORY + " WHERE operation_id=?";
        try (var c = connections.connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, operationId.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    public Optional<TaxHistoryRecord> findLatestUnrefunded(UUID playerUuid) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + EconomySchema.TAX_HISTORY
                + " WHERE player_uuid=? AND refunded=0 ORDER BY tax_id DESC LIMIT 1";
        try (var c = connections.connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, playerUuid.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    public List<TaxHistoryRecord> list(UUID playerUuid, int limit, int offset) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + EconomySchema.TAX_HISTORY
                + (playerUuid == null ? "" : " WHERE player_uuid=?")
                + " ORDER BY tax_id DESC LIMIT ? OFFSET ?";
        var out = new ArrayList<TaxHistoryRecord>();
        try (var c = connections.connection(); PreparedStatement s = c.prepareStatement(sql)) {
            int idx = 1;
            if (playerUuid != null) s.setString(idx++, playerUuid.toString());
            s.setInt(idx++, limit);
            s.setInt(idx, offset);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(read(r));
            }
        }
        return List.copyOf(out);
    }

    public int count(UUID playerUuid) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + EconomySchema.TAX_HISTORY
                + (playerUuid == null ? "" : " WHERE player_uuid=?");
        try (var c = connections.connection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (playerUuid != null) s.setString(1, playerUuid.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        }
    }

    /** Lists every account with positive available funds, excluding the tax account itself. */
    public List<AccountBalance> listPositiveAccounts(UUID excludeAccount) throws SQLException {
        String sql = "SELECT account_uuid, balance_minor - reserved_minor FROM " + EconomySchema.ACCOUNTS
                + " WHERE account_uuid<>? AND (balance_minor - reserved_minor) > 0 ORDER BY account_uuid";
        var out = new ArrayList<AccountBalance>();
        try (var c = connections.connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, excludeAccount.toString());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new AccountBalance(UUID.fromString(r.getString(1)), new Money(r.getLong(2))));
                }
            }
        }
        return List.copyOf(out);
    }

    private static final String COLUMNS =
            "tax_id,operation_id,player_uuid,player_name,amount_minor,percentage_bps,balance_before_minor,"
                    + "imposer,created_at,refunded,refund_amount_minor,refund_percentage_bps,refunder,"
                    + "refund_operation_id,refunded_at";

    private static TaxHistoryRecord read(ResultSet r) throws SQLException {
        String refundOp = r.getString(14);
        long refundedAtRaw = r.getLong(15);
        Long refundedAt = r.wasNull() ? null : refundedAtRaw;
        return new TaxHistoryRecord(
                r.getLong(1),
                UUID.fromString(r.getString(2)),
                UUID.fromString(r.getString(3)),
                r.getString(4),
                new Money(r.getLong(5)),
                r.getInt(6),
                new Money(r.getLong(7)),
                r.getString(8),
                r.getLong(9),
                r.getInt(10) == 1,
                new Money(r.getLong(11)),
                r.getInt(12),
                r.getString(13),
                refundOp == null ? null : UUID.fromString(refundOp),
                refundedAt
        );
    }
}
