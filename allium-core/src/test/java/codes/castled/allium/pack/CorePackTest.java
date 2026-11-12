package codes.castled.allium.pack;

import codes.castled.allium.tool.disarmer.DisarmerCharges;
import codes.castled.allium.tool.disarmer.DisarmerVisualState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class CorePackTest {

    private static final Path ROOT = Path.of("..", "pack").toAbsolutePath().normalize();
    private static final String GOLDEN_HOE_MODEL = "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"minecraft:item/golden_hoe\"}}";
    private static final String GOLDEN_HOE_ITEM = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/golden_hoe\"}}";

    private static AssetManifest manifest() throws IOException {
        return AssetManifest.parse(Files.readString(ROOT.resolve("manifest.json")));
    }

    private static SortedMap<String, byte[]> build(AssetManifest manifest) throws IOException {
        return CorePackBuilder.files(manifest, t -> Files.readAllBytes(ROOT.resolve(t.file())), true);
    }

    /** The vanilla files the pack touches, identical on every validated lane (checked live against client jars). */
    private static VanillaAssets vanilla(boolean itemDefinitions) {
        Map<String, String> files = new HashMap<>();
        files.put("assets/minecraft/models/item/golden_hoe.json", GOLDEN_HOE_MODEL);
        if (itemDefinitions) {
            files.put("assets/minecraft/items/golden_hoe.json", GOLDEN_HOE_ITEM);
        }
        return new VanillaAssets("test-vanilla",
                Set.of("minecraft:item/handheld", "minecraft:item/generated", "minecraft:item/golden_hoe"),
                Set.of("minecraft:item/golden_hoe"),
                itemDefinitions ? Set.of("minecraft:golden_hoe") : Set.of(),
                files);
    }

    @Test
    void manifestAllocatesEveryCoreToolInsideTheBlockWithoutCollisions() throws IOException {
        AssetManifest manifest = manifest();
        assertEquals(List.of(), PackValidator.manifest(manifest));
        assertEquals(List.of("handcuffs", "mob_disarmer", "phantom_obliterator", "item_renamer", "lazy_axe"),
                manifest.tools().stream().map(AssetManifest.Tool::id).toList());
        AssetManifest.Tool disarmer = manifest.tool("mob_disarmer").orElseThrow();
        assertTrue(disarmer.ready());
        assertEquals("golden_hoe", disarmer.carrier());
        for (DisarmerVisualState state : DisarmerVisualState.values()) {
            assertTrue(disarmer.state(state.key()).isPresent(), "manifest state for " + state);
        }
        for (AssetManifest.Texture texture : manifest.textures()) {
            assertTrue(texture.distributable());
            assertFalse(texture.author().isBlank() || texture.origin().isBlank() || texture.license().isBlank());
        }
    }

    @Test
    void generationIsDeterministicAndStructurallyValid() throws IOException {
        AssetManifest manifest = manifest();
        SortedMap<String, byte[]> first = build(manifest);
        byte[] zip = CorePackBuilder.zip(first);
        byte[] again = CorePackBuilder.zip(build(manifest));
        assertArrayEquals(zip, again, "same manifest must give a byte-identical zip");
        assertEquals(CorePackBuilder.sha1(zip), CorePackBuilder.sha1(again));
        assertEquals(List.of(), PackValidator.structure(first, manifest));
        SortedMap<String, byte[]> unzipped = PackValidator.unzip(zip);
        assertEquals(first.keySet(), unzipped.keySet());
        assertEquals(List.of(), PackValidator.structure(unzipped, manifest));
        assertTrue(first.containsKey("assets/allium/models/item/mob_disarmer.json"), "1.21.2 item_model ns:id reads models/item/id");
        assertTrue(first.containsKey("allium_item_definitions/assets/allium/items/mob_disarmer.json"));
        assertTrue(first.containsKey("allium_item_definitions/assets/minecraft/items/golden_hoe.json"));
        assertFalse(first.keySet().stream().anyMatch(p -> p.contains("blaze_rod") || p.contains("phantom_membrane") || p.contains("iron_shovel") || p.contains("name_tag") || p.contains("netherite_axe")),
                "planned tools reserve values but generate nothing");
    }

    @Test
    void everyLaneFormatReadsThePackAndOverlaysApplyFromFormat46() throws IOException {
        Map<String, Object> mcmeta = Json.parseObject(new String(build(manifest()).get("pack.mcmeta"), StandardCharsets.UTF_8));
        Map<String, List<String>> expected = new TreeMap<>(Map.of(
                "15", List.of(), "18", List.of(), "42", List.of(), "46", List.of("allium_item_definitions"),
                "64", List.of("allium_item_definitions"), "69.0", List.of("allium_item_definitions"),
                "97.1", List.of("allium_item_definitions")));
        expected.forEach((format, overlays) -> {
            PackFormatRules.Result result = PackFormatRules.evaluate(mcmeta, PackFormatRules.Format.parse(format));
            assertTrue(result.compatible(), format + " " + result.errors());
            assertEquals(overlays, result.overlays(), format);
        });
        assertFalse(PackFormatRules.evaluate(mcmeta, PackFormatRules.Format.parse("98.0")).compatible());
        assertFalse(PackFormatRules.evaluate(mcmeta, PackFormatRules.Format.parse("14")).compatible());
    }

    @Test
    void format65AndUpRulesMatchTheDecompiledValidator() {
        // A pack reaching format 64 or lower needs supported_formats that starts at min_format.
        assertError(Map.of("description", "x", "pack_format", 15L, "min_format", 15L, "max_format", 97L), "needs supported_formats");
        assertError(Map.of("description", "x", "pack_format", 15L, "min_format", 15L, "max_format", 97L,
                "supported_formats", List.of(16L, 97L)), "starts at 16");
        assertError(Map.of("description", "x", "pack_format", 15L, "min_format", 15L, "max_format", 97L,
                "supported_formats", List.of(15L, 90L)), "ends at 90");
        assertError(Map.of("description", "x", "min_format", 15L, "max_format", 97L,
                "supported_formats", List.of(15L, 64L)), "needs pack_format");
        // Above 64 only, supported_formats is not allowed and pack_format is optional.
        assertError(Map.of("description", "x", "min_format", 65L, "max_format", 97L, "supported_formats", List.of(65L, 97L)),
                "must not declare supported_formats");
        assertTrue(PackFormatRules.evaluate(Map.of("pack", Map.of("description", "x", "min_format", 65L, "max_format", List.of(97L, 1L))),
                PackFormatRules.Format.parse("97.1")).compatible());
        assertFalse(PackFormatRules.evaluate(Map.of("pack", Map.of("description", "x", "min_format", 65L, "max_format", List.of(97L, 0L))),
                PackFormatRules.Format.parse("97.1")).compatible(), "[97, 0] stops before 97.1");
        // Overlays: once any overlay reaches 64 or lower, every overlay needs formats.
        Map<String, Object> pack = Map.of("description", "x", "pack_format", 15L, "min_format", 15L, "max_format", 97L,
                "supported_formats", List.of(15L, 97L));
        Map<String, Object> overlays = Map.of("entries", List.of(
                Map.of("directory", "a", "formats", List.of(46L, 97L), "min_format", 46L, "max_format", 97L),
                Map.of("directory", "b", "min_format", 70L, "max_format", 97L)));
        PackFormatRules.Result result = PackFormatRules.evaluate(Map.of("pack", pack, "overlays", overlays), PackFormatRules.Format.parse("97.1"));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Overlay \"b\" is missing formats")), result.errors().toString());
    }

    @Test
    void olderClientsFollowTheirOwnRules() {
        Map<String, Object> pack = Map.of("pack", Map.of("description", "x", "pack_format", 15L, "supported_formats", List.of(18L, 64L)));
        assertTrue(PackFormatRules.evaluate(pack, PackFormatRules.Format.parse("15")).compatible(), "1.20.1 reads only pack_format");
        PackFormatRules.Result ranged = PackFormatRules.evaluate(pack, PackFormatRules.Format.parse("64"));
        assertFalse(ranged.compatible(), "supported_formats without pack_format falls back to pack_format");
        assertFalse(PackFormatRules.evaluate(Map.of("pack", Map.of("description", "x", "pack_format", 18L)),
                PackFormatRules.Format.parse("15")).compatible());
    }

    @Test
    void laneValidationRendersEveryStateThroughCarrierAndItemModelAndKeepsVanillaElsewhere() throws IOException {
        AssetManifest manifest = manifest();
        SortedMap<String, byte[]> files = build(manifest);
        for (String format : List.of("15", "42", "46", "64", "97.1")) {
            PackFormatRules.Format client = PackFormatRules.Format.parse(format);
            PackValidator.Report report = PackValidator.lane(format, client, files, manifest, vanilla(client.major() >= 46));
            assertEquals(List.of(), report.errors(), format);
            assertTrue(report.clean(), format);
            assertEquals("allium:item/mob_disarmer/two_thirds", report.renders().get("golden_hoe cmd=2577021"), format);
            assertEquals("allium:item/mob_disarmer/broken", report.renders().get("golden_hoe cmd=2577024"), format);
            assertTrue(report.renders().get("golden_hoe cmd=2577025").endsWith("golden_hoe"), format + " closer");
            assertTrue(report.renders().get("golden_hoe cmd=none").endsWith("golden_hoe"), format);
            if (client.major() >= 42) {
                assertEquals("allium:item/mob_disarmer/one_third", report.renders().get("item_model allium:mob_disarmer cmd=2577022"), format);
            }
        }
    }

    @Test
    void validatorCatchesCollisionsUnusedFilesBrokenReferencesAndBadTextures() throws IOException {
        AssetManifest manifest = manifest();
        AssetManifest.Tool cuffs = manifest.tools().get(0);
        AssetManifest colliding = new AssetManifest(manifest.schema(), manifest.namespace(), manifest.pack(), manifest.customModelData(),
                manifest.carriers(), List.of(cuffs, new AssetManifest.Tool("extra", "allium:extra", "stick", "x", "planned",
                new AssetManifest.Range(2577015, 2577025), null, List.of(new AssetManifest.State("default", 2577019, null, null)))),
                manifest.textures());
        assertTrue(PackValidator.manifest(colliding).stream().anyMatch(e -> e.contains("collides with")), "reserved slots collide too");

        SortedMap<String, byte[]> files = build(manifest);
        files.put("assets/allium/textures/item/stray.png", files.get("assets/allium/textures/item/mob_disarmer/full.png"));
        assertTrue(PackValidator.structure(files, manifest).stream().anyMatch(e -> e.contains("stray.png is not referenced")));

        files = build(manifest);
        files.remove("assets/allium/textures/item/mob_disarmer/empty.png");
        PackValidator.Report report = PackValidator.lane("15", PackFormatRules.Format.parse("15"), files, manifest, vanilla(false));
        assertTrue(report.errors().stream().anyMatch(e -> e.contains("texture allium:item/mob_disarmer/empty does not resolve")), report.errors().toString());

        assertTrue(PackValidator.png(new byte[40]).isPresent());
        byte[] wide = files.get("assets/allium/textures/item/mob_disarmer/full.png").clone();
        wide[19] = 32; // width 32, height 16
        assertTrue(PackValidator.png(wide).orElseThrow().contains("32x16"));

        assertThrows(IOException.class, () -> CorePackBuilder.files(manifest, t -> new byte[]{1, 2, 3}, true),
                "textures must match their recorded provenance hash");
    }

    @Test
    void validatorRejectsAShadowedCarrierThatDriftsFromVanilla() throws IOException {
        AssetManifest manifest = manifest();
        SortedMap<String, byte[]> files = build(manifest);
        Map<String, String> drifted = new HashMap<>();
        drifted.put("assets/minecraft/models/item/golden_hoe.json", "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"minecraft:item/golden_hoe_v2\"}}");
        VanillaAssets future = new VanillaAssets("future", Set.of("minecraft:item/handheld"), Set.of("minecraft:item/golden_hoe_v2"), Set.of(), drifted);
        PackValidator.Report report = PackValidator.lane("15", PackFormatRules.Format.parse("15"), files, manifest, future);
        assertTrue(report.errors().stream().anyMatch(e -> e.contains("differs from vanilla")), report.errors().toString());
    }

    @Test
    void visualStateFollowsChargesAndBreakage() {
        assertEquals(DisarmerVisualState.FULL, DisarmerVisualState.of(DisarmerCharges.full(3), 3));
        assertEquals(DisarmerVisualState.TWO_THIRDS, DisarmerVisualState.of(new DisarmerCharges(2, 0L, false, 1L), 3));
        assertEquals(DisarmerVisualState.ONE_THIRD, DisarmerVisualState.of(new DisarmerCharges(1, 0L, false, 2L), 3));
        assertEquals(DisarmerVisualState.EMPTY, DisarmerVisualState.of(new DisarmerCharges(0, 99L, false, 3L), 3));
        assertEquals(DisarmerVisualState.BROKEN, DisarmerVisualState.of(new DisarmerCharges(2, 0L, true, 3L), 3));
        assertEquals(DisarmerVisualState.TWO_THIRDS, DisarmerVisualState.of(new DisarmerCharges(4, 0L, false, 1L), 5));
        assertEquals(DisarmerVisualState.ONE_THIRD, DisarmerVisualState.of(new DisarmerCharges(2, 0L, false, 1L), 5));
        assertEquals(DisarmerVisualState.ONE_THIRD, DisarmerVisualState.of(new DisarmerCharges(1, 0L, false, 1L), 5));
    }

    @Test
    void jsonRoundTripsAndRejectsDuplicateKeys() {
        Object value = Json.parse("{\"a\":[1,2.5,\"\\u00a76x\",true,null],\"b\":{}}");
        assertEquals("{\"a\":[1,2.5,\"\\u00a76x\",true,null],\"b\":{}}", Json.write(value));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} x"));
    }

    private static void assertError(Map<String, Object> pack, String fragment) {
        PackFormatRules.Result result = PackFormatRules.evaluate(Map.of("pack", pack), PackFormatRules.Format.parse("97.1"));
        assertFalse(result.compatible());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains(fragment)), fragment + " in " + result.errors());
    }
}
