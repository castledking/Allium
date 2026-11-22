package codes.castled.allium.pack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PackMergerTest {

    private static byte[] zipOf(Map<String, String> textEntries) throws Exception {
        SortedMap<String, byte[]> map = new TreeMap<>();
        textEntries.forEach((k, v) -> map.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return PackMerger.writeZip(map);
    }

    @Test
    void mergesPaperOverridesRangeDispatchFontsAndShadowedOverlayDeterministically() throws Exception {
        byte[] base = zipOf(Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"Allium\"},\"overlays\":{\"entries\":[{\"directory\":\"allium_item_definitions\",\"formats\":[46,97]}]}}",
                "assets/minecraft/models/item/fishing_rod.json",
                "{\"parent\":\"minecraft:item/handheld_rod\",\"overrides\":[{\"predicate\":{\"cast\":1},\"model\":\"minecraft:item/fishing_rod_cast\"},{\"predicate\":{\"custom_model_data\":1000},\"model\":\"allium:item/handcuffs\"}]}",
                "allium_item_definitions/assets/minecraft/items/fishing_rod.json",
                "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\",\"index\":0,\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/fishing_rod\"},\"entries\":[{\"threshold\":1000,\"model\":{\"type\":\"minecraft:model\",\"model\":\"allium:item/handcuffs\"}}]}}",
                "assets/curios/font/shared.json",
                "{\"providers\":[{\"type\":\"space\",\"advances\":{\"a\":-1}}]}"
        ));
        byte[] addon = zipOf(Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"Curios\"}}",
                "assets/minecraft/models/item/fishing_rod.json",
                "{\"parent\":\"minecraft:item/handheld_rod\",\"overrides\":[{\"predicate\":{\"custom_model_data\":2000},\"model\":\"curios:item/rod\"}]}",
                "assets/minecraft/items/fishing_rod.json",
                "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\",\"index\":0,\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/fishing_rod\"},\"entries\":[{\"threshold\":2000,\"model\":{\"type\":\"minecraft:model\",\"model\":\"curios:item/rod\"}}]}}",
                "assets/minecraft/models/item/paper.json",
                "{\"parent\":\"minecraft:item/generated\",\"overrides\":[{\"predicate\":{\"custom_model_data\":2680000},\"model\":\"curios:item/card_allay_simple\"}]}",
                "assets/minecraft/items/paper.json",
                "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\",\"index\":0,\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/paper\"},\"entries\":[{\"threshold\":2680000,\"model\":{\"type\":\"minecraft:model\",\"model\":\"curios:item/card_allay_simple\"}}]}}",
                "assets/curios/font/shared.json",
                "{\"providers\":[{\"type\":\"space\",\"advances\":{\"a\":-1}},{\"type\":\"space\",\"advances\":{\"b\":-2}}]}"
        ));

        PackMerger.Result first = PackMerger.merge(base, List.of(new PackMerger.Contribution("Curios", addon)));
        PackMerger.Result second = PackMerger.merge(base, List.of(new PackMerger.Contribution("Curios", addon)));
        assertArrayEquals(first.bytes(), second.bytes(), "merged pack must be byte-deterministic");
        assertTrue(first.warnings().isEmpty(), () -> "unexpected warnings: " + first.warnings());

        SortedMap<String, byte[]> out = PackMerger.readZip(first.bytes());
        // Base pack.mcmeta wins
        assertTrue(new String(out.get("pack.mcmeta"), StandardCharsets.UTF_8).contains("Allium"));
        // Fishing rod overrides preserve non-CMD cast override first, then sorted CMD overrides
        Map<String, Object> rodModel = Json.parseObject(new String(out.get("assets/minecraft/models/item/fishing_rod.json"), StandardCharsets.UTF_8));
        List<?> overrides = (List<?>) rodModel.get("overrides");
        assertEquals(3, overrides.size());
        // Shadowed overlay file receives addon's threshold 2000 entry too
        Map<String, Object> rodOverlay = Json.parseObject(new String(out.get("allium_item_definitions/assets/minecraft/items/fishing_rod.json"), StandardCharsets.UTF_8));
        Map<?, ?> rodDispatch = (Map<?, ?>) rodOverlay.get("model");
        assertEquals(2, ((List<?>) rodDispatch.get("entries")).size());
        // Paper added cleanly at root
        assertTrue(out.containsKey("assets/minecraft/models/item/paper.json"));
        assertTrue(out.containsKey("assets/minecraft/items/paper.json"));
        // Font providers concatenated and deduplicated
        Map<String, Object> font = Json.parseObject(new String(out.get("assets/curios/font/shared.json"), StandardCharsets.UTF_8));
        assertEquals(2, ((List<?>) font.get("providers")).size());
    }

    @Test
    void baseWinsAndWarnsOnConflictingOverridesOrBinaryAssets() throws Exception {
        byte[] base = zipOf(Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"Allium\"}}",
                "assets/minecraft/models/item/paper.json",
                "{\"overrides\":[{\"predicate\":{\"custom_model_data\":10},\"model\":\"allium:item/a\"}]}",
                "assets/shared/textures/icon.png", "BASE"
        ));
        byte[] addon = zipOf(Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"Curios\"}}",
                "assets/minecraft/models/item/paper.json",
                "{\"overrides\":[{\"predicate\":{\"custom_model_data\":10},\"model\":\"curios:item/b\"}]}",
                "assets/shared/textures/icon.png", "ADDON"
        ));

        PackMerger.Result result = PackMerger.merge(base, List.of(new PackMerger.Contribution("Curios", addon)));
        assertEquals(2, result.warnings().size(), () -> "warnings=" + result.warnings());
        SortedMap<String, byte[]> out = PackMerger.readZip(result.bytes());
        assertEquals("BASE", new String(out.get("assets/shared/textures/icon.png"), StandardCharsets.UTF_8));
        assertTrue(new String(out.get("assets/minecraft/models/item/paper.json"), StandardCharsets.UTF_8).contains("allium:item/a"));
    }
}
