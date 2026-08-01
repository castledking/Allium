package codes.castled.allium.harvest.crop.def;

/**
 * How a mature crop was taken.
 *
 * <p>The two are deliberately separable: right-clicking is "pick the fruit and
 * leave the plant standing", breaking is "pull the whole thing up". A crop can
 * allow either, both or neither, and can regrow to a different stage depending
 * on which one happened.
 */
public enum HarvestSource {

    /** Right-clicked the plant or the soil under it. */
    RIGHT_CLICK,

    /** The plant was punched, or the block it stands on was broken. */
    BREAK
}
