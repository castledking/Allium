package codes.castled.ores;

import java.util.Locale;
import java.util.Objects;

/** A namespaced block from the config, such as {@code sf:salt_ore}; no namespace means {@code minecraft}. */
record BlockRef(String namespace, String id) {
    BlockRef {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(id, "id");
    }

    static BlockRef parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("block reference is empty");
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0) return new BlockRef("minecraft", trimmed.toLowerCase(Locale.ROOT));
        String namespace = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
        String id = trimmed.substring(colon + 1);
        if (id.isBlank()) throw new IllegalArgumentException("block reference '" + raw + "' has an empty id");
        if (namespace.equals("vanilla")) namespace = "minecraft";
        return new BlockRef(namespace, namespace.equals("minecraft") ? id.toLowerCase(Locale.ROOT) : id);
    }

    boolean vanilla() {
        return namespace.equals("minecraft");
    }

    @Override
    public String toString() {
        return namespace + ":" + id;
    }
}
