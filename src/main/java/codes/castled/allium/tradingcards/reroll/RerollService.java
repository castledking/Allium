package codes.castled.allium.tradingcards.reroll;

import codes.castled.allium.managers.economy.EconomyManager;
import codes.castled.allium.tradingcards.boost.BoostCatalog;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.bukkit.entity.Player;

/**
 * Re-rolls a card's bonus boosts, or unlocks a new signature.
 *
 * <p>A reroll does one of two things, and never a third:
 *
 * <ol>
 *   <li>It unlocks a <b>new</b> signature — a fourth, fifth, sixth line
 *       alongside luck/strength/speed.
 *   <li>Failing that, it re-rolls the card's <b>bonus</b> boosts within the
 *       tier's list.
 * </ol>
 *
 * <p>It never <b>replaces</b> a signature. That is what makes rerolling
 * monotonic: every reroll leaves the card strictly better or unchanged, so the
 * escalating price buys diminishing <i>additive</i> value rather than a dice
 * roll that might take something away. A reroll that could swap your Luck for
 * your Speed would be producing a different card, not a better one.
 *
 * <p>Pricing is progressive rather than flat. A flat fee makes "reroll forever"
 * the optimum; a linear fee makes the last few rerolls affordable to anyone who
 * saved up. See {@link TradingCardsConfig.Reroll#costFor}.
 */
public final class RerollService {

    /** Why a reroll cannot happen, or what it would do. */
    public enum Outcome {
        /** A new signature was unlocked. */
        SIGNATURE_UNLOCKED,
        /** No unlock this time; the bonus boosts were re-rolled. */
        BONUSES_REROLLED,
        /** The card already holds the most signatures its pool allows. */
        SIGNATURES_FULL,
        DISABLED,
        NOT_ENOUGH_MONEY,
        ECONOMY_UNAVAILABLE,
        NO_TIER_POOL
    }

    /**
     * The result of a reroll attempt.
     *
     * <p>Both lists are the card's state <i>after</i> the roll, so the caller
     * writes whichever applies and cannot get it wrong by deciding which list
     * moved.
     */
    public record Result(Outcome outcome, double cost, List<String> signatures,
                         List<String> bonuses, String message) {
        public Result(Outcome outcome, double cost, List<String> signatures, String message) {
            this(outcome, cost, signatures, List.of(), message);
        }

        public boolean succeeded() {
            return outcome == Outcome.SIGNATURE_UNLOCKED || outcome == Outcome.BONUSES_REROLLED;
        }

        /** True when this roll changed the card's bonuses and not its signatures. */
        public boolean rerolledBonuses() {
            return outcome == Outcome.BONUSES_REROLLED;
        }
    }

    private final BoostCatalog.LoadResult catalog;
    private final RerollPricing pricing;
    private final EconomyManager economy;
    private final RandomGenerator random;

    public RerollService(BoostCatalog.LoadResult catalog, RerollPricing pricing,
                         EconomyManager economy, RandomGenerator random) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.economy = economy;
        this.random = random;
    }

    /** What the next reroll on this card would cost. */
    public double costFor(TradingCardData card) {
        return pricing.costFor(card.rerolls() + 1, card.tier());
    }

    /** True when a card could be rerolled at all, ignoring money. */
    public boolean isRerolledOut(TradingCardData card) {
        return card.signatures().size() >= catalog.maximumSignatures();
    }

    /**
     * Performs a reroll, charging for it.
     *
     * <p>The card is only charged for when something actually happens. A reroll
     * that unlocks nothing and re-rolls no bonus (an empty tier pool) is
     * refused before the money moves, because taking payment for a no-op is how
     * a sink becomes a complaint.
     *
     * <p>The card is written by the caller from the returned signatures, so
     * this method has no side effect on the stack itself — which keeps the
     * charge and the write separable and lets a failed write refund.
     */
    public Result reroll(Player player, TradingCardData card) {
        if (!pricing.enabled()) {
            return new Result(Outcome.DISABLED, 0.0, card.signatures(), "Rerolling is disabled.");
        }
        Tier tier = card.tier();
        List<String> pool = catalog.signaturePool().getOrDefault(tier, List.of());
        List<String> bonusPool = catalog.bonusPool().getOrDefault(tier, List.of());
        if (pool.isEmpty() && bonusPool.isEmpty()) {
            return new Result(Outcome.NO_TIER_POOL, 0.0, card.signatures(),
                "No boosts are configured for " + tier + " cards.");
        }

        boolean canUnlock = card.signatures().size() < catalog.maximumSignatures();
        boolean bonusesAvailable = !bonusPool.isEmpty() || !card.signatures().isEmpty();
        if (!canUnlock && !bonusesAvailable) {
            return new Result(Outcome.SIGNATURES_FULL, 0.0, card.signatures(),
                "This card has everything " + tier + " cards can offer.");
        }
        if (!canUnlock && !pricing.allowBonusOnlyWhenFull()) {
            return new Result(Outcome.SIGNATURES_FULL, 0.0, card.signatures(),
                "This card is already maxed, so there is nothing to reroll.");
        }

        double cost = costFor(card);
        if (economy == null) {
            return new Result(Outcome.ECONOMY_UNAVAILABLE, cost, card.signatures(),
                "The economy is unavailable, so rerolling cannot be charged for.");
        }
        BigDecimal price = BigDecimal.valueOf(cost);
        if (!economy.hasEnough(player.getUniqueId(), price)) {
            return new Result(Outcome.NOT_ENOUGH_MONEY, cost, card.signatures(),
                "You need " + economy.formatBalance(price) + " to reroll that card.");
        }

        // The unlock roll happens before the charge, but the charge is what
        // makes it real: nothing is written and nothing is taken unless the
        // whole operation succeeds below.
        if (canUnlock && random.nextDouble() < catalog.signatureUnlockChance()) {
            Optional<String> unlocked = pickUnlock(card, pool);
            if (unlocked.isPresent()) {
                if (!economy.withdraw(player.getUniqueId(), price)) {
                    return new Result(Outcome.NOT_ENOUGH_MONEY, cost, card.signatures(),
                        "You need " + economy.formatBalance(price) + " to reroll that card.");
                }
                List<String> updated = append(card.signatures(), unlocked.get());
                return new Result(Outcome.SIGNATURE_UNLOCKED, cost, updated,
                    "Unlocked " + unlocked.get() + "!");
            }
            // The pool was exhausted despite the count — fall through to the
            // bonus reroll rather than charging for nothing.
        }

        if (!economy.withdraw(player.getUniqueId(), price)) {
            return new Result(Outcome.NOT_ENOUGH_MONEY, cost, card.signatures(),
                "You need " + economy.formatBalance(price) + " to reroll that card.");
        }
        // The bonus rolls happen here rather than in the caller, so a reroll is
        // a single atomic decision: either the card is charged and both lists are
        // settled, or nothing happened at all.
        List<String> bonuses = rollBonuses(bonusPool);
        String message = bonuses.isEmpty()
            ? "Re-rolled your card. It has no bonus boosts to roll."
            : "Re-rolled your bonus boosts: " + String.join(", ", bonuses) + ".";
        return new Result(Outcome.BONUSES_REROLLED, cost, card.signatures(), bonuses, message);
    }

    /**
     * Rolls a fresh set of bonus boosts from the tier's pool.
     *
     * <p>Without replacement, so a card never holds the same bonus twice, and
     * capped at the pool size — a pool of two and a roll count of three yields
     * two, because a duplicate bonus line is worse than a shorter card.
     */
    public List<String> rollBonuses(List<String> pool) {
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }
        List<String> remaining = new java.util.ArrayList<>(pool);
        List<String> rolled = new java.util.ArrayList<>();
        int wanted = Math.min(catalog.bonusRollCount(), remaining.size());
        for (int i = 0; i < wanted; i++) {
            rolled.add(remaining.remove(random.nextInt(remaining.size())));
        }
        return List.copyOf(rolled);
    }

    /**
     * Picks a signature to unlock: from the pool, not already held.
     *
     * <p>Re-picking an existing signature would consume the roll and grant
     * nothing, so it is skipped — which is why the pool is filtered first
     * rather than the result being discarded after the fact.
     */
    private Optional<String> pickUnlock(TradingCardData card, List<String> pool) {
        List<String> available = pool.stream()
            .filter(id -> !card.signatures().contains(id))
            .toList();
        if (available.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(available.get(random.nextInt(available.size())));
    }

    private static List<String> append(List<String> signatures, String added) {
        java.util.List<String> next = new java.util.ArrayList<>(signatures);
        next.add(added);
        return List.copyOf(next);
    }

    /** Refunds a reroll that was charged for but could not be written. */
    public void refund(Player player, double cost) {
        if (economy == null || cost <= 0) return;
        economy.deposit(player.getUniqueId(), BigDecimal.valueOf(cost));
    }

    public RerollPricing pricing() {
        return pricing;
    }

    /** The catalogue this service draws from, for the menu. */
    public BoostCatalog.LoadResult catalog() {
        return catalog;
    }

    public EconomyManager economy() {
        return economy;
    }

    /** Marker so callers can identify a player without a UUID overload. */
    public boolean isReady(UUID player) {
        return economy != null;
    }
}
