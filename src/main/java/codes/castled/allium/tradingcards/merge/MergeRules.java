package codes.castled.allium.tradingcards.merge;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.List;
import java.util.Optional;

/**
 * Whether two cards may be merged, and what the result would be.
 *
 * <p>A merge is two cards of the <b>same mob</b> at <b>maximum level</b> of the
 * <b>same tier</b> becoming one card of the next tier at level 0.
 *
 * <p>Level 0 is deliberate. A dropped card also starts at level 0, so a merged
 * card is worth exactly what a fresh drop of the next tier would be worth.
 * Merging therefore converts <i>spare</i> cards into progress rather than
 * granting a head start — had it landed anywhere above a natural drop, merging
 * would have been a strictly better source than killing the mob, and players
 * would have farmed duplicates instead of playing.
 *
 * <p>All three conditions are checked, and a partial match names what
 * mismatched. A vague refusal is how a merge feature loses player trust: someone
 * merges two cards, gets a confusing result, and never tries again.
 */
public final class MergeRules {

    private final boolean enabled;
    private final boolean requireSameMob;
    private final int maximumLevel;
    private final int targetLevel;

    public MergeRules(boolean enabled, boolean requireSameMob, int maximumLevel, int targetLevel) {
        this.enabled = enabled;
        this.requireSameMob = requireSameMob;
        this.maximumLevel = maximumLevel;
        this.targetLevel = targetLevel;
    }

    /** Why a merge cannot happen. */
    public enum Denial {
        DISABLED("Merging is disabled."),
        DIFFERENT_MOBS("Those are different mobs."),
        DIFFERENT_TIERS("Both cards must be the same tier."),
        NOT_MAXIMUM("Both cards must be at the maximum level first."),
        TOP_TIER("There is no tier above that one to merge into.");

        private final String defaultMessage;

        Denial(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String defaultMessage() {
            return defaultMessage;
        }
    }

    /** The outcome of a merge check. */
    public record Verdict(boolean allowed, Denial denial, Tier resultTier, String message) {
        public static Verdict ok(Tier resultTier) {
            return new Verdict(true, null, resultTier,
                "Merged into a " + resultTier + " card.");
        }

        public static Verdict no(Denial denial, String message) {
            return new Verdict(false, denial, null, message);
        }
    }

    /**
     * Checks a merge without performing it.
     *
     * @param first  the card in the first slot
     * @param second the card in the second slot
     */
    public Verdict check(TradingCardData first, TradingCardData second) {
        if (!enabled) {
            return Verdict.no(Denial.DISABLED, "Merging is disabled.");
        }
        if (first == null || second == null) {
            return Verdict.no(Denial.DIFFERENT_MOBS, "Put two trading cards in the slots.");
        }
        if (requireSameMob && !first.mob().equalsIgnoreCase(second.mob())) {
            return Verdict.no(Denial.DIFFERENT_MOBS,
                "Those are different mobs — " + display(first.mob()) + " and "
                    + display(second.mob()) + ".");
        }
        if (first.tier() != second.tier()) {
            return Verdict.no(Denial.DIFFERENT_TIERS,
                "Both cards must be the same tier — those are " + first.tier()
                    + " and " + second.tier() + ".");
        }
        if (!isMaximum(first) || !isMaximum(second)) {
            return Verdict.no(Denial.NOT_MAXIMUM,
                "Both cards must be level " + maximumLevel + " first — "
                    + first.tier().name().toLowerCase(java.util.Locale.ROOT)
                    + " is at " + first.level() + " and level " + second.level() + ".");
        }
        Optional<Tier> next = nextTier(first.tier());
        if (next.isEmpty()) {
            return Verdict.no(Denial.TOP_TIER,
                first.tier() + " is the highest tier, so there is nothing to merge into.");
        }
        return Verdict.ok(next.get());
    }

    /** True when a card is old enough to be consumed by a merge. */
    public boolean isMaximum(TradingCardData card) {
        return card != null && card.level() >= maximumLevel;
    }

    public boolean canMerge(TradingCardData first, TradingCardData second) {
        return check(first, second).allowed();
    }

    /** The tier a merge of {@code tier} produces, or empty at the top. */
    public Optional<Tier> nextTier(Tier tier) {
        List<Tier> values = List.of(Tier.values());
        int index = values.indexOf(tier);
        if (index < 0 || index + 1 >= values.size()) {
            return Optional.empty();
        }
        return Optional.of(values.get(index + 1));
    }

    /**
     * The signature list a merged card starts with.
     *
     * <p>The three every card drops with, not the union of both inputs: a merge
     * produces a card of a higher tier, and its signatures are that tier's
     * identity. Carrying a FABLED card's lucky unlock into an ELITE card would
     * give the merged card a signature its own tier's pool could not roll.
     */
    public List<String> mergedSignatures() {
        return codes.castled.allium.tradingcards.card.CardFactory.BASE_SIGNATURES;
    }

    /** The level a merged card lands at. */
    public int targetLevel() {
        return targetLevel;
    }

    public int maximumLevel() {
        return maximumLevel;
    }

    private static String display(String mob) {
        if (mob == null || mob.isBlank()) return "nothing";
        String lower = mob.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
