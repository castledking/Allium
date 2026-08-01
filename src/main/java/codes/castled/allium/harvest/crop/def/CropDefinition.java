package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.item.ItemRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, validated definition of one crop. Runtime crop instances never
 * hold references to definition objects — they store the crop id and resolve
 * the definition through the registry, so configuration reloads swap cleanly.
 *
 * @param fallbackPath path used when the weighted roll cannot select any path
 *                     (all weights zero), or null
 * @param interaction how players may take and inspect this crop; defaults come
 *                    from {@code harvest/config.yml}
 */
public record CropDefinition(
    String id,
    String displayName,
    ItemRef seed,
    GrowthRequirements requirements,
    GrowthSettings growth,
    Map<String, CropPathDefinition> paths,
    String fallbackPath,
    InteractionSettings interaction
) {

    public CropDefinition {
        paths = new LinkedHashMap<>(paths);
        if (interaction == null) {
            interaction = InteractionSettings.DEFAULT;
        }
    }

    /** Convenience for callers that do not care about interaction settings. */
    public CropDefinition(String id, String displayName, ItemRef seed,
                          GrowthRequirements requirements, GrowthSettings growth,
                          Map<String, CropPathDefinition> paths, String fallbackPath) {
        this(id, displayName, seed, requirements, growth, paths, fallbackPath,
            InteractionSettings.DEFAULT);
    }

    public Optional<CropPathDefinition> path(String pathId) {
        return Optional.ofNullable(paths.get(pathId));
    }
}
