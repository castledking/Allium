package codes.castled.allium.importer.legacy;

import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.BalanceRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.HomeRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.LogoutRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.MailRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.MaxHomesRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.NoteRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.Point;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.SupersededHome;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.WarpRow;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the travel data of a legacy Allium H2 database ({@code database/sfcore.mv.db}) over plain
 * JDBC. Tables and optional columns are detected, never assumed: a missing table is reported and
 * yields nothing. The connection should be a read-only one on a copy of the file.
 */
public final class LegacyAlliumReader {

    /** Rows past this per table are refused rather than held in memory. */
    static final long ROW_LIMIT = 5_000_000;

    private LegacyAlliumReader() {
    }

    public static LegacyAlliumSnapshot read(Connection c) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        String version = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        Set<String> tables = tables(meta);
        List<String> notes = new ArrayList<>();
        if (tables.contains("DATABASE_VERSION")) notes.add("legacy schema version " + schemaVersion(c));

        List<HomeRow> homes = new ArrayList<>();
        List<SupersededHome> superseded = new ArrayList<>();
        List<LogoutRow> logouts = new ArrayList<>();
        long unnamed = 0, olderLogouts = 0;
        Map<String, Long> other = new TreeMap<>();
        if (!tables.contains("PLAYER_LOCATIONS")) {
            notes.add("no player_locations table: no homes or logout positions");
        } else {
            Set<String> columns = columns(meta, "PLAYER_LOCATIONS");
            boolean named = columns.contains("HOME_NAME");
            if (!named) notes.add("player_locations has no home_name column: its HOME rows cannot be named");
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT location_type, COUNT(*) FROM player_locations GROUP BY location_type")) {
                while (r.next()) {
                    String type = String.valueOf(r.getString(1)).toUpperCase(Locale.ROOT);
                    if (!type.equals("HOME") && !type.equals("LOGOUT")) other.put(type, r.getLong(2));
                }
            }
            Map<String, HomeRow> best = new LinkedHashMap<>();
            Map<String, List<HomeRow>> losers = new LinkedHashMap<>();
            long seen = 0;
            try (PreparedStatement s = c.prepareStatement("SELECT id, player_uuid, " + (named ? "home_name" : "NULL")
                    + " AS home_name, world, x, y, z, yaw, pitch, timestamp FROM player_locations WHERE location_type = 'HOME' ORDER BY id")) {
                s.setFetchSize(1000);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        if (++seen > ROW_LIMIT) throw new SQLException("more than " + ROW_LIMIT + " HOME rows");
                        String name = r.getString("home_name");
                        if (name == null) {
                            unnamed++;
                            continue;
                        }
                        HomeRow row = new HomeRow(r.getLong("id"), r.getString("player_uuid"), name, point(r), r.getLong("timestamp"));
                        String key = row.owner() + "\u0000" + name;
                        HomeRow current = best.get(key);
                        if (current == null) {
                            best.put(key, row);
                        } else if (newer(row.timestamp(), row.id(), current.timestamp(), current.id())) {
                            losers.computeIfAbsent(key, k -> new ArrayList<>()).add(current);
                            best.put(key, row);
                        } else {
                            losers.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                        }
                    }
                }
            }
            homes.addAll(best.values());
            losers.forEach((key, rows) -> {
                HomeRow kept = best.get(key);
                for (HomeRow row : rows) superseded.add(new SupersededHome(row.id(), row.owner(), row.name(), kept.id()));
            });
            Map<String, LogoutRow> latest = new LinkedHashMap<>();
            seen = 0;
            try (PreparedStatement s = c.prepareStatement("SELECT id, player_uuid, world, x, y, z, yaw, pitch, timestamp "
                    + "FROM player_locations WHERE location_type = 'LOGOUT' ORDER BY id")) {
                s.setFetchSize(1000);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        if (++seen > ROW_LIMIT) throw new SQLException("more than " + ROW_LIMIT + " LOGOUT rows");
                        LogoutRow row = new LogoutRow(r.getLong("id"), r.getString("player_uuid"), point(r), r.getLong("timestamp"));
                        LogoutRow current = latest.get(row.owner());
                        if (current == null) {
                            latest.put(row.owner(), row);
                        } else {
                            olderLogouts++;
                            if (newer(row.timestamp(), row.id(), current.timestamp(), current.id())) latest.put(row.owner(), row);
                        }
                    }
                }
            }
            logouts.addAll(latest.values());
        }

        List<WarpRow> warps = new ArrayList<>();
        if (!tables.contains("WARPS")) {
            notes.add("no warps table");
        } else {
            Set<String> columns = columns(meta, "WARPS");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM warps ORDER BY name")) {
                while (r.next()) {
                    warps.add(new WarpRow(r.getString("name"), point(r),
                            columns.contains("CREATOR_UUID") ? r.getString("creator_uuid") : null,
                            columns.contains("CREATOR_NAME") ? r.getString("creator_name") : null,
                            columns.contains("IS_PUBLIC") ? (Boolean) r.getObject("is_public", Boolean.class) : null));
                }
            }
        }

        List<MaxHomesRow> limits = new ArrayList<>();
        if (tables.contains("PLAYER_MAX_HOMES")) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT uuid, max_homes FROM player_max_homes ORDER BY uuid")) {
                while (r.next()) limits.add(new MaxHomesRow(r.getString(1), r.getInt(2)));
            }
        }

        List<BalanceRow> balances = new ArrayList<>();
        if (tables.contains("PLAYER_BALANCES")) {
            long seen = 0;
            try (PreparedStatement s = c.prepareStatement("SELECT uuid, balance FROM player_balances ORDER BY uuid")) {
                s.setFetchSize(1000);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        if (++seen > ROW_LIMIT) throw new SQLException("more than " + ROW_LIMIT + " PLAYER_BALANCES rows");
                        Object raw = r.getObject(2);
                        balances.add(new BalanceRow(r.getString(1), raw == null ? "" : raw.toString().trim()));
                    }
                }
            }
        }

        List<NoteRow> staffNotes = new ArrayList<>();
        if (tables.contains("PLAYER_NOTES")) {
            long seen = 0;
            try (PreparedStatement s = c.prepareStatement("SELECT id, player_uuid, staff_uuid, note, created_at FROM player_notes ORDER BY id")) {
                s.setFetchSize(1000);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        if (++seen > ROW_LIMIT) throw new SQLException("more than " + ROW_LIMIT + " PLAYER_NOTES rows");
                        staffNotes.add(new NoteRow(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), timestampMillis(r, 5)));
                    }
                }
            }
        }

        List<MailRow> mailMessages = new ArrayList<>();
        if (tables.contains("MAIL_MESSAGES")) {
            long seen = 0;
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT id, recipient, sender, sender_name, message, sent_time, is_read FROM mail_messages ORDER BY id")) {
                s.setFetchSize(1000);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        if (++seen > ROW_LIMIT) throw new SQLException("more than " + ROW_LIMIT + " MAIL_MESSAGES rows");
                        mailMessages.add(new MailRow(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                                r.getString(5), timestampMillis(r, 6), r.getBoolean(7)));
                    }
                }
            }
        }

        // Read-only inspection counts for legacy serialized item gifts & inventory blobs (never deserialized or auto-imported).
        Map<String, Long> historicalCounts = new TreeMap<>();
        if (tables.contains("MAIL_GIFTS")) {
            long total = countTable(c, "SELECT COUNT(*) FROM mail_gifts");
            long unclaimed = countTable(c, "SELECT COUNT(*) FROM mail_gifts WHERE is_claimed = FALSE");
            if (total > 0) historicalCounts.put("mail_gifts_total", total);
            if (unclaimed > 0) historicalCounts.put("mail_gifts_unclaimed", unclaimed);
        }
        for (String invTable : List.of("player_inventories", "inventory_snapshots",
                "offline_inventory_state", "offline_inventory_overrides")) {
            if (tables.contains(invTable.toUpperCase(Locale.ROOT))) {
                long count = countTable(c, "SELECT COUNT(*) FROM " + invTable);
                if (count > 0) historicalCounts.put(invTable, count);
            }
        }
        if (!historicalCounts.isEmpty()) {
            notes.add("Serialized item gifts and historical inventory tables (" + historicalCounts
                    + ") are inspected read-only and never auto-imported.");
        }

        return new LegacyAlliumSnapshot(version, homes, superseded, unnamed, logouts, olderLogouts, warps, limits,
                other, notes, balances, staffNotes, mailMessages, historicalCounts);
    }

    private static long countTable(Connection c, String sql) {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getLong(1) : 0L;
        } catch (SQLException ignored) {
            return 0L;
        }
    }

    private static long timestampMillis(ResultSet r, int col) {
        try {
            Timestamp ts = r.getTimestamp(col);
            if (ts != null) return ts.getTime();
        } catch (SQLException ignored) {
        }
        try {
            long val = r.getLong(col);
            return r.wasNull() ? 0L : val;
        } catch (SQLException ignored) {
            return 0L;
        }
    }

    private static boolean newer(long timestamp, long id, long otherTimestamp, long otherId) {
        return timestamp > otherTimestamp || timestamp == otherTimestamp && id > otherId;
    }

    private static Point point(ResultSet r) throws SQLException {
        return new Point(r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch"));
    }

    private static Set<String> tables(DatabaseMetaData meta) throws SQLException {
        Set<String> out = new HashSet<>();
        try (ResultSet r = meta.getTables(null, null, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (r.next()) {
                String schema = r.getString("TABLE_SCHEM");
                if (schema == null || schema.equalsIgnoreCase("PUBLIC")) out.add(r.getString("TABLE_NAME").toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static Set<String> columns(DatabaseMetaData meta, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        try (ResultSet r = meta.getColumns(null, null, table, "%")) {
            while (r.next()) out.add(r.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static String schemaVersion(Connection c) {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT MAX(version) FROM database_version")) {
            return r.next() ? String.valueOf(r.getObject(1)) : "unknown";
        } catch (SQLException unreadable) {
            return "unreadable";
        }
    }
}
