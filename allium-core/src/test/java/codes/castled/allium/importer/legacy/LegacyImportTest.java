package codes.castled.allium.importer.legacy;

import codes.castled.allium.importer.ImportLedger;
import codes.castled.allium.importer.legacy.LegacyDestinationApplier.Result;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Destination;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Domain;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Record;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Status;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.travel.TravelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static codes.castled.allium.importer.legacy.LegacyFixtures.OWNER_A;
import static codes.castled.allium.importer.legacy.LegacyFixtures.OWNER_B;
import static org.junit.jupiter.api.Assertions.*;

class LegacyImportTest {

    static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final LegacyDestinationPlan.Worlds WORLDS = name -> name.equals("world") ? Optional.of(new ResolvedWorld(WORLD, "world")) : Optional.empty();

    @TempDir
    Path dir;

    LegacyAlliumSnapshot snapshot() throws Exception {
        Path db = LegacyFixtures.create(dir.resolve("legacy-" + UUID.randomUUID()));
        try (Connection c = LegacyFixtures.open(db)) {
            return LegacyAlliumReader.read(c);
        }
    }

    @Test
    void readsTheNewestRowOfEachHomeAndLogoutAndReportsTheRest() throws Exception {
        LegacyAlliumSnapshot s = snapshot();
        assertTrue(s.h2Version().contains("2.1.214"), s.h2Version());
        var base = s.homes().stream().filter(h -> h.owner().equals(OWNER_A) && h.name().equals("base")).toList();
        assertEquals(1, base.size(), "one candidate per owner and exact name");
        assertEquals(20, base.get(0).point().x(), "the newest timestamp wins");
        assertEquals(1, s.supersededHomes().size());
        assertEquals(1, s.unnamedHomes(), "a HOME row without a name is counted, never imported");
        assertEquals(7, s.homes().size());
        assertEquals(2, s.logouts().size());
        assertEquals(101, s.logouts().stream().filter(l -> l.owner().equals(OWNER_A)).findFirst().orElseThrow().point().x());
        assertEquals(1, s.supersededLogouts());
        assertEquals(Map.of("DEATH", 1L), s.otherLocationTypes());
        assertEquals(3, s.warps().size());
        assertEquals(Boolean.FALSE, s.warps().stream().filter(w -> w.name().equals("shop")).findFirst().orElseThrow().isPublic());
        assertEquals(2, s.maxHomes().size());
        assertTrue(s.notes().contains("legacy schema version 9"), s.notes().toString());
    }

    @Test
    void theDriverComesFromAJarAndOnlyACopyIsOpened() throws Exception {
        Path db = LegacyFixtures.create(dir.resolve("legacy"));
        String before = LegacyH2Source.sha256(db);
        Path h2 = Path.of(Class.forName("org.h2.Driver").getProtectionDomain().getCodeSource().getLocation().toURI());
        Path notH2 = Files.writeString(dir.resolve("readme.jar"), "not a jar");
        try (LegacyH2Source source = LegacyH2Source.load(List.of(notH2, h2)).orElseThrow()) {
            assertEquals("org.h2.Driver", source.driverClass());
            var copy = LegacyH2Source.snapshot(db, dir.resolve("run/source.mv.db"));
            assertEquals(before, copy.sha256());
            try (Connection c = source.openReadOnly(copy.copy())) {
                assertEquals(7, LegacyAlliumReader.read(c).homes().size());
                assertThrows(java.sql.SQLException.class, () -> c.createStatement().execute("DELETE FROM warps"), "the copy is read-only");
            }
            // H2 keeps the creating user: any other login is refused, with the reason spelled out.
            var refused = assertThrows(java.sql.SQLException.class, () -> source.openReadOnly(copy.copy(), "", ""));
            assertEquals(28000, refused.getErrorCode());
            assertTrue(refused.getMessage().contains("refused user ''") && refused.getMessage().contains("legacy-allium.yml"), refused.getMessage());
        }
        assertEquals(before, LegacyH2Source.sha256(db), "the original file is untouched");
        assertTrue(LegacyH2Source.load(List.of(notH2)).isEmpty());
    }

    @Test
    void planBlocksWhatDoesNotFitAndNeverGuesses() throws Exception {
        LegacyDestinationPlan plan = LegacyDestinationPlan.build(snapshot(), WORLDS, Destination.empty());
        Map<String, Record> homes = byKey(plan, Domain.HOME);
        assertEquals(Status.IMPORT, homes.get(OWNER_A + "/base").status());
        assertEquals(20, homes.get(OWNER_A + "/base").location().x());
        assertEquals(Status.IMPORT, homes.get(OWNER_A + "/farm").status(), "names are lowercased");
        assertEquals(Status.BLOCKED, homes.get(OWNER_A + "/my home").status());
        assertTrue(homes.get(OWNER_A + "/nether").reason().contains("world_nether"), "an unknown world is never replaced");
        assertTrue(plan.records().stream().filter(r -> r.key().equals(OWNER_B + "/base")).allMatch(r -> r.status() == Status.BLOCKED
                && r.reason().contains("all become 'base'")), "two legacy spellings of one name block both");
        assertEquals(Status.BLOCKED, homes.get("not-a-uuid/home").status());
        assertTrue(plan.records().stream().anyMatch(r -> r.status() == Status.SKIPPED && r.reason().startsWith("superseded")));
        Map<String, Record> warps = byKey(plan, Domain.WARP);
        assertEquals(Status.IMPORT, warps.get("spawn").status());
        assertEquals(Status.IMPORT, warps.get("shop").status());
        assertEquals(Status.BLOCKED, warps.get("bad warp").status());
        assertEquals(2, plan.counts(Domain.LOGOUT).get(Status.IMPORT));
        assertEquals(1, plan.overrides().size(), "a -1 limit meant 'use permissions' and is not an override");
        assertEquals(plan.sha256(), LegacyDestinationPlan.build(snapshot(), WORLDS, Destination.empty()).sha256(), "the plan hash is stable");
    }

    @Test
    void applyWritesDestinationsAndLedgerTogetherAndReplaysAsNoOps() throws Exception {
        ConnectionSource db = LegacyFixtures.sqlite(dir);
        TravelRepository travel = new TravelRepository(db, SqlDialect.SQLITE, () -> 1L);
        TeleportRepository teleports = new TeleportRepository(db, SqlDialect.SQLITE, () -> 1L);
        ImportLedger ledger = new ImportLedger(db, SqlDialect.SQLITE, () -> 42L);
        travel.migrate();
        teleports.migrate();
        ledger.migrate();
        UUID a = UUID.fromString(OWNER_A), b = UUID.fromString(OWNER_B);
        // Before the import: a different "shop" warp, and B already logged out on this server.
        SavedLocation elsewhere = new SavedLocation(WORLD, "world", 7, 7, 7, 0, 0);
        assertEquals(TravelRepository.MutationResult.CREATED, travel.createWarp("shop", elsewhere));
        teleports.save(b, "logout", elsewhere);

        LegacyAlliumSnapshot s = snapshot();
        LegacyDestinationPlan plan = LegacyDestinationPlan.build(s, WORLDS, destination(travel, teleports, ledger));
        assertEquals(Status.CONFLICT, byKey(plan, Domain.WARP).get("shop").status(), "a different warp of that name is kept");
        assertEquals(Status.SKIPPED, byKey(plan, Domain.LOGOUT).get(OWNER_B).status(), "this server's logout position is newer");

        var outcomes = LegacyDestinationApplier.apply(plan, "run-1", ledger, travel, teleports);
        var counts = LegacyDestinationApplier.counts(outcomes);
        assertEquals(4, counts.get(Result.APPLIED), counts.toString()); // a/base, a/farm, spawn, a's logout
        assertEquals(new SavedLocation(WORLD, "world", 20, 65, 20, 12.5f, -3), travel.homes(a).get("base"));
        assertEquals(30, travel.homes(a).get("farm").x());
        assertEquals(0.5, travel.warps().get("spawn").x());
        assertEquals(elsewhere, travel.warps().get("shop"), "the conflicting warp is untouched");
        assertEquals(101, teleports.load(a, "logout").orElseThrow().x());
        assertEquals(elsewhere, teleports.load(b, "logout").orElseThrow());
        assertEquals(4, ledger.hashes(LegacyDestinationPlan.SOURCE).size());

        // A second dry run sees the ledger: everything already imported is REPLAYED, nothing to write.
        LegacyDestinationPlan again = LegacyDestinationPlan.build(s, WORLDS, destination(travel, teleports, ledger));
        assertEquals(4, again.records().stream().filter(r -> r.status() == Status.REPLAYED).count());
        assertEquals(0, again.records().stream().filter(r -> r.status() == Status.IMPORT).count());
        assertNotEquals(plan.sha256(), again.sha256());
        var replayedPlan = LegacyDestinationApplier.counts(LegacyDestinationApplier.apply(again, "run-2", ledger, travel, teleports));
        assertEquals(0, replayedPlan.get(Result.APPLIED));
        assertEquals(4, replayedPlan.get(Result.REPLAYED));
        // Applying the first plan twice is still a no-op: the ledger is checked inside the transaction.
        var replay = LegacyDestinationApplier.counts(LegacyDestinationApplier.apply(plan, "run-3", ledger, travel, teleports));
        assertEquals(0, replay.get(Result.APPLIED));
        assertEquals(4, replay.get(Result.REPLAYED));
        assertEquals(2, travel.homes(a).size());
    }

    @Test
    void anIdenticalExistingHomeIsAdoptedIntoTheLedger() throws Exception {
        ConnectionSource db = LegacyFixtures.sqlite(dir);
        TravelRepository travel = new TravelRepository(db, SqlDialect.SQLITE, () -> 1L);
        TeleportRepository teleports = new TeleportRepository(db, SqlDialect.SQLITE, () -> 1L);
        ImportLedger ledger = new ImportLedger(db, SqlDialect.SQLITE, () -> 42L);
        travel.migrate();
        teleports.migrate();
        ledger.migrate();
        UUID a = UUID.fromString(OWNER_A);
        travel.createHome(a, "farm", new SavedLocation(WORLD, "world", 30, 70, -30, 12.5f, -3), 1);
        LegacyDestinationPlan plan = LegacyDestinationPlan.build(snapshot(), WORLDS, destination(travel, teleports, ledger));
        assertEquals(Status.PRESENT, byKey(plan, Domain.HOME).get(OWNER_A + "/farm").status());
        var counts = LegacyDestinationApplier.counts(LegacyDestinationApplier.apply(plan, "run-1", ledger, travel, teleports));
        assertEquals(1, counts.get(Result.ADOPTED));
        assertTrue(ledger.hashes(LegacyDestinationPlan.SOURCE).containsKey("home:" + OWNER_A + "/farm"));
    }

    static Destination destination(TravelRepository travel, TeleportRepository teleports, ImportLedger ledger) throws Exception {
        return new Destination(travel.allHomes(), travel.warps(), teleports.slot("logout"), ledger.hashes(LegacyDestinationPlan.SOURCE));
    }

    static Map<String, Record> byKey(LegacyDestinationPlan plan, Domain domain) {
        Map<String, Record> out = new java.util.LinkedHashMap<>();
        for (Record r : plan.records()) if (r.domain() == domain) out.putIfAbsent(r.key(), r);
        return out;
    }
}
