package codes.castled.allium.item.spec;

import codes.castled.allium.item.ProviderKey;

import java.util.Objects;
import java.util.Optional;

/**
 * One parsed item reference: the canonical {@link ProviderKey} plus optional raw vanilla data.
 *
 * @param text the reference as written, including any raw data
 */
public record ItemReference(String text, ProviderKey key, Optional<RawItemData> raw) {

    public ItemReference {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(key, "key");
        raw = raw == null ? Optional.empty() : raw;
    }
}
