package codes.castled.allium.economy;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;
import java.sql.SQLException;
import java.util.List;

/** Phase 1 SQLite schema, independent of player names and the legacy scaffold table. */
public final class EconomySchema {
    public static final String RESERVATIONS = "allium_economy_reservations";
    public static final String ACCOUNTS = "allium_economy_accounts";
    public static final String OPERATIONS = "allium_economy_operations";
    public static final String ENTRIES = "allium_economy_entries";
    public static final String CHEQUES = "allium_economy_cheques";
    public static final String TAX_HISTORY = "allium_economy_tax_history";
    private static final String BOUND = Long.toString(Money.HARD);
    private static final String CHEQUES_DDL =
        "CREATE TABLE IF NOT EXISTS "+CHEQUES+" (cheque_id TEXT COLLATE BINARY NOT NULL PRIMARY KEY, "
            +"amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>0 AND amount_minor<="+BOUND+"), "
            +"tax_minor INTEGER NOT NULL DEFAULT 0 CHECK(typeof(tax_minor)='integer' AND tax_minor>=0 AND tax_minor<="+BOUND+"), "
            +"issuer_uuid TEXT COLLATE BINARY, issuer_name TEXT NOT NULL, "
            +"admin_issued INTEGER NOT NULL CHECK(admin_issued IN (0,1)), "
            +"state TEXT NOT NULL CHECK(state IN ('PENDING','ISSUED','REDEEMED','VOIDED')), "
            +"issue_operation_id TEXT COLLATE BINARY, redeem_operation_id TEXT COLLATE BINARY, "
            +"redeemer_uuid TEXT COLLATE BINARY, created_at INTEGER NOT NULL, redeemed_at INTEGER)";
    private static final String TAX_HISTORY_DDL =
        "CREATE TABLE IF NOT EXISTS "+TAX_HISTORY+" (tax_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            +"operation_id TEXT COLLATE BINARY NOT NULL UNIQUE, "
            +"player_uuid TEXT COLLATE BINARY NOT NULL, player_name TEXT NOT NULL, "
            +"amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>0 AND amount_minor<="+BOUND+"), "
            +"percentage_bps INTEGER NOT NULL CHECK(typeof(percentage_bps)='integer' AND percentage_bps>0 AND percentage_bps<=10000), "
            +"balance_before_minor INTEGER NOT NULL CHECK(typeof(balance_before_minor)='integer'), "
            +"imposer TEXT NOT NULL, created_at INTEGER NOT NULL, "
            +"refunded INTEGER NOT NULL DEFAULT 0 CHECK(refunded IN (0,1)), "
            +"refund_amount_minor INTEGER NOT NULL DEFAULT 0 CHECK(typeof(refund_amount_minor)='integer' AND refund_amount_minor>=0), "
            +"refund_percentage_bps INTEGER NOT NULL DEFAULT 0 CHECK(typeof(refund_percentage_bps)='integer' AND refund_percentage_bps>=0 AND refund_percentage_bps<=10000), "
            +"refunder TEXT, refund_operation_id TEXT COLLATE BINARY, refunded_at INTEGER)";
    private static final List<String> DDL = List.of(
        "CREATE TABLE IF NOT EXISTS "+ACCOUNTS+" (account_uuid TEXT COLLATE BINARY NOT NULL PRIMARY KEY, "
            +"balance_minor INTEGER NOT NULL CHECK(typeof(balance_minor)='integer' AND balance_minor BETWEEN -"+BOUND+" AND "+BOUND+"), "
            +"reserved_minor INTEGER NOT NULL DEFAULT 0 CHECK(typeof(reserved_minor)='integer' AND reserved_minor>=0), "
            +"version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>=0), created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS "+OPERATIONS+" (operation_id TEXT COLLATE BINARY NOT NULL PRIMARY KEY, "
            +"origin TEXT COLLATE BINARY NOT NULL, request_key TEXT COLLATE BINARY NOT NULL, kind TEXT NOT NULL, actor TEXT NOT NULL, "
            +"source_uuid TEXT COLLATE BINARY NOT NULL, recipient_uuid TEXT COLLATE BINARY, amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer'), "
            +"payload BLOB NOT NULL CHECK(typeof(payload)='blob'), payload_hash BLOB NOT NULL CHECK(typeof(payload_hash)='blob'), "
            +"state TEXT NOT NULL CHECK(state IN ('COMMITTED','REJECTED')), result_code TEXT NOT NULL, "
            +"source_balance INTEGER CHECK(typeof(source_balance) IN ('integer','null')), source_reserved INTEGER CHECK(typeof(source_reserved) IN ('integer','null')), source_version INTEGER CHECK(typeof(source_version) IN ('integer','null')), recipient_balance INTEGER CHECK(typeof(recipient_balance) IN ('integer','null')), recipient_reserved INTEGER CHECK(typeof(recipient_reserved) IN ('integer','null')), recipient_version INTEGER CHECK(typeof(recipient_version) IN ('integer','null')), "
            +"created_at INTEGER NOT NULL, finished_at INTEGER NOT NULL, UNIQUE(origin,request_key))",
        "CREATE TABLE IF NOT EXISTS "+ENTRIES+" (operation_id TEXT COLLATE BINARY NOT NULL, account_uuid TEXT COLLATE BINARY NOT NULL, "
            +"book_delta INTEGER NOT NULL CHECK(typeof(book_delta)='integer'), held_delta INTEGER NOT NULL CHECK(typeof(held_delta)='integer'), "
            +"balance_before INTEGER NOT NULL CHECK(typeof(balance_before)='integer'), balance_after INTEGER NOT NULL CHECK(typeof(balance_after)='integer'), reserved_before INTEGER NOT NULL CHECK(typeof(reserved_before)='integer'), reserved_after INTEGER NOT NULL CHECK(typeof(reserved_after)='integer'), "
            +"account_version_after INTEGER NOT NULL CHECK(typeof(account_version_after)='integer'), PRIMARY KEY(operation_id,account_uuid), UNIQUE(account_uuid,account_version_after), "
            +"FOREIGN KEY(operation_id) REFERENCES "+OPERATIONS+"(operation_id) ON DELETE RESTRICT, "
            +"FOREIGN KEY(account_uuid) REFERENCES "+ACCOUNTS+"(account_uuid) ON DELETE RESTRICT)",
        "CREATE TABLE IF NOT EXISTS "+RESERVATIONS+" (workflow_kind TEXT COLLATE BINARY NOT NULL, workflow_id TEXT COLLATE BINARY NOT NULL, "
            +"account_uuid TEXT COLLATE BINARY NOT NULL, amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>0 AND amount_minor<="+BOUND+"), "
            +"intent_hash TEXT COLLATE BINARY NOT NULL, state TEXT NOT NULL CHECK(state IN ('HELD','CAPTURED','RELEASED')), version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>=0), "
            +"reserve_operation_id TEXT COLLATE BINARY NOT NULL, final_operation_id TEXT COLLATE BINARY, created_at INTEGER NOT NULL, finished_at INTEGER, "
            +"PRIMARY KEY(workflow_kind,workflow_id), CHECK((state='HELD' AND final_operation_id IS NULL AND finished_at IS NULL) OR (state<>'HELD' AND final_operation_id IS NOT NULL AND finished_at IS NOT NULL)), "
            +"FOREIGN KEY(account_uuid) REFERENCES "+ACCOUNTS+"(account_uuid) ON DELETE RESTRICT, FOREIGN KEY(reserve_operation_id) REFERENCES "+OPERATIONS+"(operation_id) ON DELETE RESTRICT, "
            +"FOREIGN KEY(final_operation_id) REFERENCES "+OPERATIONS+"(operation_id) ON DELETE RESTRICT)",
        CHEQUES_DDL,
        TAX_HISTORY_DDL
    );
    private EconomySchema() {}
    public static void migrate(ConnectionSource source) throws SQLException {
        try (var c = source.connection()) {
            if (!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite"))
                throw new SQLException("Phase 1 economy requires SQLite");
        }
        new SchemaMigrator(source, SqlDialect.SQLITE, System::currentTimeMillis).migrate("economy",
            List.of(Migration.of(1, "exact money accounts, atomic operations and entries", DDL.subList(0,3), List.of()),
                Migration.of(2,"workflow holds and terminal receipts",List.of(DDL.get(3),"CREATE INDEX allium_economy_reservations_by_account ON "+RESERVATIONS+" (account_uuid,state)"),List.of()),
                Migration.of(3,"operator reservation audit",List.of("CREATE TABLE allium_economy_reservation_audit (workflow_kind TEXT NOT NULL, workflow_id TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(workflow_kind,workflow_id))"),List.of()),
                Migration.of(4,"cheques and tax history",List.of(
                    CHEQUES_DDL,
                    "CREATE INDEX IF NOT EXISTS allium_economy_cheques_by_state ON "+CHEQUES+" (state,created_at)",
                    TAX_HISTORY_DDL,
                    "CREATE INDEX IF NOT EXISTS allium_economy_tax_history_by_player ON "+TAX_HISTORY+" (player_uuid,created_at)"),List.of())));
        // Refuse a pre-existing lookalike table instead of trusting CREATE IF NOT EXISTS.
        var names = List.of(ACCOUNTS,OPERATIONS,ENTRIES,RESERVATIONS,CHEQUES,TAX_HISTORY);
        try (var c = source.connection(); var q = c.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            for (int i=0;i<DDL.size();i++) {
                q.setString(1,names.get(i));
                try (var rows=q.executeQuery()) {
                    if (!rows.next() || !normalize(DDL.get(i)).equals(normalize(rows.getString(1))))
                        throw new SQLException("economy schema definition mismatch: "+names.get(i));
                }
            }
        }
    }
    private static String normalize(String sql) { return sql.replace("IF NOT EXISTS ", "").replaceAll("\\s+", " ").trim(); }
}
