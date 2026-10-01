package codes.castled.allium.tradingcards.boost;

import java.util.Locale;
import java.util.Map;

/**
 * One entry in the boost catalogue: what it is called, what it does, and how
 * much of it.
 *
 * <p>Boosts are declared once in {@code boosts.yml} and referenced by id from
 * card definitions, so a card never restates a mechanism. Two families exist and
 * they are not interchangeable:
 *
 * <ul>
 *   <li><b>Signature</b> boosts are the three every card drops with (luck,
 *       strength, speed). They are hard-strapped to AuraSkills stats, because
 *       they are the numbers a player compares between cards and they must feed
 *       the same stat pool as everything else on the server.
 *   <li><b>Bonus</b> boosts are everything else — percentages, economy payouts,
 *       range and scale. They are rolled at drop and frozen.
 * </ul>
 *
 * @param id          catalogue id, referenced from card definitions
 * @param display     lore label
 * @param mechanism   what implements it; see {@link BoostMechanism}
 * @param amount      the primary value, meaning depends on the mechanism
 * @param secondary   a second value for mechanisms that need two, else 0
 * @param incrementable whether levelling's +1 means something in this boost's
 *                      unit. Only signatures may be true, because levelling
 *                      only ever grows signatures.
 */
public record BoostDefinition(
    String id,
    String display,
    BoostMechanism mechanism,
    double amount,
    double secondary,
    boolean incrementable,
    Map<String, String> options
) {

    public BoostDefinition {
        options = Map.copyOf(options);
    }

    /** True when levelling may add {@code +1} to this boost. */
    public boolean isSignature() {
        return incrementable;
    }

    public String option(String key, String fallback) {
        return options.getOrDefault(key, fallback);
    }

    public double optionDouble(String key, double fallback) {
        String raw = options.get(key);
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * A boost's total, given a level.
     *
     * <p>Levelling adds {@code perLevel} to one signature boost, not to every
     * boost on the card — so a bonus boost's value is whatever it dropped with,
     * forever. That is deliberate: levelling should deepen what a card already
     * is, never grant a boost the drop did not award.
     *
     * <p>Scale is the exception. It is not incrementable, so levelling never
     * touches it, but it is clamped on every read because a card that rolled a
     * large factor must not exceed the ceiling at any quality or tier.
     */
    public double totalAt(int level, double perLevel, double scaleMin, double scaleMax) {
        if (mechanism == BoostMechanism.CARD_SCALE) {
            return Math.max(scaleMin, Math.min(scaleMax, amount));
        }
        if (!incrementable) {
            return amount;
        }
        return amount + Math.max(0, level) * perLevel;
    }

    public static BoostMechanism parseMechanism(String raw) {
        if (raw == null) return null;
        for (BoostMechanism mechanism : BoostMechanism.values()) {
            if (mechanism.name().equalsIgnoreCase(raw.trim())) {
                return mechanism;
            }
        }
        return null;
    }

    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
