package codes.castled.allium.addon;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.AlliumCommand;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.pack.delivery.AddonPacks;
import codes.castled.allium.pack.delivery.PackExport;
import codes.castled.allium.scheduler.AlliumScheduler;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AddonManagerTest {

    @TempDir
    Path tempDir;

    private static void writeAddonJar(Path target, String name, String apiRange, String packEntry) throws Exception {
        Files.createDirectories(target.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            StringBuilder yml = new StringBuilder();
            yml.append("name: ").append(name).append('\n');
            yml.append("main: codes.castled.test.").append(name).append('\n');
            yml.append("version: '1.0.0'\n");
            yml.append("depend: [Allium]\n");
            yml.append("allium-api: '").append(apiRange).append("'\n");
            yml.append("allium-addon-dir: true\n");
            if (packEntry != null) yml.append("allium-pack: ").append(packEntry).append('\n');
            out.write(yml.toString().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (packEntry != null) {
                out.putNextEntry(new JarEntry(packEntry));
                out.write("fake-pack-bytes".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private PluginStart mockAllium(Path dataFolder, YamlConfiguration config, PluginManager pm) {
        PluginStart allium = mock(PluginStart.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        MessageManager messages = mock(MessageManager.class);
        AlliumScheduler scheduler = mock(AlliumScheduler.class);
        PluginDescriptionFile desc = new PluginDescriptionFile("Allium", "1.0.0", "codes.castled.allium.PluginStart");

        when(allium.getDataFolder()).thenReturn(dataFolder.toFile());
        when(allium.getConfig()).thenReturn(config);
        when(allium.getDescription()).thenReturn(desc);
        when(allium.getServer()).thenReturn(server);
        when(allium.logger()).thenReturn(logger);
        when(allium.getMessageManager()).thenReturn(messages);
        when(allium.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getServicesManager()).thenReturn(services);
        when(pm.getPlugins()).thenReturn(new Plugin[]{allium});
        doAnswer(inv -> { inv.<Runnable>getArgument(0).run(); return TaskHandle.NONE; }).when(scheduler).runOnServer(any(Runnable.class));
        return allium;
    }

    @Test
    void buildsExactGithubReleaseUrlsForOfficialAddons() {
        var curios = AddonCatalog.find("curios").orElseThrow();
        assertEquals(
                "https://github.com/castledking/Allium/releases/download/v1.0.0/Addon-Curios-1.0.0.jar",
                AddonCatalog.downloadUrl(null, AddonCatalog.releaseVersion("1.0.0-SNAPSHOT", ""), curios)
        );
        var party = AddonCatalog.find("party-locator").orElseThrow();
        assertEquals("partylocator", party.key());
        assertEquals(
                "https://github.com/castledking/Allium/releases/download/v1.0.0/Addon-PartyLocator-1.0.0.jar",
                AddonCatalog.downloadUrl("https://github.com/castledking/Allium/releases/download/", "1.0.0", party)
        );
    }

    @Test
    void disabledAddonJarStaysInAddonsDirWithoutStartingOrMergingPack() throws Exception {
        Path dataFolder = tempDir.resolve("Allium");
        Path jar = dataFolder.resolve("addons/Addon-Curios-1.0.0.jar");
        writeAddonJar(jar, "Curios", "^1.1", "curios-pack.zip");

        YamlConfiguration config = new YamlConfiguration();
        config.set("addons.enabled.curios", false);
        PluginManager pm = mock(PluginManager.class);
        PluginStart allium = mockAllium(dataFolder, config, pm);

        AlliumApiService api = new AlliumApiService(allium);
        api.install();
        verify(pm, never()).loadPlugin(any());
        assertTrue(Files.isRegularFile(jar), "jar remains in addons/ when disabled");

        var logs = new PackExport.Logs(m -> {}, m -> {});
        assertTrue(AddonPacks.discover(dataFolder, config, logs).isEmpty(), "disabled addon pack is not merged");

        config.set("addons.enabled.curios", true);
        assertEquals(1, AddonPacks.discover(dataFolder, config, logs).size(), "enabled addon pack is discovered");
    }

    @Test
    void downloadCommandInstallsJarSetsConfigTrueAndReportsRestartRequired() throws Exception {
        Path dataFolder = tempDir.resolve("Allium");
        Files.createDirectories(dataFolder);
        Path configFile = dataFolder.resolve("config.yml");
        YamlConfiguration config = new YamlConfiguration();
        config.set("addons.enabled.curios", false);
        config.set("addons.enabled.crops", false);
        config.save(configFile.toFile());

        PluginManager pm = mock(PluginManager.class);
        PluginStart allium = mockAllium(dataFolder, config, pm);

        List<String> requestedUrls = new ArrayList<>();
        AddonManager manager = new AddonManager(allium, (url, target) -> {
            requestedUrls.add(url);
            try {
                writeAddonJar(target, "Curios", "^1.1", null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, Runnable::run);
        when(allium.getAddonManager()).thenReturn(manager);

        // Before download, curios appears in non-installed tab completions.
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("allium.addon")).thenReturn(true);
        AlliumCommand cmd = new AlliumCommand(allium);
        assertTrue(cmd.completeAddon(sender, new String[]{"download", "c"}).containsAll(List.of("crops", "curios")));

        manager.downloadCommand(sender, "curios");

        assertEquals(List.of("https://github.com/castledking/Allium/releases/download/v1.0.0/Addon-Curios-1.0.0.jar"), requestedUrls);
        Path installed = dataFolder.resolve("addons/Addon-Curios-1.0.0.jar");
        assertTrue(Files.isRegularFile(installed));
        assertTrue(config.getBoolean("addons.enabled.curios"));

        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(configFile.toFile());
        assertTrue(reloaded.getBoolean("addons.enabled.curios"));
        verify(allium.getMessageManager()).send(eq(sender), eq(Messages.ADDON_DOWNLOAD_COMPLETE),
                eq("{addon}"), eq("Curios"), eq("{file}"), eq("Addon-Curios-1.0.0.jar"), eq("{key}"), eq("curios"));

        // After download, curios is no longer suggested for download, and appears under disable.
        assertFalse(cmd.completeAddon(sender, new String[]{"download", ""}).contains("curios"));
        assertTrue(cmd.completeAddon(sender, new String[]{"disable", ""}).contains("curios"));

        // Toggling disable keeps the jar and persists false.
        manager.toggleCommand(sender, "curios", false);
        assertTrue(Files.isRegularFile(installed));
        assertFalse(YamlConfiguration.loadConfiguration(configFile.toFile()).getBoolean("addons.enabled.curios"));
        verify(allium.getMessageManager()).send(eq(sender), eq(Messages.ADDON_DISABLED_RESTART),
                eq("{addon}"), eq("Curios"), eq("{key}"), eq("curios"));
        assertTrue(cmd.completeAddon(sender, new String[]{"enable", ""}).contains("curios"));
    }

    @Test
    void autoDownloadOnlyFetchesEnabledMissingOfficialAddonsAndRejectsBadJars() throws Exception {
        Path dataFolder = tempDir.resolve("Allium");
        writeAddonJar(dataFolder.resolve("addons/Crops.jar"), "Crops", "^1.4", null);

        YamlConfiguration config = new YamlConfiguration();
        config.set("addons.auto-download", true);
        config.set("addons.enabled.crops", true); // already installed
        config.set("addons.enabled.curios", true); // should download
        config.set("addons.enabled.ores", false); // disabled
        config.set("addons.enabled.spawnercraft", true); // will return wrong plugin.yml -> rejected

        List<String> urls = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int count = AddonManager.ensureAutoDownloaded(dataFolder, config, "1.0.0", (url, target) -> {
            urls.add(url);
            try {
                if (url.contains("Addon-Curios-1.0.0.jar")) {
                    writeAddonJar(target, "Curios", "^1.1", null);
                } else {
                    writeAddonJar(target, "WrongPlugin", "^1.1", null);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, m -> {}, warnings::add);

        assertEquals(1, count);
        assertTrue(Files.isRegularFile(dataFolder.resolve("addons/Addon-Curios-1.0.0.jar")));
        assertFalse(Files.exists(dataFolder.resolve("addons/Addon-SpawnerCraft-1.0.0.jar")));
        assertEquals(2, urls.size());
        assertFalse(warnings.isEmpty());
        // Temp files are cleaned up.
        try (var stream = Files.list(dataFolder.resolve("addons"))) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }
}
