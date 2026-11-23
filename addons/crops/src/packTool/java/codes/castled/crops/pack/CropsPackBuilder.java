package codes.castled.crops.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the Crops resource pack from its bundled catalogue and model assets. */
public final class CropsPackBuilder {
    private static final int JADE_SEED_CMD_OFFSET = 1_000_000;
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CropsPackBuilder() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: CropsPackBuilder <packDir> <resourcesDir> <outputZip>");
        }
        build(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path file) throws IOException {
        Object loaded = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String afterLastColon(String ref) {
        int idx = ref.lastIndexOf(':');
        return idx >= 0 ? ref.substring(idx + 1) : ref;
    }

    private static String afterFirstColon(String ref) {
        int idx = ref.indexOf(':');
        return idx >= 0 ? ref.substring(idx + 1) : ref;
    }

    private static Map<String, Object> model(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "minecraft:model");
        out.put("model", name);
        return out;
    }

    private static Map<String, Object> paperBase() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parent", "minecraft:item/generated");
        out.put("textures", Map.of("layer0", "minecraft:item/paper"));
        return out;
    }

    private static Map<String, String> stageSeedModels(Map<String, Object> crops, Map<String, Object> items) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Object cropObj : crops.values()) {
            Map<String, Object> crop = mapOf(cropObj);
            String seedId = afterLastColon(str(mapOf(crop.get("seed")).get("item"), ""));
            if (!items.containsKey(seedId)) {
                throw new IllegalArgumentException("Missing seed item " + seedId + " for crop " + crop.get("id"));
            }
            String seedModel = str(mapOf(items.get(seedId)).get("model"), "");
            Map<String, Object> pathEntries = mapOf(mapOf(crop.get("paths")).get("entries"));
            for (Object pathObj : pathEntries.values()) {
                for (Object stageObj : listOf(mapOf(pathObj).get("stages"))) {
                    String stageId = afterLastColon(str(mapOf(stageObj).get("model"), ""));
                    mapping.put(stageId, seedModel);
                }
            }
        }
        return mapping;
    }

    private static Map<String, String> kitchenIcons(Path kitchenFile, Map<String, Object> items) throws IOException {
        Map<String, Object> kitchen = loadYaml(kitchenFile);
        Map<String, Object> pies = mapOf(kitchen.get("pies"));
        Map<String, Object> models = mapOf(pies.get("models"));
        List<String> slices = List.of("", "_slice1", "_slice2", "_slice3");
        Map<String, String> icons = new LinkedHashMap<>();

        Map<String, Object> crust = mapOf(pies.get("crust"));
        if (crust.get("model") != null && crust.get("item") != null) {
            icons.put(afterFirstColon(str(crust.get("model"), "")), afterFirstColon(str(crust.get("item"), "")));
        }
        for (var entry : mapOf(pies.get("types")).entrySet()) {
            String typeId = entry.getKey();
            Map<String, Object> row = mapOf(entry.getValue());
            String itemTemplate = str(models.get("item"), "");
            String coldRef = row.get("cold-item") != null ? str(row.get("cold-item"), "")
                    : itemTemplate.replace("{state}", "cold").replace("{type}", typeId);
            String bakedRef = row.get("baked-item") != null ? str(row.get("baked-item"), "")
                    : itemTemplate.replace("{state}", "baked").replace("{type}", typeId);
            String cold = afterFirstColon(coldRef);
            String baked = afterFirstColon(bakedRef);

            if (row.get("crust-model") != null && row.get("crust-item") != null) {
                icons.put(afterFirstColon(str(row.get("crust-model"), "")), afterFirstColon(str(row.get("crust-item"), "")));
            }
            String filled = row.get("filled-model") != null ? str(row.get("filled-model"), "")
                    : str(models.get("filled"), "").replace("{type}", typeId);
            List<String> filledAndProgress = new ArrayList<>();
            filledAndProgress.add(filled);
            for (Object progress : listOf(row.get("progress-models"))) {
                filledAndProgress.add(str(progress, ""));
            }
            for (String ref : filledAndProgress) {
                icons.put(afterFirstColon(ref), cold);
            }
            for (String[] pair : new String[][]{{"cold", cold}, {"baked", baked}}) {
                String state = pair[0];
                String item = pair[1];
                List<?> explicit = listOf(row.get(state + "-models"));
                List<String> placed = new ArrayList<>();
                if (!explicit.isEmpty()) {
                    for (Object p : explicit) placed.add(str(p, ""));
                } else {
                    String placedTemplate = str(models.get("placed"), "");
                    for (String s : slices) {
                        placed.add(placedTemplate.replace("{state}", state).replace("{type}", typeId).replace("{slice}", s));
                    }
                }
                for (String ref : placed) {
                    icons.put(afterFirstColon(ref), item);
                }
            }
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (var e : icons.entrySet()) {
            if (items.containsKey(e.getKey()) && items.containsKey(e.getValue())) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        return filtered;
    }

    private record Route(int cmd, String ref, String gui) {}

    public static void build(Path packDir, Path resourcesDir, Path output) throws IOException {
        Map<String, Object> items = mapOf(loadYaml(resourcesDir.resolve("items.yml")).get("items"));
        Map<String, Object> crops = mapOf(loadYaml(resourcesDir.resolve("crops.yml")).get("crops"));

        Map<String, Object> files = new TreeMap<>();
        Path assetsDir = packDir.resolve("assets");
        try (var walk = Files.walk(assetsDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = packDir.relativize(p).toString().replace('\\', '/');
                files.put(rel, Files.readAllBytes(p));
            }
        }
        Map<String, Object> packSection = new LinkedHashMap<>();
        packSection.put("description", "§aCrops Resources §7v1.0");
        packSection.put("pack_format", 15);
        packSection.put("supported_formats", List.of(15, 97));
        packSection.put("min_format", 15);
        packSection.put("max_format", List.of(97, 1));
        files.put("pack.mcmeta", Map.of("pack", packSection));
        files.put("assets/crops/models/item/plain_paper.json", paperBase());
        files.put("pack.png", Files.readAllBytes(packDir.resolve("pack.png")));

        Map<String, String> seedModels = stageSeedModels(crops, items);
        Map<String, String> icons = kitchenIcons(resourcesDir.resolve("kitchen.yml"), items);
        List<Route> routes = new ArrayList<>();
        for (var entry : items.entrySet()) {
            String itemId = entry.getKey();
            Map<String, Object> item = mapOf(entry.getValue());
            int cmd = ((Number) item.get("cmd")).intValue();
            String ref = str(item.get("model"), "");
            String gui = item.get("gui") != null ? str(item.get("gui"), null)
                    : (icons.containsKey(itemId) ? str(mapOf(items.get(icons.get(itemId))).get("model"), null) : null);
            routes.add(new Route(cmd, ref, gui));
            if (seedModels.containsKey(itemId)) {
                routes.add(new Route(cmd + JADE_SEED_CMD_OFFSET, ref, seedModels.get(itemId)));
            }
        }
        routes.sort(Comparator.comparingInt(Route::cmd));
        Set<Integer> usedCmds = new HashSet<>();
        for (Route r : routes) {
            if (usedCmds.contains(r.cmd()) || usedCmds.contains(r.cmd() + 1) || usedCmds.contains(r.cmd() - 1)) {
                throw new IllegalArgumentException("Colliding custom_model_data " + r.cmd());
            }
            usedCmds.add(r.cmd());
        }

        List<Map<String, Object>> overrides = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Route r : routes) {
            for (String route : new String[]{r.ref(), r.gui() != null ? r.gui() : r.ref()}) {
                int colon = route.indexOf(':');
                String ns = route.substring(0, colon);
                String path = route.substring(colon + 1);
                if (!files.containsKey("assets/" + ns + "/models/" + path + ".json")) {
                    throw new IllegalArgumentException("Missing crop model " + route);
                }
            }
            overrides.add(override(r.cmd(), r.ref()));
            overrides.add(override(r.cmd() + 1, "crops:item/plain_paper"));

            Map<String, Object> modern = model(r.ref());
            if (r.gui() != null) {
                Map<String, Object> select = new LinkedHashMap<>();
                select.put("type", "minecraft:select");
                select.put("property", "minecraft:display_context");
                Map<String, Object> guiCase = new LinkedHashMap<>();
                guiCase.put("when", "gui");
                guiCase.put("model", model(r.gui()));
                select.put("cases", List.of(guiCase));
                select.put("fallback", modern);
                modern = select;
            }
            entries.add(thresholdEntry(r.cmd(), modern));
            entries.add(thresholdEntry(r.cmd() + 1, model("minecraft:item/paper")));
        }

        Map<String, Object> paperLegacy = paperBase();
        paperLegacy.put("overrides", overrides);
        files.put("assets/minecraft/models/item/paper.json", paperLegacy);

        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("type", "minecraft:range_dispatch");
        dispatch.put("property", "minecraft:custom_model_data");
        dispatch.put("index", 0);
        dispatch.put("entries", entries);
        dispatch.put("fallback", model("minecraft:item/paper"));
        files.put("assets/minecraft/items/paper.json", Map.of("model", dispatch));

        validateSingleAtlas(files);

        Files.createDirectories(output.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (var e : files.entrySet()) {
                byte[] bytes = e.getValue() instanceof byte[] raw
                        ? raw : GSON.toJson(e.getValue()).getBytes(StandardCharsets.UTF_8);
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTimeLocal(FIXED_TIME);
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    private static Map<String, Object> override(int cmd, String target) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("predicate", Map.of("custom_model_data", cmd));
        out.put("model", target);
        return out;
    }

    private static Map<String, Object> thresholdEntry(int threshold, Map<String, Object> modelObj) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threshold", threshold);
        out.put("model", modelObj);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void validateSingleAtlas(Map<String, Object> files) {
        Map<String, Map<String, Object>> parsed = new TreeMap<>();
        for (var e : files.entrySet()) {
            String name = e.getKey();
            if (name.contains("/models/") && name.endsWith(".json")) {
                if (e.getValue() instanceof Map<?, ?> map) {
                    parsed.put(name, (Map<String, Object>) map);
                } else if (e.getValue() instanceof byte[] raw) {
                    parsed.put(name, GSON.fromJson(new String(raw, StandardCharsets.UTF_8), Map.class));
                }
            }
        }
        for (String name : parsed.keySet()) {
            Set<String> atlases = new TreeSet<>();
            for (Object texObj : resolveTextures(parsed, name, new HashSet<>()).values()) {
                if (!(texObj instanceof String tex) || tex.startsWith("#")) continue;
                String rel = tex.contains(":") ? afterFirstColon(tex) : tex;
                if (rel.startsWith("block/")) atlases.add("blocks");
                else if (rel.startsWith("item/")) atlases.add("items");
            }
            if (atlases.size() > 1) {
                throw new IllegalArgumentException("Mixed block/item atlases in model " + name + ": " + atlases);
            }
        }
    }

    private static Map<String, Object> resolveTextures(Map<String, Map<String, Object>> parsed, String path, Set<String> seen) {
        if (!seen.add(path) || !parsed.containsKey(path)) return Map.of();
        Map<String, Object> data = parsed.get(path);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (data.get("parent") instanceof String parent && parent.contains(":")
                && !parent.startsWith("minecraft:") && !parent.startsWith("builtin/")) {
            int colon = parent.indexOf(':');
            String ns = parent.substring(0, colon);
            String rel = parent.substring(colon + 1);
            merged.putAll(resolveTextures(parsed, "assets/" + ns + "/models/" + rel + ".json", seen));
        }
        merged.putAll(mapOf(data.get("textures")));
        return merged;
    }
}
