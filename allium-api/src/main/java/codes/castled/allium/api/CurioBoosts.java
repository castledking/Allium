package codes.castled.allium.api;

import java.util.UUID;

/**
 * Optional service registered in Bukkit's {@code ServicesManager} by Curios, exposing active
 * equipped trading-card boost totals for other Allium addons and third-party bridges.
 *
 * <p>Callers load this service lazily through {@code Bukkit.getServicesManager().load(CurioBoosts.class)}
 * and treat a {@code null} result as zero bonus / 1.0 multiplier, so addons remain decoupled when
 * Curios is not installed.
 */
public interface CurioBoosts {

    /** Additive crop quality bias fraction (e.g. {@code 0.25} for +25% bias toward rarer outcomes). */
    double cropQualityBonus(UUID player);

    /** Additive flat locator-bar transmit range in blocks (never negative). */
    double locatorRangeBonus(UUID player);

    /** Multiplicative locator-bar reach factor (never below {@code 1.0}). */
    double locatorRangeMultiplier(UUID player);

    /** Multiplicative experience factor (never below {@code 1.0}). */
    double xpMultiplier(UUID player);

    /** Multiplicative sell-value factor (never below {@code 1.0}). */
    double sellMultiplier(UUID player);

    /** Flat bonus money awarded on quest completion (never negative). */
    double questMoneyBonus(UUID player);

    /** Flat bonus tokens awarded on quest completion (never negative). */
    double questTokensBonus(UUID player);

    /** Extra drop chance fraction for token drops (clamped {@code 0.0..1.0}). */
    double tokenDropChance(UUID player);

    /** Multiplicative token drop yield factor (never below {@code 1.0}). */
    double tokenDropMultiplier(UUID player);

    /** Helper applying {@code (base * max(1, multiplier)) + max(0, flat)} without shrinking {@code base}. */
    default double effectiveLocatorRadius(UUID player, double baseRadius) {
        double mult = locatorRangeMultiplier(player);
        double flat = locatorRangeBonus(player);
        if (!Double.isFinite(mult) || !Double.isFinite(flat)) {
            return baseRadius;
        }
        return baseRadius * Math.max(1.0, mult) + Math.max(0.0, flat);
    }
}
