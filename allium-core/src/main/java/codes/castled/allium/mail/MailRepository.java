package codes.castled.allium.mail;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * JDBC storage for mail, on SQLite or MySQL. Blocking: callers use a bounded pool, never the
 * server thread.
 *
 * <p>Concurrency, including two servers on one MySQL database:
 * <ul>
 *   <li>A send locks the recipient's (and the sender's) row in {@code allium_mail_boxes}, in UUID
 *       order, before it counts the mailbox and the sender's recent sends. Concurrent sends to one
 *       mailbox therefore never exceed its size, and one sender's sends never exceed the rate.</li>
 *   <li>A claim is a conditional update from READY to CLAIMING with a fresh token: exactly one
 *       claimer wins. Finishing and releasing are conditional on that token, so a stale or
 *       duplicate finish changes nothing.</li>
 * </ul>
 *
 * <p>Import mapping (M6): Essentials' {@code mail:} entries and legacy Allium's
 * {@code mail_messages} map to TEXT rows, and legacy {@code mail_gifts} (unclaimed) to GIFT rows,
 * with {@code source} naming the importer and {@code source_ref} its idempotency key (unique per
 * source). See docs/mail.md.
 */
public final class MailRepository {

    public static final String COMPONENT = "mail";

    public enum SendResult {
        SAVED,
        MAILBOX_FULL,
        RATE_LIMITED
    }

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public MailRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = connections;
        this.dialect = dialect;
        this.clock = clock;
    }

    static List<Migration> migrations() {
        List<String> journal = List.of(
                "CREATE TABLE IF NOT EXISTS allium_mail_activations (id CHAR(36) NOT NULL PRIMARY KEY)",
                "CREATE TABLE IF NOT EXISTS allium_mail_claim_receipts (token CHAR(36) NOT NULL PRIMARY KEY, mail CHAR(36) NOT NULL)");
        return List.of(Migration.of(1, "mail, send log and mailbox locks", v1(SqlDialect.SQLITE), v1(SqlDialect.MYSQL)),
                Migration.of(2, "durable activation and claim acknowledgements", journal, journal));
    }

    private static List<String> v1(SqlDialect dialect) {
        String items = dialect == SqlDialect.MYSQL ? "MEDIUMTEXT" : "TEXT";
        return List.of(
                "CREATE TABLE IF NOT EXISTS allium_mail ("
                        + "id CHAR(36) NOT NULL PRIMARY KEY, "
                        + "recipient CHAR(36) NOT NULL, "
                        + "sender CHAR(36) NULL, "
                        + "sender_name VARCHAR(32) NOT NULL, "
                        + "kind VARCHAR(8) NOT NULL, "
                        + "body TEXT NULL, "
                        + "items " + items + " NULL, "
                        + "item_count INT NOT NULL, "
                        + "state VARCHAR(12) NOT NULL, "
                        + "claim_token CHAR(36) NULL, "
                        + "claim_owner VARCHAR(64) NULL, "
                        + "claim_started BIGINT NULL, "
                        + "origin VARCHAR(64) NOT NULL, "
                        + "unread BOOLEAN NOT NULL, "
                        + "created_at BIGINT NOT NULL, "
                        + "expires_at BIGINT NOT NULL, "
                        + "source VARCHAR(32) NOT NULL, "
                        + "source_ref VARCHAR(191) NULL, "
                        + "version BIGINT NOT NULL)",
                "CREATE INDEX allium_mail_by_recipient ON allium_mail (recipient, state, created_at)",
                "CREATE INDEX allium_mail_by_sender ON allium_mail (sender, state)",
                "CREATE UNIQUE INDEX allium_mail_import ON allium_mail (source, source_ref)",
                "CREATE TABLE IF NOT EXISTS allium_mail_sends ("
                        + "id CHAR(36) NOT NULL PRIMARY KEY, sender CHAR(36) NOT NULL, created_at BIGINT NOT NULL)",
                "CREATE INDEX allium_mail_sends_by_sender ON allium_mail_sends (sender, created_at)",
                "CREATE TABLE IF NOT EXISTS allium_mail_boxes (uuid CHAR(36) NOT NULL PRIMARY KEY, version BIGINT NOT NULL)");
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, migrations());
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} as one transaction. MySQL may abort one of two transactions that lock the
     * same new mailbox row at once (a deadlock or lock wait timeout); the whole transaction is then
     * retried, a few times, so a busy mailbox never turns into a failed send.
     */
    private <T> T transaction(Work<T> work) throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try (Connection connection = connections.connection()) {
                boolean auto = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T value = work.run(connection);
                    connection.commit();
                    return value;
                } catch (SQLException e) {
                    connection.rollback();
                    if (!retryable(e)) {
                        throw e;
                    }
                    last = e;
                } catch (RuntimeException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(auto);
                }
            }
            if (attempt == 5) break;
            try {
                Thread.sleep(5L + (long) (Math.random() * 20L * (attempt + 1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last;
    }

    /** A deadlock or lock wait timeout (MySQL 1213, 1205; SQLSTATE 40001), or SQLite's busy lock. */
    static boolean retryable(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && ("40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1213
                    || sql.getErrorCode() == 1205 || (sql.getMessage() != null && sql.getMessage().contains("SQLITE_BUSY")))) {
                return true;
            }
        }
        return false;
    }

    private <T> T plain(Work<T> work) throws SQLException {
        try (Connection connection = connections.connection()) {
            return work.run(connection);
        }
    }

    /**
     * Stores {@code mail} if the recipient's mailbox has room and the sender is under the rate,
     * as one transaction. A gift is stored PENDING; text is stored READY.
     */
    public SendResult send(MailRecord mail, MailLimits limits, boolean bypassLimits) throws SQLException {
        long now = mail.createdAt();
        return transaction(c -> {
            TreeSet<String> locks = new TreeSet<>();
            locks.add(mail.recipient().toString());
            if (mail.sender() != null) {
                locks.add(mail.sender().toString());
            }
            for (String uuid : locks) {
                lockBox(c, uuid);
            }
            // A retry after a lost commit response reuses its ID and never inserts twice.
            if (count(c, "SELECT COUNT(*) FROM allium_mail WHERE id = ?", mail.id().toString()) > 0) return SendResult.SAVED;
            if (!bypassLimits) {
                long inBox = count(c, "SELECT COUNT(*) FROM allium_mail WHERE recipient = ? AND (expires_at = 0 OR expires_at > ?)",
                        mail.recipient().toString(), now);
                if (inBox >= limits.mailboxSize()) {
                    return SendResult.MAILBOX_FULL;
                }
                if (mail.sender() != null) {
                    long recent = count(c, "SELECT COUNT(*) FROM allium_mail_sends WHERE sender = ? AND created_at > ?",
                            mail.sender().toString(), now - limits.windowMillis());
                    if (recent >= limits.sendsPerWindow()) {
                        return SendResult.RATE_LIMITED;
                    }
                }
            }
            insert(c, mail);
            if (mail.sender() != null) {
                update(c, "INSERT INTO allium_mail_sends (id, sender, created_at) VALUES (?, ?, ?)",
                        mail.id().toString(), mail.sender().toString(), now);
            }
            return SendResult.SAVED;
        });
    }

    /** Takes the row lock of one player's mailbox (creating the row first). */
    private void lockBox(Connection c, String uuid) throws SQLException {
        String insert = dialect == SqlDialect.MYSQL
                ? "INSERT IGNORE INTO allium_mail_boxes (uuid, version) VALUES (?, 0)"
                : "INSERT OR IGNORE INTO allium_mail_boxes (uuid, version) VALUES (?, 0)";
        update(c, insert, uuid);
        update(c, "UPDATE allium_mail_boxes SET version = version + 1 WHERE uuid = ?", uuid);
    }

    private void insert(Connection c, MailRecord m) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO allium_mail (id, recipient, sender, sender_name, kind, body, items, "
                + "item_count, state, claim_token, claim_owner, claim_started, origin, unread, created_at, expires_at, source, source_ref, "
                + "version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, 1)")) {
            int i = 1;
            s.setString(i++, m.id().toString());
            s.setString(i++, m.recipient().toString());
            setNullable(s, i++, m.sender() == null ? null : m.sender().toString());
            s.setString(i++, m.senderName());
            s.setString(i++, m.kind().name());
            setNullable(s, i++, m.body());
            setNullable(s, i++, m.items());
            s.setInt(i++, m.itemCount());
            s.setString(i++, m.state().name());
            s.setString(i++, m.origin());
            s.setBoolean(i++, m.unread());
            s.setLong(i++, m.createdAt());
            s.setLong(i++, m.expiresAt());
            s.setString(i++, m.source());
            setNullable(s, i, m.sourceRef());
            s.executeUpdate();
        }
    }

    /** PENDING to READY: the gift's items durably left the sender. False when it was not PENDING. */
    public boolean activate(UUID mail) throws SQLException {
        return transaction(c -> {
            if (count(c, "SELECT COUNT(*) FROM allium_mail_activations WHERE id = ?", mail.toString()) > 0) return true;
            if (update(c, "UPDATE allium_mail SET state = 'READY', version = version + 1 WHERE id = ? AND state = 'PENDING'",
                    mail.toString()) != 1) return false;
            update(c, "INSERT INTO allium_mail_activations (id) VALUES (?)", mail.toString());
            return true;
        });
    }

    /** Deletes a PENDING gift whose items never left the sender, and refunds its rate-limit slot. */
    public boolean cancelPending(UUID mail) throws SQLException {
        return transaction(c -> {
            int n = update(c, "DELETE FROM allium_mail WHERE id = ? AND state = 'PENDING'", mail.toString());
            if (n == 1) {
                update(c, "DELETE FROM allium_mail_sends WHERE id = ?", mail.toString());
            }
            return n == 1;
        });
    }

    public Optional<MailRecord> find(UUID mail) throws SQLException {
        return plain(c -> query(c, "SELECT * FROM allium_mail WHERE id = ?", mail.toString())).stream().findFirst();
    }

    /** The visible mailbox: READY and CLAIMING mail that has not expired, oldest first. */
    public List<MailRecord> mailbox(UUID recipient, long now) throws SQLException {
        return plain(c -> query(c, "SELECT * FROM allium_mail WHERE recipient = ? AND state <> 'PENDING' "
                + "AND (expires_at = 0 OR expires_at > ?) ORDER BY created_at, id", recipient.toString(), now));
    }

    public int unread(UUID recipient, long now) throws SQLException {
        return plain(c -> (int) count(c, "SELECT COUNT(*) FROM allium_mail WHERE recipient = ? AND state <> 'PENDING' AND unread = ? "
                + "AND (expires_at = 0 OR expires_at > ?)", recipient.toString(), true, now));
    }

    /** PENDING gifts this server created for {@code sender}: recovery input on their join. */
    public List<MailRecord> pendingFrom(UUID sender, String origin) throws SQLException {
        return plain(c -> query(c, "SELECT * FROM allium_mail WHERE sender = ? AND state = 'PENDING' AND origin = ?",
                sender.toString(), origin));
    }

    /** CLAIMING gifts of {@code recipient} that this server owns: recovery input on their join. */
    public List<MailRecord> claimingFor(UUID recipient, String owner) throws SQLException {
        return plain(c -> query(c, "SELECT * FROM allium_mail WHERE recipient = ? AND state = 'CLAIMING' AND claim_owner = ?",
                recipient.toString(), owner));
    }

    /** READY to CLAIMING with {@code token}. Exactly one concurrent claimer gets true. */
    public boolean beginClaim(UUID mail, UUID recipient, UUID token, String owner, long now) throws SQLException {
        return plain(c -> update(c, "UPDATE allium_mail SET state = 'CLAIMING', claim_token = ?, claim_owner = ?, claim_started = ?, "
                        + "version = version + 1 WHERE id = ? AND recipient = ? AND kind = 'GIFT' AND state = 'READY'",
                token.toString(), owner, now, mail.toString(), recipient.toString()) == 1);
    }

    /**
     * Finishes claim {@code token}: deletes the gift when everything was delivered
     * ({@code remainingItems == null}), else stores what was not delivered and makes it READY
     * again. False when the claim is not (or no longer) this token's.
     */
    public boolean finishClaim(UUID mail, UUID token, @Nullable String remainingItems, int remainingCount) throws SQLException {
        return transaction(c -> {
            if (count(c, "SELECT COUNT(*) FROM allium_mail_claim_receipts WHERE token = ? AND mail = ?",
                    token.toString(), mail.toString()) > 0) return true;
            int changed = remainingItems == null
                    ? update(c, "DELETE FROM allium_mail WHERE id = ? AND state = 'CLAIMING' AND claim_token = ?",
                    mail.toString(), token.toString())
                    : update(c, "UPDATE allium_mail SET state = 'READY', items = ?, item_count = ?, claim_token = NULL, claim_owner = NULL, "
                        + "claim_started = NULL, version = version + 1 WHERE id = ? AND state = 'CLAIMING' AND claim_token = ?",
                    remainingItems, remainingCount, mail.toString(), token.toString());
            if (changed != 1) return false;
            update(c, "INSERT INTO allium_mail_claim_receipts (token, mail) VALUES (?, ?)", token.toString(), mail.toString());
            return true;
        });
    }

    public boolean claimAcknowledged(UUID mail, UUID token) throws SQLException {
        return plain(c -> count(c, "SELECT COUNT(*) FROM allium_mail_claim_receipts WHERE token = ? AND mail = ?",
                token.toString(), mail.toString()) > 0);
    }

    /** CLAIMING back to READY, unchanged: claim {@code token} delivered nothing. */
    public boolean releaseClaim(UUID mail, UUID token) throws SQLException {
        return plain(c -> update(c, "UPDATE allium_mail SET state = 'READY', claim_token = NULL, claim_owner = NULL, claim_started = NULL, "
                + "version = version + 1 WHERE id = ? AND state = 'CLAIMING' AND claim_token = ?", mail.toString(), token.toString()) == 1);
    }

    public int markRead(UUID recipient, Collection<UUID> mail) throws SQLException {
        if (mail.isEmpty()) {
            return 0;
        }
        return transaction(c -> {
            int n = 0;
            for (UUID id : mail) {
                n += update(c, "UPDATE allium_mail SET unread = ? WHERE id = ? AND recipient = ? AND unread = ?",
                        false, id.toString(), recipient.toString(), true);
            }
            return n;
        });
    }

    /** Deletes one text mail. Gifts are never deleted: their items would be lost. */
    public boolean deleteText(UUID recipient, UUID mail) throws SQLException {
        return plain(c -> update(c, "DELETE FROM allium_mail WHERE id = ? AND recipient = ? AND kind = 'TEXT'",
                mail.toString(), recipient.toString()) == 1);
    }

    /** Deletes every read text mail. */
    public int clearRead(UUID recipient) throws SQLException {
        return plain(c -> update(c, "DELETE FROM allium_mail WHERE recipient = ? AND kind = 'TEXT' AND unread = ?",
                recipient.toString(), false));
    }

    /** Deletes expired text mail and send-log rows older than {@code sendLogBefore}. Gifts never expire. */
    public int purge(long now, long sendLogBefore) throws SQLException {
        return transaction(c -> {
            int n = update(c, "DELETE FROM allium_mail WHERE kind = 'TEXT' AND expires_at > 0 AND expires_at <= ?", now);
            update(c, "DELETE FROM allium_mail_sends WHERE created_at < ?", sendLogBefore);
            return n;
        });
    }

    private static List<MailRecord> query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet r = s.executeQuery()) {
                List<MailRecord> out = new ArrayList<>();
                while (r.next()) {
                    out.add(new MailRecord(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("recipient")),
                            uuid(r.getString("sender")), r.getString("sender_name"), MailKind.valueOf(r.getString("kind")),
                            r.getString("body"), r.getString("items"), r.getInt("item_count"), MailState.valueOf(r.getString("state")),
                            uuid(r.getString("claim_token")), r.getString("claim_owner"), r.getString("origin"), r.getBoolean("unread"),
                            r.getLong("created_at"), r.getLong("expires_at"), r.getString("source"), r.getString("source_ref"),
                            r.getLong("version")));
                }
                return out;
            }
        }
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            return s.executeUpdate();
        }
    }

    private static long count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Boolean b) {
                s.setBoolean(i + 1, b);
            } else if (a instanceof Long l) {
                s.setLong(i + 1, l);
            } else if (a instanceof Integer n) {
                s.setInt(i + 1, n);
            } else {
                setNullable(s, i + 1, a == null ? null : a.toString());
            }
        }
    }

    private static void setNullable(PreparedStatement s, int index, @Nullable String value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.VARCHAR);
        } else {
            s.setString(index, value);
        }
    }

    @Nullable
    private static UUID uuid(@Nullable String s) {
        return s == null ? null : UUID.fromString(s);
    }
}
