package codes.castled.crops;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class CropsCommandAndPackTest {

    @Test
    void pluginYmlRegistersCropsCommandAndAliases() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in);
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals(List.of("Allium"), yml.getStringList("depend"));
            assertTrue(yml.getBoolean("allium-addon-dir"));
            assertEquals("crops-pack.zip", yml.getString("allium-pack"));
            assertNotNull(yml.getConfigurationSection("commands.crops"));
            assertEquals(List.of("crop", "hf", "harvest"), yml.getStringList("commands.crops.aliases"));
            assertEquals("crops.use", yml.getString("commands.crops.permission"));
            assertNotNull(yml.getConfigurationSection("permissions.crops.admin"));
        }
    }

    @Test
    void cropsCatalogExcludesWeedAndParsesCleanly() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("crops.yml")) {
            assertNotNull(in);
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            var crops = Catalog.read(yml.getConfigurationSection("crops"));
            assertFalse(crops.isEmpty());
            assertFalse(crops.containsKey("weed"));
        }
    }

    @Test
    void durationFormattingWorksForSecondsMinutesAndHours() {
        assertEquals("5s", CropsCommand.formatDuration(4500));
        assertEquals("2m", CropsCommand.formatDuration(120_000));
        assertEquals("1m 30s", CropsCommand.formatDuration(90_000));
        assertEquals("1h 15m", CropsCommand.formatDuration(4_500_000));
    }

    @Test
    void pitayaTextureLivesInBlocksAtlasWithMatchingModelReferences() throws Exception {
        Path packRoot = Path.of("pack/assets/crops");
        assertTrue(Files.isRegularFile(packRoot.resolve("textures/block/ce/block/crops/pitaya/stage.png")));
        assertFalse(Files.exists(packRoot.resolve("textures/item/ce/block/crops/pitaya/stage.png")));
        for (int stage = 1; stage <= 6; stage++) {
            String json = Files.readString(packRoot.resolve("models/sf/ce/item/crops/pitaya/stage_" + stage + ".json"));
            assertTrue(json.contains("crops:block/ce/block/crops/pitaya/stage"));
            assertFalse(json.contains("crops:item/ce/block/crops/pitaya/stage"));
        }
    }

    @Test
    void defaultConfigAndItemsAreNonItalicAndJadeOverlaysAreColorFree() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(cfg.contains("crop-model-in-jade"));
            assertFalse(cfg.getBoolean("crop-model-in-jade"));
        }

        Set<String> cropStageIds = new HashSet<>();
        try (var in = getClass().getClassLoader().getResourceAsStream("crops.yml")) {
            assertNotNull(in);
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            var cropsSection = yml.getConfigurationSection("crops");
            assertNotNull(cropsSection);
            for (String cropId : cropsSection.getKeys(false)) {
                String displayName = cropsSection.getString(cropId + ".display-name");
                assertNotNull(displayName);
                assertFalse(displayName.contains("<") || displayName.contains("&") || displayName.contains("§"),
                        "crops.yml display-name for " + cropId + " should not have color codes: " + displayName);
            }
            for (Catalog.Crop crop : Catalog.read(cropsSection).values()) {
                for (Catalog.Path path : crop.paths().values()) {
                    for (Catalog.Stage stage : path.stages()) {
                        cropStageIds.add(CropsPlugin.stripNamespace(stage.visual().item()));
                    }
                }
            }
        }
        assertEquals(113, cropStageIds.size());

        try (var in = getClass().getClassLoader().getResourceAsStream("items.yml")) {
            assertNotNull(in);
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            var items = yml.getConfigurationSection("items");
            assertNotNull(items);
            for (String id : items.getKeys(false)) {
                String name = items.getString(id + ".name");
                assertNotNull(name, "Missing name for " + id);
                if (cropStageIds.contains(id)) {
                    assertFalse(name.contains("<") || name.contains("&") || name.contains("§"),
                            "Crop stage " + id + " should have color-free Jade name: " + name);
                } else {
                    assertTrue(name.startsWith("&") || name.startsWith("§") || name.startsWith("<"),
                            "Default item " + id + " should declare non-italic color formatting: " + name);
                }
            }
        }
    }

    @Test
    void runtimeHelpersSanitizeLegacyConfigsAndSelectJadeCmdVariant() {
        assertEquals("Banana", CropsPlugin.stripColors("<red>Banana"));
        assertEquals("Spinach", CropsPlugin.stripColors("<green>Spinach"));
        assertEquals("Apple Pie (Baked)", CropsPlugin.stripColors("<#f0a040>Apple Pie <gray>(Baked)"));
        assertEquals("Banana", CropsPlugin.stripColors("&cBanana"));

        assertEquals("Banana", CropsPlugin.normalizeDisplayName("<red>Banana", true));
        assertEquals("&fBanana", CropsPlugin.normalizeDisplayName("Banana", false));
        assertEquals("&fBanana", CropsPlugin.normalizeDisplayName("&fBanana", false));
        assertEquals("<#f0a040>Apple Pie <gray>(Baked)",
                CropsPlugin.normalizeDisplayName("<#f0a040>Apple Pie <gray>(Baked)", false));

        // Jade shows placed models by name; catalogues saved before " Placed" was dropped still load clean.
        assertEquals("&fPie Crust", CropsPlugin.placedName("pie_crust_placed", "&fPie Crust Placed"));
        assertEquals("Pie Crust", CropsPlugin.placedName("pie_crust_placed", "Pie Crust Placed"));
        assertEquals("&fBaked Apple Pie", CropsPlugin.placedName("baked_apple_pie_placed_slice2", "&fBaked Apple Pie"));
        assertEquals("&fPlaced Sign", CropsPlugin.placedName("placed_sign", "&fPlaced Sign"));
        assertEquals("&fBlock Placed", CropsPlugin.placedName("pie_crust", "&fBlock Placed"));

        assertEquals(4_025_641, CropsPlugin.resolveCustomModelData(3_025_641, true, false));
        assertEquals(3_025_641, CropsPlugin.resolveCustomModelData(3_025_641, true, true));
        assertEquals(3_025_641, CropsPlugin.resolveCustomModelData(3_025_641, false, false));
    }

    @Test
    void builtPackEmitsBothSeedAndStageJadeIconsForCropStages(@TempDir Path tempDir) throws Exception {
        Path zipPath = tempDir.resolve("crops-pack.zip");
        Process process = new ProcessBuilder("python3", "pack/build.py", zipPath.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "pack/build.py failed: " + output);

        YamlConfiguration itemsYml;
        try (var in = getClass().getClassLoader().getResourceAsStream("items.yml")) {
            itemsYml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        int bananaStageCmd = itemsYml.getInt("items.banana_stage_1.cmd");
        int blackberryBushCmd = itemsYml.getInt("items.blackberry_bush_1.cmd");

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entry = zip.getEntry("assets/minecraft/items/paper.json");
            assertNotNull(entry);
            String json = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);

            // crop-model-in-jade: true -> uses base cmd with stage icon in gui
            assertTrue(json.contains("\"threshold\":" + bananaStageCmd + ","), "Missing base banana_stage_1 CMD");
            assertTrue(json.contains("\"model\":\"crops:sf/ce/item/crop_stage_icons/banana_stage_1\""),
                    "Missing stage icon model for banana_stage_1");

            // crop-model-in-jade: false -> uses offset cmd with seed model in gui & 3D stage model as fallback
            int bananaSeedJadeCmd = bananaStageCmd + CropsPlugin.JADE_SEED_CMD_OFFSET;
            assertTrue(json.contains("\"threshold\":" + bananaSeedJadeCmd
                            + ",\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                            + "\"cases\":[{\"when\":\"gui\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"crops:item/banana_seeds\"}}],"
                            + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"crops:sf/ce/item/crops/banana/stage_1\"}}"),
                    "Missing seed-in-Jade entry for banana_stage_1");

            // Kitchen's placed models show the item they stand for in GUIs (Jade's icon).
            for (String[] placed : new String[][]{{"pie_crust_placed", "pie_crust"},
                    {"baked_blackberry_pie_placed_slice2", "baked_blackberry_pie"}, {"blackberry_pie_filled", "cold_blackberry_pie"}}) {
                assertTrue(json.contains("\"threshold\":" + itemsYml.getInt("items." + placed[0] + ".cmd")
                                + ",\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                                + "\"cases\":[{\"when\":\"gui\",\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                                + itemsYml.getString("items." + placed[1] + ".model") + "\"}}],"
                                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"" + itemsYml.getString("items." + placed[0] + ".model") + "\"}}"),
                        "Missing the GUI icon for " + placed[0]);
            }

            int blackberrySeedJadeCmd = blackberryBushCmd + CropsPlugin.JADE_SEED_CMD_OFFSET;
            assertTrue(json.contains("\"threshold\":" + blackberrySeedJadeCmd
                            + ",\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                            + "\"cases\":[{\"when\":\"gui\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"crops:sf/sf/crops/blackberry/blackberries\"}}],"
                            + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"crops:sf/ce/item/crops/blackberry/blackberry_bush_1\"}}"),
                    "Missing berry-seed-in-Jade entry for blackberry_bush_1");
        }
    }
}
