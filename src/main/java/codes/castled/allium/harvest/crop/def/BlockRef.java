package codes.castled.allium.harvest.crop.def;

import java.util.Locale;
import java.util.Objects;

/**
 * A namespaced block reference from configuration, e.g. {@code default:tomato}
 * or {@code minecraft:stone}. A missing namespace defaults to
 * {@code minecraft}.
 *
 * <p>Block references are deliberately kept apart from {@code ItemRef}: a
 * crop stage renders either an item model or a real block, and the two are
 * never interchangeable.
 */
public record BlockRef(String namespace, String id) {

    public static final String NS_MINECRAFT = "minecraft";

    public BlockRef {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(id, "id");
    }

    /**
     * Parses a block reference string.
     *
     * @throws IllegalArgumentException for blank input
     */
    public static BlockRef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Block reference is empty");
        }
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return new BlockRef(NS_MINECRAFT, trimmed.toLowerCase(Locale.ROOT));
        }
        String ns = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
        String id = trimmed.substring(colon + 1);
        if (id.isBlank()) {
            throw new IllegalArgumentException("Block reference '" + raw + "' has an empty id");
        }
        if (ns.equals("vanilla")) {
            ns = NS_MINECRAFT;
        }
        if (ns.equals(NS_MINECRAFT)) {
            id = id.toLowerCase(Locale.ROOT);
        }
        return new BlockRef(ns, id);
    }

    public boolean isVanilla() {
        return NS_MINECRAFT.equals(namespace);
    }

    @Override
    public String toString() {
        return namespace + ":" + id;
    }
}
