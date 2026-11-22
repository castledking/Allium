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
    private static final String DIAMOND_SWORD_MODEL = "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"minecraft:item/diamond_sword\"}}";
    private static final String DIAMOND_SWORD_ITEM = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/diamond_sword\"}}";
    private static final String PANE_MODEL = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:block/light_gray_stained_glass\"}}";
    private static final String PANE_ITEM = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/light_gray_stained_glass_pane\"}}";

    private static AssetManifest manifest() throws IOException {
        return AssetManifest.parse(Files.readString(ROOT.resolve("manifest.json")));
    }

    private static SortedMap<String, byte[]> build(AssetManifest manifest) throws IOException {
        return CorePackBuilder.files(manifest, t -> Files.readAllBytes(ROOT.resolve(t.file())), true);
    }

    /** The vanilla files the pack touches, identical on every validated lane (checked live against client jars). */
    private static VanillaAssets vanilla(boolean itemDefinitions) {
        Map<String, String> files = new HashMap<>();
        files.put("assets/minecraft/models/item/diamond_sword.json", DIAMOND_SWORD_MODEL);
        files.put("assets/minecraft/models/item/light_gray_stained_glass_pane.json", PANE_MODEL);
        if (itemDefinitions) {
            files.put("assets/minecraft/items/diamond_sword.json", DIAMOND_SWORD_ITEM);
            files.put("assets/minecraft/items/light_gray_stained_glass_pane.json", PANE_ITEM);
        }
        return new VanillaAssets("test-vanilla",
                Set.of("minecraft:item/handheld", "minecraft:item/generated", "minecraft:item/diamond_sword",
                        "minecraft:item/light_gray_stained_glass_pane"),
                Set.of("minecraft:item/diamond_sword", "minecraft:block/light_gray_stained_glass"),
                itemDefinitions ? Set.of("minecraft:diamond_sword", "minecraft:light_gray_stained_glass_pane") : Set.of(),
                files);
    }

    @Test
    void manifestAllocatesEveryCoreToolInsideTheBlockWithoutCollisions() throws IOException {
        AssetManifest manifest = manifest();
        assertEquals(List.of(), PackValidator.manifest(manifest));
        assertEquals(List.of("handcuffs", "mob_disarmer", "phantom_obliterator", "item_renamer", "lazy_axe", "claim_handcuffs", "gui_slot"),
                manifest.tools().stream().map(AssetManifest.Tool::id).toList());
        AssetManifest.Tool disarmer = manifest.tool("mob_disarmer").orElseThrow();
        assertTrue(disarmer.ready());
        assertEquals("diamond_sword", disarmer.carrier());
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
        assertTrue(first.containsKey("allium_item_definitions/assets/minecraft/items/diamond_sword.json"));
        assertFalse(first.keySet().stream().anyMatch(p -> p.contains("netherite_sword") || p.contains("fishing_rod") || p.contains("name_tag") || p.contains("netherite_axe")),
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
            assertEquals("allium:item/mob_disarmer/two_thirds", report.renders().get("diamond_sword cmd=2577021"), format);
            assertEquals("allium:item/mob_disarmer/broken", report.renders().get("diamond_sword cmd=2577024"), format);
            assertTrue(report.renders().get("diamond_sword cmd=2577025").endsWith("diamond_sword"), format + " closer");
            assertTrue(report.renders().get("diamond_sword cmd=none").endsWith("diamond_sword"), format);
            if (client.major() >= 42) {
                assertEquals("allium:item/mob_disarmer/one_third", report.renders().get("item_model allium:mob_disarmer cmd=2577022"), format);
            }
        }
    }

    /** The manifest with both handcuffs ready, drawn with a committed texture, as sf_export.py makes them with SF's art. */
    @SuppressWarnings("unchecked")
    private static AssetManifest readyHandcuffs() throws IOException {
        Map<String, Object> root = Json.parseObject(Files.readString(ROOT.resolve("manifest.json")));
        for (Object entry : (List<Object>) root.get("tools")) {
            Map<String, Object> tool = (Map<String, Object>) entry;
            if (List.of("handcuffs", "claim_handcuffs").contains(tool.get("id"))) {
                tool.put("status", "ready");
                for (Object state : (List<Object>) tool.get("states")) {
                    ((Map<String, Object>) state).put("texture", "allium:item/mob_disarmer/full");
                }
            }
        }
        return AssetManifest.parse(Json.write(root));
    }

    /** 1.20.1 writes the rod without namespaces and with its cast override; 1.21.4+ moved the cast look to the item definition. */
    private static VanillaAssets vanillaRod(boolean itemDefinitions) {
        Map<String, String> files = new HashMap<>();
        files.put("assets/minecraft/models/item/diamond_sword.json", DIAMOND_SWORD_MODEL);
        files.put("assets/minecraft/models/item/light_gray_stained_glass_pane.json", PANE_MODEL);
        if (itemDefinitions) {
            files.put("assets/minecraft/models/item/fishing_rod.json",
                    "{\"parent\":\"minecraft:item/handheld_rod\",\"textures\":{\"layer0\":\"minecraft:item/fishing_rod\"}}");
            files.put("assets/minecraft/items/fishing_rod.json", "{\"model\":{\"type\":\"minecraft:condition\",\"on_false\":{\"type\":"
                    + "\"minecraft:model\",\"model\":\"minecraft:item/fishing_rod\"},\"on_true\":{\"type\":\"minecraft:model\",\"model\":"
                    + "\"minecraft:item/fishing_rod_cast\"},\"property\":\"minecraft:fishing_rod/cast\"}}");
            files.put("assets/minecraft/items/diamond_sword.json", DIAMOND_SWORD_ITEM);
            files.put("assets/minecraft/items/light_gray_stained_glass_pane.json", PANE_ITEM);
        } else {
            files.put("assets/minecraft/models/item/fishing_rod.json", "{\"parent\":\"item/handheld_rod\",\"textures\":{\"layer0\":"
                    + "\"item/fishing_rod\"},\"overrides\":[{\"predicate\":{\"cast\":1},\"model\":\"item/fishing_rod_cast\"}]}");
        }
        return new VanillaAssets("test-vanilla-rod",
                Set.of("minecraft:item/handheld", "minecraft:item/generated", "minecraft:item/diamond_sword",
                        "minecraft:item/light_gray_stained_glass_pane", "minecraft:item/handheld_rod", "minecraft:item/fishing_rod",
                        "minecraft:item/fishing_rod_cast"),
                Set.of("minecraft:item/diamond_sword", "minecraft:block/light_gray_stained_glass", "minecraft:item/fishing_rod"),
                itemDefinitions ? Set.of("minecraft:diamond_sword", "minecraft:light_gray_stained_glass_pane", "minecraft:fishing_rod") : Set.of(),
                files);
    }

    @Test
    void handcuffStatesShareTheFishingRodAndPlainRodsKeepTheirCastLook() throws IOException {
        AssetManifest manifest = readyHandcuffs();
        assertEquals(List.of(), PackValidator.manifest(manifest, true));
        SortedMap<String, byte[]> files = build(manifest);
        Map<String, Object> rod = Json.parseObject(new String(files.get("assets/minecraft/models/item/fishing_rod.json"), StandardCharsets.UTF_8));
        List<?> overrides = (List<?>) rod.get("overrides");
        assertEquals(Map.of("predicate", Map.of("cast", 1L), "model", "item/fishing_rod_cast"), overrides.get(0),
                "vanilla's cast override comes first, so Allium's values still win");
        assertEquals("item/handheld_rod", rod.get("parent"));
        Map<String, Object> item = Json.parseObject(new String(files.get("allium_item_definitions/assets/minecraft/items/fishing_rod.json"),
                StandardCharsets.UTF_8));
        assertEquals("minecraft:condition", ((Map<?, ?>) ((Map<?, ?>) item.get("model")).get("fallback")).get("type"),
                "plain rods fall back to vanilla's cast condition");

        for (String format : List.of("15", "42", "46", "97.1")) {
            PackFormatRules.Format client = PackFormatRules.Format.parse(format);
            PackValidator.Report report = PackValidator.lane(format, client, files, manifest, vanillaRod(client.major() >= 46));
            assertEquals(List.of(), report.errors(), format);
            assertEquals("allium:item/handcuffs/default", report.renders().get("fishing_rod cmd=2577010"), format);
            assertEquals("allium:item/handcuffs/restrained", report.renders().get("fishing_rod cmd=2577011"), format);
            assertEquals("allium:item/claim_handcuffs/restrained", report.renders().get("fishing_rod cmd=2577061"), format);
            assertTrue(report.renders().get("fishing_rod cmd=none").endsWith("item/fishing_rod"), format);
        }

        rod.put("overrides", overrides.subList(1, overrides.size()));
        files.put("assets/minecraft/models/item/fishing_rod.json", Json.write(rod).getBytes(StandardCharsets.UTF_8));
        PackValidator.Report dropped = PackValidator.lane("15", PackFormatRules.Format.parse("15"), files, manifest, vanillaRod(false));
        assertTrue(dropped.errors().stream().anyMatch(e -> e.contains("drops vanilla's own overrides")), dropped.errors().toString());
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
    void serverOnlyArtIsRefusedInTheCommittedManifestButAllowedInALocalExport() throws IOException {
        AssetManifest manifest = manifest();
        List<AssetManifest.Texture> textures = new java.util.ArrayList<>(manifest.textures());
        AssetManifest.Texture first = textures.get(0);
        textures.set(0, new AssetManifest.Texture(first.id(), first.file(), first.sha256(), "SF", "$SF/...", "server-only", false));
        AssetManifest serverOnly = new AssetManifest(manifest.schema(), manifest.namespace(), manifest.pack(), manifest.customModelData(),
                manifest.carriers(), manifest.tools(), textures);
        assertTrue(PackValidator.manifest(serverOnly).stream().anyMatch(e -> e.contains("is not distributable")));
        assertEquals(List.of(), PackValidator.manifest(serverOnly, true), "sf_export.py's derived manifest is never committed");
    }

    @Test
    void validatorRejectsAShadowedCarrierThatDriftsFromVanilla() throws IOException {
        AssetManifest manifest = manifest();
        SortedMap<String, byte[]> files = build(manifest);
        Map<String, String> drifted = new HashMap<>();
        drifted.put("assets/minecraft/models/item/diamond_sword.json", "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"minecraft:item/diamond_sword_v2\"}}");
        VanillaAssets future = new VanillaAssets("future", Set.of("minecraft:item/handheld"), Set.of("minecraft:item/diamond_sword_v2"), Set.of(), drifted);
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

    @Test
    void packIconAndDescriptionValidateCleanly() throws IOException {
        AssetManifest manifest = manifest();
        assertEquals("§dAllium Resources §7v1.0", manifest.pack().description());
        SortedMap<String, byte[]> files = build(manifest);
        files.put("pack.png", Files.readAllBytes(ROOT.resolve("pack.png")));
        assertEquals(List.of(), PackValidator.structure(files, manifest));
        files.put("pack.png", "not-a-png".getBytes(StandardCharsets.UTF_8));
        assertTrue(PackValidator.structure(files, manifest).stream().anyMatch(e -> e.contains("pack.png")));
    }

    private static void assertError(Map<String, Object> pack, String fragment) {
        PackFormatRules.Result result = PackFormatRules.evaluate(Map.of("pack", pack), PackFormatRules.Format.parse("97.1"));
        assertFalse(result.compatible());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains(fragment)), fragment + " in " + result.errors());
    }
}
