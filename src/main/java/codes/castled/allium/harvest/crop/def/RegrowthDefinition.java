package codes.castled.allium.harvest.crop.def;

/**
 * Regrowth behavior after harvest.
 *
 * @param stage zero-based stage the crop regresses to after harvest
 */
public record RegrowthDefinition(boolean enabled, int stage) {

    public static final RegrowthDefinition DISABLED = new RegrowthDefinition(false, 0);
}
