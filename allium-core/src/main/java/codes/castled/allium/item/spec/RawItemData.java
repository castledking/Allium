package codes.castled.allium.item.spec;

import java.util.Objects;

/**
 * Raw vanilla item data attached to a reference: {@code [...]} data components (1.20.5+) or
 * {@code {...}} NBT (before 1.20.5). The text includes its brackets or braces.
 */
public record RawItemData(Kind kind, String text) {

    public enum Kind {
        COMPONENTS,
        NBT
    }

    public RawItemData {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
    }
}
