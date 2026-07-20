package codes.castled.allium.harvest.crop.def;

/**
 * Growth clock behavior of a crop.
 *
 * @param maxCatchUpStages maximum stages advanced at once when a chunk loads
 *                         after offline time
 */
public record GrowthSettings(
    CropClock clock,
    boolean growWhileUnloaded,
    int maxCatchUpStages
) {

    public static final GrowthSettings DEFAULT =
        new GrowthSettings(CropClock.REAL_TIME, true, 10);
}
