package codes.castled.allium.pack.delivery;

import codes.castled.allium.pack.PackMerger;
import codes.castled.allium.pack.ResourcePackSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.*;

class AddonPacksTest {

    @TempDir
    Path tempDir;

    private static byte[] miniPack(String desc, String extraPath, String extraContent) throws Exception {
        SortedMap<String, byte[]> files = new TreeMap<>();
        files.put("pack.mcmeta", ("{\"pack\":{\"pack_format\":15,\"description\":\"" + desc + "\"}}").getBytes(StandardCharsets.UTF_8));
        files.put(extraPath, extraContent.getBytes(StandardCharsets.UTF_8));
        return PackMerger.writeZip(files);
    }

    private static void writeAddonJar(Path jarPath, String pluginYml, String bundledConfig, String packEntryName, byte[] packBytes) throws Exception {
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
            if (packEntryName != null && packBytes != null) {
                out.putNextEntry(new JarEntry(packEntryName));
                out.write(packBytes);
                out.closeEntry();
            }
        }
    }

    @Test
    void discoversModesRespectsExternalConfigOverrideAndMergesOrExportsToRspm() throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path dataFolder = plugins.resolve("Allium");
        Path addons = dataFolder.resolve("addons");
        Path mixer = plugins.resolve("ResourcePackManager/mixer");
        Files.createDirectories(mixer);

        byte[] baseBytes = miniPack("Allium", "assets/allium/models/item/a.json", "{}");
        byte[] curiosPack = miniPack("Curios", "assets/curios/models/item/c.json", "{}");
        byte[] rspmPack = miniPack("Extra", "assets/extra/models/item/e.json", "{}");

        // Curios: bundled config says none, external config overrides to allium
        writeAddonJar(addons.resolve("Addon-Curios.jar"),
                "name: Curios\nallium-api: '^1.1'\nallium-pack: curios-pack.zip\n",
                "resource-pack:\n  delivery: none\n",
                "curios-pack.zip", curiosPack);
        Files.createDirectories(addons.resolve("Curios"));
        Files.writeString(addons.resolve("Curios/config.yml"), "resource-pack:\n  delivery: allium\n");

        // Extra: bundled config says rspm (no external config yet)
        writeAddonJar(addons.resolve("Addon-Extra.jar"),
                "name: Extra\nallium-api: '^1.1'\nallium-pack: extra-pack.zip\n",
                "resource-pack:\n  delivery: rspm\n",
                "extra-pack.zip", rspmPack);

        // Disabled: the bundled config says allium, but the addon's own data folder (plugins/Muted/,
        // where Bukkit keeps its config.yml) says none and wins over the bundled default.
        writeAddonJar(addons.resolve("Addon-Muted.jar"),
                "name: Muted\nallium-api: '^1.1'\nallium-pack: muted.zip\n",
                "resource-pack:\n  delivery: allium\n",
                "muted.zip", rspmPack);
        Files.createDirectories(plugins.resolve("Muted"));
        Files.writeString(plugins.resolve("Muted/config.yml"), "resource-pack:\n  delivery: none\n");

        List<String> infos = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        PackExport.Logs logs = new PackExport.Logs(infos::add, warns::add);

        List<AddonPacks.Discovered> discovered = AddonPacks.discover(dataFolder, logs);
        assertEquals(2, discovered.size());
        assertEquals(AddonPacks.Mode.ALLIUM, discovered.stream().filter(d -> d.addonName().equals("Curios")).findFirst().orElseThrow().mode());
        assertEquals(AddonPacks.Mode.RSPM, discovered.stream().filter(d -> d.addonName().equals("Extra")).findFirst().orElseThrow().mode());

        // Merge ALLIUM mode into base pack
        PackSource merged = AddonPacks.mergeIntoBase(PackSource.bundled(baseBytes), discovered, logs);
        assertTrue(merged.source().contains("Curios"));
        Map<String, byte[]> mergedEntries = PackMerger.readZip(merged.bytes());
        assertTrue(mergedEntries.containsKey("assets/allium/models/item/a.json"));
        assertTrue(mergedEntries.containsKey("assets/curios/models/item/c.json"));
        assertFalse(mergedEntries.containsKey("assets/extra/models/item/e.json"));

        // Export RSPM mode into mixer
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("owner", "none");
        cfg.set("rpm-export.mixer-directory", "ResourcePackManager/mixer");
        cfg.set("rpm-export.dry-run", false);
        ResourcePackSettings settings = ResourcePackSettings.parse(cfg);
        AddonPacks.exportRspm(plugins, settings, discovered, logs);

        Path exported = mixer.resolve("Extra_resource_pack.zip");
        assertTrue(Files.isRegularFile(exported));
        assertArrayEquals(rspmPack, Files.readAllBytes(exported));
        assertTrue(warns.isEmpty(), () -> "unexpected warnings: " + warns);
    }

    @Test
    void invalidDeliveryModeWarnsAndFallsBackToAllium() throws Exception {
        Path dataFolder = tempDir.resolve("plugins/Allium");
        Path addons = dataFolder.resolve("addons");
        byte[] pack = miniPack("Bad", "assets/bad/models/item/b.json", "{}");
        writeAddonJar(addons.resolve("Addon-Bad.jar"),
                "name: Bad\nallium-api: '^1.1'\nallium-pack: bad.zip\n",
                "resource-pack:\n  delivery: bogus\n",
                "bad.zip", pack);

        List<String> warns = new ArrayList<>();
        List<AddonPacks.Discovered> discovered = AddonPacks.discover(dataFolder, new PackExport.Logs(m -> { }, warns::add));
        assertEquals(1, discovered.size());
        assertEquals(AddonPacks.Mode.ALLIUM, discovered.get(0).mode());
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("invalid resource-pack.delivery"));
    }
}
