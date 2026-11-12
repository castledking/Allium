package codes.castled.allium.pack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validates a generated core pack.
 *
 * <p>{@link #structure} is lane-independent: every JSON file parses, every PNG is a valid square
 * texture, the manifest's allocations are disjoint and inside the reserved block, every file is
 * reachable from an entry point, and dispatch tables hold only manifest values and their closers.
 * {@link #lane} checks one client version: {@code pack.mcmeta} as that client reads it, the
 * effective files after its overlays, every model/texture reference against the pack and that
 * version's vanilla assets, vanilla parity of every replaced carrier file, and a render
 * simulation of each allocation through both the carrier and {@code item_model} paths.
 */
public final class PackValidator {

    /** Floats hold every integer up to 2^24 exactly; range_dispatch compares floats. */
    public static final int FLOAT_EXACT_LIMIT = 1 << 24;

    private PackValidator() {
    }

    public record Report(String lane, PackFormatRules.Result format, List<String> errors, List<String> notes, Map<String, String> renders) {
        public Report {
            errors = List.copyOf(errors);
            notes = List.copyOf(notes);
            renders = Map.copyOf(renders);
        }

        public boolean clean() {
            return errors.isEmpty() && format.compatible();
        }
    }

    public static SortedMap<String, byte[]> unzip(byte[] zip) throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && files.put(entry.getName(), in.readAllBytes()) != null) {
                    throw new IOException("duplicate zip entry " + entry.getName());
                }
            }
        }
        return files;
    }

    // ---------------------------------------------------------------- lane-independent

    public static List<String> structure(SortedMap<String, byte[]> files, AssetManifest manifest) {
        return structure(files, manifest, false);
    }

    /**
     * @param localExport a local server-only export ({@code build --texture-root}): its derived
     *                    manifest may name non-distributable textures, since it is never committed
     */
    public static List<String> structure(SortedMap<String, byte[]> files, AssetManifest manifest, boolean localExport) {
        List<String> errors = new ArrayList<>(manifest(manifest, localExport));
        Map<String, Object> parsed = new HashMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String path = file.getKey();
            if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
                try {
                    parsed.put(path, Json.parse(new String(file.getValue(), StandardCharsets.UTF_8)));
                } catch (IllegalArgumentException e) {
                    errors.add(path + ": " + e.getMessage());
                }
            } else if (path.endsWith(".png")) {
                png(file.getValue()).ifPresent(problem -> errors.add(path + ": " + problem));
            } else {
                errors.add(path + ": unexpected file type");
            }
        }
        if (!files.containsKey("pack.mcmeta")) {
            errors.add("pack.mcmeta is missing");
        }
        reachability(files, parsed, manifest, errors);
        dispatchValues(files, parsed, manifest, errors);
        return errors;
    }

    /** Manifest rules: allocations are disjoint (used and reserved), inside the block, and ready tools are complete. */
    public static List<String> manifest(AssetManifest manifest) {
        return manifest(manifest, false);
    }

    public static List<String> manifest(AssetManifest manifest, boolean localExport) {
        List<String> errors = new ArrayList<>();
        AssetManifest.Range block = manifest.customModelData();
        if (block.first() < 1 || block.last() >= FLOAT_EXACT_LIMIT) {
            errors.add("customModelData block " + block + " must lie in 1.." + (FLOAT_EXACT_LIMIT - 1) + " so floats hold every value");
        }
        Set<String> ids = new HashSet<>();
        Set<String> behaviors = new HashSet<>();
        Set<String> textureIds = new HashSet<>();
        for (AssetManifest.Texture texture : manifest.textures()) {
            if (!textureIds.add(texture.id())) {
                errors.add("texture " + texture.id() + " is listed twice");
            }
            if (!texture.file().equals(CorePackBuilder.texturePath(texture.id()))) {
                errors.add("texture " + texture.id() + " must live at " + CorePackBuilder.texturePath(texture.id()));
            }
            if (!texture.distributable() && !localExport) {
                errors.add("texture " + texture.id() + " is not distributable; server-only art belongs in a local export, not the manifest");
            }
        }
        List<AssetManifest.Tool> tools = manifest.tools();
        for (int i = 0; i < tools.size(); i++) {
            AssetManifest.Tool tool = tools.get(i);
            if (!ids.add(tool.id())) {
                errors.add("tool " + tool.id() + " is listed twice");
            }
            if (!behaviors.add(tool.behavior())) {
                errors.add("behavior " + tool.behavior() + " is listed twice");
            }
            if (block.first() > tool.allocation().first() || block.last() < tool.allocation().last()) {
                errors.add(tool.id() + " allocation " + tool.allocation() + " is outside the block " + block);
            }
            for (int j = i + 1; j < tools.size(); j++) {
                AssetManifest.Tool other = tools.get(j);
                if (tool.allocation().overlaps(other.allocation())) {
                    errors.add(tool.id() + " allocation " + tool.allocation() + " collides with " + other.id() + " " + other.allocation());
                }
            }
            Set<String> keys = new HashSet<>();
            Set<Integer> values = new HashSet<>();
            for (AssetManifest.State state : tool.states()) {
                if (!keys.add(state.key())) {
                    errors.add(tool.id() + " state " + state.key() + " is listed twice");
                }
                if (!values.add(state.customModelData())) {
                    errors.add(tool.id() + " uses " + state.customModelData() + " twice");
                }
                if (!tool.allocation().contains(state.customModelData())) {
                    errors.add(tool.id() + " state " + state.key() + " value " + state.customModelData() + " is outside its allocation " + tool.allocation());
                }
                if (tool.ready()) {
                    if (state.texture() == null) {
                        errors.add(tool.id() + " state " + state.key() + " has no texture");
                    } else if (manifest.texture(state.texture()).isEmpty()) {
                        errors.add(tool.id() + " state " + state.key() + " texture " + state.texture() + " has no provenance entry");
                    }
                }
            }
            if (tool.ready()) {
                if (!manifest.carriers().containsKey(tool.carrier())) {
                    errors.add(tool.id() + " carrier " + tool.carrier() + " has no vanilla files in the manifest");
                }
                if (tool.modelParent() == null) {
                    errors.add(tool.id() + " needs a modelParent");
                }
            }
        }
        return errors;
    }

    private static void reachability(SortedMap<String, byte[]> files, Map<String, Object> parsed, AssetManifest manifest, List<String> errors) {
        String ns = manifest.namespace();
        String overlay = manifest.pack().overlayDirectory() + "/";
        Deque<String> pending = new ArrayDeque<>();
        Set<String> reached = new HashSet<>();
        pending.add("pack.mcmeta");
        for (AssetManifest.Tool tool : manifest.readyTools()) {
            pending.add("assets/minecraft/models/item/" + tool.carrier() + ".json");
            pending.add(overlay + "assets/minecraft/items/" + tool.carrier() + ".json");
            pending.add(CorePackBuilder.modelPath(tool.legacyItemModelTarget(ns)));
            pending.add(overlay + CorePackBuilder.itemDefinitionPath(tool.itemModelId(ns)));
        }
        while (!pending.isEmpty()) {
            String path = pending.poll();
            if (!reached.add(path)) {
                continue;
            }
            if (!files.containsKey(path)) {
                errors.add("entry point or reference " + path + " is missing from the pack");
                continue;
            }
            Object json = parsed.get(path);
            if (json == null) {
                continue;
            }
            for (String model : modelReferences(json)) {
                String modelPath = CorePackBuilder.modelPath(normalize(model));
                if (files.containsKey(modelPath)) pending.add(modelPath);
            }
            for (String texture : textureReferences(json)) {
                String texturePath = CorePackBuilder.texturePath(normalize(texture));
                if (files.containsKey(texturePath)) pending.add(texturePath);
            }
        }
        for (String path : files.keySet()) {
            if (!reached.contains(path)) {
                errors.add(path + " is not referenced by any entry point (unused file)");
            }
        }
    }

    /** Dispatch tables may hold only manifest values and, after each run, one closing value. */
    private static void dispatchValues(SortedMap<String, byte[]> files, Map<String, Object> parsed, AssetManifest manifest, List<String> errors) {
        Map<String, Set<Integer>> used = new HashMap<>();
        for (AssetManifest.Tool tool : manifest.readyTools()) {
            for (AssetManifest.State state : tool.states()) {
                used.computeIfAbsent(tool.carrier(), c -> new HashSet<>()).add(state.customModelData());
            }
        }
        String overlay = manifest.pack().overlayDirectory() + "/";
        for (Map.Entry<String, Set<Integer>> e : used.entrySet()) {
            Set<Integer> values = e.getValue();
            for (String path : List.of("assets/minecraft/models/item/" + e.getKey() + ".json", overlay + "assets/minecraft/items/" + e.getKey() + ".json")) {
                Object json = parsed.get(path);
                if (json == null) {
                    continue;
                }
                List<Integer> found = dispatchedValues(json);
                Set<Integer> seen = new HashSet<>();
                for (int value : found) {
                    if (!seen.add(value)) {
                        errors.add(path + " dispatches " + value + " twice");
                    }
                    boolean closer = !values.contains(value) && values.contains(value - 1);
                    if (!values.contains(value) && !closer) {
                        errors.add(path + " dispatches " + value + ", which no manifest state owns");
                    }
                }
                for (int value : values) {
                    if (!seen.contains(value)) errors.add(path + " does not dispatch manifest value " + value);
                    if (!values.contains(value + 1) && !seen.contains(value + 1)) errors.add(path + " has no closing value after " + value);
                }
            }
        }
    }

    // ---------------------------------------------------------------- one lane

    public static Report lane(String lane, PackFormatRules.Format client, SortedMap<String, byte[]> files, AssetManifest manifest, VanillaAssets vanilla) {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, String> renders = new LinkedHashMap<>();
        Map<String, Object> mcmeta;
        try {
            mcmeta = Json.parseObject(new String(files.getOrDefault("pack.mcmeta", new byte[0]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            errors.add("pack.mcmeta: " + e.getMessage());
            return new Report(lane, new PackFormatRules.Result(false, "none", List.of(), List.of(e.getMessage()), List.of()), errors, notes, renders);
        }
        PackFormatRules.Result format = PackFormatRules.evaluate(mcmeta, client);
        errors.addAll(format.errors());
        notes.addAll(format.notes());
        View view = new View(files, format.overlays(), vanilla, client);

        for (String path : view.paths()) {
            Optional<String> modelId = VanillaAssets.resourceId(path, "models", ".json");
            Optional<String> itemId = VanillaAssets.resourceId(path, "items", ".json");
            if (modelId.isPresent()) {
                checkModel(path, view, errors, notes);
            } else if (itemId.isPresent()) {
                if (client.major() < 46) {
                    notes.add(path + " is not read before format 46");
                } else {
                    for (String ref : modelReferences(view.json(path))) {
                        if (!view.hasModel(normalize(ref))) errors.add(path + " references missing model " + ref);
                    }
                }
            }
        }

        String ns = manifest.namespace();
        for (AssetManifest.Tool tool : manifest.readyTools()) {
            String carrier = tool.carrier();
            parity(carrier, manifest, view, vanilla, client, errors);
            String vanillaModel = "minecraft:item/" + carrier;
            for (AssetManifest.State state : tool.states()) {
                String expected = CorePackBuilder.stateModel(ns, tool, state);
                String viaCarrier = view.renderCarrier(carrier, state.customModelData());
                renders.put(carrier + " cmd=" + state.customModelData(), viaCarrier);
                if (!expected.equals(viaCarrier)) {
                    errors.add(carrier + " with custom_model_data " + state.customModelData() + " renders " + viaCarrier + ", expected " + expected);
                }
                if (client.major() >= 42) {
                    String viaItemModel = view.renderItemModel(tool.itemModelId(ns), state.customModelData());
                    renders.put("item_model " + tool.itemModelId(ns) + " cmd=" + state.customModelData(), viaItemModel);
                    if (!expected.equals(viaItemModel)) {
                        errors.add("item_model " + tool.itemModelId(ns) + " with custom_model_data " + state.customModelData()
                                + " renders " + viaItemModel + ", expected " + expected);
                    }
                }
            }
            int first = tool.states().stream().mapToInt(AssetManifest.State::customModelData).min().orElseThrow();
            int last = tool.states().stream().mapToInt(AssetManifest.State::customModelData).max().orElseThrow();
            for (Integer probe : Arrays.asList(null, 1, first - 1, last + 1, manifest.customModelData().last() + 1, FLOAT_EXACT_LIMIT - 1)) {
                String rendered = view.renderCarrier(carrier, probe);
                renders.put(carrier + " cmd=" + (probe == null ? "none" : probe), rendered);
                if (!view.vanillaEquivalent(carrier, rendered)) {
                    errors.add(carrier + " with custom_model_data " + probe + " renders " + rendered + " instead of vanilla " + vanillaModel);
                }
            }
        }
        return new Report(lane, format, errors, notes, renders);
    }

    private static void checkModel(String path, View view, List<String> errors, List<String> notes) {
        Object json = view.json(path);
        if (!(json instanceof Map<?, ?> model)) {
            errors.add(path + " is not a JSON object");
            return;
        }
        Object parent = model.get("parent");
        if (parent instanceof String p && !view.hasModel(normalize(p))) {
            errors.add(path + " parent " + p + " does not resolve");
        }
        if (model.get("textures") instanceof Map<?, ?> textures) {
            for (Object value : textures.values()) {
                if (value instanceof String t && !t.startsWith("#") && !view.hasTexture(normalize(t))) {
                    errors.add(path + " texture " + t + " does not resolve");
                }
            }
        }
        if (model.get("overrides") instanceof List<?> overrides) {
            if (view.client.major() >= 46) {
                notes.add(path + " overrides are ignored from format 46 (kept for 1.20 to 1.21.3)");
            } else {
                for (Object o : overrides) {
                    if (o instanceof Map<?, ?> override && override.get("model") instanceof String m && !view.hasModel(normalize(m))) {
                        errors.add(path + " override model " + m + " does not resolve");
                    }
                }
            }
        }
    }

    /** Replaced carrier files must equal this version's vanilla files apart from the dispatch. */
    private static void parity(String carrier, AssetManifest manifest, View view, VanillaAssets vanilla, PackFormatRules.Format client, List<String> errors) {
        String modelPath = "assets/minecraft/models/item/" + carrier + ".json";
        Optional<String> vanillaModelText = vanilla.file(modelPath);
        if (vanillaModelText.isEmpty()) {
            errors.add(vanilla.label() + " has no " + modelPath);
            return;
        }
        Object vanillaModel = Json.parse(vanillaModelText.get());
        AssetManifest.Carrier recorded = manifest.carriers().get(carrier);
        if (!vanillaModel.equals(recorded.vanillaModel())) {
            errors.add("manifest vanillaModel for " + carrier + " differs from " + vanilla.label() + ": " + Json.write(vanillaModel));
        }
        if (view.json(modelPath) instanceof Map<?, ?> packModel) {
            Map<Object, Object> stripped = new LinkedHashMap<>(packModel);
            stripped.remove("overrides");
            if (!stripped.equals(vanillaModel)) {
                errors.add(modelPath + " differs from vanilla beyond its overrides");
            }
        }
        String copy = CorePackBuilder.modelPath(manifest.namespace() + ":item/vanilla/" + carrier);
        if (view.has(copy) && !Objects.equals(view.json(copy), vanillaModel)) {
            errors.add(copy + " is not an exact copy of vanilla " + modelPath);
        }
        if (client.major() >= 46) {
            String itemPath = "assets/minecraft/items/" + carrier + ".json";
            Optional<String> vanillaItemText = vanilla.file(itemPath);
            if (vanillaItemText.isEmpty()) {
                errors.add(vanilla.label() + " has no " + itemPath);
                return;
            }
            Map<String, Object> vanillaItem = Json.parseObject(vanillaItemText.get());
            if (!vanillaItem.equals(recorded.vanillaItem())) {
                errors.add("manifest vanillaItem for " + carrier + " differs from " + vanilla.label() + ": " + Json.write(vanillaItem));
            }
            if (view.json(itemPath) instanceof Map<?, ?> packItem) {
                Map<Object, Object> others = new LinkedHashMap<>(packItem);
                Object dispatch = others.remove("model");
                Map<Object, Object> vanillaOthers = new LinkedHashMap<>(vanillaItem);
                Object vanillaDispatch = vanillaOthers.remove("model");
                if (!others.equals(vanillaOthers)) {
                    errors.add(itemPath + " changes vanilla item definition fields other than model");
                }
                if (!(dispatch instanceof Map<?, ?> d) || !Objects.equals(d.get("fallback"), vanillaDispatch)) {
                    errors.add(itemPath + " fallback is not the vanilla model definition");
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The files one client sees: base files with applicable overlays on top (later overlays win). */
    private static final class View {
        private final Map<String, byte[]> files = new HashMap<>();
        private final Map<String, Object> parsed = new HashMap<>();
        private final VanillaAssets vanilla;
        private final PackFormatRules.Format client;

        View(SortedMap<String, byte[]> all, List<String> overlays, VanillaAssets vanilla, PackFormatRules.Format client) {
            this.vanilla = vanilla;
            this.client = client;
            Set<String> overlayPrefixes = new HashSet<>();
            for (String path : all.keySet()) {
                int slash = path.indexOf('/');
                if (slash > 0 && !path.startsWith("assets/")) overlayPrefixes.add(path.substring(0, slash + 1));
            }
            for (Map.Entry<String, byte[]> e : all.entrySet()) {
                boolean inOverlay = overlayPrefixes.stream().anyMatch(e.getKey()::startsWith);
                if (!inOverlay) files.put(e.getKey(), e.getValue());
            }
            for (String overlay : overlays) {
                String prefix = overlay + "/";
                for (Map.Entry<String, byte[]> e : all.entrySet()) {
                    if (e.getKey().startsWith(prefix)) files.put(e.getKey().substring(prefix.length()), e.getValue());
                }
            }
        }

        List<String> paths() {
            return files.keySet().stream().sorted().toList();
        }

        boolean has(String path) {
            return files.containsKey(path);
        }

        Object json(String path) {
            return parsed.computeIfAbsent(path, p -> {
                byte[] data = files.get(p);
                if (data != null) return Json.parse(new String(data, StandardCharsets.UTF_8));
                return vanilla.file(p).map(Json::parse).orElse(null);
            });
        }

        boolean hasModel(String id) {
            return id.startsWith("minecraft:builtin/") || files.containsKey(CorePackBuilder.modelPath(id)) || vanilla.hasModel(id);
        }

        boolean hasTexture(String id) {
            return files.containsKey(CorePackBuilder.texturePath(id)) || vanilla.hasTexture(id);
        }

        String renderCarrier(String carrier, Integer cmd) {
            if (client.major() >= 46) {
                return evaluate(json("assets/minecraft/items/" + carrier + ".json"), cmd);
            }
            return applyOverrides("minecraft:item/" + carrier, cmd);
        }

        String renderItemModel(String itemModel, Integer cmd) {
            if (client.major() >= 46) {
                Object definition = json(CorePackBuilder.itemDefinitionPath(itemModel));
                return definition == null ? "missing:" + itemModel : evaluate(definition, cmd);
            }
            String model = itemModel.replaceFirst(":", ":item/");
            return hasModel(model) ? applyOverrides(model, cmd) : "missing:" + model;
        }

        /** Pre-1.21.4: the last override whose custom_model_data predicate is at most the item's value wins. */
        private String applyOverrides(String model, Integer cmd) {
            Object json = json(CorePackBuilder.modelPath(model));
            String chosen = model;
            if (json instanceof Map<?, ?> m && m.get("overrides") instanceof List<?> overrides) {
                float value = cmd == null ? 0.0F : cmd.floatValue();
                for (Object o : overrides) {
                    if (o instanceof Map<?, ?> override && override.get("predicate") instanceof Map<?, ?> predicate
                            && predicate.get("custom_model_data") instanceof Number threshold
                            && predicate.size() == 1 && value >= threshold.floatValue()
                            && override.get("model") instanceof String target) {
                        chosen = normalize(target);
                    }
                }
            }
            return chosen;
        }

        /** 1.21.4+: evaluate model and range_dispatch item models on custom_model_data floats[0] (0 when absent). */
        private String evaluate(Object node, Integer cmd) {
            Object model = node instanceof Map<?, ?> m && m.containsKey("model") && !(m.get("model") instanceof String) ? m.get("model") : node;
            if (!(model instanceof Map<?, ?> map)) {
                return "unreadable";
            }
            String type = normalize(String.valueOf(map.get("type")));
            if (type.equals("minecraft:model")) {
                return normalize(String.valueOf(map.get("model")));
            }
            if (type.equals("minecraft:range_dispatch") && "minecraft:custom_model_data".equals(normalize(String.valueOf(map.get("property"))))) {
                float value = cmd == null ? 0.0F : cmd.floatValue();
                Object chosen = map.get("fallback");
                float best = Float.NEGATIVE_INFINITY;
                if (map.get("entries") instanceof List<?> entries) {
                    for (Object o : entries) {
                        if (o instanceof Map<?, ?> entry && entry.get("threshold") instanceof Number t
                                && t.floatValue() <= value && t.floatValue() >= best) {
                            best = t.floatValue();
                            chosen = entry.get("model");
                        }
                    }
                }
                return chosen == null ? "missing" : evaluate(Map.of("model", chosen), cmd);
            }
            return "unsupported:" + type;
        }

        /** The vanilla model, or the pack's exact vanilla copy (checked by parity). */
        boolean vanillaEquivalent(String carrier, String model) {
            return model.equals("minecraft:item/" + carrier) || model.endsWith(":item/vanilla/" + carrier);
        }
    }

    static List<String> modelReferences(Object json) {
        List<String> out = new ArrayList<>();
        collect(json, out, true);
        return out;
    }

    private static void collect(Object node, List<String> out, boolean root) {
        if (node instanceof Map<?, ?> map) {
            if (root && map.get("parent") instanceof String parent) out.add(parent);
            Object type = map.get("type");
            if (type != null && normalize(String.valueOf(type)).equals("minecraft:model") && map.get("model") instanceof String m) {
                out.add(m);
            }
            if (map.get("overrides") instanceof List<?> overrides) {
                for (Object o : overrides) {
                    if (o instanceof Map<?, ?> override && override.get("model") instanceof String m) out.add(m);
                }
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!"overrides".equals(e.getKey()) && !"textures".equals(e.getKey())) collect(e.getValue(), out, false);
            }
        } else if (node instanceof List<?> list) {
            for (Object o : list) collect(o, out, false);
        }
    }

    static List<String> textureReferences(Object json) {
        List<String> out = new ArrayList<>();
        if (json instanceof Map<?, ?> map && map.get("textures") instanceof Map<?, ?> textures) {
            for (Object value : textures.values()) {
                if (value instanceof String t && !t.startsWith("#")) out.add(t);
            }
        }
        return out;
    }

    private static List<Integer> dispatchedValues(Object json) {
        List<Integer> out = new ArrayList<>();
        if (json instanceof Map<?, ?> map) {
            if (map.get("overrides") instanceof List<?> overrides) {
                for (Object o : overrides) {
                    if (o instanceof Map<?, ?> override && override.get("predicate") instanceof Map<?, ?> p && p.get("custom_model_data") instanceof Number n) {
                        out.add(n.intValue());
                    }
                }
            }
            if (map.get("model") instanceof Map<?, ?> model && model.get("entries") instanceof List<?> entries) {
                for (Object o : entries) {
                    if (o instanceof Map<?, ?> entry && entry.get("threshold") instanceof Number n) out.add(n.intValue());
                }
            }
        }
        return out;
    }

    static String normalize(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /** A valid 8-bit PNG whose width is a positive multiple of 16 and whose height equals its width. */
    static Optional<String> png(byte[] data) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        if (data.length < 33 || !Arrays.equals(Arrays.copyOf(data, 8), signature)) {
            return Optional.of("not a PNG");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.getInt(8) != 13 || buffer.getInt(12) != 0x49484452) {
            return Optional.of("PNG does not start with IHDR");
        }
        int width = buffer.getInt(16);
        int height = buffer.getInt(20);
        int depth = data[24];
        int colorType = data[25];
        if (width <= 0 || width % 16 != 0 || height != width) {
            return Optional.of("texture is " + width + "x" + height + "; expected a square multiple of 16 (no animation is declared)");
        }
        if (depth != 8 || (colorType != 6 && colorType != 2 && colorType != 3 && colorType != 0 && colorType != 4)) {
            return Optional.of("unsupported PNG depth " + depth + " / color type " + colorType);
        }
        return Optional.empty();
    }
}
