package codes.castled.allium.harvest.crop.def;

/**
 * Harvest behavior of one crop path: the mutually exclusive primary table
 * (quality tiers) and the independent additional table (seeds, bonuses).
 */
public record HarvestDefinition(
    HarvestTable primary,
    HarvestTable additional,
    DeliveryMode delivery
) {

    public static HarvestDefinition empty() {
        return new HarvestDefinition(HarvestTable.empty(), HarvestTable.empty(), DeliveryMode.DROP_AT_CROP);
    }
}
