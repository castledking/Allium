package codes.castled.allium.modguard;

import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    void foliaEnforcementFollowsTheConnectionThatReportedTheBrand() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        TaskScheduler scheduler = mock(TaskScheduler.class);
        UUID id = UUID.randomUUID();
        Player original = player(id);
        Player replacement = player(id);
        ServerThread originalOwner = mock(ServerThread.class);
        ServerThread replacementOwner = mock(ServerThread.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("rules.fixture.brands", List.of("test"));
        config.set("rules.fixture.severity", "WARNING");
        config.set("alerts.minimum-severity", "CRITICAL");
        config.set("access-control.disconnect-mode", "SERVER");
        config.set("kick-message", "Refused {mod}");
        ModGuard guard = new ModGuard(plugin, scheduler, config, new YamlConfiguration());
        byte[] brand = {4, 't', 'e', 's', 't'};

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, original)).thenReturn(originalOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, replacement)).thenReturn(replacementOwner);
            var originalKick = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            var replacementKick = org.mockito.ArgumentCaptor.forClass(Runnable.class);

            join(guard, original);
            guard.onPluginMessageReceived("minecraft:brand", original, brand);
            verify(scheduler).runOn(eq(originalOwner), originalKick.capture(), any(), eq(0L));
            verify(original, never()).kickPlayer(anyString());

            join(guard, replacement);
            originalKick.getValue().run();
            verify(original, never()).kickPlayer(anyString());
            verify(replacement, never()).kickPlayer(anyString());

            guard.onPluginMessageReceived("minecraft:brand", replacement, brand);
            verify(scheduler).runOn(eq(replacementOwner), replacementKick.capture(), any(), eq(0L));
            replacementKick.getValue().run();
            verify(replacement).kickPlayer("Refused fixture");
            verify(scheduler, never()).runOnServer(any());
        }
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Client");
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private static void join(ModGuard guard, Player player) {
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(player);
        guard.onJoin(join);
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
