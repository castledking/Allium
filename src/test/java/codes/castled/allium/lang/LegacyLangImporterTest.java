package codes.castled.allium.lang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyLangImporterTest {

    private static final String LEGACY = """
            core-prefix: '<gradient:#B2C3EE:#ADF3FD>Core</gradient>'
            msg:
              received:
                - "&7(From &f{sender}&7) &r{message}"
                - "[sound] block.note_block.pling 0.5 1.5"
              reply: "You have no one to reply to."
            heal:
              self:
                - "&aYou have been healed."
                - "[sound] entity.player.levelup"
            warp:
              set: "&aWarp set."
            """;

    private static InputStream bundled(String path) {
        return LegacyLangImporterTest.class.getClassLoader().getResourceAsStream(path);
    }

    @Test
    void dryRunWritesNothingApplyBacksUpAndWritesAndASecondRunChangesNothing(@TempDir Path folder) throws Exception {
        File data = folder.toFile();
        Files.writeString(folder.resolve("lang.yml"), LEGACY);
        Path target = folder.resolve("lang/en_US.yml");
        Files.createDirectories(target.getParent());
        try (InputStream in = bundled("lang/en_US.yml")) {
            Files.copy(in, target);
        }
        // An operator already customized the heal message in the recode: it must be kept.
        YamlConfiguration customized = YamlConfiguration.loadConfiguration(target.toFile());
        customized.set("player.heal", "{prefix} &bPatched up.");
        customized.save(target.toFile());
        byte[] before = Files.readAllBytes(target);
        byte[] legacyBefore = Files.readAllBytes(folder.resolve("lang.yml"));

        LegacyLangImporter.Plan plan = LegacyLangImporter.plan(data, "en_US", LegacyLangImporterTest::bundled);
        assertEquals(3, plan.count(LegacyLangImporter.Status.WRITE), plan.items().toString());
        assertEquals(1, plan.count(LegacyLangImporter.Status.KEPT));
        assertEquals(java.util.List.of("warp.set"), plan.report().unmapped());
        assertArrayEquals(before, Files.readAllBytes(target), "planning writes nothing");

        File backup = LegacyLangImporter.apply(plan);
        assertArrayEquals(before, Files.readAllBytes(backup.toPath()), "the backup is the file as it was");
        YamlConfiguration written = YamlConfiguration.loadConfiguration(target.toFile());
        assertEquals("&7(From &f{sender}&7) &r{message}", written.getString("messaging.received.text"));
        assertEquals("minecraft:block.note_block.pling", written.getString("messaging.received.sound"));
        assertEquals(0.5D, written.getDouble("messaging.received.volume"));
        assertEquals(1.5D, written.getDouble("messaging.received.pitch"));
        assertEquals("{prefix} You have no one to reply to.", written.getString("messaging.no-reply-target"));
        assertEquals("<gradient:#B2C3EE:#ADF3FD>Core</gradient>", written.getString("prefix"));
        assertEquals("{prefix} &bPatched up.", written.getString("player.heal"), "the customized message is kept");
        assertTrue(written.contains("messaging.sent"), "the rest of the file is still there");
        assertArrayEquals(legacyBefore, Files.readAllBytes(folder.resolve("lang.yml")), "the legacy file is only read");

        LegacyLangImporter.Plan again = LegacyLangImporter.plan(data, "en_US", LegacyLangImporterTest::bundled);
        assertEquals(0, again.count(LegacyLangImporter.Status.WRITE));
        assertEquals(3, again.count(LegacyLangImporter.Status.SAME));
    }

    @Test
    void aSoundlessImportReplacesASectionWithPlainText(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("lang.yml"), "msg:\n  received: \"&7(From &f{sender}&7) {message}\"\n");
        Path target = folder.resolve("lang/en_US.yml");
        Files.createDirectories(target.getParent());
        try (InputStream in = bundled("lang/en_US.yml")) {
            Files.copy(in, target);
        }
        LegacyLangImporter.Plan plan = LegacyLangImporter.plan(folder.toFile(), "en_US", LegacyLangImporterTest::bundled);
        LegacyLangImporter.apply(plan);
        YamlConfiguration written = YamlConfiguration.loadConfiguration(target.toFile());
        assertEquals("&7(From &f{sender}&7) {message}", written.getString("messaging.received"));
        assertNull(written.getConfigurationSection("messaging.received"));
    }
}
