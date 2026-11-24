package codes.castled.allium.moderation;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.SavedLocation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * SQL persistence for moderation state and history. S19 mutations and their audit rows share a transaction.
 * Blocking JDBC; runtime callers invoke this exclusively from the bounded moderation storage pool.
 */
public final class ModerationRepository {

    public static final String COMPONENT = "moderation";
    public static final String VANISH_TABLE = "allium_vanish";
    public static final String MUTES_TABLE = "allium_mutes";
    public static final String AUDIT_TABLE = "allium_moderation_audit";
    public static final String BANS_TABLE = "allium_bans";
    public static final String FREEZES_TABLE = "allium_freezes";
    public static final String JAILS_TABLE = "allium_jails";
    public static final String JAILED_TABLE = "allium_jailed";
    public static final String WARNINGS_TABLE = "allium_warnings";
    public static final String NOTES_TABLE = "allium_staff_notes";
    public static final int MAX_HISTORY_LIMIT = 100;

    private static final String PLAYER_COLUMNS = "player_uuid, player_name, actor_name, reason, created_at";
    private static final String BAN_COLUMNS = PLAYER_COLUMNS + ", expires_at, ip_address";
    private static final String LOCATION_COLUMNS = "world_id, world_name, x, y, z, yaw, pitch";
    private static final String ASSIGNMENT_COLUMNS = PLAYER_COLUMNS + ", jail_name, " + LOCATION_COLUMNS + ", expires_at";
    private static final String PLAYER_DEFINITION = "player_uuid CHAR(36) NOT NULL PRIMARY KEY, "
            + "player_name VARCHAR(36) NOT NULL, actor_name VARCHAR(64) NOT NULL, "
            + "reason VARCHAR(256) NOT NULL, created_at BIGINT NOT NULL";
    private static final String LOCATION_DEFINITION = "world_id CHAR(36) NOT NULL, world_name VARCHAR(255) NOT NULL, "
            + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL";

    private static List<String> s19Schema(SqlDialect dialect) {
        String id = dialect == SqlDialect.MYSQL ? "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String history = " (id " + id + ", player_uuid CHAR(36) NOT NULL, player_name VARCHAR(36) NOT NULL, "
                + "actor_name VARCHAR(64) NOT NULL, reason VARCHAR(256) NOT NULL, created_at BIGINT NOT NULL)";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + BANS_TABLE + " (" + PLAYER_DEFINITION + ", expires_at BIGINT NULL, ip_address VARCHAR(45) NULL)",
                "CREATE TABLE IF NOT EXISTS " + FREEZES_TABLE + " (" + PLAYER_DEFINITION + ")",
                "CREATE TABLE IF NOT EXISTS " + JAILS_TABLE + " (name VARCHAR(32) NOT NULL PRIMARY KEY, " + LOCATION_DEFINITION + ")",
                "CREATE TABLE IF NOT EXISTS " + JAILED_TABLE + " (" + PLAYER_DEFINITION + ", jail_name VARCHAR(32) NOT NULL, " + LOCATION_DEFINITION + ")",
                "CREATE TABLE IF NOT EXISTS " + WARNINGS_TABLE + history,
                "CREATE TABLE IF NOT EXISTS " + NOTES_TABLE + history,
                "CREATE INDEX allium_bans_expiry ON " + BANS_TABLE + " (expires_at)",
                "CREATE INDEX allium_bans_ip ON " + BANS_TABLE + " (ip_address, created_at, player_uuid)",
                "CREATE INDEX allium_jailed_jail ON " + JAILED_TABLE + " (jail_name)",
                "CREATE INDEX allium_warnings_player ON " + WARNINGS_TABLE + " (player_uuid, id)",
                "CREATE INDEX allium_staff_notes_player ON " + NOTES_TABLE + " (player_uuid, id)"
        );
    }

    private static final String CREATE_VANISH = "CREATE TABLE IF NOT EXISTS " + VANISH_TABLE + " ("
            + "player_uuid CHAR(36) NOT NULL PRIMARY KEY, "
            + "vanished INT NOT NULL, "
            + "updated_at BIGINT NOT NULL, "
            + "actor_name VARCHAR(64) NOT NULL"
            + ")";

    private static final String CREATE_MUTES = "CREATE TABLE IF NOT EXISTS " + MUTES_TABLE + " ("
            + "player_uuid CHAR(36) NOT NULL PRIMARY KEY, "
            + "player_name VARCHAR(36) NOT NULL, "
            + "actor_name VARCHAR(64) NOT NULL, "
            + "reason VARCHAR(256) NOT NULL, "
            + "created_at BIGINT NOT NULL, "
            + "expires_at BIGINT NULL"
            + ")";

    private static final String CREATE_AUDIT_SQLITE = "CREATE TABLE IF NOT EXISTS " + AUDIT_TABLE + " ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "created_at BIGINT NOT NULL, "
            + "action VARCHAR(32) NOT NULL, "
            + "actor_name VARCHAR(64) NOT NULL, "
            + "target_uuid CHAR(36) NULL, "
            + "target_name VARCHAR(36) NULL, "
            + "detail VARCHAR(512) NOT NULL"
            + ")";

    private static final String CREATE_AUDIT_MYSQL = "CREATE TABLE IF NOT EXISTS " + AUDIT_TABLE + " ("
            + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
            + "created_at BIGINT NOT NULL, "
            + "action VARCHAR(32) NOT NULL, "
            + "actor_name VARCHAR(64) NOT NULL, "
            + "target_uuid CHAR(36) NULL, "
            + "target_name VARCHAR(36) NULL, "
            + "detail VARCHAR(512) NOT NULL"
            + ")";

    private final ConnectionSource source;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public ModerationRepository(ConnectionSource source, SqlDialect dialect, LongSupplier clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int migrate() throws SQLException {
        List<String> v3 = List.of("ALTER TABLE " + JAILED_TABLE + " ADD COLUMN expires_at BIGINT NULL");
        return new SchemaMigrator(source, dialect, clock).migrate(COMPONENT, List.of(
                Migration.of(
                        1,
                        "vanish state, mutes, and moderation audit log",
                        List.of(CREATE_VANISH, CREATE_MUTES, CREATE_AUDIT_SQLITE),
                        List.of(CREATE_VANISH, CREATE_MUTES, CREATE_AUDIT_MYSQL)
                ),
                Migration.of(2, "bans, freezes, jails, warnings, and staff notes",
                        s19Schema(SqlDialect.SQLITE), s19Schema(SqlDialect.MYSQL)),
                Migration.of(3, "timed jail expiry", v3, v3)
        ));
    }

    public Set<UUID> loadVanishedPlayers() throws SQLException {
        Set<UUID> out = new LinkedHashSet<>();
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_uuid FROM " + VANISH_TABLE + " WHERE vanished = 1 ORDER BY player_uuid")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return out;
    }

    public boolean loadVanished(UUID playerUuid) throws SQLException {
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT vanished FROM " + VANISH_TABLE + " WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
    }

    public void setVanished(UUID playerUuid, boolean vanished, String actorName) throws SQLException {
        long now = clock.getAsLong();
        String safeActor = (actorName == null || actorName.isBlank()) ? "Console" : actorName.trim();
        if (!vanished) {
            try (Connection c = source.connection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM " + VANISH_TABLE + " WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            }
            return;
        }
        String sql = dialect == SqlDialect.MYSQL
                ? "INSERT INTO " + VANISH_TABLE + " (player_uuid, vanished, updated_at, actor_name) VALUES (?, 1, ?, ?) "
                  + "ON DUPLICATE KEY UPDATE vanished = 1, updated_at = VALUES(updated_at), actor_name = VALUES(actor_name)"
                : "INSERT INTO " + VANISH_TABLE + " (player_uuid, vanished, updated_at, actor_name) VALUES (?, 1, ?, ?) "
                  + "ON CONFLICT(player_uuid) DO UPDATE SET vanished = 1, updated_at = excluded.updated_at, actor_name = excluded.actor_name";
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, now);
            ps.setString(3, safeActor);
            ps.executeUpdate();
        }
    }

    public List<MuteRecord> loadActiveMutes() throws SQLException {
        long now = clock.getAsLong();
        purgeExpiredMutes(now);
        List<MuteRecord> out = new ArrayList<>();
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_uuid, player_name, actor_name, reason, created_at, expires_at FROM "
                             + MUTES_TABLE + " ORDER BY created_at ASC, player_uuid ASC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MuteRecord record = readMute(rs);
                    if (!record.isExpired(now)) {
                        out.add(record);
                    }
                }
            }
        }
        return out;
    }

    public Optional<MuteRecord> loadActiveMute(UUID playerUuid) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        try (Connection c = source.connection()) {
            return loadActiveMute(c, playerUuid);
        }
    }

    public Optional<MuteRecord> loadActiveMute(Connection c, UUID playerUuid) throws SQLException {
        long now = clock.getAsLong();
        MuteRecord record;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_uuid, player_name, actor_name, reason, created_at, expires_at FROM "
                        + MUTES_TABLE + " WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                record = readMute(rs);
            }
        }
        if (record.isExpired(now)) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + MUTES_TABLE + " WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            }
            return Optional.empty();
        }
        return Optional.of(record);
    }

    public void saveMute(MuteRecord record) throws SQLException {
        Objects.requireNonNull(record, "record");
        try (Connection c = source.connection()) {
            saveMute(c, record);
        }
    }

    public void saveMute(Connection c, MuteRecord record) throws SQLException {
        String sql = dialect == SqlDialect.MYSQL
                ? "INSERT INTO " + MUTES_TABLE
                  + " (player_uuid, player_name, actor_name, reason, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?) "
                  + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), actor_name = VALUES(actor_name), "
                  + "reason = VALUES(reason), created_at = VALUES(created_at), expires_at = VALUES(expires_at)"
                : "INSERT INTO " + MUTES_TABLE
                  + " (player_uuid, player_name, actor_name, reason, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?) "
                  + "ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, actor_name = excluded.actor_name, "
                  + "reason = excluded.reason, created_at = excluded.created_at, expires_at = excluded.expires_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, record.playerUuid().toString());
            ps.setString(2, record.playerName());
            ps.setString(3, record.actorName());
            ps.setString(4, record.reason());
            ps.setLong(5, record.createdAt());
            if (record.expiresAt() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, record.expiresAt());
            }
            ps.executeUpdate();
        }
    }

    public boolean removeMute(UUID playerUuid) throws SQLException {
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + MUTES_TABLE + " WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public int purgeExpiredMutes(long nowMillis) throws SQLException {
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + MUTES_TABLE + " WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            ps.setLong(1, nowMillis);
            return ps.executeUpdate();
        }
    }

    /** Expiry is inclusive and checked using one captured clock value; reads never delete a newer ban. */
    public List<BanRecord> loadActiveBans() throws SQLException {
        long now = clock.getAsLong();
        return query("SELECT " + BAN_COLUMNS + " FROM " + BANS_TABLE
                        + " WHERE expires_at IS NULL OR expires_at > ? ORDER BY created_at, player_uuid",
                ps -> ps.setLong(1, now), ModerationRepository::readBan);
    }

    public Optional<BanRecord> loadActiveBan(UUID playerUuid) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        long now = clock.getAsLong();
        return query("SELECT " + BAN_COLUMNS + " FROM " + BANS_TABLE
                        + " WHERE player_uuid = ? AND (expires_at IS NULL OR expires_at > ?)",
                ps -> { ps.setString(1, playerUuid.toString()); ps.setLong(2, now); },
                ModerationRepository::readBan).stream().findFirst();
    }

    public Optional<BanRecord> loadBanByIp(String ipAddress) throws SQLException {
        String address = ipAddress(ipAddress);
        if (address == null) return Optional.empty();
        long now = clock.getAsLong();
        // Several UUID bans can share an address. Removing/expiring one must not hide the others.
        return query("SELECT " + BAN_COLUMNS + " FROM " + BANS_TABLE
                        + " WHERE ip_address = ? AND (expires_at IS NULL OR expires_at > ?)"
                        + " ORDER BY created_at DESC, player_uuid ASC LIMIT 1",
                ps -> { ps.setString(1, address); ps.setLong(2, now); },
                ModerationRepository::readBan).stream().findFirst();
    }

    public void saveBan(BanRecord record) throws SQLException {
        Objects.requireNonNull(record, "record");
        transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(upsert(BANS_TABLE, BAN_COLUMNS, "player_uuid", true))) {
                bindPlayer(ps, record.playerUuid(), record.playerName(), record.actorName(), record.reason(), record.createdAt());
                if (record.expiresAt() == null) ps.setNull(6, Types.BIGINT); else ps.setLong(6, record.expiresAt());
                ps.setString(7, record.ipAddress());
                ps.executeUpdate();
            }
            writeAudit(c, "BAN", record.actorName(), record.playerUuid(), record.playerName(),
                    "reason=" + record.reason() + " expires_at=" + record.expiresAt() + " ip_address=" + record.ipAddress());
            return null;
        });
    }

    public boolean removeBan(UUID playerUuid, String actorName) throws SQLException {
        return removePlayerState(BANS_TABLE, playerUuid, actorName, "UNBAN");
    }

    /** Removes every ban row tied to {@code ipAddress} in one transaction and records an UNBANIP audit row. */
    public int removeBansByIp(String ipAddress, String actorName) throws SQLException {
        String address = ipAddress(ipAddress);
        if (address == null) return 0;
        String actor = actorName(actorName);
        return transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + BANS_TABLE + " SET ip_address = ip_address WHERE ip_address = ?")) {
                ps.setString(1, address);
                ps.executeUpdate();
            }
            int removed;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + BANS_TABLE + " WHERE ip_address = ?")) {
                ps.setString(1, address);
                removed = ps.executeUpdate();
            }
            if (removed == 0) return 0;
            writeAudit(c, "UNBANIP", actor, null, boundedText(address, 36), "ip_address=" + address + " removed=" + removed);
            return removed;
        });
    }

    public List<FreezeRecord> loadFrozenPlayers() throws SQLException {
        return query("SELECT " + PLAYER_COLUMNS + " FROM " + FREEZES_TABLE + " ORDER BY created_at, player_uuid",
                ps -> {}, ModerationRepository::readFreeze);
    }

    public Optional<FreezeRecord> loadFrozen(UUID playerUuid) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return query("SELECT " + PLAYER_COLUMNS + " FROM " + FREEZES_TABLE + " WHERE player_uuid = ?",
                ps -> ps.setString(1, playerUuid.toString()), ModerationRepository::readFreeze).stream().findFirst();
    }

    public void setFrozen(FreezeRecord record) throws SQLException {
        Objects.requireNonNull(record, "record");
        transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(upsert(FREEZES_TABLE, PLAYER_COLUMNS, "player_uuid", true))) {
                bindPlayer(ps, record.playerUuid(), record.playerName(), record.actorName(), record.reason(), record.createdAt());
                ps.executeUpdate();
            }
            writeAudit(c, "FREEZE", record.actorName(), record.playerUuid(), record.playerName(), record.reason());
            return null;
        });
    }

    public boolean removeFrozen(UUID playerUuid, String actorName) throws SQLException {
        return removePlayerState(FREEZES_TABLE, playerUuid, actorName, "UNFREEZE");
    }

    public List<JailRecord> loadJails() throws SQLException {
        return query("SELECT name, " + LOCATION_COLUMNS + " FROM " + JAILS_TABLE + " ORDER BY name",
                ps -> {}, rs -> new JailRecord(rs.getString(1), readLocation(rs, 2)));
    }

    public Optional<JailRecord> loadJail(String name) throws SQLException {
        String key = jailName(name);
        try (Connection c = source.connection()) {
            return loadJail(c, key);
        }
    }

    public Optional<JailRecord> loadJail(Connection c, String name) throws SQLException {
        String key = jailName(name);
        try (PreparedStatement ps = c.prepareStatement("SELECT name, " + LOCATION_COLUMNS + " FROM " + JAILS_TABLE + " WHERE name = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new JailRecord(rs.getString(1), readLocation(rs, 2))) : Optional.empty();
            }
        }
    }

    public void saveJail(JailRecord record, String actorName) throws SQLException {
        Objects.requireNonNull(record, "record");
        transaction(c -> {
            saveJail(c, record, actorName);
            return null;
        });
    }

    public void saveJail(Connection c, JailRecord record, String actorName) throws SQLException {
        Objects.requireNonNull(record, "record");
        String actor = actorName(actorName);
        try (PreparedStatement ps = c.prepareStatement(upsert(JAILS_TABLE, "name, " + LOCATION_COLUMNS, "name", true))) {
            ps.setString(1, record.name());
            bindLocation(ps, 2, record.location());
            ps.executeUpdate();
        }
        writeAudit(c, "SETJAIL", actor, null, null, "jail=" + record.name());
    }

    /** Returns false when absent or in use. Assignment and deletion serialize on the jail row. */
    public boolean deleteJail(String name, String actorName) throws SQLException {
        String key = jailName(name);
        String actor = actorName(actorName);
        return transaction(c -> {
            if (!lockJail(c, key)) return false;
            try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid FROM " + JAILED_TABLE + " WHERE jail_name = ? LIMIT 1")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + JAILS_TABLE + " WHERE name = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            writeAudit(c, "DELJAIL", actor, null, null, "jail=" + key);
            return true;
        });
    }

    public List<JailAssignment> loadJailedPlayers() throws SQLException {
        return query("SELECT " + ASSIGNMENT_COLUMNS + " FROM " + JAILED_TABLE + " ORDER BY created_at, player_uuid",
                ps -> {}, ModerationRepository::readAssignment);
    }

    public Optional<JailAssignment> loadJailed(UUID playerUuid) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        try (Connection c = source.connection()) {
            return loadJailed(c, playerUuid);
        }
    }

    public Optional<JailAssignment> loadJailed(Connection c, UUID playerUuid) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ASSIGNMENT_COLUMNS + " FROM " + JAILED_TABLE + " WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readAssignment(rs)) : Optional.empty();
            }
        }
    }

    /** Returns false when the named jail is absent; a re-jail retains the original return location. */
    public boolean setJailed(JailAssignment record) throws SQLException {
        Objects.requireNonNull(record, "record");
        return transaction(c -> setJailed(c, record));
    }

    /** Assigns a player to an existing jail on the caller's active transaction connection. */
    public boolean setJailed(Connection c, JailAssignment record) throws SQLException {
        Objects.requireNonNull(record, "record");
        if (!lockJail(c, record.jailName())) return false;
        // Only identity/reason/jail change on conflict. The seven origin columns are insert-only.
        try (PreparedStatement ps = c.prepareStatement(upsert(JAILED_TABLE, ASSIGNMENT_COLUMNS, "player_uuid", false))) {
            bindPlayer(ps, record.playerUuid(), record.playerName(), record.actorName(), record.reason(), record.createdAt());
            ps.setString(6, record.jailName());
            bindLocation(ps, 7, record.returnLocation());
            if (record.expiresAt() == null) ps.setNull(14, Types.BIGINT); else ps.setLong(14, record.expiresAt());
            ps.executeUpdate();
        }
        writeAudit(c, "JAIL", record.actorName(), record.playerUuid(), record.playerName(),
                "jail=" + record.jailName() + " expires_at=" + record.expiresAt() + " reason=" + record.reason());
        return true;
    }

    public boolean removeJailed(UUID playerUuid, String actorName) throws SQLException {
        return removePlayerState(JAILED_TABLE, playerUuid, actorName, "UNJAIL");
    }

    public HistoryEntry addWarning(UUID playerUuid, String targetName, String actorName, String reason) throws SQLException {
        return addHistory(WARNINGS_TABLE, "WARN", playerUuid, targetName, actorName, reason);
    }

    public List<HistoryEntry> listWarnings(UUID playerUuid, int limit) throws SQLException {
        return listHistory(WARNINGS_TABLE, playerUuid, limit);
    }

    public HistoryEntry addNote(UUID playerUuid, String targetName, String actorName, String reason) throws SQLException {
        return addHistory(NOTES_TABLE, "STAFF_NOTE", playerUuid, targetName, actorName, reason);
    }

    public List<HistoryEntry> listNotes(UUID playerUuid, int limit) throws SQLException {
        return listHistory(NOTES_TABLE, playerUuid, limit);
    }

    public enum NoteRemovalStatus { REMOVED, NOT_FOUND, NOT_OWNER }

    /** Outcome of removing a staff note; {@code removed} is non-null for REMOVED and NOT_OWNER. */
    public record NoteRemovalResult(NoteRemovalStatus status, HistoryEntry removed) {}

    /** Looks up a single active staff note by its numeric ID. */
    public Optional<HistoryEntry> findNoteById(long noteId) throws SQLException {
        if (noteId <= 0) return Optional.empty();
        return query("SELECT id, " + PLAYER_COLUMNS + " FROM " + NOTES_TABLE + " WHERE id = ?",
                ps -> ps.setLong(1, noteId),
                rs -> new HistoryEntry(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6))).stream().findFirst();
    }

    /**
     * Removes an active staff note by ID while permanently retaining a {@code STAFF_NOTE_REMOVE}
     * audit row in {@link #AUDIT_TABLE} containing the original note ID, author, timestamp, and text.
     *
     * @param noteId             positive note row ID
     * @param actorName          staff actor removing the note
     * @param expectedTargetUuid optional target player UUID filter (null accepts any target)
     * @param canRemoveOthers    true if the actor may remove notes authored by other staff members
     */
    public NoteRemovalResult removeNote(long noteId, String actorName, UUID expectedTargetUuid, boolean canRemoveOthers) throws SQLException {
        if (noteId <= 0) return new NoteRemovalResult(NoteRemovalStatus.NOT_FOUND, null);
        String safeActor = actorName(actorName);
        return transaction(c -> {
            // Acquire a write lock first to avoid SQLite deferred upgrade races.
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + NOTES_TABLE + " SET id = id WHERE id = ?")) {
                ps.setLong(1, noteId);
                ps.executeUpdate();
            }
            HistoryEntry existing;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, " + PLAYER_COLUMNS + " FROM " + NOTES_TABLE + " WHERE id = ?")) {
                ps.setLong(1, noteId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return new NoteRemovalResult(NoteRemovalStatus.NOT_FOUND, null);
                    existing = new HistoryEntry(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(6));
                }
            }
            if (expectedTargetUuid != null && !expectedTargetUuid.equals(existing.playerUuid())) {
                return new NoteRemovalResult(NoteRemovalStatus.NOT_FOUND, null);
            }
            if (!canRemoveOthers && !existing.actorName().equalsIgnoreCase(safeActor)) {
                return new NoteRemovalResult(NoteRemovalStatus.NOT_OWNER, existing);
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + NOTES_TABLE + " WHERE id = ?")) {
                ps.setLong(1, noteId);
                ps.executeUpdate();
            }
            String auditDetail = "note_id=" + existing.id()
                    + " author=" + existing.actorName()
                    + " created_at=" + existing.createdAt()
                    + " note=" + existing.reason();
            writeAudit(c, "STAFF_NOTE_REMOVE", safeActor, existing.playerUuid(), existing.targetName(), auditDetail);
            return new NoteRemovalResult(NoteRemovalStatus.REMOVED, existing);
        });
    }

    /** Returns {@code uuid|createdAt|reason} fingerprints of existing notes for idempotent import planning. */
    public java.util.Set<String> loadNoteFingerprints() throws SQLException {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement("SELECT player_uuid, created_at, reason FROM " + NOTES_TABLE);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1) + "|" + rs.getLong(2) + "|" + rs.getString(3));
            }
        }
        return java.util.Set.copyOf(out);
    }

    /** Imports a historical staff note on the caller's transaction connection, preserving its original timestamp. */
    public void importNote(Connection c, UUID playerUuid, String targetName, String actorName, String reason, long createdAt) throws SQLException {
        Objects.requireNonNull(c, "connection");
        HistoryEntry entry = new HistoryEntry(0, playerUuid, targetName, actorName, reason, createdAt > 0L ? createdAt : clock.getAsLong());
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + NOTES_TABLE + " (" + PLAYER_COLUMNS + ") VALUES (?, ?, ?, ?, ?)")) {
            bindPlayer(ps, playerUuid, entry.targetName(), entry.actorName(), entry.reason(), entry.createdAt());
            ps.executeUpdate();
        }
        writeAudit(c, "STAFF_NOTE_IMPORT", entry.actorName(), playerUuid, entry.targetName(), entry.reason());
    }

    private HistoryEntry addHistory(String table, String action, UUID uuid, String name, String actor, String reason) throws SQLException {
        HistoryEntry entry = new HistoryEntry(0, uuid, name, actor, reason, clock.getAsLong());
        return transaction(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (" + PLAYER_COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                bindPlayer(ps, uuid, entry.targetName(), entry.actorName(), entry.reason(), entry.createdAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("no generated moderation history ID");
                    id = keys.getLong(1);
                }
            }
            writeAudit(c, action, entry.actorName(), uuid, entry.targetName(), entry.reason());
            return new HistoryEntry(id, uuid, entry.targetName(), entry.actorName(), entry.reason(), entry.createdAt());
        });
    }

    private List<HistoryEntry> listHistory(String table, UUID uuid, int limit) throws SQLException {
        Objects.requireNonNull(uuid, "playerUuid");
        return query("SELECT id, " + PLAYER_COLUMNS + " FROM " + table + " WHERE player_uuid = ? ORDER BY id DESC LIMIT ?",
                ps -> { ps.setString(1, uuid.toString()); ps.setInt(2, boundedLimit(limit)); },
                rs -> new HistoryEntry(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)));
    }

    private boolean removePlayerState(String table, UUID uuid, String actorName, String action) throws SQLException {
        Objects.requireNonNull(uuid, "playerUuid");
        String actor = actorName(actorName);
        return transaction(c -> {
            // Acquire a write lock before reading the audit name; also avoids SQLite deferred upgrade races.
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET player_uuid = player_uuid WHERE player_uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            String name;
            try (PreparedStatement ps = c.prepareStatement("SELECT player_name FROM " + table + " WHERE player_uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    name = rs.getString(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE player_uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            writeAudit(c, action, actor, uuid, name, "");
            return true;
        });
    }

    private boolean lockJail(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + JAILS_TABLE + " SET name = name WHERE name = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
        // MySQL can report changed rows rather than matched rows for the no-op UPDATE.
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM " + JAILS_TABLE + " WHERE name = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private String upsert(String table, String columns, String key, boolean updateLocation) {
        String[] names = columns.split(", ");
        List<String> updates = new ArrayList<>();
        for (String name : names) {
            if (name.equals(key) || (!updateLocation && List.of(LOCATION_COLUMNS.split(", ")).contains(name))) continue;
            updates.add(name + (dialect == SqlDialect.MYSQL ? " = VALUES(" + name + ")" : " = excluded." + name));
        }
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + String.join(", ", java.util.Collections.nCopies(names.length, "?"))
                + ") " + (dialect == SqlDialect.MYSQL ? "ON DUPLICATE KEY UPDATE " : "ON CONFLICT(" + key + ") DO UPDATE SET ")
                + String.join(", ", updates);
    }

    private static void bindPlayer(PreparedStatement ps, UUID uuid, String name, String actor, String reason, long createdAt) throws SQLException {
        ps.setString(1, uuid.toString());
        ps.setString(2, name);
        ps.setString(3, actor);
        ps.setString(4, reason);
        ps.setLong(5, createdAt);
    }

    private static void bindLocation(PreparedStatement ps, int index, SavedLocation location) throws SQLException {
        ps.setString(index++, location.worldId().toString());
        ps.setString(index++, location.worldName());
        ps.setDouble(index++, location.x());
        ps.setDouble(index++, location.y());
        ps.setDouble(index++, location.z());
        ps.setFloat(index++, location.yaw());
        ps.setFloat(index, location.pitch());
    }

    private static SavedLocation readLocation(ResultSet rs, int index) throws SQLException {
        return new SavedLocation(UUID.fromString(rs.getString(index++)), rs.getString(index++),
                rs.getDouble(index++), rs.getDouble(index++), rs.getDouble(index++), rs.getFloat(index++), rs.getFloat(index));
    }

    private static BanRecord readBan(ResultSet rs) throws SQLException {
        long expiry = rs.getLong(6);
        Long nullableExpiry = rs.wasNull() ? null : expiry;
        return new BanRecord(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), nullableExpiry, rs.getString(7));
    }

    private static FreezeRecord readFreeze(ResultSet rs) throws SQLException {
        return new FreezeRecord(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5));
    }

    private static JailAssignment readAssignment(ResultSet rs) throws SQLException {
        long expiry = rs.getLong(14);
        Long nullableExpiry = rs.wasNull() ? null : expiry;
        return new JailAssignment(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getString(6), readLocation(rs, 7), nullableExpiry);
    }

    static String playerName(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "playerUuid");
        return boundedText(name == null || name.isBlank() ? uuid.toString() : name.trim(), 36);
    }

    static String actorName(String actor) {
        return boundedText(actor == null || actor.isBlank() ? "Console" : actor.trim(), 64);
    }

    static String ipAddress(String address) {
        if (address == null || address.isBlank()) return null;
        String trimmed = address.trim();
        if (trimmed.length() > 45) throw new IllegalArgumentException("IP address exceeds 45 characters");
        return trimmed;
    }

    /** True when {@code raw} is a literal IPv4 or IPv6 address (checked purely syntactically, with no DNS lookup). */
    public static boolean isLiteralIp(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 45) return false;
        if (s.indexOf('.') >= 0 && s.indexOf(':') < 0) {
            String[] parts = s.split("\\.", -1);
            if (parts.length != 4) return false;
            for (String p : parts) {
                if (p.isEmpty() || p.length() > 3) return false;
                for (int i = 0; i < p.length(); i++) {
                    if (!Character.isDigit(p.charAt(i))) return false;
                }
                int val = Integer.parseInt(p);
                if (val < 0 || val > 255) return false;
            }
            return true;
        }
        if (s.indexOf(':') >= 0) {
            // Strip optional brackets around IPv6 literal
            if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
                s = s.substring(1, s.length() - 1);
            }
            if (s.contains(":::")) return false;
            int doubleColon = s.indexOf("::");
            if (doubleColon >= 0 && s.indexOf("::", doubleColon + 2) >= 0) return false;
            String[] groups = s.split(":", -1);
            int nonEmpty = 0;
            for (int i = 0; i < groups.length; i++) {
                String g = groups[i];
                if (g.isEmpty()) continue;
                if (i == groups.length - 1 && g.contains(".")) {
                    if (!isLiteralIp(g)) return false;
                    nonEmpty += 2;
                    continue;
                }
                if (g.length() > 4) return false;
                for (int j = 0; j < g.length(); j++) {
                    char c = g.charAt(j);
                    boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                    if (!hex) return false;
                }
                nonEmpty++;
            }
            return doubleColon >= 0 ? nonEmpty <= 7 : nonEmpty == 8;
        }
        return false;
    }

    /** Deterministic UUID key for a literal IP ban row that is not tied to a player account. */
    public static UUID ipBanUuid(String ipAddress) {
        String normalized = Objects.requireNonNull(ipAddress(ipAddress), "ipAddress").toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(("allium-ipban:" + normalized).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String jailName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("jail name must contain 1..32 ASCII letters, digits, underscores or hyphens");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    static void validateLocation(SavedLocation location) {
        Objects.requireNonNull(location, "location");
        if (location.worldName().isBlank() || location.worldName().length() > 255) {
            throw new IllegalArgumentException("location world name must contain 1..255 characters");
        }
        // SavedLocation's constructor rejects non-finite coordinates and angles.
    }

    private static String boundedText(String text, int max) { return text.length() <= max ? text : text.substring(0, max); }
    private static int boundedLimit(int limit) { return Math.max(1, Math.min(MAX_HISTORY_LIMIT, limit)); }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }
    @FunctionalInterface
    private interface Reader<T> { T read(ResultSet rs) throws SQLException; }
    @FunctionalInterface
    private interface Work<T> { T run(Connection c) throws SQLException; }

    private <T> List<T> query(String sql, Binder binder, Reader<T> reader) throws SQLException {
        try (Connection c = source.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(reader.read(rs));
                return List.copyOf(out);
            }
        }
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = source.connection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            boolean ended = false;
            try {
                T result = work.run(c);
                c.commit();
                ended = true;
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    c.rollback();
                    ended = true;
                } catch (SQLException | RuntimeException | Error rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            } finally {
                // Enabling auto-commit after a failed rollback could commit the partial mutation.
                // Close/discard that connection instead; never retry an uncertain transaction.
                if (ended) c.setAutoCommit(autoCommit);
            }
        }
    }

    private void writeAudit(Connection c, String action, String actor, UUID uuid, String name, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + AUDIT_TABLE
                + " (created_at, action, actor_name, target_uuid, target_name, detail) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, clock.getAsLong());
            ps.setString(2, action);
            ps.setString(3, actor);
            ps.setString(4, uuid == null ? null : uuid.toString());
            ps.setString(5, name);
            ps.setString(6, boundedText(detail, 512));
            ps.executeUpdate();
        }
    }

    public void recordAudit(
            String action,
            String actorName,
            UUID targetUuid,
            String targetName,
            String detail
    ) throws SQLException {
        String safeAction = Objects.requireNonNull(action, "action").trim();
        String safeActor = (actorName == null || actorName.isBlank()) ? "Console" : actorName.trim();
        String safeDetail = detail == null ? "" : (detail.length() <= 512 ? detail : detail.substring(0, 512));
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO " + AUDIT_TABLE
                             + " (created_at, action, actor_name, target_uuid, target_name, detail) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, clock.getAsLong());
            ps.setString(2, safeAction);
            ps.setString(3, safeActor);
            if (targetUuid == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, targetUuid.toString());
            }
            if (targetName == null || targetName.isBlank()) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, targetName.trim());
            }
            ps.setString(6, safeDetail);
            ps.executeUpdate();
        }
    }

    public List<ModerationAuditRecord> loadRecentAudit(int limit) throws SQLException {
        List<ModerationAuditRecord> out = new ArrayList<>();
        try (Connection c = source.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, created_at, action, actor_name, target_uuid, target_name, detail FROM "
                             + AUDIT_TABLE + " ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, boundedLimit(limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rawUuid = rs.getString(5);
                    out.add(new ModerationAuditRecord(
                            rs.getLong(1),
                            rs.getLong(2),
                            rs.getString(3),
                            rs.getString(4),
                            rawUuid == null ? null : UUID.fromString(rawUuid),
                            rs.getString(6),
                            rs.getString(7)
                    ));
                }
            }
        }
        return out;
    }

    /**
     * Checks whether the player has an unresolved workflow hold that forbids mutating their offline
     * {@code <uuid>.dat} file (held economy reservations, pending/claiming mail, or in-flight kit
     * deliveries). Checks table existence so isolated/minimal module deployments work cleanly.
     */
    public boolean hasPendingWorkflowHold(UUID playerUuid) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        String uuidText = playerUuid.toString();
        try (Connection c = source.connection()) {
            if (tableExists(c, "allium_economy_reservations") && rowExists(
                    c,
                    "SELECT 1 FROM allium_economy_reservations WHERE account_uuid = ? AND state = 'HELD' LIMIT 1",
                    uuidText
            )) {
                return true;
            }
            if (tableExists(c, "allium_mail") && rowExists(
                    c,
                    "SELECT 1 FROM allium_mail WHERE (sender = ? AND state = 'PENDING') OR (recipient = ? AND state = 'CLAIMING') LIMIT 1",
                    uuidText,
                    uuidText
            )) {
                return true;
            }
            if (tableExists(c, "allium_kit_deliveries") && rowExists(
                    c,
                    "SELECT 1 FROM allium_kit_deliveries WHERE player_uuid = ? LIMIT 1",
                    uuidText
            )) {
                return true;
            }
            return false;
        }
    }

    private boolean tableExists(Connection c, String tableName) throws SQLException {
        String sql = dialect == SqlDialect.SQLITE
                ? "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1"
                : "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ? LIMIT 1";
        return rowExists(c, sql, tableName);
    }

    private static boolean rowExists(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static MuteRecord readMute(ResultSet rs) throws SQLException {
        UUID playerUuid = UUID.fromString(rs.getString(1));
        String playerName = rs.getString(2);
        String actorName = rs.getString(3);
        String reason = rs.getString(4);
        long createdAt = rs.getLong(5);
        long rawExpires = rs.getLong(6);
        Long expiresAt = rs.wasNull() ? null : rawExpires;
        return new MuteRecord(playerUuid, playerName, actorName, reason, createdAt, expiresAt);
    }
}
