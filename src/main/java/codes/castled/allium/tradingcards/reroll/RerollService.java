package codes.castled.allium.tradingcards.reroll;

import codes.castled.allium.managers.economy.EconomyManager;
import codes.castled.allium.tradingcards.boost.BoostCatalog;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.bukkit.entity.Player;

/** Legacy card-wide rerolls are retired. Only individual bonus slots may be rolled. */
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

    /** Whole-card rerolls are permanently unavailable. */
    public boolean isRerolledOut(TradingCardData card) {
        return true;
    }

    /** Kept for old callers; signature unlock settings can never enable a roll. */
    public Result reroll(Player player, TradingCardData card) {
        return new Result(Outcome.DISABLED, 0.0, card.signatures(),
            "Use the bonus slots menu. Signature boosts are fixed by tier.");
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
