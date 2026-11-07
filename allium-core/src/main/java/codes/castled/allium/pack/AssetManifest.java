package codes.castled.allium.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The core resource pack's asset manifest ({@code pack/manifest.json}): stable item IDs, the
 * {@code custom_model_data} allocation of every core tool, texture paths and texture provenance.
 *
 * <p>Tools with status {@code ready} are generated into the pack; {@code planned} tools only hold
 * their allocation so nothing else can take it.
 */
public record AssetManifest(
        int schema,
        String namespace,
        PackSpec pack,
        Range customModelData,
        Map<String, Carrier> carriers,
        List<Tool> tools,
        List<Texture> textures
) {

    public static final int SCHEMA = 1;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public AssetManifest {
        carriers = Collections.unmodifiableMap(new LinkedHashMap<>(carriers));
        tools = List.copyOf(tools);
        textures = List.copyOf(textures);
    }

    /** @param overlayDirectory overlay holding 1.21.4+ item model definitions, applied from {@code overlayMinFormat} */
    public record PackSpec(String description, int baseFormat, int maxFormat, String overlayDirectory, int overlayMinFormat) {
    }

    public record Range(int first, int last) {
        public boolean contains(int value) {
            return value >= first && value <= last;
        }

        public boolean overlaps(Range other) {
            return first <= other.last && other.first <= last;
        }

        @Override
        public String toString() {
            return first + ".." + last;
        }
    }

    /** A vanilla carrier item, with the exact vanilla files the pack must keep behaving like. */
    public record Carrier(String id, Map<String, Object> vanillaModel, Map<String, Object> vanillaItem) {
    }

    public record Tool(
            String id,
            String behavior,
            String carrier,
            String name,
            String status,
            Range allocation,
            String modelParent,
            List<State> states
    ) {
        public Tool {
            states = List.copyOf(states);
        }

        public boolean ready() {
            return "ready".equals(status);
        }

        /** The state a new stack starts in; its model data is the definition's base value. */
        public State defaultState() {
            return states.get(0);
        }

        public Optional<State> state(String key) {
            return states.stream().filter(s -> s.key().equals(key)).findFirst();
        }

        public String itemModelId(String namespace) {
            return namespace + ":" + id;
        }

        /** 1.21.2/1.21.3 resolve {@code item_model ns:id} to the model {@code ns:item/id}. */
        public String legacyItemModelTarget(String namespace) {
            return namespace + ":item/" + id;
        }
    }

    public record State(String key, int customModelData, String texture, String meaning) {
    }

    public record Texture(String id, String file, String sha256, String author, String origin, String license, boolean distributable) {
    }

    public Optional<Tool> tool(String id) {
        return tools.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public Optional<Texture> texture(String id) {
        return textures.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public List<Tool> readyTools() {
        return tools.stream().filter(Tool::ready).toList();
    }

    public static AssetManifest parse(String json) {
        Map<String, Object> root = Json.parseObject(json);
        int schema = integer(root, "schema", "");
        if (schema != SCHEMA) {
            throw new IllegalArgumentException("manifest schema " + schema + " is not supported (expected " + SCHEMA + ")");
        }
        String namespace = id(string(root, "namespace", ""), "namespace");

        Map<String, Object> pack = object(root, "pack", "");
        Map<String, Object> overlay = object(pack, "itemDefinitionsOverlay", "pack.");
        PackSpec spec = new PackSpec(
                string(pack, "description", "pack."),
                integer(pack, "baseFormat", "pack."),
                integer(pack, "maxFormat", "pack."),
                id(string(overlay, "directory", "pack.itemDefinitionsOverlay."), "pack.itemDefinitionsOverlay.directory"),
                integer(overlay, "minFormat", "pack.itemDefinitionsOverlay."));

        Range block = range(object(root, "customModelData", ""), "customModelData.");

        Map<String, Carrier> carriers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : object(root, "carriers", "").entrySet()) {
            String path = "carriers." + e.getKey() + ".";
            Map<String, Object> c = asObject(e.getValue(), path);
            carriers.put(id(e.getKey(), path), new Carrier(e.getKey(), object(c, "vanillaModel", path), object(c, "vanillaItem", path)));
        }

        List<Tool> tools = new ArrayList<>();
        int index = 0;
        for (Object raw : list(root, "tools", "")) {
            String path = "tools[" + index++ + "].";
            Map<String, Object> t = asObject(raw, path);
            List<State> states = new ArrayList<>();
            int s = 0;
            for (Object rawState : list(t, "states", path)) {
                String statePath = path + "states[" + s++ + "].";
                Map<String, Object> st = asObject(rawState, statePath);
                String texture = optionalString(st, "texture");
                if (texture != null && !RESOURCE.matcher(texture).matches()) {
                    throw new IllegalArgumentException(statePath + "texture '" + texture + "' is not a namespaced id");
                }
                states.add(new State(id(string(st, "key", statePath), statePath + "key"), integer(st, "cmd", statePath),
                        texture, optionalString(st, "meaning")));
            }
            if (states.isEmpty()) {
                throw new IllegalArgumentException(path + "states must not be empty");
            }
            String status = string(t, "status", path);
            if (!status.equals("ready") && !status.equals("planned")) {
                throw new IllegalArgumentException(path + "status must be ready or planned");
            }
            tools.add(new Tool(
                    id(string(t, "id", path), path + "id"),
                    string(t, "behavior", path),
                    id(string(t, "carrier", path).toLowerCase(Locale.ROOT), path + "carrier"),
                    string(t, "name", path),
                    status,
                    range(object(t, "allocation", path), path + "allocation."),
                    optionalString(t, "modelParent"),
                    states));
        }

        List<Texture> textures = new ArrayList<>();
        index = 0;
        for (Object raw : list(root, "textures", "")) {
            String path = "textures[" + index++ + "].";
            Map<String, Object> t = asObject(raw, path);
            Object distributable = t.get("distributable");
            if (!(distributable instanceof Boolean)) {
                throw new IllegalArgumentException(path + "distributable must be true or false");
            }
            textures.add(new Texture(string(t, "id", path), string(t, "file", path), string(t, "sha256", path),
                    string(t, "author", path), string(t, "origin", path), string(t, "license", path), (Boolean) distributable));
        }
        return new AssetManifest(schema, namespace, spec, block, carriers, tools, textures);
    }

    private static Range range(Map<String, Object> object, String path) {
        Range range = new Range(integer(object, "first", path), integer(object, "last", path));
        if (range.first() > range.last()) {
            throw new IllegalArgumentException(path + "first must not exceed last");
        }
        return range;
    }

    private static String id(String value, String path) {
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException(path + " '" + value + "' must match " + ID.pattern());
        }
        return value;
    }

    private static Map<String, Object> object(Map<String, Object> parent, String key, String path) {
        return asObject(require(parent, key, path), path + key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String path) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> parent, String key, String path) {
        Object value = require(parent, key, path);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(path + key + " must be an array");
        }
        return (List<Object>) value;
    }

    private static String string(Map<String, Object> parent, String key, String path) {
        Object value = require(parent, key, path);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(path + key + " must be a non-blank string");
        }
        return s;
    }

    private static String optionalString(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static int integer(Map<String, Object> parent, String key, String path) {
        Object value = require(parent, key, path);
        if (!(value instanceof Long l) || l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + key + " must be an integer");
        }
        return l.intValue();
    }

    private static Object require(Map<String, Object> parent, String key, String path) {
        Object value = parent.get(key);
        if (value == null) {
            throw new IllegalArgumentException(path + key + " is required");
        }
        return Objects.requireNonNull(value);
    }
}
