package codes.castled.allium.harvest.crop.def;

import java.util.Set;
import org.bukkit.Material;

/**
 * Placement and growth requirements of a crop.
 *
 * @param validSoils block types the seed can be planted on
 * @param requiresWater whether farmland-style hydration is required to plant
 * @param minimumLight minimum block light + sky light level at the crop position
 * @param biomes allowed biome keys (lowercase, e.g. {@code minecraft:plains});
 *               empty allows all biomes
 * @param worldAllow allowed world names; empty allows all worlds
 * @param worldDeny denied world names
 * @param permission permission required to plant, or empty for none
 */
public record GrowthRequirements(
    Set<Material> validSoils,
    boolean requiresWater,
    int minimumLight,
    Set<String> biomes,
    Set<String> worldAllow,
    Set<String> worldDeny,
    String permission
) {

    public GrowthRequirements {
        validSoils = Set.copyOf(validSoils);
        biomes = Set.copyOf(biomes);
        worldAllow = Set.copyOf(worldAllow);
        worldDeny = Set.copyOf(worldDeny);
    }
}
