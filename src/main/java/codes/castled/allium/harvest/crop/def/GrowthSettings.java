package codes.castled.allium.harvest.crop.def;

/**
 * Growth clock behavior of a crop.
 *
 * @param maxCatchUpStages maximum stages advanced at once when a chunk loads
 *                         after offline time
 * @param randomness symmetric spread applied to every stage duration, as a
 *                   fraction: {@code 0.0} is exact, {@code 0.25} means a stage
 *                   takes anywhere from 75% to 125% of its configured
 *                   duration. See
 *                   {@link codes.castled.allium.harvest.crop.StageDuration}
 *                   for why the roll is derived rather than stored.
 */
public record GrowthSettings(
    CropClock clock,
    boolean growWhileUnloaded,
    int maxCatchUpStages,
    double randomness
) {

    public static final GrowthSettings DEFAULT =
        new GrowthSettings(CropClock.REAL_TIME, true, 10, 0.25D);

    public GrowthSettings {
        randomness = codes.castled.allium.harvest.crop.StageDuration.clampRandomness(randomness);
    }

    /** Legacy shorthand for exact, unrandomised growth. */
    public GrowthSettings(CropClock clock, boolean growWhileUnloaded, int maxCatchUpStages) {
        this(clock, growWhileUnloaded, maxCatchUpStages, 0.0D);
    }
}
