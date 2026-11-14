package codes.castled.allium.kit;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.Migration;
import codes.castled.allium.storage.SchemaMigrator;
import codes.castled.allium.storage.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * JDBC storage for kits (S16), on SQLite or MySQL. Blocking: callers use a bounded pool, never
 * the server thread.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Every claim attempt for a player first acquires a per-player lock row in
 *       {@code allium_kit_locks} inside a single SQL transaction, serializing concurrent claims
 *       on both SQLite and MySQL.</li>
 *   <li>{@link #beginClaim} verifies that the player has no active in-flight delivery, that a
 *       one-time kit has not already been claimed, and that the cooldown has elapsed (unless
 *       {@code bypassCooldown} is true), before inserting a {@code CLAIMING} row in
 *       {@code allium_kit_deliveries}.</li>
 *   <li>Cooldowns and one-time usage in {@code allium_kit_usages} are recorded <b>only</b> when
 *       {@link #finishClaim} succeeds after native {@code player.dat} receipt verification, and
 *       an immutable acknowledgement is recorded in {@code allium_kit_claim_receipts}.</li>
 *   <li>{@link #releaseClaim} deletes an undelivered {@code CLAIMING} row (on disconnect, full
 *       inventory, event cancellation, or crash before {@code player.dat} save) without touching
 *       {@code allium_kit_usages}, so a failed or interrupted delivery never burns the player's
 *       cooldown or one-time kit status.</li>
 *   <li>Recovery ownership: both {@link #finishClaim} and {@link #releaseClaim} require
 *       {@code claim_owner = serverId}, so a {@code CLAIMING} row owned by another server ID is
 *       never released or finalized by this server.</li>
 * </ul>
 */
public final class KitRepository {

    public static final String COMPONENT = "kits";

    public sealed interface BeginOutcome permits BeginOutcome.Claiming, BeginOutcome.AlreadyClaiming,
            BeginOutcome.OneTimeClaimed, BeginOutcome.OnCooldown {
        record Claiming(KitDeliveryRecord delivery) implements BeginOutcome {}
        record AlreadyClaiming() implements BeginOutcome {}
        record OneTimeClaimed() implements BeginOutcome {}
        record OnCooldown(long remainingMillis) implements BeginOutcome {}
    }

    private final ConnectionSource connections;
    private final SqlDialect dialect;
    private final LongSupplier clock;

    public KitRepository(ConnectionSource connections, SqlDialect dialect, LongSupplier clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static List<Migration> migrations() {
        return List.of(Migration.of(1, "kit usage, in-flight deliveries and durable claim receipts",
                v1(SqlDialect.SQLITE), v1(SqlDialect.MYSQL)));
    }

    private static List<String> v1(SqlDialect dialect) {
        String engine = dialect == SqlDialect.MYSQL ? " ENGINE=InnoDB" : "";
        String itemsType = dialect == SqlDialect.MYSQL ? "MEDIUMTEXT" : "TEXT";
        return List.of(
                "CREATE TABLE IF NOT EXISTS allium_kit_locks ("
                        + "player_uuid CHAR(36) NOT NULL PRIMARY KEY, "
                        + "version BIGINT NOT NULL)" + engine,
                "CREATE TABLE IF NOT EXISTS allium_kit_usages ("
                        + "player_uuid CHAR(36) NOT NULL, "
                        + "kit_name VARCHAR(32) NOT NULL, "
                        + "last_claimed_at BIGINT NOT NULL, "
                        + "claim_count BIGINT NOT NULL, "
                        + "one_time_claimed BOOLEAN NOT NULL, "
                        + "PRIMARY KEY (player_uuid, kit_name))" + engine,
                "CREATE TABLE IF NOT EXISTS allium_kit_deliveries ("
                        + "id CHAR(36) NOT NULL PRIMARY KEY, "
                        + "player_uuid CHAR(36) NOT NULL, "
                        + "kit_name VARCHAR(32) NOT NULL, "
                        + "claim_token CHAR(36) NOT NULL, "
                        + "claim_owner VARCHAR(64) NOT NULL, "
                        + "items_payload " + itemsType + " NOT NULL, "
                        + "item_count INT NOT NULL, "
                        + "cooldown_millis BIGINT NOT NULL, "
                        + "one_time BOOLEAN NOT NULL, "
                        + "bypass_cooldown BOOLEAN NOT NULL, "
                        + "created_at BIGINT NOT NULL)" + engine,
                "CREATE INDEX allium_kit_deliveries_by_player ON allium_kit_deliveries (player_uuid, claim_owner)",
                "CREATE TABLE IF NOT EXISTS allium_kit_claim_receipts ("
                        + "token CHAR(36) NOT NULL PRIMARY KEY, "
                        + "delivery_id CHAR(36) NOT NULL, "
                        + "player_uuid CHAR(36) NOT NULL, "
                        + "kit_name VARCHAR(32) NOT NULL, "
                        + "delivered_at BIGINT NOT NULL)" + engine
        );
    }

    public int migrate() throws SQLException {
        return new SchemaMigrator(connections, dialect, clock).migrate(COMPONENT, migrations());
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

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

    private void lockPlayer(Connection c, UUID playerUuid) throws SQLException {
        String id = playerUuid.toString();
        String insert = dialect == SqlDialect.MYSQL
                ? "INSERT IGNORE INTO allium_kit_locks (player_uuid, version) VALUES (?, 0)"
                : "INSERT OR IGNORE INTO allium_kit_locks (player_uuid, version) VALUES (?, 0)";
        update(c, insert, id);
        update(c, "UPDATE allium_kit_locks SET version = version + 1 WHERE player_uuid = ?", id);
    }

    /**
     * Atomically checks cooldown, one-time usage, and in-flight state for {@code playerUuid},
     * and inserts a {@code CLAIMING} delivery row when allowed.
     */
    public BeginOutcome beginClaim(KitDeliveryRecord delivery, long now) throws SQLException {
        Objects.requireNonNull(delivery, "delivery");
        return transaction(c -> {
            lockPlayer(c, delivery.playerUuid());

            Optional<KitDeliveryRecord> existingById = findDelivery(c, delivery.id());
            if (existingById.isPresent()) {
                return new BeginOutcome.Claiming(existingById.get());
            }

            long activeCount = count(c, "SELECT COUNT(*) FROM allium_kit_deliveries WHERE player_uuid = ?",
                    delivery.playerUuid().toString());
            if (activeCount > 0) {
                return new BeginOutcome.AlreadyClaiming();
            }

            Optional<KitUsageRecord> usage = findUsage(c, delivery.playerUuid(), delivery.kitName());
            if (usage.isPresent()) {
                KitUsageRecord u = usage.get();
                if (delivery.oneTime() && (u.oneTimeClaimed() || u.claimCount() > 0)) {
                    return new BeginOutcome.OneTimeClaimed();
                }
                if (!delivery.bypassCooldown() && delivery.cooldownMillis() > 0 && u.lastClaimedAt() > 0) {
                    long availableAt = u.lastClaimedAt() + delivery.cooldownMillis();
                    if (now < availableAt) {
                        return new BeginOutcome.OnCooldown(availableAt - now);
                    }
                }
            }

            update(c, "INSERT INTO allium_kit_deliveries ("
                            + "id, player_uuid, kit_name, claim_token, claim_owner, items_payload, item_count, "
                            + "cooldown_millis, one_time, bypass_cooldown, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    delivery.id().toString(),
                    delivery.playerUuid().toString(),
                    delivery.kitName(),
                    delivery.claimToken().toString(),
                    delivery.claimOwner(),
                    delivery.itemsPayload(),
                    delivery.itemCount(),
                    delivery.cooldownMillis(),
                    delivery.oneTime(),
                    delivery.bypassCooldown(),
                    delivery.createdAt());
            return new BeginOutcome.Claiming(delivery);
        });
    }

    /**
     * Atomically finalizes a verified kit delivery owned by {@code ownerServer}: records the
     * immutable receipt, updates {@code allium_kit_usages} (starting cooldown and marking one-time
     * claimed), and deletes the in-flight row in {@code allium_kit_deliveries}.
     *
     * <p>A {@code CLAIMING} row owned by another server ID is never finalized by this server.
     * Idempotent on replay of the same {@code (deliveryId, token)}.
     */
    public boolean finishClaim(UUID deliveryId, UUID token, String ownerServer, long deliveredAt) throws SQLException {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(ownerServer, "ownerServer");
        return transaction(c -> {
            if (count(c, "SELECT COUNT(*) FROM allium_kit_claim_receipts WHERE token = ? AND delivery_id = ?",
                    token.toString(), deliveryId.toString()) > 0) {
                return true;
            }
            Optional<KitDeliveryRecord> row = findDelivery(c, deliveryId);
            if (row.isEmpty() || !row.get().claimToken().equals(token) || !row.get().claimOwner().equals(ownerServer)) {
                return false;
            }
            KitDeliveryRecord d = row.get();
            lockPlayer(c, d.playerUuid());

            int deleted = update(c, "DELETE FROM allium_kit_deliveries WHERE id = ? AND claim_token = ? AND claim_owner = ?",
                    deliveryId.toString(), token.toString(), ownerServer);
            if (deleted != 1) {
                return false;
            }

            Optional<KitUsageRecord> current = findUsage(c, d.playerUuid(), d.kitName());
            long effectiveTime = Math.max(deliveredAt, d.createdAt());
            if (current.isEmpty()) {
                update(c, "INSERT INTO allium_kit_usages (player_uuid, kit_name, last_claimed_at, claim_count, one_time_claimed) "
                                + "VALUES (?, ?, ?, 1, ?)",
                        d.playerUuid().toString(), d.kitName(), effectiveTime, d.oneTime());
            } else {
                KitUsageRecord u = current.get();
                long nextLastClaimed = Math.max(u.lastClaimedAt(), effectiveTime);
                boolean nextOneTime = u.oneTimeClaimed() || d.oneTime();
                update(c, "UPDATE allium_kit_usages SET last_claimed_at = ?, claim_count = claim_count + 1, one_time_claimed = ? "
                                + "WHERE player_uuid = ? AND kit_name = ?",
                        nextLastClaimed, nextOneTime, d.playerUuid().toString(), d.kitName());
            }

            update(c, "INSERT INTO allium_kit_claim_receipts (token, delivery_id, player_uuid, kit_name, delivered_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    token.toString(), deliveryId.toString(), d.playerUuid().toString(), d.kitName(), effectiveTime);
            return true;
        });
    }

    /**
     * Releases an undelivered kit claim owned by {@code ownerServer} without modifying
     * {@code allium_kit_usages}. A {@code CLAIMING} row owned by another server ID is never
     * released by this server.
     */
    public boolean releaseClaim(UUID deliveryId, UUID token, String ownerServer) throws SQLException {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(ownerServer, "ownerServer");
        return transaction(c -> {
            Optional<KitDeliveryRecord> row = findDelivery(c, deliveryId);
            if (row.isEmpty() || !row.get().claimToken().equals(token) || !row.get().claimOwner().equals(ownerServer)) {
                return false;
            }
            lockPlayer(c, row.get().playerUuid());
            return update(c, "DELETE FROM allium_kit_deliveries WHERE id = ? AND claim_token = ? AND claim_owner = ?",
                    deliveryId.toString(), token.toString(), ownerServer) == 1;
        });
    }

    public boolean claimAcknowledged(UUID deliveryId, UUID token) throws SQLException {
        return plain(c -> count(c, "SELECT COUNT(*) FROM allium_kit_claim_receipts WHERE token = ? AND delivery_id = ?",
                token.toString(), deliveryId.toString()) > 0);
    }

    public List<KitDeliveryRecord> claimingFor(UUID playerUuid, String ownerServer) throws SQLException {
        return plain(c -> queryDeliveries(c,
                "SELECT * FROM allium_kit_deliveries WHERE player_uuid = ? AND claim_owner = ? ORDER BY created_at, id",
                playerUuid.toString(), ownerServer));
    }

    public Optional<KitDeliveryRecord> findDelivery(UUID deliveryId) throws SQLException {
        return plain(c -> findDelivery(c, deliveryId));
    }

    public Optional<KitUsageRecord> usage(UUID playerUuid, String kitName) throws SQLException {
        String valid = KitNames.validate(kitName);
        return plain(c -> findUsage(c, playerUuid, valid));
    }

    public Map<String, KitUsageRecord> usages(UUID playerUuid) throws SQLException {
        return plain(c -> {
            Map<String, KitUsageRecord> out = new LinkedHashMap<>();
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT * FROM allium_kit_usages WHERE player_uuid = ? ORDER BY kit_name")) {
                s.setString(1, playerUuid.toString());
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        KitUsageRecord row = readUsage(r);
                        out.put(row.kitName(), row);
                    }
                }
            }
            return Map.copyOf(out);
        });
    }

    private static Optional<KitDeliveryRecord> findDelivery(Connection c, UUID id) throws SQLException {
        List<KitDeliveryRecord> rows = queryDeliveries(c, "SELECT * FROM allium_kit_deliveries WHERE id = ?", id.toString());
        return rows.stream().findFirst();
    }

    private static List<KitDeliveryRecord> queryDeliveries(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet r = s.executeQuery()) {
                List<KitDeliveryRecord> out = new ArrayList<>();
                while (r.next()) {
                    out.add(new KitDeliveryRecord(
                            UUID.fromString(r.getString("id")),
                            UUID.fromString(r.getString("player_uuid")),
                            r.getString("kit_name"),
                            UUID.fromString(r.getString("claim_token")),
                            r.getString("claim_owner"),
                            r.getString("items_payload"),
                            r.getInt("item_count"),
                            r.getLong("cooldown_millis"),
                            r.getBoolean("one_time"),
                            r.getBoolean("bypass_cooldown"),
                            r.getLong("created_at")
                    ));
                }
                return out;
            }
        }
    }

    private static Optional<KitUsageRecord> findUsage(Connection c, UUID playerUuid, String kitName) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT * FROM allium_kit_usages WHERE player_uuid = ? AND kit_name = ?")) {
            s.setString(1, playerUuid.toString());
            s.setString(2, kitName);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(readUsage(r));
            }
        }
    }

    private static KitUsageRecord readUsage(ResultSet r) throws SQLException {
        return new KitUsageRecord(
                UUID.fromString(r.getString("player_uuid")),
                r.getString("kit_name"),
                r.getLong("last_claimed_at"),
                r.getLong("claim_count"),
                r.getBoolean("one_time_claimed")
        );
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
                s.setString(i + 1, String.valueOf(a));
            }
        }
    }
}
