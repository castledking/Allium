package codes.castled.allium.harvest.sprinkler;

import codes.castled.allium.harvest.item.ItemRef;

/**
 * One configured sprinkler tier.
 *
 * <p>Tiers are independent definitions, not levels of a formula: a tier-3
 * sprinkler is a separate entry with its own radius and multiplier, so its
 * numbers can be tuned without disturbing tiers 1 and 2.
 *
 * @param radius            how far the sprinkler reaches, in blocks, as a
 *                          square radius around its own column
 * @param speedMultiplier   multiplier applied to stage durations of covered
 *                          crops; below {@code 1.0} grows faster
 * @param keepsSoilMoist    whether coverage satisfies a crop's water
 *                          requirement, standing in for adjacent water
 * @param soilRetainMillis  soil life granted to covered soil each upkeep pass,
 *                          or {@code 0} for a sprinkler that does not feed soil
 * @param model             optional display item shown on the sprinkler block
 */
public record SprinklerDefinition(
    String id,
    ItemRef item,
    String displayName,
    int radius,
    double speedMultiplier,
    boolean keepsSoilMoist,
    long soilRetainMillis,
    ItemRef model
) {

    public SprinklerDefinition {
        if (radius < 0) radius = 0;
        if (speedMultiplier <= 0.0D) speedMultiplier = 1.0D;
        if (soilRetainMillis < 0L) soilRetainMillis = 0L;
    }

    /** Whether a block offset falls inside this sprinkler's coverage. */
    public boolean covers(int dx, int dz) {
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
    }
}
