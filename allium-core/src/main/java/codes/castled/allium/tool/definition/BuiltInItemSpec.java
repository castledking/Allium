package codes.castled.allium.tool.definition;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code item} section of a tool definition: a built-in {@code allium:<id>} item that is not
 * part of the core pack. Core pack tools (the Mob Disarmer) take their item from the pack
 * manifest instead.
 *
 * @param material        a vanilla item material, e.g. {@code golden_hoe}
 * @param name            the vanilla-fallback display name, or {@code null} for none
 * @param customModelData a fixed model, or {@code null}
 * @param lore            optional lore lines
 * @param enchantments    optional enchantment key to level (1–255)
 * @param unbreakable     whether the item is unbreakable
 * @param hideFlags       optional Bukkit ItemFlag names (or {@code all})
 */
public record BuiltInItemSpec(
        String material,
        @Nullable String name,
        @Nullable Integer customModelData,
        List<String> lore,
        Map<String, Integer> enchantments,
        boolean unbreakable,
        List<String> hideFlags
) {
    public BuiltInItemSpec(String material, @Nullable String name, @Nullable Integer customModelData) {
        this(material, name, customModelData, List.of(), Map.of(), false, List.of());
    }

    public BuiltInItemSpec {
        Objects.requireNonNull(material, "material");
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchantments = enchantments == null || enchantments.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(enchantments));
        hideFlags = hideFlags == null ? List.of() : List.copyOf(hideFlags);
    }
}
