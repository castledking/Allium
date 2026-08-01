package codes.castled.allium.harvest.crop;

import java.util.UUID;

/**
 * How long one crop spends in one stage.
 *
 * <p>A configured {@code duration} is a <em>mean</em>, not a promise. Vanilla
 * crops advance on random ticks, so two seeds planted together never finish
 * together; a fixed duration makes custom crops feel like an egg timer instead.
 * {@code growth.randomness} restores that by spreading each stage symmetrically
 * around its configured length — {@code 0.25} on a 30m stage gives 22m30s to
 * 37m30s.
 *
 * <p>The roll is <strong>derived, not stored</strong>. Hashing the crop's
 * instance id together with the stage index yields a value that is stable for
 * the life of that crop but unrelated between crops or between stages. That
 * matters because growth is not always computed forward one stage at a time:
 * {@link CatchUp} replays stages in bulk when a chunk loads after offline time,
 * and it has to arrive at exactly the timings the growth engine would have
 * produced. A stored per-stage roll would need to survive the database and the
 * unloaded period to do that; a derived one is reproducible from what the crop
 * already knows.
 *
 * <p>Because it is keyed on the instance id, replanting is a genuinely fresh
 * roll — a player cannot learn "this spot is a fast one" and farm it.
 */
public final class StageDuration {

    /** Beyond this a "30 minute" stage stops meaning anything useful. */
    public static final double MAXIMUM_RANDOMNESS = 0.9D;

    private StageDuration() {
    }

    public static double clampRandomness(double randomness) {
        if (!Double.isFinite(randomness) || randomness <= 0.0D) {
            return 0.0D;
        }
        return Math.min(MAXIMUM_RANDOMNESS, randomness);
    }

    /**
     * The effective length of {@code stageIndex} for one specific crop, with
     * jitter applied first and the growth-speed multiplier on top.
     *
     * <p>Order matters: jitter scales the configured duration, so a fertilizer
     * that halves growth still halves it. The alternative — jittering after the
     * multiplier — would let a fast crop occasionally come out slower than a
     * slow one, which reads as the fertilizer having failed.
     */
    public static long forStage(
        UUID instanceId,
        int stageIndex,
        long baseDurationMs,
        double speedMultiplier,
        double randomness
    ) {
        return GrowthSpeed.apply(jitter(instanceId, stageIndex, baseDurationMs, randomness), speedMultiplier);
    }

    /**
     * Applies the symmetric spread alone. A zero-length stage (the mature one)
     * and zero randomness both pass straight through.
     */
    public static long jitter(UUID instanceId, int stageIndex, long baseDurationMs, double randomness) {
        double spread = clampRandomness(randomness);
        if (baseDurationMs <= 0L || spread <= 0.0D || instanceId == null) {
            return baseDurationMs;
        }
        // unit in [0,1) -> factor in [1-spread, 1+spread)
        double factor = 1.0D + spread * (2.0D * unit(instanceId, stageIndex) - 1.0D);
        return Math.max(1L, Math.round(baseDurationMs * factor));
    }

    /**
     * A uniform value in {@code [0,1)} for this crop and stage.
     *
     * <p>SplitMix64's finalizer: it avalanches well enough that adjacent stage
     * indices of the same crop produce unrelated values, which is the whole
     * point — otherwise every stage of a given crop would be jittered the same
     * direction and the crop would simply be uniformly fast or slow.
     */
    private static double unit(UUID instanceId, int stageIndex) {
        long z = instanceId.getMostSignificantBits() * 0x9E3779B97F4A7C15L;
        z ^= instanceId.getLeastSignificantBits();
        z += stageIndex * 0x632BE59BD9B4E019L;

        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }
}
