package codes.castled.allium.tool.definition;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The {@code item} section of a tool definition: a built-in {@code allium:<id>} item that is not
 * part of the core pack. Core pack tools (the Mob Disarmer) take their item from the pack
 * manifest instead.
 *
 * @param material        a vanilla item material, e.g. {@code golden_hoe}
 * @param name            the vanilla-fallback display name, or {@code null} for none
 * @param customModelData a fixed model, or {@code null}
 */
public record BuiltInItemSpec(String material, @Nullable String name, @Nullable Integer customModelData) {
    public BuiltInItemSpec {
        Objects.requireNonNull(material, "material");
    }
}
