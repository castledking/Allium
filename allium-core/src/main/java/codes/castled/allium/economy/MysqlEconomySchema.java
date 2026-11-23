package codes.castled.allium.economy;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** InnoDB schema for exact balances and the receipt-backed workflows. */
final class MysqlEconomySchema {
    private static final String WRITER = "allium_economy_writer";
    private static final String AUDIT = "allium_economy_reservation_audit";
    private static final String INNODB = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
    private static final String ACCOUNT = EconomySchema.ACCOUNTS;
    private static final String OPERATION = EconomySchema.OPERATIONS;

    private MysqlEconomySchema() {}

    static void migrate(ConnectionSource source) throws SQLException {
        String bound = Long.toString(Money.HARD);
        List<String> ddl = List.of(
                "CREATE TABLE IF NOT EXISTS " + ACCOUNT + " ("
                        + "account_uuid CHAR(36) PRIMARY KEY, balance_minor BIGINT NOT NULL CHECK(balance_minor BETWEEN -"
                        + bound + " AND " + bound + "), reserved_minor BIGINT NOT NULL DEFAULT 0 CHECK(reserved_minor>=0), "
                        + "version BIGINT NOT NULL CHECK(version>=0), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)" + INNODB,
                "CREATE TABLE IF NOT EXISTS " + OPERATION + " ("
                        + "operation_id CHAR(36) PRIMARY KEY, origin VARBINARY(512) NOT NULL, request_key VARBINARY(1024) NOT NULL, "
                        + "kind VARCHAR(32) NOT NULL, actor VARCHAR(128) NOT NULL, source_uuid CHAR(36) NOT NULL, recipient_uuid CHAR(36), "
                        + "amount_minor BIGINT NOT NULL, payload BLOB NOT NULL, payload_hash BINARY(32) NOT NULL, "
                        + "state VARCHAR(16) NOT NULL CHECK(state IN ('COMMITTED','REJECTED')), result_code VARCHAR(64) NOT NULL, "
                        + "source_balance BIGINT, source_reserved BIGINT, source_version BIGINT, recipient_balance BIGINT, "
                        + "recipient_reserved BIGINT, recipient_version BIGINT, created_at BIGINT NOT NULL, finished_at BIGINT NOT NULL, "
                        + "UNIQUE KEY economy_request_key (origin,request_key))" + INNODB,
                "CREATE TABLE IF NOT EXISTS " + EconomySchema.ENTRIES + " ("
                        + "operation_id CHAR(36) NOT NULL, account_uuid CHAR(36) NOT NULL, book_delta BIGINT NOT NULL, held_delta BIGINT NOT NULL, "
                        + "balance_before BIGINT NOT NULL, balance_after BIGINT NOT NULL, reserved_before BIGINT NOT NULL, reserved_after BIGINT NOT NULL, "
                        + "account_version_after BIGINT NOT NULL, PRIMARY KEY(operation_id,account_uuid), UNIQUE KEY economy_account_version (account_uuid,account_version_after), "
                        + "FOREIGN KEY(operation_id) REFERENCES " + OPERATION + "(operation_id) ON DELETE RESTRICT, "
                        + "FOREIGN KEY(account_uuid) REFERENCES " + ACCOUNT + "(account_uuid) ON DELETE RESTRICT)" + INNODB,
                "CREATE TABLE IF NOT EXISTS " + EconomySchema.RESERVATIONS + " ("
                        + "workflow_kind VARCHAR(32) NOT NULL, workflow_id CHAR(36) NOT NULL, account_uuid CHAR(36) NOT NULL, "
                        + "amount_minor BIGINT NOT NULL CHECK(amount_minor>0 AND amount_minor<=" + bound + "), intent_hash CHAR(64) NOT NULL, "
                        + "state VARCHAR(16) NOT NULL CHECK(state IN ('HELD','CAPTURED','RELEASED')), version BIGINT NOT NULL CHECK(version>=0), "
                        + "reserve_operation_id CHAR(36) NOT NULL, final_operation_id CHAR(36), created_at BIGINT NOT NULL, finished_at BIGINT, "
                        + "PRIMARY KEY(workflow_kind,workflow_id), KEY allium_economy_reservations_by_account (account_uuid,state), "
                        + "CHECK((state='HELD' AND final_operation_id IS NULL AND finished_at IS NULL) OR "
                        + "(state<>'HELD' AND final_operation_id IS NOT NULL AND finished_at IS NOT NULL)), "
                        + "FOREIGN KEY(account_uuid) REFERENCES " + ACCOUNT + "(account_uuid) ON DELETE RESTRICT, "
                        + "FOREIGN KEY(reserve_operation_id) REFERENCES " + OPERATION + "(operation_id) ON DELETE RESTRICT, "
                        + "FOREIGN KEY(final_operation_id) REFERENCES " + OPERATION + "(operation_id) ON DELETE RESTRICT)" + INNODB,
                "CREATE TABLE IF NOT EXISTS " + AUDIT + " (workflow_kind VARCHAR(32) NOT NULL, workflow_id CHAR(36) NOT NULL, "
                        + "actor TEXT NOT NULL, action VARCHAR(16) NOT NULL, reason TEXT NOT NULL, created_at BIGINT NOT NULL, "
                        + "PRIMARY KEY(workflow_kind,workflow_id))" + INNODB,
                "CREATE TABLE IF NOT EXISTS " + EconomySchema.CHEQUES + " (cheque_id CHAR(36) PRIMARY KEY, "
                        + "amount_minor BIGINT NOT NULL CHECK(amount_minor>0 AND amount_minor<=" + bound + "), "
                        + "tax_minor BIGINT NOT NULL DEFAULT 0 CHECK(tax_minor>=0 AND tax_minor<=" + bound + "), "
                        + "issuer_uuid CHAR(36), issuer_name TEXT NOT NULL, admin_issued TINYINT NOT NULL CHECK(admin_issued IN (0,1)), "
                        + "state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','ISSUED','REDEEMED','VOIDED')), issue_operation_id CHAR(36), "
                        + "redeem_operation_id CHAR(36), redeemer_uuid CHAR(36), created_at BIGINT NOT NULL, redeemed_at BIGINT, "
                        + "KEY allium_economy_cheques_by_state (state,created_at))" + INNODB,
                "CREATE TABLE IF NOT EXISTS " + EconomySchema.TAX_HISTORY + " (tax_id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "operation_id CHAR(36) NOT NULL UNIQUE, player_uuid CHAR(36) NOT NULL, player_name TEXT NOT NULL, "
                        + "amount_minor BIGINT NOT NULL CHECK(amount_minor>0 AND amount_minor<=" + bound + "), "
                        + "percentage_bps INT NOT NULL CHECK(percentage_bps BETWEEN 1 AND 10000), balance_before_minor BIGINT NOT NULL, "
                        + "imposer TEXT NOT NULL, created_at BIGINT NOT NULL, refunded TINYINT NOT NULL DEFAULT 0 CHECK(refunded IN (0,1)), "
                        + "refund_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK(refund_amount_minor>=0), "
                        + "refund_percentage_bps INT NOT NULL DEFAULT 0 CHECK(refund_percentage_bps BETWEEN 0 AND 10000), "
                        + "refunder TEXT, refund_operation_id CHAR(36), refunded_at BIGINT, "
                        + "KEY allium_economy_tax_history_by_player (player_uuid,created_at))" + INNODB,
                "CREATE TABLE IF NOT EXISTS " + WRITER + " (id INT PRIMARY KEY CHECK(id=1), recovery_owner VARCHAR(64))" + INNODB,
                "INSERT INTO " + WRITER + " (id) VALUES (1) ON DUPLICATE KEY UPDATE id=1");
        new SchemaMigrator(source, SqlDialect.MYSQL, System::currentTimeMillis).migrate("economy",
                List.of(Migration.of(1, "InnoDB economy, reservations, cheques and tax history", List.of(), ddl)));
        try (var connection = source.connection()) {
            requireDurableCommits(connection);
            for (String table : List.of(ACCOUNT, OPERATION, EconomySchema.ENTRIES, EconomySchema.RESERVATIONS,
                    AUDIT, EconomySchema.CHEQUES, EconomySchema.TAX_HISTORY, WRITER)) {
                try (var statement = connection.prepareStatement(
                        "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                    statement.setString(1, table);
                    try (var row = statement.executeQuery()) {
                        if (!row.next() || !"InnoDB".equalsIgnoreCase(row.getString(1))) {
                            throw new SQLException("Economy table must use InnoDB: " + table);
                        }
                    }
                }
            }
        }
    }

    static void requireDurableCommits(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT @@GLOBAL.innodb_flush_log_at_trx_commit, @@GLOBAL.log_bin, "
                     + "@@GLOBAL.sync_binlog, @@SESSION.foreign_key_checks")) {
            if (!row.next() || row.getInt(1) != 1 || row.getBoolean(2) && row.getInt(3) != 1 || !row.getBoolean(4)) {
                throw new SQLException("Economy requires durable InnoDB commits and enabled foreign key checks "
                        + "(innodb_flush_log_at_trx_commit=1, sync_binlog=1 when binary logging is enabled)");
            }
        }
    }

    static void lockWriter(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT id FROM " + WRITER + " WHERE id=1 FOR UPDATE")) {
            if (!row.next()) throw new SQLException("Economy writer row is missing");
        }
    }

    /** Native item receipts live with one server's player data, so schema ownership persists through restarts. */
    static void bindRecoveryOwner(Connection connection, String owner) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE " + WRITER + " SET recovery_owner=? WHERE id=1 AND recovery_owner IS NULL")) {
            update.setString(1, owner);
            update.executeUpdate();
        }
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT recovery_owner FROM " + WRITER + " WHERE id=1")) {
            if (!row.next() || !owner.equals(row.getString(1))) {
                throw new SQLException("This economy schema belongs to another Allium server's player data");
            }
        }
    }
}
