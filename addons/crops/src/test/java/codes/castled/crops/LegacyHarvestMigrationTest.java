package codes.castled.crops;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyHarvestMigrationTest {

    @Test
    void discoverFindsActiveFirstThenNewestTidyArchiveAndReadsTablePrefix(@TempDir Path dir) throws Exception {
        Path alliumDir = dir.resolve("Allium");
        Path olderTidy = alliumDir.resolve("import/backups/tidy-20261120-010000/harvest");
        Path newerTidy = alliumDir.resolve("import/backups/tidy-20261123-020000/harvest");
        Files.createDirectories(olderTidy);
        Files.createDirectories(newerTidy);
        Files.writeString(olderTidy.resolve("harvest.db"), "old");
        Files.writeString(newerTidy.resolve("harvest.mv.db"), "new");
        Files.writeString(newerTidy.resolve("config.yml"), "storage:\n  table-prefix: sf_\n");

        var archived = LegacyHarvestReader.discover(alliumDir).orElseThrow();
        assertEquals(newerTidy.resolve("harvest.mv.db"), archived.databaseFile());
        assertEquals("sf_", archived.tablePrefix());

        // Active harvest directory takes precedence over archived copies.
        Path active = alliumDir.resolve("harvest");
        Files.createDirectories(active);
        Files.writeString(active.resolve("harvest.db"), "active");
        var foundActive = LegacyHarvestReader.discover(alliumDir).orElseThrow();
        assertEquals(active.resolve("harvest.db"), foundActive.databaseFile());
        assertEquals("", foundActive.tablePrefix());
    }

    @Test
    void sqliteAndH2ReadOnlyCopyPlansCropsSoilSprinklersOrphansConflictsAndWeedOmission(@TempDir Path dir) throws Exception {
        UUID worldId = UUID.randomUUID();
        UUID missingWorld = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        long now = 1_800_000_000_000L;

        // Build real legacy v2 SQLite database with table prefix "sf_".
        Path sqliteSource = dir.resolve("harvest.db");
        createLegacyV2Schema("jdbc:sqlite:" + sqliteSource, "sf_", worldId, missingWorld, alice, bob, now);
        byte[] beforeBytes = Files.readAllBytes(sqliteSource);

        LegacyHarvestReader.Snapshot snapshot = LegacyHarvestReader.copyAndRead(
                sqliteSource, dir.resolve("run1/source.db"), "sf_", null);
        // Original source file must remain completely untouched.
        assertArrayEquals(beforeBytes, Files.readAllBytes(sqliteSource));
        assertEquals(7, snapshot.crops().size());
        assertEquals(2, snapshot.cells().size());
        assertEquals(2, snapshot.soils().size());
        assertEquals(2, snapshot.sprinklers().size());

        // Also verify H2 (.mv.db) copy-and-read works identically via classpath H2 driver.
        Path h2Base = dir.resolve("h2harvest");
        createLegacyV2Schema("jdbc:h2:" + h2Base.toAbsolutePath() + ";MODE=MySQL", "sf_",
                worldId, missingWorld, alice, bob, now);
        Path h2File = dir.resolve("h2harvest.mv.db");
        assertTrue(Files.isRegularFile(h2File));
        LegacyHarvestReader.Snapshot h2Snapshot = LegacyHarvestReader.copyAndRead(
                h2File, dir.resolve("run-h2/source.mv.db"), "sf_", null);
        assertEquals(snapshot.crops().size(), h2Snapshot.crops().size());
        assertEquals(snapshot.sprinklers().size(), h2Snapshot.sprinklers().size());

        // Load real bundled Crops catalog, fertilizers, and sprinklers.
        Map<String, Catalog.Crop> catalog = loadBundledCatalog();
        Set<String> fertilizers = Set.of("speed_fertilizer_1", "quality_fertilizer_2");
        Map<String, String> sprinklerLookup = Map.of(
                "sprinkler_1", "allium:sprinkler_1",
                "allium:sprinkler_1", "allium:sprinkler_1"
        );
        Set<Material> validSoils = Set.of(Material.FARMLAND, Material.DIRT, Material.GRASS_BLOCK);

        // Chunk (0, 0) is loaded; chunk (5, 5) is unloaded (must NOT be inspected or mutated).
        LegacyHarvestPlan.WorldInspector inspector = new LegacyHarvestPlan.WorldInspector() {
            @Override
            public Optional<String> worldName(UUID id) {
                return worldId.equals(id) ? Optional.of("world") : Optional.empty();
            }

            @Override
            public boolean isChunkLoaded(UUID id, int cx, int cz) {
                return worldId.equals(id) && cx == 0 && cz == 0;
            }

            @Override
            public LegacyHarvestPlan.BlockCheck inspectCrop(UUID id, int x, int y, int z,
                                                            Catalog.Crop crop, Catalog.Path path, int stage) {
                if (x == 3 && z == 3) {
                    return LegacyHarvestPlan.BlockCheck.conflict("conflicting world block COBBLESTONE");
                }
                return LegacyHarvestPlan.BlockCheck.ready("ready on farmland");
            }

            @Override
            public LegacyHarvestPlan.BlockCheck inspectSprinkler(UUID id, int x, int y, int z, String item) {
                return LegacyHarvestPlan.BlockCheck.ready("ready on floor support");
            }

            @Override
            public LegacyHarvestPlan.BlockCheck inspectSoil(UUID id, int x, int y, int z, String fert, long exhaustedAt) {
                return LegacyHarvestPlan.BlockCheck.ready("ready to write soil PDC");
            }
        };

        LegacyHarvestPlan plan = LegacyHarvestPlan.build(
                snapshot, catalog, fertilizers, sprinklerLookup, validSoils,
                true, 14L * 86_400_000L, inspector, now);

        // Crops:
        //  (1,65,1) tomato growing loaded -> READY
        //  (2,65,2) strawberry MATURE loaded -> READY (clamped to final stage)
        //  (85,65,85) tomato in unloaded chunk (5,5) -> READY (chunkLoaded=false)
        //  (3,65,3) tomato blocked by cobblestone -> CONFLICT
        //  (4,65,4) weed -> SKIPPED
        //  (5,65,5) REMOVED -> SKIPPED
        //  (6,65,6) missing world -> BLOCKED
        assertEquals(3L, plan.counts(LegacyHarvestPlan.Domain.CROP).get(LegacyHarvestPlan.Status.READY));
        assertEquals(1L, plan.counts(LegacyHarvestPlan.Domain.CROP).get(LegacyHarvestPlan.Status.CONFLICT));
        assertEquals(2L, plan.counts(LegacyHarvestPlan.Domain.CROP).get(LegacyHarvestPlan.Status.SKIPPED));
        assertEquals(1L, plan.counts(LegacyHarvestPlan.Domain.CROP).get(LegacyHarvestPlan.Status.BLOCKED));

        // Orphan cell row flagged as ORPHAN.
        assertEquals(1L, plan.counts(LegacyHarvestPlan.Domain.CELL).get(LegacyHarvestPlan.Status.ORPHAN));

        // Sprinklers: (10,65,10) READY, (1,65,1) colliding with tomato -> CONFLICT.
        assertEquals(1L, plan.counts(LegacyHarvestPlan.Domain.SPRINKLER).get(LegacyHarvestPlan.Status.READY));
        assertEquals(1L, plan.counts(LegacyHarvestPlan.Domain.SPRINKLER).get(LegacyHarvestPlan.Status.CONFLICT));

        // Soils: (1,64,1) fertilized READY, (2,64,2) expired past 14d forget window -> SKIPPED.
        assertEquals(1L, plan.counts(LegacyHarvestPlan.Domain.SOIL).get(LegacyHarvestPlan.Status.READY));
        assertEquals(1L, plan.counts(LegacyHarvestPlan.Domain.SOIL).get(LegacyHarvestPlan.Status.SKIPPED));

        // Verify unloaded chunk count and owner preservation.
        assertEquals(1L, plan.unloadedReadyCount());
        var tomato = plan.records().stream()
                .filter(r -> r.domain() == LegacyHarvestPlan.Domain.CROP && r.x() == 1)
                .findFirst().orElseThrow();
        assertEquals(alice, tomato.ownerUuid());
        assertEquals("speed_fertilizer_1", tomato.crop().fertilizerId());
        assertEquals(0.85d, tomato.crop().growthSpeed(), 0.0001d);

        var strawberry = plan.records().stream()
                .filter(r -> r.domain() == LegacyHarvestPlan.Domain.CROP && r.x() == 2)
                .findFirst().orElseThrow();
        Catalog.Crop strawDef = catalog.get("strawberry");
        int finalStage = strawDef.paths().values().iterator().next().stages().size() - 1;
        assertEquals(finalStage, strawberry.crop().stage());

        var sprinkler = plan.records().stream()
                .filter(r -> r.domain() == LegacyHarvestPlan.Domain.SPRINKLER && r.status() == LegacyHarvestPlan.Status.READY)
                .findFirst().orElseThrow();
        assertEquals(bob, sprinkler.ownerUuid());
        assertEquals("allium:sprinkler_1", sprinkler.sprinkler().sprinklerItem());

        String summary = plan.renderSummary("crops-20261124-090000", sqliteSource);
        assertTrue(summary.contains("Distinct owners preserved: 2"));
        assertTrue(summary.contains("orphan=1"));
        assertTrue(summary.contains("Unloaded READY records (deferred to chunk load on apply): 1"));
        String jsonl = plan.renderJsonl();
        assertTrue(jsonl.contains("\"status\":\"ORPHAN\""));
        assertTrue(jsonl.contains("weed is intentionally omitted"));
        assertTrue(jsonl.contains(alice.toString()));
        assertTrue(jsonl.contains(bob.toString()));
    }

    @Test
    void legacyV1SchemaWithoutSoilSprinklersOrSpeedMultiplierReadsCleanly(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("v1.db");
        UUID worldId = UUID.randomUUID();
        UUID cropId = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE harvest_crops ("
                    + "instance_uuid VARCHAR(36) PRIMARY KEY, crop_id VARCHAR(128) NOT NULL, "
                    + "path_id VARCHAR(128) NOT NULL, world_uuid VARCHAR(36) NOT NULL, "
                    + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, stage INT NOT NULL, "
                    + "state VARCHAR(32) NOT NULL, planted_at BIGINT NOT NULL, stage_started_at BIGINT NOT NULL, "
                    + "next_growth_at BIGINT, remaining_ms BIGINT, planter_uuid VARCHAR(36), "
                    + "visual_uuid VARCHAR(36), fertilizer_id VARCHAR(128), data_json TEXT)");
            st.executeUpdate("INSERT INTO harvest_crops VALUES ('" + cropId + "','tomato','default','" + worldId
                    + "',1,65,1,1,'GROWING',1000,1000,2000,NULL,NULL,NULL,NULL,NULL)");
        }
        var snap = LegacyHarvestReader.copyAndRead(db, dir.resolve("copy.db"), "", null);
        assertEquals(1, snap.crops().size());
        assertNull(snap.crops().get(0).speedMultiplier());
        assertTrue(snap.soils().isEmpty());
        assertTrue(snap.sprinklers().isEmpty());
        assertTrue(snap.notes().stream().anyMatch(n -> n.contains("harvest_soil")));
        assertTrue(snap.notes().stream().anyMatch(n -> n.contains("harvest_sprinklers")));
    }

    private static void createLegacyV2Schema(String jdbcUrl, String prefix, UUID worldId, UUID missingWorld,
                                             UUID alice, UUID bob, long now) throws Exception {
        String crops = prefix + "harvest_crops";
        String cells = prefix + "harvest_crop_cells";
        String soil = prefix + "harvest_soil";
        String sprinklers = prefix + "harvest_sprinklers";
        UUID tomatoId = UUID.randomUUID();
        UUID strawId = UUID.randomUUID();
        UUID unloadedId = UUID.randomUUID();
        UUID conflictId = UUID.randomUUID();
        UUID weedId = UUID.randomUUID();
        UUID removedId = UUID.randomUUID();
        UUID missingWorldId = UUID.randomUUID();
        UUID orphanParent = UUID.randomUUID();

        try (Connection c = DriverManager.getConnection(jdbcUrl, "sa", ""); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE " + crops + " ("
                    + "instance_uuid VARCHAR(36) PRIMARY KEY, crop_id VARCHAR(128) NOT NULL, "
                    + "path_id VARCHAR(128) NOT NULL, world_uuid VARCHAR(36) NOT NULL, "
                    + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, stage INT NOT NULL, "
                    + "state VARCHAR(32) NOT NULL, planted_at BIGINT NOT NULL, stage_started_at BIGINT NOT NULL, "
                    + "next_growth_at BIGINT, remaining_ms BIGINT, planter_uuid VARCHAR(36), "
                    + "visual_uuid VARCHAR(36), fertilizer_id VARCHAR(128), data_json TEXT, speed_multiplier DOUBLE)");
            st.executeUpdate("CREATE TABLE " + cells + " ("
                    + "instance_uuid VARCHAR(36) NOT NULL, world_uuid VARCHAR(36) NOT NULL, "
                    + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, PRIMARY KEY (world_uuid, x, y, z))");
            st.executeUpdate("CREATE TABLE " + soil + " ("
                    + "world_uuid VARCHAR(36) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, "
                    + "established_at BIGINT NOT NULL, exhausted_at BIGINT NOT NULL, fertilizer_id VARCHAR(128), "
                    + "PRIMARY KEY (world_uuid, x, y, z))");
            st.executeUpdate("CREATE TABLE " + sprinklers + " ("
                    + "world_uuid VARCHAR(36) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, "
                    + "sprinkler_id VARCHAR(128) NOT NULL, visual_uuid VARCHAR(36), placed_at BIGINT NOT NULL, "
                    + "owner_uuid VARCHAR(36), PRIMARY KEY (world_uuid, x, y, z))");

            String tomatoPath = firstPath("tomato");
            String strawPath = firstPath("strawberry");

            st.executeUpdate("INSERT INTO " + crops + " VALUES ('" + tomatoId + "','tomato','" + tomatoPath + "','" + worldId
                    + "',1,65,1,1,'GROWING'," + (now - 5000) + "," + (now - 1000) + "," + (now + 5000)
                    + ",NULL,'" + alice + "',NULL,'speed_fertilizer_1',NULL,0.85)");
            st.executeUpdate("INSERT INTO " + crops + " VALUES ('" + strawId + "','strawberry','" + strawPath + "','" + worldId
                    + "',2,65,2,0,'MATURE'," + (now - 50000) + "," + (now - 10000) + ",NULL,NULL,'" + alice + "',NULL,NULL,NULL,1.0)");
            st.executeUpdate("INSERT INTO " + crops + " VALUES ('" + unloadedId + "','tomato','" + tomatoPath + "','" + worldId
                    + "',85,65,85,1,'GROWING'," + (now - 5000) + "," + (now - 1000) + "," + (now + 5000)
                    + ",NULL,'" + alice + "',NULL,NULL,NULL,1.0)");
            st.executeUpdate("INSERT INTO " + crops + " VALUES ('" + conflictId + "','tomato','" + tomatoPath + "','" + worldId
                    + "',3,65,3,1,'GROWING'," + (now - 5000) + "," + (now - 1000) + "," + (now + 5000)
                    + ",NULL,'" + alice + "',NULL,NULL,NULL,1.0)");
            st.executeUpdate("INSERT INTO " + crops + " VALUES ('" + weedId + "','weed','default','" + worldId
                    + "',4,65,4,0,'GROWING'," + now + "," + now + ",NULL,NULL,NULL,NULL,NULL,NULL,1.0)");
            st.executeUpdate("INSERT INTO " + crops + " VALUES ('" + removedId + "','tomato','" + tomatoPath + "','" + worldId
                    + "',5,65,5,0,'REMOVED'," + now + "," + now + ",NULL,NULL,NULL,NULL,NULL,NULL,1.0)");
            st.executeUpdate("INSERT INTO " + crops + " VALUES ('" + missingWorldId + "','tomato','" + tomatoPath + "','" + missingWorld
                    + "',6,65,6,0,'GROWING'," + now + "," + now + ",NULL,NULL,NULL,NULL,NULL,NULL,1.0)");

            // Valid cell + orphan cell.
            st.executeUpdate("INSERT INTO " + cells + " VALUES ('" + tomatoId + "','" + worldId + "',1,65,1)");
            st.executeUpdate("INSERT INTO " + cells + " VALUES ('" + orphanParent + "','" + worldId + "',9,65,9)");

            // Active fertilized soil + long-expired soil.
            st.executeUpdate("INSERT INTO " + soil + " VALUES ('" + worldId + "',1,64,1," + (now - 1000)
                    + "," + (now + 86_400_000L) + ",'quality_fertilizer_2')");
            st.executeUpdate("INSERT INTO " + soil + " VALUES ('" + worldId + "',2,64,2," + (now - 30L * 86_400_000L)
                    + "," + (now - 20L * 86_400_000L) + ",NULL)");

            // Valid sprinkler + colliding sprinkler at (1,65,1).
            st.executeUpdate("INSERT INTO " + sprinklers + " VALUES ('" + worldId + "',10,65,10,'sprinkler_1',NULL,"
                    + now + ",'" + bob + "')");
            st.executeUpdate("INSERT INTO " + sprinklers + " VALUES ('" + worldId + "',1,65,1,'sprinkler_1',NULL,"
                    + now + ",'" + bob + "')");
        }
    }

    private static Map<String, Catalog.Crop> loadBundledCatalog() throws Exception {
        try (var in = LegacyHarvestMigrationTest.class.getClassLoader().getResourceAsStream("crops.yml")) {
            assertNotNull(in);
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            return Catalog.read(yml.getConfigurationSection("crops"));
        }
    }

    private static String firstPath(String cropId) throws Exception {
        Catalog.Crop crop = loadBundledCatalog().get(cropId);
        assertNotNull(crop, "Bundled crops.yml should contain " + cropId);
        return crop.paths().keySet().iterator().next();
    }
}
