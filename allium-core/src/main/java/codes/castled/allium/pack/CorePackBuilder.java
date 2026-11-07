package codes.castled.allium.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates the core resource pack from an {@link AssetManifest}. The same manifest and textures
 * always produce a byte-identical zip: files are sorted, stored uncompressed (no zlib
 * dependence), and carry one fixed timestamp and no extra fields.
 *
 * <p>Layout, for each ready tool on carrier {@code C} with states {@code S}:
 * <ul>
 *   <li>Base pack (1.20 to 1.21.3): {@code assets/minecraft/models/item/C.json} is the vanilla
 *       model plus {@code custom_model_data} overrides. Each Allium value selects its state model;
 *       the value after each run of Allium values closes the range back to a vanilla copy, so other
 *       plugins' higher values on the same carrier are not swallowed.
 *       {@code assets/<ns>/models/item/<tool>.json} is the 1.21.2+ {@code item_model} target.</li>
 *   <li>Overlay (1.21.4+): {@code assets/minecraft/items/C.json} keeps the vanilla item definition
 *       but dispatches the same values with {@code range_dispatch}; {@code assets/<ns>/items/<tool>.json}
 *       is the {@code item_model} target.</li>
 * </ul>
 * Charge states are model selections on one stack, never item swaps.
 */
public final class CorePackBuilder {

    /** Fixed entry time; DOS time has no zone, so this is the same everywhere. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    @FunctionalInterface
    public interface TextureSource {
        byte[] read(AssetManifest.Texture texture) throws IOException;
    }

    private CorePackBuilder() {
    }

    /**
     * @param verifyProvenance require each texture's bytes to match the manifest's sha256; off only
     *                         for local exports that substitute server-only art
     */
    public static SortedMap<String, byte[]> files(AssetManifest manifest, TextureSource textures, boolean verifyProvenance) throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        String ns = manifest.namespace();
        AssetManifest.PackSpec spec = manifest.pack();
        String overlay = spec.overlayDirectory() + "/";

        files.put("pack.mcmeta", json(packMcmeta(manifest)));

        Map<String, List<Dispatch>> byCarrier = new TreeMap<>();
        for (AssetManifest.Tool tool : manifest.readyTools()) {
            AssetManifest.State fallback = tool.defaultState();
            List<Map<String, Object>> overrides = new ArrayList<>();
            List<Object> entries = new ArrayList<>();
            for (AssetManifest.State state : tool.states()) {
                String model = stateModel(ns, tool, state);
                Map<String, Object> textureMap = new LinkedHashMap<>();
                textureMap.put("layer0", state.texture());
                Map<String, Object> stateModel = new LinkedHashMap<>();
                stateModel.put("parent", tool.modelParent());
                stateModel.put("textures", textureMap);
                files.put(modelPath(model), json(stateModel));

                AssetManifest.Texture texture = manifest.texture(state.texture())
                        .orElseThrow(() -> new IOException("no manifest texture " + state.texture()));
                byte[] png = textures.read(texture);
                if (verifyProvenance && !texture.sha256().equals(sha256(png))) {
                    throw new IOException(texture.file() + " does not match its recorded sha256; update the manifest's provenance");
                }
                files.put(texturePath(state.texture()), png);

                overrides.add(override(state.customModelData(), model));
                entries.add(entry(state.customModelData(), model));
                byCarrier.computeIfAbsent(tool.carrier(), c -> new ArrayList<>()).add(new Dispatch(state.customModelData(), model));
            }
            Map<String, Object> itemModel = new LinkedHashMap<>();
            itemModel.put("parent", stateModel(ns, tool, fallback));
            itemModel.put("overrides", overrides);
            files.put(modelPath(tool.legacyItemModelTarget(ns)), json(itemModel));

            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("model", rangeDispatch(entries, modelRef(stateModel(ns, tool, fallback))));
            files.put(overlay + itemDefinitionPath(tool.itemModelId(ns)), json(definition));
        }

        for (Map.Entry<String, List<Dispatch>> e : byCarrier.entrySet()) {
            String carrier = e.getKey();
            AssetManifest.Carrier vanilla = manifest.carriers().get(carrier);
            if (vanilla == null) {
                throw new IOException("carrier " + carrier + " has no vanilla model in the manifest");
            }
            String vanillaCopy = ns + ":item/vanilla/" + carrier;
            files.put(modelPath(vanillaCopy), json(vanilla.vanillaModel()));

            List<Dispatch> dispatch = withClosers(e.getValue());
            List<Map<String, Object>> overrides = new ArrayList<>();
            List<Object> entries = new ArrayList<>();
            Object vanillaItemModel = vanilla.vanillaItem().get("model");
            for (Dispatch d : dispatch) {
                overrides.add(override(d.value(), d.model() == null ? vanillaCopy : d.model()));
                entries.add(d.model() == null ? entry(d.value(), vanillaItemModel) : entry(d.value(), d.model()));
            }
            Map<String, Object> carrierModel = new LinkedHashMap<>(vanilla.vanillaModel());
            carrierModel.put("overrides", overrides);
            files.put("assets/minecraft/models/item/" + carrier + ".json", json(carrierModel));

            Map<String, Object> carrierItem = new LinkedHashMap<>(vanilla.vanillaItem());
            carrierItem.put("model", rangeDispatch(entries, vanillaItemModel));
            files.put(overlay + "assets/minecraft/items/" + carrier + ".json", json(carrierItem));
        }
        return files;
    }

    /** A value and the model it selects; {@code model == null} closes back to vanilla. */
    record Dispatch(int value, String model) {
    }

    static List<Dispatch> withClosers(List<Dispatch> used) {
        List<Dispatch> sorted = new ArrayList<>(used);
        sorted.sort(Comparator.comparingInt(Dispatch::value));
        TreeSet<Integer> values = new TreeSet<>();
        sorted.forEach(d -> values.add(d.value()));
        List<Dispatch> out = new ArrayList<>();
        for (Dispatch d : sorted) {
            out.add(d);
            if (!values.contains(d.value() + 1)) {
                out.add(new Dispatch(d.value() + 1, null));
            }
        }
        return out;
    }

    static Map<String, Object> packMcmeta(AssetManifest manifest) {
        AssetManifest.PackSpec spec = manifest.pack();
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("description", spec.description());
        pack.put("pack_format", spec.baseFormat());
        // Formats up to 64 still read supported_formats; 26.3 requires it to match min/max (or end at 64).
        pack.put("supported_formats", List.of(spec.baseFormat(), spec.maxFormat()));
        pack.put("min_format", spec.baseFormat());
        pack.put("max_format", spec.maxFormat());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("directory", spec.overlayDirectory());
        entry.put("formats", List.of(spec.overlayMinFormat(), spec.maxFormat()));
        entry.put("min_format", spec.overlayMinFormat());
        entry.put("max_format", spec.maxFormat());
        Map<String, Object> overlays = new LinkedHashMap<>();
        overlays.put("entries", List.of(entry));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pack", pack);
        root.put("overlays", overlays);
        return root;
    }

    public static byte[] zip(SortedMap<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            out.setMethod(ZipOutputStream.STORED);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                byte[] data = file.getValue();
                CRC32 crc = new CRC32();
                crc.update(data);
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                entry.setCrc(crc.getValue());
                entry.setTimeLocal(ENTRY_TIME);
                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    public static String stateModel(String ns, AssetManifest.Tool tool, AssetManifest.State state) {
        return ns + ":item/" + tool.id() + "/" + state.key();
    }

    public static String modelPath(String id) {
        int colon = id.indexOf(':');
        return "assets/" + id.substring(0, colon) + "/models/" + id.substring(colon + 1) + ".json";
    }

    public static String texturePath(String id) {
        int colon = id.indexOf(':');
        return "assets/" + id.substring(0, colon) + "/textures/" + id.substring(colon + 1) + ".png";
    }

    public static String itemDefinitionPath(String id) {
        int colon = id.indexOf(':');
        return "assets/" + id.substring(0, colon) + "/items/" + id.substring(colon + 1) + ".json";
    }

    public static String sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    public static String sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> override(int value, String model) {
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("custom_model_data", value);
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("predicate", predicate);
        override.put("model", model);
        return override;
    }

    private static Map<String, Object> entry(int threshold, Object model) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("threshold", threshold);
        entry.put("model", model instanceof String id ? modelRef(id) : model);
        return entry;
    }

    private static Map<String, Object> modelRef(String id) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", "minecraft:model");
        ref.put("model", id);
        return ref;
    }

    private static Map<String, Object> rangeDispatch(List<Object> entries, Object fallback) {
        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("type", "minecraft:range_dispatch");
        dispatch.put("property", "minecraft:custom_model_data");
        dispatch.put("entries", entries);
        dispatch.put("fallback", fallback);
        return dispatch;
    }

    private static byte[] json(Object value) {
        return (Json.write(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
