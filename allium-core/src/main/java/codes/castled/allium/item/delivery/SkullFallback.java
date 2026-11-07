package codes.castled.allium.item.delivery;

import java.util.Locale;
import java.util.Optional;

/** {@code items.give.skull-timeout-fallback}: what a delivery does when a skull's skin cannot be resolved. */
public enum SkullFallback {

    /** Give the head without its skin; the delivery reports {@link DeliveryOutcome.Status#DEGRADED}. */
    UNTEXTURED("untextured"),
    /** Give nothing; the delivery reports {@link DeliveryOutcome.Status#REFUSED_UNRESOLVED}. */
    REFUSE("refuse");

    private final String configName;

    SkullFallback(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static Optional<SkullFallback> fromConfig(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (SkullFallback fallback : values()) {
            if (fallback.configName.equals(normalized)) {
                return Optional.of(fallback);
            }
        }
        return Optional.empty();
    }
}
