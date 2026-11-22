package codes.castled.allium.pack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Deterministic resource-pack merger: combines Allium's base pack zip with one or more addon pack
 * zips so Allium can deliver a single merged archive via standalone self-hosting or RPM export.
 */
public final class PackMerger {

    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    public record Contribution(String addonName, byte[] zipBytes) {
    }

    public record Result(byte[] bytes, List<String> warnings) {
    }

    private PackMerger() {
    }

    public static Result merge(byte[] baseZip, List<Contribution> contributions) throws IOException {
        if (contributions == null || contributions.isEmpty()) {
            return new Result(baseZip, List.of());
        }
        SortedMap<String, byte[]> files = readZip(baseZip);
        List<String> warnings = new ArrayList<>();
        List<String> baseOverlays = overlayDirectories(files.get("pack.mcmeta"));

        for (Contribution contribution : contributions) {
            String addon = contribution.addonName();
            SortedMap<String, byte[]> addonFiles = readZip(contribution.zipBytes());
            for (Map.Entry<String, byte[]> entry : addonFiles.entrySet()) {
                String path = entry.getKey();
                byte[] addonBytes = entry.getValue();
                if ("pack.mcmeta".equals(path)) {
                    byte[] baseMeta = files.get("pack.mcmeta");
                    if (baseMeta != null) {
                        byte[] mergedMeta = mergePackMcmeta(baseMeta, addonBytes, addon, warnings);
                        files.put("pack.mcmeta", mergedMeta);
                        baseOverlays = overlayDirectories(mergedMeta);
                    } else {
                        files.put(path, addonBytes);
                        baseOverlays = overlayDirectories(addonBytes);
                    }
                    continue;
                }

                // If an addon adds a root 1.21.4+ item definition that is shadowed by a base
                // overlay directory, merge into the shadowed overlay file too so newer clients
                // receive both base and addon entries.
                if (isRootItemDefinition(path)) {
                    for (String overlayDir : baseOverlays) {
                        String shadowedPath = overlayDir + "/" + path;
                        byte[] overlayBase = files.get(shadowedPath);
                        if (overlayBase != null) {
                            byte[] merged = mergeEntry(shadowedPath, overlayBase, addonBytes, addon, warnings);
                            files.put(shadowedPath, merged);
                        }
                    }
                }

                byte[] existing = files.get(path);
                if (existing == null) {
                    files.put(path, addonBytes);
                } else if (!Arrays.equals(existing, addonBytes)) {
                    files.put(path, mergeEntry(path, existing, addonBytes, addon, warnings));
                }
            }
        }
        return new Result(writeZip(files), List.copyOf(warnings));
    }

    private static byte[] mergeEntry(String path, byte[] baseBytes, byte[] addonBytes, String addon, List<String> warnings) {
        if (isItemModel(path)) {
            return mergeItemModel(path, baseBytes, addonBytes, addon, warnings);
        }
        if (isItemDefinition(path)) {
            return mergeItemDefinition(path, baseBytes, addonBytes, addon, warnings);
        }
        if (isFontJson(path)) {
            return mergeListField(path, baseBytes, addonBytes, "providers", addon, warnings);
        }
        if (isAtlasJson(path)) {
            return mergeListField(path, baseBytes, addonBytes, "sources", addon, warnings);
        }
        if (isLangOrSoundsJson(path)) {
            return mergeMapKeys(path, baseBytes, addonBytes, addon, warnings);
        }
        warnings.add("Pack merge conflict at " + path + " from addon " + addon + "; kept base pack entry.");
        return baseBytes;
    }

    private static byte[] mergePackMcmeta(byte[] baseBytes, byte[] addonBytes, String addon, List<String> warnings) {
        try {
            Map<String, Object> base = Json.parseObject(utf8(baseBytes));
            Map<String, Object> other = Json.parseObject(utf8(addonBytes));
            List<Object> otherEntries = extractOverlayEntries(other);
            if (otherEntries.isEmpty()) {
                return baseBytes;
            }
            Map<String, Object> result = new LinkedHashMap<>(base);
            @SuppressWarnings("unchecked")
            Map<String, Object> overlaysObj = result.get("overlays") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m)
                    : new LinkedHashMap<>();
            List<Object> combined = new ArrayList<>(extractOverlayEntries(base));
            Set<String> seenDirs = new LinkedHashSet<>();
            for (Object entry : combined) {
                String dir = overlayDir(entry);
                if (dir != null) seenDirs.add(dir);
            }
            boolean changed = false;
            for (Object entry : otherEntries) {
                String dir = overlayDir(entry);
                if (dir == null) continue;
                if (seenDirs.add(dir)) {
                    combined.add(entry);
                    changed = true;
                } else {
                    warnings.add("Pack merge skipped duplicate overlay directory '" + dir + "' from addon " + addon + ".");
                }
            }
            if (!changed) {
                return baseBytes;
            }
            overlaysObj.put("entries", combined);
            result.put("overlays", overlaysObj);
            return utf8Bytes(Json.write(result));
        } catch (RuntimeException e) {
            warnings.add("Could not merge pack.mcmeta from addon " + addon + " (" + e.getMessage() + "); kept base.");
            return baseBytes;
        }
    }

    @SuppressWarnings("unchecked")
    private static byte[] mergeItemModel(String path, byte[] baseBytes, byte[] addonBytes, String addon, List<String> warnings) {
        try {
            Map<String, Object> base = Json.parseObject(utf8(baseBytes));
            Map<String, Object> other = Json.parseObject(utf8(addonBytes));
            if (!(base.get("overrides") instanceof List<?> baseList) || !(other.get("overrides") instanceof List<?> otherList)) {
                warnings.add("Pack merge conflict at " + path + " from addon " + addon + " (no overrides array); kept base.");
                return baseBytes;
            }
            List<Map<String, Object>> nonCmd = new ArrayList<>();
            Map<Long, Map<String, Object>> byCmd = new TreeMap<>();
            List<Map<String, Object>> extra = new ArrayList<>();
            Set<String> seenPredicates = new LinkedHashSet<>();

            for (Object item : baseList) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> entry = (Map<String, Object>) raw;
                Long cmd = customModelDataOf(entry);
                String predKey = predicateKey(entry);
                if (cmd != null) {
                    byCmd.put(cmd, entry);
                    seenPredicates.add(predKey);
                } else {
                    nonCmd.add(entry);
                    seenPredicates.add(predKey);
                }
            }
            for (Object item : otherList) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> entry = (Map<String, Object>) raw;
                Long cmd = customModelDataOf(entry);
                String predKey = predicateKey(entry);
                if (cmd != null) {
                    Map<String, Object> prior = byCmd.get(cmd);
                    if (prior == null) {
                        byCmd.put(cmd, entry);
                        seenPredicates.add(predKey);
                    } else if (!Json.write(prior).equals(Json.write(entry))) {
                        warnings.add("Pack merge conflict at " + path + " custom_model_data=" + cmd + " from addon " + addon + "; kept base.");
                    }
                } else if (seenPredicates.add(predKey)) {
                    extra.add(entry);
                } else {
                    warnings.add("Pack merge conflict at " + path + " override " + predKey + " from addon " + addon + "; kept base.");
                }
            }
            List<Object> merged = new ArrayList<>(nonCmd);
            merged.addAll(extra);
            merged.addAll(byCmd.values());
            Map<String, Object> result = new LinkedHashMap<>(base);
            result.put("overrides", merged);
            return utf8Bytes(Json.write(result));
        } catch (RuntimeException e) {
            warnings.add("Could not merge item model " + path + " from addon " + addon + " (" + e.getMessage() + "); kept base.");
            return baseBytes;
        }
    }

    @SuppressWarnings("unchecked")
    private static byte[] mergeItemDefinition(String path, byte[] baseBytes, byte[] addonBytes, String addon, List<String> warnings) {
        try {
            Map<String, Object> base = Json.parseObject(utf8(baseBytes));
            Map<String, Object> other = Json.parseObject(utf8(addonBytes));
            if (!(base.get("model") instanceof Map<?, ?> baseModelRaw) || !(other.get("model") instanceof Map<?, ?> otherModelRaw)) {
                warnings.add("Pack merge conflict at " + path + " from addon " + addon + "; kept base.");
                return baseBytes;
            }
            Map<String, Object> baseModel = (Map<String, Object>) baseModelRaw;
            Map<String, Object> otherModel = (Map<String, Object>) otherModelRaw;
            if (!isCmdRangeDispatch(baseModel) || !isCmdRangeDispatch(otherModel) || indexOf(baseModel) != indexOf(otherModel)) {
                warnings.add("Pack merge conflict at " + path + " from addon " + addon + " (incompatible item definition); kept base.");
                return baseBytes;
            }
            List<?> baseEntries = baseModel.get("entries") instanceof List<?> l ? l : List.of();
            List<?> otherEntries = otherModel.get("entries") instanceof List<?> l ? l : List.of();

            TreeMap<Double, Map<String, Object>> byThreshold = new TreeMap<>();
            for (Object item : baseEntries) {
                if (item instanceof Map<?, ?> raw && raw.get("threshold") instanceof Number n) {
                    byThreshold.put(n.doubleValue(), (Map<String, Object>) raw);
                }
            }
            for (Object item : otherEntries) {
                if (item instanceof Map<?, ?> raw && raw.get("threshold") instanceof Number n) {
                    double threshold = n.doubleValue();
                    Map<String, Object> entry = (Map<String, Object>) raw;
                    Map<String, Object> prior = byThreshold.get(threshold);
                    if (prior == null) {
                        byThreshold.put(threshold, entry);
                    } else if (!Json.write(prior).equals(Json.write(entry))) {
                        warnings.add("Pack merge conflict at " + path + " threshold=" + threshold + " from addon " + addon + "; kept base.");
                    }
                }
            }
            Map<String, Object> mergedModel = new LinkedHashMap<>(baseModel);
            if (!mergedModel.containsKey("fallback") && otherModel.containsKey("fallback")) {
                mergedModel.put("fallback", otherModel.get("fallback"));
            }
            mergedModel.put("entries", new ArrayList<>(byThreshold.values()));
            Map<String, Object> result = new LinkedHashMap<>(base);
            result.put("model", mergedModel);
            return utf8Bytes(Json.write(result));
        } catch (RuntimeException e) {
            warnings.add("Could not merge item definition " + path + " from addon " + addon + " (" + e.getMessage() + "); kept base.");
            return baseBytes;
        }
    }

    private static byte[] mergeListField(String path, byte[] baseBytes, byte[] addonBytes, String field, String addon, List<String> warnings) {
        try {
            Map<String, Object> base = Json.parseObject(utf8(baseBytes));
            Map<String, Object> other = Json.parseObject(utf8(addonBytes));
            if (!(base.get(field) instanceof List<?> baseList) || !(other.get(field) instanceof List<?> otherList)) {
                warnings.add("Pack merge conflict at " + path + " from addon " + addon + "; kept base.");
                return baseBytes;
            }
            List<Object> combined = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Object item : baseList) {
                if (seen.add(Json.write(item))) {
                    combined.add(item);
                }
            }
            for (Object item : otherList) {
                if (seen.add(Json.write(item))) {
                    combined.add(item);
                }
            }
            Map<String, Object> result = new LinkedHashMap<>(base);
            result.put(field, combined);
            return utf8Bytes(Json.write(result));
        } catch (RuntimeException e) {
            warnings.add("Could not merge " + path + " from addon " + addon + " (" + e.getMessage() + "); kept base.");
            return baseBytes;
        }
    }

    private static byte[] mergeMapKeys(String path, byte[] baseBytes, byte[] addonBytes, String addon, List<String> warnings) {
        try {
            Map<String, Object> base = Json.parseObject(utf8(baseBytes));
            Map<String, Object> other = Json.parseObject(utf8(addonBytes));
            Map<String, Object> result = new LinkedHashMap<>(base);
            for (Map.Entry<String, Object> e : other.entrySet()) {
                if (!result.containsKey(e.getKey())) {
                    result.put(e.getKey(), e.getValue());
                } else if (!Json.write(result.get(e.getKey())).equals(Json.write(e.getValue()))) {
                    warnings.add("Pack merge conflict at " + path + " key '" + e.getKey() + "' from addon " + addon + "; kept base.");
                }
            }
            return utf8Bytes(Json.write(result));
        } catch (RuntimeException e) {
            warnings.add("Could not merge " + path + " from addon " + addon + " (" + e.getMessage() + "); kept base.");
            return baseBytes;
        }
    }

    private static boolean isCmdRangeDispatch(Map<String, Object> model) {
        return "minecraft:range_dispatch".equals(model.get("type"))
                && "minecraft:custom_model_data".equals(model.get("property"));
    }

    private static int indexOf(Map<String, Object> model) {
        return model.get("index") instanceof Number n ? n.intValue() : 0;
    }

    private static Long customModelDataOf(Map<String, Object> override) {
        if (override.get("predicate") instanceof Map<?, ?> pred && pred.size() == 1
                && pred.get("custom_model_data") instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static String predicateKey(Map<String, Object> override) {
        Object pred = override.get("predicate");
        return pred == null ? "{}" : Json.write(pred);
    }

    private static boolean isItemModel(String path) {
        String p = stripOverlayPrefix(path);
        return p.startsWith("assets/") && p.contains("/models/item/") && p.endsWith(".json");
    }

    private static boolean isItemDefinition(String path) {
        String p = stripOverlayPrefix(path);
        return p.startsWith("assets/") && p.contains("/items/") && p.endsWith(".json");
    }

    private static boolean isRootItemDefinition(String path) {
        return path.startsWith("assets/") && path.contains("/items/") && path.endsWith(".json");
    }

    private static boolean isFontJson(String path) {
        String p = stripOverlayPrefix(path);
        return p.startsWith("assets/") && p.contains("/font/") && p.endsWith(".json");
    }

    private static boolean isAtlasJson(String path) {
        String p = stripOverlayPrefix(path);
        return p.startsWith("assets/") && p.contains("/atlases/") && p.endsWith(".json");
    }

    private static boolean isLangOrSoundsJson(String path) {
        String p = stripOverlayPrefix(path);
        return p.startsWith("assets/") && (p.contains("/lang/") && p.endsWith(".json") || p.endsWith("/sounds.json"));
    }

    private static String stripOverlayPrefix(String path) {
        int idx = path.indexOf("/assets/");
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static List<String> overlayDirectories(byte[] mcmeta) {
        if (mcmeta == null) return List.of();
        try {
            List<Object> entries = extractOverlayEntries(Json.parseObject(utf8(mcmeta)));
            List<String> dirs = new ArrayList<>();
            for (Object entry : entries) {
                String dir = overlayDir(entry);
                if (dir != null && !dir.isBlank()) dirs.add(dir);
            }
            return dirs;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> extractOverlayEntries(Map<String, Object> mcmeta) {
        if (mcmeta.get("overlays") instanceof Map<?, ?> overlays && overlays.get("entries") instanceof List<?> entries) {
            return (List<Object>) entries;
        }
        return List.of();
    }

    private static String overlayDir(Object entry) {
        if (entry instanceof Map<?, ?> map && map.get("directory") instanceof String s) {
            return s;
        }
        return null;
    }

    public static SortedMap<String, byte[]> readZip(byte[] zip) throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.contains("..")) continue;
                files.put(name, in.readAllBytes());
            }
        }
        if (files.isEmpty()) {
            throw new IOException("resource pack zip contains no entries");
        }
        return files;
    }

    public static byte[] writeZip(SortedMap<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            List<String> keys = new ArrayList<>(files.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String path : keys) {
                byte[] data = files.get(path);
                ZipEntry entry = new ZipEntry(path);
                entry.setTimeLocal(ENTRY_TIME);
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String utf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
