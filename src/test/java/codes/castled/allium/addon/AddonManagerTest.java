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
        writeAddonJar(target, name, "1.0.0", apiRange, packEntry, true, false);
    }

    private static void writeAddonJar(
            Path target,
            String name,
            String version,
            String apiRange,
            String packEntry,
            boolean includeMainClass,
            boolean shadeApiClass
    ) throws Exception {
        Files.createDirectories(target.getParent());
        String mainClass = "codes.castled.test." + name;
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            StringBuilder yml = new StringBuilder();
            yml.append("name: ").append(name).append('\n');
            yml.append("main: ").append(mainClass).append('\n');
            if (version != null) yml.append("version: '").append(version).append("'\n");
            yml.append("depend: [Allium]\n");
            if (apiRange != null) yml.append("allium-api: '").append(apiRange).append("'\n");
            yml.append("allium-addon-dir: true\n");
            if (packEntry != null) yml.append("allium-pack: ").append(packEntry).append('\n');
            out.write(yml.toString().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (includeMainClass) {
                out.putNextEntry(new JarEntry(mainClass.replace('.', '/') + ".class"));
                out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
                out.closeEntry();
            }
            if (shadeApiClass) {
                out.putNextEntry(new JarEntry("codes/castled/allium/api/AlliumApi.class"));
                out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
                out.closeEntry();
            }
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
        assertEquals(
                "https://github.com/castledking/Allium/releases/download/v1.0.0/SHA256SUMS.txt",
                AddonCatalog.checksumsUrl(null, "1.0.0")
        );
        assertTrue(AddonCatalog.isOfficialReleaseBase(null));
        assertTrue(AddonCatalog.isOfficialReleaseBase(AddonCatalog.DEFAULT_RELEASE_BASE_URL + "/"));
        assertFalse(AddonCatalog.isOfficialReleaseBase("https://mirror.example.com/allium"));
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

        Path stagedCurios = tempDir.resolve("staged-curios.jar");
        writeAddonJar(stagedCurios, "Curios", "^1.1", null);
        String curiosSha = AddonChecksums.sha256Hex(stagedCurios);

        List<String> requestedUrls = new ArrayList<>();
        AddonManager manager = new AddonManager(allium, (url, target) -> {
            requestedUrls.add(url);
            if (url.endsWith("/SHA256SUMS.txt")) {
                Files.writeString(target, curiosSha + "  Addon-Curios-1.0.0.jar\n", StandardCharsets.UTF_8);
            } else {
                Files.copy(stagedCurios, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }, Runnable::run, Map.of());
        when(allium.getAddonManager()).thenReturn(manager);

        // Before download, curios appears in non-installed tab completions.
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("allium.addon")).thenReturn(true);
        AlliumCommand cmd = new AlliumCommand(allium);
        assertTrue(cmd.completeAddon(sender, new String[]{"download", "c"}).containsAll(List.of("crops", "curios")));

        manager.downloadCommand(sender, "curios");

        assertEquals(List.of(
                "https://github.com/castledking/Allium/releases/download/v1.0.0/SHA256SUMS.txt",
                "https://github.com/castledking/Allium/releases/download/v1.0.0/Addon-Curios-1.0.0.jar"
        ), requestedUrls);
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

        Path stagedCurios = tempDir.resolve("auto-curios.jar");
        Path stagedBad = tempDir.resolve("auto-wrong.jar");
        writeAddonJar(stagedCurios, "Curios", "^1.1", null);
        writeAddonJar(stagedBad, "WrongPlugin", "^1.1", null);
        String manifest = AddonChecksums.sha256Hex(stagedCurios) + "  Addon-Curios-1.0.0.jar\n"
                + AddonChecksums.sha256Hex(stagedBad) + "  Addon-SpawnerCraft-1.0.0.jar\n";

        List<String> urls = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int count = AddonManager.ensureAutoDownloaded(dataFolder, config, "1.0.0", (url, target) -> {
            urls.add(url);
            if (url.endsWith("/SHA256SUMS.txt")) {
                Files.writeString(target, manifest, StandardCharsets.UTF_8);
            } else if (url.contains("Addon-Curios-1.0.0.jar")) {
                Files.copy(stagedCurios, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(stagedBad, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }, Map.of(), m -> {}, warnings::add);

        assertEquals(1, count);
        assertTrue(Files.isRegularFile(dataFolder.resolve("addons/Addon-Curios-1.0.0.jar")));
        assertFalse(Files.exists(dataFolder.resolve("addons/Addon-SpawnerCraft-1.0.0.jar")));
        assertEquals(4, urls.size());
        assertFalse(warnings.isEmpty());
        // Temp files are cleaned up.
        try (var stream = Files.list(dataFolder.resolve("addons"))) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsNonHttpsUrlsInInstallAndDefaultDownloader() throws Exception {
        Path addonsDir = tempDir.resolve("addons-https");
        var curios = AddonCatalog.find("curios").orElseThrow();
        List<String> attempted = new ArrayList<>();

        var httpOutcome = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0",
                "http://mirror.example.com/allium",
                "http://mirror.example.com/allium/v1.0.0/Addon-Curios-1.0.0.jar",
                "a".repeat(64),
                Map.of(),
                (url, target) -> attempted.add(url)
        );
        assertFalse(httpOutcome.success());
        assertTrue(httpOutcome.message().contains("HTTPS"), httpOutcome.message());
        assertTrue(attempted.isEmpty(), "non-HTTPS URL must be rejected before invoking downloader");

        var defaultDownloader = AddonManager.defaultDownloader();
        var ex = assertThrows(java.io.IOException.class,
                () -> defaultDownloader.download("http://example.com/Addon-Curios-1.0.0.jar", tempDir.resolve("nope.jar")));
        assertTrue(ex.getMessage().contains("HTTPS"), ex.getMessage());

        var fileEx = assertThrows(java.io.IOException.class,
                () -> defaultDownloader.download("file:///etc/passwd", tempDir.resolve("nope2.jar")));
        assertTrue(fileEx.getMessage().contains("HTTPS"), fileEx.getMessage());
    }

    @Test
    void verifiesBundledAndPinnedDigestsAndRefusesUnpinnedCustomMirror() throws Exception {
        Path addonsDir = tempDir.resolve("addons-mirrors");
        var curios = AddonCatalog.find("curios").orElseThrow();
        Path staged = tempDir.resolve("mirror-curios.jar");
        writeAddonJar(staged, "Curios", "^1.1", null);
        String sha = AddonChecksums.sha256Hex(staged);

        String mirrorBase = "https://mirror.example.com/releases";
        String mirrorUrl = AddonCatalog.downloadUrl(mirrorBase, "1.0.0", curios);

        // Unpinned custom mirror without bundled digest MUST fail closed without downloading the jar.
        List<String> unpinnedCalls = new ArrayList<>();
        var unpinned = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", mirrorBase, mirrorUrl, null, Map.of(),
                (url, target) -> unpinnedCalls.add(url)
        );
        assertFalse(unpinned.success());
        assertTrue(unpinned.message().contains("addons.checksums.curios"), unpinned.message());
        assertTrue(unpinnedCalls.isEmpty());

        // Pinned digest on custom HTTPS mirror succeeds without fetching remote SHA256SUMS.txt.
        List<String> pinnedCalls = new ArrayList<>();
        var pinned = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", mirrorBase, mirrorUrl, sha, Map.of(),
                (url, target) -> {
                    pinnedCalls.add(url);
                    Files.copy(staged, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
        );
        assertTrue(pinned.success(), pinned.message());
        assertEquals(List.of(mirrorUrl), pinnedCalls);

        // Bundled digest map also verifies directly without fetching remote SHA256SUMS.txt.
        Path addonsDir2 = tempDir.resolve("addons-bundled");
        List<String> bundledCalls = new ArrayList<>();
        var bundled = AddonManager.downloadAndInstallSync(
                addonsDir2, curios, "1.0.0", mirrorBase, mirrorUrl, null,
                Map.of("Addon-Curios-1.0.0.jar", sha),
                (url, target) -> {
                    bundledCalls.add(url);
                    Files.copy(staged, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
        );
        assertTrue(bundled.success(), bundled.message());
        assertEquals(List.of(mirrorUrl), bundledCalls);
    }

    @Test
    void rejectsCorruptMismatchedAndPreflightFailedAssetsWhilePreservingExistingJar() throws Exception {
        Path addonsDir = tempDir.resolve("addons-preserve");
        var curios = AddonCatalog.find("curios").orElseThrow();
        Path installedJar = addonsDir.resolve("Addon-Curios-1.0.0.jar");
        writeAddonJar(installedJar, "Curios", "1.0.0", "^1.1", "curios-pack.zip", true, false);
        byte[] originalBytes = Files.readAllBytes(installedJar);

        String url = AddonCatalog.downloadUrl(null, "1.0.0", curios);

        // 1. Corrupt / tampered download (SHA-256 mismatch).
        Path goodCandidate = tempDir.resolve("good.jar");
        writeAddonJar(goodCandidate, "Curios", "1.0.0", "^1.2", null, true, false);
        String goodSha = AddonChecksums.sha256Hex(goodCandidate);
        var mismatch = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", null, url, goodSha, Map.of(),
                (u, target) -> Files.writeString(target, "corrupt-bytes", StandardCharsets.UTF_8)
        );
        assertFalse(mismatch.success());
        assertTrue(mismatch.message().contains("SHA-256"), mismatch.message());
        assertArrayEquals(originalBytes, Files.readAllBytes(installedJar));

        // 2. Non-ZIP / corrupt asset even if its SHA-256 matches a pin.
        Path corruptFile = tempDir.resolve("corrupt.bin");
        Files.writeString(corruptFile, "not a jar file at all", StandardCharsets.UTF_8);
        String corruptSha = AddonChecksums.sha256Hex(corruptFile);
        var corruptOutcome = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", null, url, corruptSha, Map.of(),
                (u, target) -> Files.copy(corruptFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        );
        assertFalse(corruptOutcome.success());
        assertTrue(corruptOutcome.message().contains("corrupt"), corruptOutcome.message());
        assertArrayEquals(originalBytes, Files.readAllBytes(installedJar));

        // 3. Mismatched version in plugin.yml (0.9.0 != 1.0.0).
        Path wrongVersion = tempDir.resolve("wrong-ver.jar");
        writeAddonJar(wrongVersion, "Curios", "0.9.0", "^1.1", null, true, false);
        var wrongVerOutcome = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", null, url, AddonChecksums.sha256Hex(wrongVersion), Map.of(),
                (u, target) -> Files.copy(wrongVersion, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        );
        assertFalse(wrongVerOutcome.success());
        assertTrue(wrongVerOutcome.message().contains("0.9.0"), wrongVerOutcome.message());
        assertArrayEquals(originalBytes, Files.readAllBytes(installedJar));

        // 4. Shaded Allium API detected by shared runtime preflight.
        Path shadedJar = tempDir.resolve("shaded.jar");
        writeAddonJar(shadedJar, "Curios", "1.0.0", "^1.1", null, true, true);
        var shadedOutcome = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", null, url, AddonChecksums.sha256Hex(shadedJar), Map.of(),
                (u, target) -> Files.copy(shadedJar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        );
        assertFalse(shadedOutcome.success());
        assertTrue(shadedOutcome.message().contains("AlliumApi"), shadedOutcome.message());
        assertArrayEquals(originalBytes, Files.readAllBytes(installedJar));

        // 5. Incompatible allium-api range detected by shared runtime preflight.
        Path futureApiJar = tempDir.resolve("future-api.jar");
        writeAddonJar(futureApiJar, "Curios", "1.0.0", "^2.0", null, true, false);
        var futureOutcome = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", null, url, AddonChecksums.sha256Hex(futureApiJar), Map.of(),
                (u, target) -> Files.copy(futureApiJar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        );
        assertFalse(futureOutcome.success());
        assertTrue(futureOutcome.message().contains("^2.0"), futureOutcome.message());
        assertArrayEquals(originalBytes, Files.readAllBytes(installedJar));

        // 6. Missing declared main class entry inside the jar.
        Path missingMainJar = tempDir.resolve("missing-main.jar");
        writeAddonJar(missingMainJar, "Curios", "1.0.0", "^1.1", null, false, false);
        var missingMainOutcome = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", null, url, AddonChecksums.sha256Hex(missingMainJar), Map.of(),
                (u, target) -> Files.copy(missingMainJar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        );
        assertFalse(missingMainOutcome.success());
        assertTrue(missingMainOutcome.message().contains("main class"), missingMainOutcome.message());
        assertArrayEquals(originalBytes, Files.readAllBytes(installedJar));

        // No temp files leaked across any failure.
        try (var stream = Files.list(addonsDir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }

        // 7. A fully valid jar atomically replaces the previous jar once all checks pass.
        var validOutcome = AddonManager.downloadAndInstallSync(
                addonsDir, curios, "1.0.0", null, url, goodSha, Map.of(),
                (u, target) -> Files.copy(goodCandidate, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        );
        assertTrue(validOutcome.success(), validOutcome.message());
        assertArrayEquals(Files.readAllBytes(goodCandidate), Files.readAllBytes(installedJar));
    }

    @Test
    void curiosCatalogDescriptionReflectsImplementedScopeWithoutMorphs() {
        var curios = AddonCatalog.find("curios").orElseThrow();
        String desc = curios.description().toLowerCase(java.util.Locale.ROOT);
        assertFalse(desc.contains("morph"), "Curios catalog entry must not advertise unimplemented morphs: " + curios.description());
        assertTrue(desc.contains("cards") && desc.contains("relics") && desc.contains("frames"), curios.description());
    }
}
