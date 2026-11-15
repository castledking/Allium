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
    private static final String BOUND = Long.toString(Money.HARD);
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
            +"FOREIGN KEY(final_operation_id) REFERENCES "+OPERATIONS+"(operation_id) ON DELETE RESTRICT)"
    );
    private EconomySchema() {}
    public static void migrate(ConnectionSource source) throws SQLException {
        try (var c = source.connection()) {
            if (!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite"))
                throw new SQLException("Phase 1 economy requires SQLite");
        }
        new SchemaMigrator(source, SqlDialect.SQLITE, System::currentTimeMillis).migrate("economy",
            List.of(Migration.of(1, "exact money accounts, atomic operations and entries", DDL.subList(0,3), List.of()),
                Migration.of(2,"workflow holds and terminal receipts",List.of(DDL.get(3),"CREATE INDEX allium_economy_reservations_by_account ON "+RESERVATIONS+" (account_uuid,state)"),List.of())));
        // Refuse a pre-existing lookalike table instead of trusting CREATE IF NOT EXISTS.
        try (var c = source.connection(); var q = c.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            for (int i=0;i<DDL.size();i++) {
                q.setString(1,List.of(ACCOUNTS,OPERATIONS,ENTRIES,RESERVATIONS).get(i));
                try (var rows=q.executeQuery()) {
                    if (!rows.next() || !normalize(DDL.get(i)).equals(normalize(rows.getString(1))))
                        throw new SQLException("economy schema definition mismatch: "+List.of(ACCOUNTS,OPERATIONS,ENTRIES,RESERVATIONS).get(i));
                }
            }
        }
    }
    private static String normalize(String sql) { return sql.replace("IF NOT EXISTS ", "").replaceAll("\\s+", " ").trim(); }
}
