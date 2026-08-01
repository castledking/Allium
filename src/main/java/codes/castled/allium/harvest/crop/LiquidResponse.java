package codes.castled.allium.harvest.crop;

import java.util.Locale;
import java.util.Optional;

/**
 * What happens to a crop when water or lava reaches the cell it stands in.
 *
 * <p>The two vanilla answers are {@link #DROP} and {@link #BURN}. Vanilla asks
 * a block what to do <em>before</em> a fluid replaces it: water returns the
 * block's contents, lava fizzes and consumes them. Either way the block is gone
 * and the fluid keeps flowing, which is why the responses that keep a crop's
 * cell to itself are the odd ones out rather than the default.
 */
public enum LiquidResponse {

    /**
     * Destroyed, returning exactly what a player breaking it would have got —
     * so a finished plant washed out of the ground still pays. This is what
     * water does in vanilla.
     */
    DROP,

    /**
     * Destroyed with a fizz and nothing to show for it. This is what lava does
     * in vanilla: the plant is consumed, not harvested.
     */
    BURN,

    /** Destroyed silently and with no loot — no fizz, no consolation seeds. */
    DESTROY,

    /**
     * The flow is cancelled and the liquid never enters the cell, leaving the
     * crop standing.
     *
     * <p>This is the one response that is <em>not</em> vanilla, and it is not
     * free: a fluid whose spread is refused retries every few ticks for as long
     * as it has somewhere to go, so a crop held under a running stream keeps
     * paying for the refusal. Fine for a handful of protected plots, wrong as a
     * server-wide setting next to an ocean.
     */
    PROTECT;

    /** Whether the crop survives this response. */
    public boolean survives() {
        return this == PROTECT;
    }

    /** @return the matching response, or empty if the value is not recognised */
    public static Optional<LiquidResponse> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
