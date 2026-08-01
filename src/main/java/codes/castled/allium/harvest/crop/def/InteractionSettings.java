package codes.castled.allium.harvest.crop.def;

/**
 * How players may interact with a crop. Global defaults live in
 * {@code harvest/config.yml}; each crop file may override any of them in its
 * own {@code interaction:} block.
 *
 * @param rightClickHarvest whether right-clicking a mature crop harvests it.
 *                          Turning this off makes breaking the only way to
 *                          take a crop, which is what reserves right-click for
 *                          inspecting the plant.
 * @param breakHarvest      whether breaking a <em>mature</em> crop runs its
 *                          {@code mature-harvest} table instead of
 *                          {@code break-drops.mature}. Immature crops always
 *                          use {@code break-drops.immature} — there is nothing
 *                          to harvest yet.
 * @param progressDisplay   what a player learns by right-clicking a crop that
 *                          is still growing
 */
public record InteractionSettings(
    boolean rightClickHarvest,
    boolean breakHarvest,
    ProgressDisplay progressDisplay
) {

    /**
     * How much a growing crop tells the player about its own timing.
     *
     * <p>{@code TIME} only makes sense for perfectly deterministic growth. Once
     * {@code growth.randomness} is in play the countdown is a prediction rather
     * than a promise, so {@code HINT} — which describes progress instead of
     * quoting a number — stays honest.
     */
    public enum ProgressDisplay {
        /** "Growing — next stage in 4m 12s". */
        TIME,
        /** "Coming along." — a bucket, never a number. */
        HINT,
        /** Right-clicking a growing crop is completely silent. */
        OFF;

        /**
         * Reads a configured value, tolerating YAML's opinion about what
         * {@code OFF} means.
         *
         * <p>SnakeYAML follows YAML 1.1, where the bare words {@code off},
         * {@code on}, {@code no} and {@code yes} are <em>booleans</em>. So
         * {@code progress-check: OFF} arrives here as the string "false" and
         * {@link #valueOf} would reject it — leaving the setting silently on
         * its default, which is the opposite of what was asked for. Quoting
         * ({@code "OFF"}) avoids the coercion, but nobody should have to know
         * that, so the coerced spellings are accepted directly.
         *
         * @return the matching mode, or empty if the value is not recognised
         */
        public static java.util.Optional<ProgressDisplay> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return java.util.Optional.empty();
            }
            String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
            // What YAML 1.1 turns `off`/`no` into before we ever see it.
            if (value.equals("FALSE") || value.equals("NO")) {
                return java.util.Optional.of(OFF);
            }
            try {
                return java.util.Optional.of(valueOf(value));
            } catch (IllegalArgumentException e) {
                return java.util.Optional.empty();
            }
        }
    }

    public static final InteractionSettings DEFAULT =
        new InteractionSettings(true, true, ProgressDisplay.HINT);
}
