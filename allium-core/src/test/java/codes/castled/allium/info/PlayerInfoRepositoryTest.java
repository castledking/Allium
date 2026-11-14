package codes.castled.allium.info;

import codes.castled.allium.command.spec.CommandCompletionContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Storage tests for S18 player info across SQLite and MariaDB/MySQL (when {@code ALLIUM_TEST_MYSQL_URL} is set). */
class PlayerInfoRepositoryTest {

    @TempDir
    Path tempDir;

    private record Db(String name, SqlDialect dialect, ConnectionSource source) {
    }

    private static List<Db> databases(Path dir) throws SQLException {
        List<Db> out = new ArrayList<>();
        String file = dir.resolve("playerinfo-" + System.nanoTime() + ".db").toAbsolutePath().toString();
        out.add(new Db("sqlite", SqlDialect.SQLITE, () -> DriverManager.getConnection("jdbc:sqlite:" + file)));

        String url = System.getenv("ALLIUM_TEST_MYSQL_URL");
        if (url != null && !url.isBlank()) {
            Db mysql = new Db("mysql", SqlDialect.MYSQL, () -> DriverManager.getConnection(url));
            try (Connection c = mysql.source().connection(); Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS " + PlayerInfoRepository.TABLE);
                s.execute("DELETE FROM allium_schema WHERE component = '" + PlayerInfoRepository.COMPONENT + "'");
                s.execute("DROP TABLE IF EXISTS " + PlayerNameRepository.HISTORY_TABLE);
                s.execute("DROP TABLE IF EXISTS " + PlayerNameRepository.TABLE);
                s.execute("DELETE FROM allium_schema WHERE component = '" + PlayerNameRepository.COMPONENT + "'");
            } catch (SQLException ignored) {
            }
            out.add(mysql);
        }
        return out;
    }

    @Test
    void persistsFirstJoinLastSeenAndPreservesLastSeenOnVanishedJoin() throws Exception {
        for (Db db : databases(tempDir)) {
            PlayerInfoRepository repo = new PlayerInfoRepository(db.source(), db.dialect(), () -> 100L);
            assertEquals(1, repo.migrate(), db.name());
            assertEquals(1, repo.migrate(), db.name());

            UUID staff = UUID.randomUUID();
            // 1. Initial non-vanished join at t=1000
            repo.recordJoin(staff, "StaffMember", 1_000L, false);
            Optional<PlayerInfoRecord> first = repo.findByUuid(staff);
            assertTrue(first.isPresent(), db.name());
            assertEquals(1_000L, first.get().firstJoin(), db.name());
            assertEquals(1_000L, first.get().lastJoin(), db.name());
            assertEquals(1_000L, first.get().lastSeen(), db.name());

            // 2. Player vanishes at t=2000 -> freezes last_seen = 2000
            repo.recordLastSeen(staff, "StaffMember", 2_000L);
            assertEquals(2_000L, repo.findByUuid(staff).orElseThrow().lastSeen(), db.name());

            // 3. Rejoin while vanished at t=5000 -> updates last_join, preserves first_join & last_seen
            repo.recordJoin(staff, "StaffRenamed", 5_000L, true);
            PlayerInfoRecord afterVanishedJoin = repo.findByName("staffrenamed").orElseThrow();
            assertEquals(1_000L, afterVanishedJoin.firstJoin(), db.name());
            assertEquals(5_000L, afterVanishedJoin.lastJoin(), db.name());
            assertEquals(2_000L, afterVanishedJoin.lastSeen(), db.name());

            // 4. First-ever vanished join initializes last_seen cleanly to join time
            UUID brandNew = UUID.randomUUID();
            repo.recordJoin(brandNew, "NewVanished", 3_000L, true);
            PlayerInfoRecord newRec = repo.findByUuid(brandNew).orElseThrow();
            assertEquals(3_000L, newRec.firstJoin(), db.name());
            assertEquals(3_000L, newRec.lastSeen(), db.name());

            // 5. Name history repository also works cleanly against both SQLite and MariaDB
            PlayerNameRepository nameRepo = new PlayerNameRepository(db.source(), db.dialect(), () -> 100L);
            assertEquals(2, nameRepo.migrate(), db.name());
            nameRepo.record(staff, "StaffMember", 1_000L);
            nameRepo.record(staff, "StaffRenamed", 5_000L);
            List<PlayerNameRepository.NameHistoryEntry> history = nameRepo.nameHistory(staff);
            assertEquals(List.of("StaffRenamed", "StaffMember"),
                    history.stream().map(PlayerNameRepository.NameHistoryEntry::name).toList(), db.name());
        }
    }

    @Test
    void infoAndItemSelfCommandSpecsMatchContract() {
        assertEquals("allium.give", CoreCommandSpecs.ITEM.basePermission());
        assertEquals(List.of("item"), CoreCommandSpecs.ITEM.aliases());
        assertTrue(CoreCommandSpecs.ITEM_SPECS.contains(CoreCommandSpecs.GIVE));
        assertTrue(CoreCommandSpecs.ITEM_SPECS.contains(CoreCommandSpecs.ITEM));

        CommandCompletionContext playerCtx = new CommandCompletionContext() {
            @Override
            public boolean hasPermission(String permissionNode) {
                return Set.of("allium.list", "allium.seen", "allium.whois", "allium.near", "allium.afk").contains(permissionNode);
            }

            @Override
            public List<String> visibleOnlinePlayers() {
                return List.of("Alice");
            }

            @Override
            public List<String> knownPlayers(String prefix) {
                return List.of("Alice", "OfflineBob").stream()
                        .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                        .toList();
            }
        };

        assertEquals(List.of("Alice", "OfflineBob"), CoreCommandSpecs.SEEN.complete(playerCtx, new String[]{""}));
        assertEquals(List.of("OfflineBob"), CoreCommandSpecs.WHOIS.complete(playerCtx, new String[]{"off"}));
        assertEquals(List.of("50", "100", "200", "500"), CoreCommandSpecs.NEAR.complete(playerCtx, new String[]{""}));
    }
}
