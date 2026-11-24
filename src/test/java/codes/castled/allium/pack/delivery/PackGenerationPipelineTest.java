package codes.castled.allium.pack.delivery;

import codes.castled.allium.addon.AddonInventory;
import codes.castled.allium.pack.PackMerger;
import codes.castled.allium.pack.ResourcePackSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the unified {@link AddonInventory} -> {@link PackGeneration} -> standalone/mixer adapter seam:
 * duplicate and incompatible jars, delivery mode changes, disappearing addons, hint changes, and HTTP cache behavior.
 */
class PackGenerationPipelineTest {

    @TempDir
    Path tempDir;

    private static byte[] miniPack(String desc, String extraPath, String extraContent) throws Exception {
        SortedMap<String, byte[]> files = new TreeMap<>();
        files.put("pack.mcmeta", ("{\"pack\":{\"pack_format\":15,\"description\":\"" + desc + "\"}}").getBytes(StandardCharsets.UTF_8));
        files.put(extraPath, extraContent.getBytes(StandardCharsets.UTF_8));
        return PackMerger.writeZip(files);
    }

    private static void writeAddonJar(
            Path jarPath,
            String pluginYml,
            String bundledConfig,
            String packEntryName,
            byte[] packBytes,
            boolean shadeApiClass
    ) throws Exception {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(pluginYml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (bundledConfig != null) {
                out.putNextEntry(new JarEntry("config.yml"));
                out.write(bundledConfig.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            if (shadeApiClass) {
                out.putNextEntry(new JarEntry("codes/castled/allium/api/AlliumApi.class"));
                out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
                out.closeEntry();
            }
            if (packEntryName != null && packBytes != null) {
                out.putNextEntry(new JarEntry(packEntryName));
                out.write(packBytes);
                out.closeEntry();
            }
        }
    }

    @Test
    void rejectsIncompatibleShadedCorruptAndDuplicateAddonJarsBeforePackAssembly() throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path dataFolder = plugins.resolve("Allium");
        Path addons = dataFolder.resolve("addons");
        Files.createDirectories(addons);

        byte[] basePack = miniPack("Allium", "assets/allium/models/item/core.json", "{}");
        byte[] validPack = miniPack("Curios", "assets/curios/models/item/good.json", "{}");
        byte[] dupPack = miniPack("CuriosDup", "assets/curios/models/item/duplicate.json", "{}");
        byte[] badApiPack = miniPack("FutureAddon", "assets/future/models/item/bad.json", "{}");
        byte[] shadedPack = miniPack("ShadedAddon", "assets/shaded/models/item/bad.json", "{}");
        byte[] nonAddonPack = miniPack("PlainPlugin", "assets/plain/models/item/bad.json", "{}");

        // 1. Valid addon jar (alphabetically first -> wins)
        writeAddonJar(addons.resolve("01-Curios.jar"),
                "name: Curios\nversion: '1.0.0'\nmain: codes.castled.Curios\nallium-api: '^1.1'\nallium-pack: pack.zip\n",
                "resource-pack:\n  delivery: allium\n", "pack.zip", validPack, false);
        // 2. Duplicate jar for the same addon name (must be rejected)
        writeAddonJar(addons.resolve("02-Curios-copy.jar"),
                "name: Curios\nversion: '1.0.1'\nmain: codes.castled.Curios\nallium-api: '^1.1'\nallium-pack: pack.zip\n",
                "resource-pack:\n  delivery: allium\n", "pack.zip", dupPack, false);
        // 3. Incompatible API range
        writeAddonJar(addons.resolve("03-Future.jar"),
                "name: FutureAddon\nversion: '1.0.0'\nmain: codes.castled.Future\nallium-api: '^9.0'\nallium-pack: pack.zip\n",
                null, "pack.zip", badApiPack, false);
        // 4. Shaded API class
        writeAddonJar(addons.resolve("04-Shaded.jar"),
                "name: ShadedAddon\nversion: '1.0.0'\nmain: codes.castled.Shaded\nallium-api: '^1.1'\nallium-pack: pack.zip\n",
                null, "pack.zip", shadedPack, true);
        // 5. Missing allium-api declaration
        writeAddonJar(addons.resolve("05-Plain.jar"),
                "name: PlainPlugin\nversion: '1.0.0'\nmain: codes.castled.Plain\nallium-pack: pack.zip\n",
                null, "pack.zip", nonAddonPack, false);
        // 6. Corrupt jar
        Files.writeString(addons.resolve("06-Corrupt.jar"), "not-a-zip");

        AddonInventory inventory = AddonInventory.scan(addons, new YamlConfiguration());
        assertEquals(1, inventory.accepted().size());
        assertEquals("Curios", inventory.accepted().get(0).name());
        assertEquals(5, inventory.rejected().size());
        assertTrue(inventory.rejected().stream().anyMatch(r -> r.reason() == AddonInventory.RejectionReason.DUPLICATE));
        assertTrue(inventory.rejected().stream().anyMatch(r -> r.reason() == AddonInventory.RejectionReason.INCOMPATIBLE_API));
        assertTrue(inventory.rejected().stream().anyMatch(r -> r.reason() == AddonInventory.RejectionReason.SHADED_API));
        assertTrue(inventory.rejected().stream().anyMatch(r -> r.reason() == AddonInventory.RejectionReason.NOT_AN_ADDON));
        assertTrue(inventory.rejected().stream().anyMatch(r -> r.reason() == AddonInventory.RejectionReason.CORRUPT));

        YamlConfiguration rpCfg = new YamlConfiguration();
        rpCfg.set("owner", "standalone");
        ResourcePackSettings settings = ResourcePackSettings.parse(rpCfg);
        List<String> warns = new ArrayList<>();
        PackGeneration generation = PackGeneration.assemble(
                dataFolder, plugins, inventory, settings, basePack, null, "", new PackExport.Logs(m -> { }, warns::add));

        assertNotNull(generation.coreArtifact());
        assertEquals(List.of("Curios"), generation.mergedAddonNames());
        Map<String, byte[]> mergedEntries = PackMerger.readZip(generation.coreArtifact().bytes());
        assertTrue(mergedEntries.containsKey("assets/curios/models/item/good.json"));
        assertFalse(mergedEntries.containsKey("assets/curios/models/item/duplicate.json"));
        assertFalse(mergedEntries.containsKey("assets/future/models/item/bad.json"));
        assertFalse(mergedEntries.containsKey("assets/shaded/models/item/bad.json"));
        assertFalse(mergedEntries.containsKey("assets/plain/models/item/bad.json"));
        assertEquals(2, generation.ownedOutputs().size()); // standalone zip + .sha1
    }

    @Test
    void deliveryModeSwitchHintChangeAddonDisappearanceAndHttpCacheInvalidationStayConsistent() throws Exception {
        Path plugins = tempDir.resolve("plugins-flow");
        Path dataFolder = plugins.resolve("Allium");
        Path addons = dataFolder.resolve("addons");
        Path mixer = plugins.resolve("ResourcePackManager/mixer");
        Files.createDirectories(mixer);

        Path foreign = mixer.resolve("Foreign_pack.zip");
        byte[] foreignBytes = miniPack("Foreign", "assets/foreign/a.json", "{}");
        Files.write(foreign, foreignBytes);

        byte[] basePack = miniPack("Allium", "assets/allium/models/item/core.json", "{}");
        byte[] curiosPack = miniPack("Curios", "assets/curios/models/item/curio.json", "{}");
        Path curiosJar = addons.resolve("Addon-Curios.jar");
        writeAddonJar(curiosJar,
                "name: Curios\nversion: '1.0.0'\nmain: codes.castled.Curios\nallium-api: '^1.1'\nallium-pack: curios.zip\n",
                "resource-pack:\n  delivery: allium\n", "curios.zip", curiosPack, false);

        YamlConfiguration rpCfg = new YamlConfiguration();
        rpCfg.set("owner", "standalone");
        rpCfg.set("rpm-export.mixer-directory", "ResourcePackManager/mixer");
        ResourcePackSettings settings = ResourcePackSettings.parse(rpCfg);
        PackExport.Logs logs = new PackExport.Logs(m -> { }, m -> { });

        // Gen 1: standalone + Curios merged + hint A
        PackGeneration gen1 = PackGeneration.assemble(dataFolder, plugins, new YamlConfiguration(), settings,
                basePack, null, "&cUnknown command A", logs);
        assertNotNull(gen1.coreArtifact());
        gen1.publishExports(dataFolder, plugins, List.of(), logs);
        assertTrue(Files.isRegularFile(dataFolder.resolve("pack/allium-core-pack.zip")));

        PackHttpServer http = new PackHttpServer("127.0.0.1", 0, gen1.coreArtifact());
        http.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String url1 = http.packUrl("127.0.0.1", "", "");
            HttpResponse<byte[]> resp1 = client.send(HttpRequest.newBuilder(URI.create(url1)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, resp1.statusCode());
            assertEquals("public, max-age=31536000, immutable", resp1.headers().firstValue("Cache-Control").orElse(""));
            assertEquals("\"" + gen1.sha1() + "\"", resp1.headers().firstValue("ETag").orElse(""));

            // Gen 2: command hint changes -> new SHA-1, old immutable URL returns 404 no-store, new URL serves 200
            PackGeneration gen2 = PackGeneration.assemble(dataFolder, plugins, new YamlConfiguration(), settings,
                    basePack, null, "&eUnknown command B", logs);
            assertNotEquals(gen1.sha1(), gen2.sha1());
            http.update(gen2.coreArtifact());

            HttpResponse<Void> staleResp = client.send(HttpRequest.newBuilder(URI.create(url1)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(404, staleResp.statusCode());
            assertEquals("no-store", staleResp.headers().firstValue("Cache-Control").orElse(""));

            String url2 = http.packUrl("127.0.0.1", "", "");
            assertTrue(url2.contains(gen2.sha1()));
            HttpResponse<byte[]> resp2 = client.send(HttpRequest.newBuilder(URI.create(url2)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, resp2.statusCode());
            assertEquals(gen2.sha1(), PackExport.sha1(resp2.body()));

            // Gen 3: Curios switches delivery mode to rspm -> removed from core artifact, exported to mixer
            Files.createDirectories(plugins.resolve("Curios"));
            Files.writeString(plugins.resolve("Curios/config.yml"), "resource-pack:\n  delivery: rspm\n");
            PackGeneration gen3 = PackGeneration.assemble(dataFolder, plugins, new YamlConfiguration(), settings,
                    basePack, null, "&eUnknown command B", logs);
            assertTrue(gen3.mergedAddonNames().isEmpty());
            assertNotEquals(gen2.sha1(), gen3.sha1());
            assertTrue(gen3.ownedOutputs().stream().anyMatch(o -> o.kind() == PackGeneration.OutputKind.MIXER_ADDON));
            gen3.publishExports(dataFolder, plugins, List.of(), logs);
            Path curiosMixer = mixer.resolve("Curios_resource_pack.zip");
            assertTrue(Files.isRegularFile(curiosMixer));

            // Gen 4: Curios jar disappears -> stale mixer export removed, foreign pack untouched, core SHA matches gen3
            Files.delete(curiosJar);
            PackGeneration gen4 = PackGeneration.assemble(dataFolder, plugins, new YamlConfiguration(), settings,
                    basePack, null, "&eUnknown command B", logs);
            assertEquals(gen3.sha1(), gen4.sha1());
            assertTrue(gen4.ownedOutputs().stream().noneMatch(o -> o.kind() == PackGeneration.OutputKind.MIXER_ADDON));
            PackGeneration.PublishOutcome outcome4 = gen4.publishExports(dataFolder, plugins, List.of(), logs);
            assertFalse(Files.exists(curiosMixer));
            assertTrue(outcome4.addonMixer().removedStale().stream().anyMatch(p -> p.getFileName().toString().equals("Curios_resource_pack.zip")));
            assertTrue(Files.isRegularFile(foreign));
        } finally {
            http.stop();
        }
    }
}
