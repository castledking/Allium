package codes.castled.allium.item.spec;

import codes.castled.allium.item.ProviderKey;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One parsed item reference: the canonical {@link ProviderKey} plus optional raw vanilla data.
 *
 * @param text   the reference as written, including any raw data but not its own amount
 * @param amount this item's own amount ({@code stick:1,diamond:2}); it wins over the batch amount
 */
public record ItemReference(String text, ProviderKey key, Optional<RawItemData> raw, OptionalInt amount) {

    public ItemReference {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(key, "key");
        raw = raw == null ? Optional.empty() : raw;
        amount = amount == null ? OptionalInt.empty() : amount;
    }

    public ItemReference(String text, ProviderKey key, Optional<RawItemData> raw) {
        this(text, key, raw, OptionalInt.empty());
    }

    public ItemReference withAmount(OptionalInt value) {
        return new ItemReference(text, key, raw, value);
    }
}
