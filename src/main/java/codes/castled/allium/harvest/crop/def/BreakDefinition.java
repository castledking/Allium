package codes.castled.allium.harvest.crop.def;

/**
 * What a crop drops when it is destroyed rather than harvested.
 *
 * <p>Breaking is not harvesting: pulling up a half-grown plant should usually
 * return the seed you put in, while destroying a finished one may be worth
 * more. The two cases therefore get separate tables, chosen by whether the
 * crop had reached its final stage.
 *
 * <p>Both tables default to empty, which reproduces the original behaviour of
 * a broken crop dropping nothing at all.
 */
public record BreakDefinition(HarvestTable immature, HarvestTable mature) {

    public static final BreakDefinition NONE =
        new BreakDefinition(HarvestTable.empty(), HarvestTable.empty());

    public BreakDefinition {
        if (immature == null) immature = HarvestTable.empty();
        if (mature == null) mature = HarvestTable.empty();
    }

    /** The table to roll for a crop broken at the given maturity. */
    public HarvestTable tableFor(boolean isMature) {
        return isMature ? mature : immature;
    }

    public boolean isEmpty() {
        return immature.isEmpty() && mature.isEmpty();
    }
}
