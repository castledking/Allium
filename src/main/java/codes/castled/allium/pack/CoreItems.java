package codes.castled.allium.pack;

import codes.castled.allium.item.AlliumItemDefinition;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Built-in core items, defined by the bundled asset manifest ({@code pack/manifest.json}): the
 * carrier item, the default state's {@code custom_model_data} and the vanilla-fallback name come
 * from the manifest, so the pack and the stamped items cannot drift apart. Behavior flags stay in
 * code.
 */
public final class CoreItems {

    public static final String MANIFEST_RESOURCE = "pack/manifest.json";
    public static final String PACK_RESOURCE = "pack/allium-core-pack.zip";
    public static final String PACK_INFO_RESOURCE = "pack/allium-core-pack.zip.json";

    private CoreItems() {
    }

    public static AssetManifest loadManifest(ClassLoader loader) throws IOException {
        try (InputStream in = loader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                throw new IOException(MANIFEST_RESOURCE + " is missing from the plugin jar");
            }
            return AssetManifest.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static List<AlliumItemDefinition> definitions(AssetManifest manifest) {
        return List.of(
                definition(manifest, "handcuffs", true, true, Map.of()),
                definition(manifest, "mob_disarmer", false, true, Map.of()),
                definition(manifest, "phantom_obliterator", true, true, Map.of("charges", "3")),
                definition(manifest, "item_renamer", false, false, Map.of()),
                definition(manifest, "lazy_axe", false, true, Map.of()));
    }

    private static AlliumItemDefinition definition(AssetManifest manifest, String id, boolean privileged, boolean signed, Map<String, String> state) {
        AssetManifest.Tool tool = manifest.tool(id)
                .orElseThrow(() -> new IllegalArgumentException("the core pack manifest has no tool " + id));
        Material carrier = Material.matchMaterial(tool.carrier());
        if (carrier == null || !carrier.isItem() || carrier.isAir()) {
            throw new IllegalArgumentException("core tool " + id + " has unknown carrier " + tool.carrier());
        }
        return new AlliumItemDefinition(id, carrier, tool.behavior(), privileged,
                tool.defaultState().customModelData(), tool.name(), state, signed);
    }
}
