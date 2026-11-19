package codes.castled.allium.flight;

import codes.castled.allium.storage.SqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlightTimeRepositoryTest {

    @TempDir
    Path dir;

    @Test
    void timeIsAddedTakenAndSetWithinItsBounds() throws Exception {
        String file = dir.resolve("flight.db").toAbsolutePath().toString();
        FlightTimeRepository repo = new FlightTimeRepository(() -> DriverManager.getConnection("jdbc:sqlite:" + file),
                SqlDialect.SQLITE, System::currentTimeMillis);
        assertEquals(1, repo.migrate());
        assertEquals(1, repo.migrate(), "migrating again changes nothing");
        UUID steve = UUID.randomUUID();
        assertEquals(0, repo.seconds(steve));
        assertEquals(600, repo.add(steve, 600));
        assertEquals(570, repo.add(steve, -30));
        assertEquals(0, repo.add(steve, -10_000), "never below zero");
        assertEquals(3600, repo.set(steve, 3600));
        assertEquals(FlightTimeRepository.MAX_SECONDS, repo.add(steve, Long.MAX_VALUE / 4), "capped");
        assertEquals(0, repo.set(steve, -5));
        assertEquals(0, repo.seconds(UUID.randomUUID()), "others are untouched");
    }
}
