package codes.castled.allium.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyTidyTest {
    @TempDir Path folder;

    @Test
    void archivesLegacyH2AlongsideUnknownFilesAndKeepsImportsReadable() throws Exception {
        Files.createDirectories(folder.resolve("database"));
        Files.writeString(folder.resolve("database/sfcore.mv.db"), "legacy");
        Files.writeString(folder.resolve("database/keep.db"), "unknown");
        Files.writeString(folder.resolve("allium.db"), "recode");
        Files.writeString(folder.resolve("itemdb.yml"), "active");
        Files.writeString(folder.resolve("vouchers.yml"), "legacy vouchers");
        Files.createDirectories(folder.resolve("harvest/crops"));
        Files.writeString(folder.resolve("harvest/crops/tomato.yml"), "legacy crop");
        var plan = LegacyTidy.plan(folder);

        assertTrue(Files.isRegularFile(folder.resolve("database/sfcore.mv.db")));
        assertFalse(Files.exists(folder.resolve("import")));
        assertTrue(plan.skipped().contains(Path.of("database/keep.db")));

        var result = LegacyTidy.apply(folder, plan);
        assertEquals(3, result.moved().size());
        assertTrue(result.failed().isEmpty());
        assertTrue(Files.isRegularFile(result.archive().resolve("manifest.txt")));
        assertEquals("legacy", Files.readString(LegacyTidy.source(folder, "database/sfcore.mv.db")));
        assertEquals("legacy vouchers", Files.readString(LegacyTidy.source(folder, "vouchers.yml")));
        assertEquals("legacy crop", Files.readString(result.archive().resolve("harvest/crops/tomato.yml")));
        assertTrue(Files.isRegularFile(folder.resolve("database/keep.db")));
        assertTrue(Files.isRegularFile(folder.resolve("allium.db")));
        assertTrue(Files.isRegularFile(folder.resolve("itemdb.yml")));
        assertTrue(LegacyTidy.plan(folder).paths().isEmpty());
    }
}
