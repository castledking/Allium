package codes.castled.allium.item.delivery;

import java.util.Locale;
import java.util.Optional;

/** What a delivery does with items that do not fit the target's inventory. */
public enum OverflowPolicy {

    /** Insert what fits and drop the rest at the target's feet. */
    DROP("drop"),
    /** Check first; if anything would not fit, give nothing. */
    REFUSE("refuse"),
    /** Insert what fits and report the exact leftover; nothing is dropped. */
    PARTIAL("partial");

    private final String configName;

    OverflowPolicy(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static Optional<OverflowPolicy> fromConfig(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (OverflowPolicy policy : values()) {
            if (policy.configName.equals(normalized)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /** How this policy handles a planned insertion. */
    public Decision decide(InventoryFit.Plan plan) {
        if (plan.fitsAll()) {
            return Decision.INSERT;
        }
        return switch (this) {
            case DROP -> Decision.INSERT_THEN_DROP;
            case REFUSE -> Decision.REFUSE;
            case PARTIAL -> Decision.INSERT_PARTIAL;
        };
    }

    public enum Decision {
        /** Everything fits. */
        INSERT,
        /** Insert what fits, drop the leftover. */
        INSERT_THEN_DROP,
        /** Give nothing. */
        REFUSE,
        /** Insert what fits, keep the leftover undelivered. */
        INSERT_PARTIAL
    }
}
