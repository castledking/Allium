package codes.castled.allium.economy;

import codes.castled.allium.storage.ConnectionSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQL persistence for journalled player-funded and admin-issued cheques. */
public final class ChequeRepository {

    private final ConnectionSource connections;

    public ChequeRepository(ConnectionSource connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Inserts a PENDING player-funded cheque inside the RESERVE transaction. */
    public boolean insertPending(Connection c, ChequeRecord record) throws SQLException {
        return insert(c, record, "PENDING");
    }

    /** Inserts an immediately ISSUED admin cheque (no payer hold). */
    public boolean insertAdminIssued(ChequeRecord record) throws SQLException {
        try (var c = connections.connection()) {
            return insert(c, record, "ISSUED");
        }
    }

    private static boolean insert(Connection c, ChequeRecord r, String state) throws SQLException {
        String sql = "INSERT INTO " + EconomySchema.CHEQUES
                + " (cheque_id,amount_minor,tax_minor,issuer_uuid,issuer_name,admin_issued,state,"
                + "issue_operation_id,redeem_operation_id,redeemer_uuid,created_at,redeemed_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, r.chequeId().toString());
            s.setLong(2, r.amount().minor());
            s.setLong(3, r.tax().minor());
            if (r.issuerUuid() == null) s.setNull(4, Types.VARCHAR);
            else s.setString(4, r.issuerUuid().toString());
            s.setString(5, r.issuerName());
            s.setInt(6, r.adminIssued() ? 1 : 0);
            s.setString(7, state);
            if (r.issueOperationId() == null) s.setNull(8, Types.VARCHAR);
            else s.setString(8, r.issueOperationId().toString());
            s.setNull(9, Types.VARCHAR);
            s.setNull(10, Types.VARCHAR);
            s.setLong(11, r.createdAt());
            s.setNull(12, Types.BIGINT);
            return s.executeUpdate() == 1;
        }
    }

    /** Transitions PENDING -> ISSUED inside the CAPTURE transaction. */
    public boolean markIssued(Connection c, UUID chequeId, UUID issueOperationId) throws SQLException {
        String sql = "UPDATE " + EconomySchema.CHEQUES
                + " SET state='ISSUED', issue_operation_id=? WHERE cheque_id=? AND state='PENDING'";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, issueOperationId.toString());
            s.setString(2, chequeId.toString());
            return s.executeUpdate() == 1;
        }
    }

    /** Transitions PENDING or ISSUED -> VOIDED when a hold is released or admin delivery fails. */
    public boolean markVoided(Connection c, UUID chequeId) throws SQLException {
        String sql = "UPDATE " + EconomySchema.CHEQUES
                + " SET state='VOIDED' WHERE cheque_id=? AND state IN ('PENDING','ISSUED')";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, chequeId.toString());
            return s.executeUpdate() == 1;
        }
    }

    public boolean markVoided(UUID chequeId) throws SQLException {
        try (var c = connections.connection()) {
            return markVoided(c, chequeId);
        }
    }

    /** Atomically marks ISSUED -> REDEEMED inside the redeemer's DEPOSIT transaction. */
    public boolean markRedeemed(Connection c, UUID chequeId, Money expectedAmount,
                                UUID redeemOperationId, UUID redeemerUuid, long now) throws SQLException {
        String sql = "UPDATE " + EconomySchema.CHEQUES
                + " SET state='REDEEMED', redeem_operation_id=?, redeemer_uuid=?, redeemed_at=? "
                + "WHERE cheque_id=? AND state='ISSUED' AND amount_minor=?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, redeemOperationId.toString());
            s.setString(2, redeemerUuid.toString());
            s.setLong(3, now);
            s.setString(4, chequeId.toString());
            s.setLong(5, expectedAmount.minor());
            return s.executeUpdate() == 1;
        }
    }

    public Optional<ChequeRecord> find(UUID chequeId) throws SQLException {
        try (var c = connections.connection()) {
            return find(c, chequeId);
        }
    }

    public static Optional<ChequeRecord> find(Connection c, UUID chequeId) throws SQLException {
        String sql = "SELECT cheque_id,amount_minor,tax_minor,issuer_uuid,issuer_name,admin_issued,state,"
                + "issue_operation_id,redeem_operation_id,redeemer_uuid,created_at,redeemed_at "
                + "FROM " + EconomySchema.CHEQUES + " WHERE cheque_id=?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, chequeId.toString());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                String issuerUuid = r.getString(4);
                String issueOp = r.getString(8);
                String redeemOp = r.getString(9);
                String redeemerUuid = r.getString(10);
                long redeemedAtRaw = r.getLong(12);
                Long redeemedAt = r.wasNull() ? null : redeemedAtRaw;
                return Optional.of(new ChequeRecord(
                        UUID.fromString(r.getString(1)),
                        new Money(r.getLong(2)),
                        new Money(r.getLong(3)),
                        issuerUuid == null ? null : UUID.fromString(issuerUuid),
                        r.getString(5),
                        r.getInt(6) == 1,
                        r.getString(7),
                        issueOp == null ? null : UUID.fromString(issueOp),
                        redeemOp == null ? null : UUID.fromString(redeemOp),
                        redeemerUuid == null ? null : UUID.fromString(redeemerUuid),
                        r.getLong(11),
                        redeemedAt
                ));
            }
        }
    }
}
