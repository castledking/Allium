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
 */
public record CropDefinition(
    String id,
    String displayName,
    ItemRef seed,
    GrowthRequirements requirements,
    GrowthSettings growth,
    Map<String, CropPathDefinition> paths,
    String fallbackPath
) {

    public CropDefinition {
        paths = new LinkedHashMap<>(paths);
    }

    public Optional<CropPathDefinition> path(String pathId) {
        return Optional.ofNullable(paths.get(pathId));
    }
}
