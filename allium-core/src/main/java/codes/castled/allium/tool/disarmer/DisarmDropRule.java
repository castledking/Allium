package codes.castled.allium.tool.disarmer;

import java.util.Locale;
import java.util.Optional;

/**
 * Which rule decides whether a removed piece drops ({@code tools.mob-disarmer.drop-rule}).
 *
 * <p>Every removed piece is decided exactly once; pieces that do not drop are destroyed. Either
 * way the slot's drop chance is set to {@code 0} afterwards.
 */
public enum DisarmDropRule {

    /** Every removed piece drops. */
    ALWAYS("always"),

    /**
     * Each piece rolls its slot's own drop chance once, as described by {@link DropChanceRule}.
     * Guaranteed pieces (villager gear and an enderman's carried block) always drop. This is the
     * pre-recode Allium behavior and the default.
     */
    SLOT_CHANCE("slot-chance");

    private final String configName;

    DisarmDropRule(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    /**
     * @param slotChance the slot's drop chance immediately before removal
     * @param guaranteed whether the piece always drops under {@link #SLOT_CHANCE}
     * @param roll       a uniform roll in {@code [0, 1)}, drawn once for this piece
     */
    public boolean drops(float slotChance, boolean guaranteed, float roll) {
        return this == ALWAYS || guaranteed || DropChanceRule.drops(slotChance, roll);
    }

    /** Parses {@code always} or {@code slot-chance} (case-insensitive, {@code _} accepted for {@code -}). */
    public static Optional<DisarmDropRule> fromConfig(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (DisarmDropRule rule : values()) {
            if (rule.configName.equals(normalized)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }
}
