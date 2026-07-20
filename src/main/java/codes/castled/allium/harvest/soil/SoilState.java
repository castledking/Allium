package codes.castled.allium.harvest.soil;

import codes.castled.allium.harvest.util.BlockPositionKey;

/**
 * Tracked lifecycle state of one soil block.
 *
 * <p>Soil wears out: once {@link #exhaustedAt()} passes, the block stops
 * accepting new plantings until a retainer fertilizer feeds it again. The
 * record deliberately outlives the block itself — breaking farmland and placing
 * fresh farmland in the same spot resumes the same timer rather than resetting
 * it, which is what stops "mine it and replace it" from being a free reset.
 *
 * @param position     the soil block (the farmland itself, not the crop above)
 * @param establishedAt epoch millis the soil was first tracked
 * @param exhaustedAt  epoch millis the soil becomes unusable, or {@code 0} for
 *                     soil that never wears out
 * @param fertilizerId fertilizer currently worked into the soil, or
 *                     {@code null}; consumed by the next crop planted here
 */
public record SoilState(
    BlockPositionKey position,
    long establishedAt,
    long exhaustedAt,
    String fertilizerId
) {

    /** Whether this soil is worn out as of {@code now}. */
    public boolean isExhausted(long now) {
        return exhaustedAt > 0L && now >= exhaustedAt;
    }

    /** Milliseconds of life left, or {@link Long#MAX_VALUE} when unlimited. */
    public long remainingMillis(long now) {
        if (exhaustedAt <= 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, exhaustedAt - now);
    }

    public SoilState withFertilizer(String newFertilizerId) {
        return new SoilState(position, establishedAt, exhaustedAt, newFertilizerId);
    }

    /**
     * Extends the soil's life by {@code millis}.
     *
     * <p>Extension is measured from whichever is later, now or the current
     * expiry, so feeding healthy soil banks the time instead of wasting it,
     * while feeding dead soil revives it for the full duration.
     */
    public SoilState extendedBy(long millis, long now) {
        if (millis <= 0L || exhaustedAt <= 0L) {
            return this;
        }
        return new SoilState(position, establishedAt, Math.max(now, exhaustedAt) + millis, fertilizerId);
    }
}
