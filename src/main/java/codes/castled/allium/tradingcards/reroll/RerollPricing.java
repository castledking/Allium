package codes.castled.allium.tradingcards.reroll;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;

/**
 * The reroll price ladder.
 *
 * <p>Exists separately from {@link RerollService} so the price can be computed
 * and shown before any money moves, and so it can be tested without an economy
 * or a running server.
 *
 * <p>Progressive by design. The two alternatives are both worse:
 *
 * <ul>
 *   <li><b>Flat</b> — every reroll costs the same, so the optimum is "reroll
 *       until you get what you want", and a rich player converges on a perfect
 *       card while everyone else gives up at the first loss.
 *   <li><b>Linear</b> — each reroll costs a bit more, but predictably so. A
 *       player who saved up can always afford the last few, which is where the
 *       interesting rolls are.
 * </ul>
 *
 * <p>Escalation above 1 makes each reroll dearer than the last, so a reroll
 * always trades money for a diminishing chance at a better roll. The tier
 * multiplier sits on top, because a FABLED reroll should not merely be the same
 * price one step further along the same ladder.
 */
public record RerollPricing(
    boolean enabled,
    double baseCost,
    double escalation,
    long roundTo,
    double maximumCost,
    java.util.Map<Tier, Double> tierMultipliers,
    double signatureUnlockChance,
    int maximumSignatures,
    boolean allowBonusOnlyWhenFull
) {

    public RerollPricing {
        tierMultipliers = java.util.Map.copyOf(tierMultipliers);
    }

    public static RerollPricing from(TradingCardsConfig.Reroll config) {
        return new RerollPricing(
            config.enabled(),
            config.baseCost(),
            config.escalation(),
            config.roundTo(),
            config.maximumCost(),
            config.tierMultipliers(),
            config.signatureUnlockChance(),
            config.maximumSignatures(),
            config.allowBonusOnlyWhenFull());
    }

    /**
     * The cost of the {@code attempt}-th reroll on a card of {@code tier},
     * counting from 1.
     *
     * <p>Rounded to {@code roundTo} so a price never reads as 3141.59 — a
     * player paying a rounded number is a player who can predict what the next
     * one costs, which is the whole point of showing a ladder.
     */
    public double costFor(int attempt, Tier tier) {
        if (attempt < 1) attempt = 1;
        double tierMultiplier = tierMultipliers.getOrDefault(tier, 1.0);
        double raw = baseCost * Math.pow(escalation, attempt - 1.0) * tierMultiplier;
        if (maximumCost > 0.0 && raw > maximumCost) {
            raw = maximumCost;
        }
        long step = Math.max(1L, roundTo);
        raw = Math.round(raw / step) * (double) step;
        return Math.max((double) step, raw);
    }

    /** The cost of the next reroll on a card that has been rerolled {@code times}. */
    public double nextCost(int rerollsSoFar, Tier tier) {
        return costFor(rerollsSoFar + 1, tier);
    }

    /** True when the ladder is strictly increasing, which is the design goal. */
    public boolean isProgressive() {
        double previous = 0.0;
        for (int attempt = 1; attempt <= 12; attempt++) {
            double cost = costFor(attempt, Tier.SIMPLE);
            if (cost <= previous && cost < maximumCost) {
                return false;
            }
            previous = cost;
        }
        return true;
    }

    /** How many rerolls a budget buys at this tier, for a menu hint. */
    public int rerollsAffordable(double budget, Tier tier) {
        int count = 0;
        double spent = 0.0;
        for (int attempt = 1; attempt <= 200; attempt++) {
            double cost = costFor(attempt, tier);
            if (spent + cost > budget) {
                break;
            }
            spent += cost;
            count++;
        }
        return count;
    }
}
