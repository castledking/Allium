package codes.castled.allium.modguard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModGuardTest {

    @Test
    void bundledModGuardLoadsWhenNoLegacyFilesExist(@TempDir Path dir) throws Exception {
        List<String> logs = new ArrayList<>();
        var files = new ModGuardFiles(dir, name -> getClass().getClassLoader().getResourceAsStream(name),
                logs::add, dir::resolve);

        YamlConfiguration loaded = files.load();
        assertTrue(Files.isRegularFile(dir.resolve(ModGuardFiles.FILE)));
        assertTrue(loaded.getBoolean("enabled"));
        assertEquals("modguard.notify", loaded.getString("staff-permission"));
        assertNotNull(loaded.getConfigurationSection("rules.meteor"));
        assertNotNull(loaded.getConfigurationSection("translation-probe.checks.meteor-client"));
        assertFalse(Files.exists(dir.resolve(ModGuardFiles.REPORT)));
    }

    @Test
    void legacyConfigSectionMigratesIntoModGuardYmlWithReport(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.legacy.yml"), """
                modguard:
                  enabled: true
                  kick-on-detection: false
                  staff-permission: staff.modguard
                  mode: blacklist
                  banned-mods:
                    - meteor-client
                    - customcheat
                """);

        List<String> logs = new ArrayList<>();
        var files = new ModGuardFiles(dir, name -> getClass().getClassLoader().getResourceAsStream(name),
                logs::add, dir::resolve);

        YamlConfiguration loaded = files.load();
        assertFalse(loaded.getBoolean("access-control.enabled"));
        assertEquals("staff.modguard", loaded.getString("staff-permission"));
        assertFalse(loaded.getBoolean("access-control.mods.meteor.allow-join"));
        assertEquals("CRITICAL", loaded.getString("rules.banned-customcheat.severity"));
        assertFalse(loaded.getBoolean("access-control.mods.banned-customcheat.allow-join"));
        assertTrue(Files.isRegularFile(dir.resolve(ModGuardFiles.REPORT)));
        String report = Files.readString(dir.resolve(ModGuardFiles.REPORT));
        assertTrue(report.contains("kick-on-detection -> access-control.enabled"));
        assertTrue(report.contains("banned-mods meteor-client -> rule meteor"));
        assertTrue(report.contains("banned-mods customcheat -> new rule banned-customcheat"));
        assertTrue(report.contains("retired  mode:"));
        assertFalse(logs.isEmpty());
    }

    @Test
    void legacyModGuardDirectoryOverridesRulesAndSettings(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("modguard"));
        Files.writeString(dir.resolve("modguard/config.yml"), """
                enabled: false
                alerts:
                  minimum-severity: CRITICAL
                access-control:
                  disconnect-mode: PROXY
                """);
        Files.writeString(dir.resolve("modguard/rules.yml"), """
                signature-pack-version: 9
                rules:
                  custom-client:
                    enabled: true
                    name: Custom Client
                    severity: WARNING
                    brands: [custom*]
                    channels: [custom:*]
                    mods: [custommod]
                    join-resources:
                      ignored: true
                """);

        var files = new ModGuardFiles(dir, name -> getClass().getClassLoader().getResourceAsStream(name),
                msg -> { }, dir::resolve);

        YamlConfiguration loaded = files.load();
        assertFalse(loaded.getBoolean("enabled"));
        assertEquals("CRITICAL", loaded.getString("alerts.minimum-severity"));
        assertEquals("PROXY", loaded.getString("access-control.disconnect-mode"));
        assertEquals(9, loaded.getInt("signature-pack-version"));
        assertNotNull(loaded.getConfigurationSection("rules.custom-client"));
        assertNull(loaded.get("rules.custom-client.join-resources"));
        assertNull(loaded.get("rules.meteor"));
    }

    @Test
    void detectionMatchingAndProbeEvaluationWork() {
        var rule = new Detection.Rule("meteor", "Meteor Client", Detection.Severity.CRITICAL,
                List.of(Detection.glob("meteor*")),
                List.of(Detection.glob("meteor:*")),
                List.of(Detection.glob("meteor-client")));
        assertTrue(rule.matches(Detection.Kind.BRAND, "Meteor-Client"));
        assertTrue(rule.matches(Detection.Kind.CHANNEL, "meteor:sync"));
        assertTrue(rule.matches(Detection.Kind.MOD, "meteor-client"));
        assertFalse(rule.matches(Detection.Kind.MOD, "sodium"));

        assertTrue(Detection.positiveProbe("key.meteor.open", "allium_probe_1", "Right Shift", true));
        assertFalse(Detection.positiveProbe("key.meteor.open", "allium_probe_1", "allium_probe_1", true));
        assertFalse(Detection.positiveProbe("key.meteor.open", "allium_probe_1", "key.meteor.open", true));
        assertFalse(Detection.positiveProbe("key.meteor.open", "allium_probe_1", "Right Shift", false));
    }
}
