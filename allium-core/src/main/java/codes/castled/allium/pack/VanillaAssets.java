package codes.castled.allium.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The vanilla assets a client of one version ships, read from that version's client jar. */
public final class VanillaAssets {

    private final String label;
    private final Set<String> models;
    private final Set<String> textures;
    private final Set<String> itemDefinitions;
    private final Map<String, String> files;

    public VanillaAssets(String label, Set<String> models, Set<String> textures, Set<String> itemDefinitions, Map<String, String> files) {
        this.label = label;
        this.models = Set.copyOf(models);
        this.textures = Set.copyOf(textures);
        this.itemDefinitions = Set.copyOf(itemDefinitions);
        this.files = Map.copyOf(files);
    }

    /** Indexes every vanilla model, texture and item definition, and keeps model/item JSON text. */
    public static VanillaAssets fromClientJar(Path jar) throws IOException {
        Set<String> models = new HashSet<>();
        Set<String> textures = new HashSet<>();
        Set<String> items = new HashSet<>();
        Map<String, String> files = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.startsWith("assets/") || entry.isDirectory()) {
                    continue;
                }
                Optional<String> model = resourceId(name, "models", ".json");
                Optional<String> texture = resourceId(name, "textures", ".png");
                Optional<String> item = resourceId(name, "items", ".json");
                model.ifPresent(models::add);
                texture.ifPresent(textures::add);
                item.ifPresent(items::add);
                if (model.isPresent() || item.isPresent()) {
                    files.put(name, new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return new VanillaAssets(jar.getFileName().toString(), models, textures, items, files);
    }

    /** {@code assets/<ns>/<kind>/<path><ext>} to {@code <ns>:<path>}. */
    static Optional<String> resourceId(String path, String kind, String extension) {
        if (!path.startsWith("assets/") || !path.endsWith(extension)) {
            return Optional.empty();
        }
        String rest = path.substring("assets/".length());
        int slash = rest.indexOf('/');
        if (slash < 0 || !rest.startsWith(kind + "/", slash + 1)) {
            return Optional.empty();
        }
        String ns = rest.substring(0, slash);
        String id = rest.substring(slash + 1 + kind.length() + 1, rest.length() - extension.length());
        return Optional.of(ns + ":" + id);
    }

    public String label() {
        return label;
    }

    public boolean hasModel(String id) {
        return models.contains(id);
    }

    public boolean hasTexture(String id) {
        return textures.contains(id);
    }

    public boolean hasItemDefinition(String id) {
        return itemDefinitions.contains(id);
    }

    public Optional<String> file(String path) {
        return Optional.ofNullable(files.get(path));
    }
}
