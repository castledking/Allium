package codes.castled.allium.managers.DB;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Database#SHARED_IP_ACCOUNTS_SQL} against a real H2 instance configured the
 * same way as the live database, so the query is verified as valid H2/MySQL-mode SQL and not
 * just as a string.
 */
class SharedIpAccountsTest {

    private static final String HUNT = UUID.nameUUIDFromBytes("hunt2k11l".getBytes()).toString();
    private static final String KYNNA = UUID.nameUUIDFromBytes("kynnajoyy".getBytes()).toString();
    private static final String SHARED_IP = "143.103.30.102";

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:shared_ip_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE player_data (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "name VARCHAR(16), " +
                "last_ip VARCHAR(64))");
            statement.executeUpdate(
                "CREATE TABLE player_ip_history (" +
                "player_uuid VARCHAR(36) NOT NULL, " +
                "name VARCHAR(16) NOT NULL, " +
                "ip_address VARCHAR(64) NOT NULL, " +
                "first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (player_uuid, ip_address))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    private void savePlayer(String uuid, String name, String lastIp) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO player_data (uuid, name, last_ip) VALUES (?, ?, ?)")) {
            statement.setString(1, uuid);
            statement.setString(2, name);
            statement.setString(3, lastIp);
            statement.executeUpdate();
        }
    }

    private void saveIpHistory(String uuid, String name, String ip) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO player_ip_history (player_uuid, name, ip_address) VALUES (?, ?, ?)")) {
            statement.setString(1, uuid);
            statement.setString(2, name);
            statement.setString(3, ip);
            statement.executeUpdate();
        }
    }

    private List<String> sharedIpAccounts(String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(Database.SHARED_IP_ACCOUNTS_SQL)) {
            statement.setString(1, uuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return Database.collectSharedIpAccountNames(resultSet);
            }
        }
    }

    @Test
    void listsAccountsSymmetricallyAfterOneOfThemChangesIp() throws SQLException {
        // The reported case: both accounts shared 143.103.30.102, then hunt2k11l moved to a
        // new address on a later session, leaving kynnajoyy's list correct and hunt2k11l's empty.
        savePlayer(HUNT, "hunt2k11l", "86.24.7.55");
        savePlayer(KYNNA, "kynnajoyy", SHARED_IP);
        saveIpHistory(HUNT, "hunt2k11l", SHARED_IP);
        saveIpHistory(HUNT, "hunt2k11l", "86.24.7.55");
        saveIpHistory(KYNNA, "kynnajoyy", SHARED_IP);

        assertEquals(List.of("kynnajoyy"), sharedIpAccounts(HUNT));
        assertEquals(List.of("hunt2k11l"), sharedIpAccounts(KYNNA));
    }

    @Test
    void returnsNothingForAccountsThatNeverSharedAnIp() throws SQLException {
        savePlayer(HUNT, "hunt2k11l", "86.24.7.55");
        savePlayer(KYNNA, "kynnajoyy", SHARED_IP);
        saveIpHistory(HUNT, "hunt2k11l", "86.24.7.55");
        saveIpHistory(KYNNA, "kynnajoyy", SHARED_IP);

        assertTrue(sharedIpAccounts(HUNT).isEmpty());
        assertTrue(sharedIpAccounts(KYNNA).isEmpty());
    }

    @Test
    void listsRenamedAccountOnceUnderItsCurrentName() throws SQLException {
        // Two shared IPs recorded under two different names for the same account.
        savePlayer(HUNT, "hunt2k11l", SHARED_IP);
        savePlayer(KYNNA, "kynnajoyy", SHARED_IP);
        saveIpHistory(HUNT, "hunt2k11l", SHARED_IP);
        saveIpHistory(HUNT, "OldHuntName", "86.24.7.55");
        saveIpHistory(KYNNA, "kynnajoyy", SHARED_IP);
        saveIpHistory(KYNNA, "kynnajoyy", "86.24.7.55");

        assertEquals(List.of("hunt2k11l"), sharedIpAccounts(KYNNA));
    }

    @Test
    void fallsBackToHistoryNameWhenPlayerDataRowIsMissing() throws SQLException {
        savePlayer(KYNNA, "kynnajoyy", SHARED_IP);
        saveIpHistory(HUNT, "hunt2k11l", SHARED_IP);
        saveIpHistory(KYNNA, "kynnajoyy", SHARED_IP);

        assertEquals(List.of("hunt2k11l"), sharedIpAccounts(KYNNA));
    }

    @Test
    void sortsMultipleAccountsCaseInsensitively() throws SQLException {
        String zed = UUID.nameUUIDFromBytes("zed".getBytes()).toString();
        String apple = UUID.nameUUIDFromBytes("apple".getBytes()).toString();

        savePlayer(KYNNA, "kynnajoyy", SHARED_IP);
        savePlayer(zed, "ZedAlt", SHARED_IP);
        savePlayer(apple, "appleAlt", SHARED_IP);
        saveIpHistory(KYNNA, "kynnajoyy", SHARED_IP);
        saveIpHistory(zed, "ZedAlt", SHARED_IP);
        saveIpHistory(apple, "appleAlt", SHARED_IP);

        assertEquals(List.of("appleAlt", "ZedAlt"), sharedIpAccounts(KYNNA));
    }
}
