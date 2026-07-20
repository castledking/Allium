package codes.castled.allium.harvest.item;

import java.util.Locale;
import java.util.Objects;

/**
 * A namespaced item reference from configuration, e.g. {@code nexo:tomato},
 * {@code oraxen:ruby}, {@code minecraft:carrot} or {@code vanilla:CARROT}.
 * The {@code vanilla} namespace is an alias for {@code minecraft}.
 */
public record ItemRef(String namespace, String id) {

    public static final String NS_MINECRAFT = "minecraft";
    public static final String NS_NEXO = "nexo";
    public static final String NS_ORAXEN = "oraxen";

    public ItemRef {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(id, "id");
    }

    /**
     * Parses an item reference string. A missing namespace defaults to
     * {@code minecraft}.
     *
     * @throws IllegalArgumentException for blank input
     */
    public static ItemRef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Item reference is empty");
        }
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return new ItemRef(NS_MINECRAFT, trimmed.toLowerCase(Locale.ROOT));
        }
        String ns = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
        String id = trimmed.substring(colon + 1);
        if (id.isBlank()) {
            throw new IllegalArgumentException("Item reference '" + raw + "' has an empty id");
        }
        if (ns.equals("vanilla")) {
            ns = NS_MINECRAFT;
        }
        if (ns.equals(NS_MINECRAFT)) {
            id = id.toLowerCase(Locale.ROOT);
        }
        return new ItemRef(ns, id);
    }

    public boolean isVanilla() {
        return NS_MINECRAFT.equals(namespace);
    }

    @Override
    public String toString() {
        return namespace + ":" + id;
    }
}
