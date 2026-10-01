package codes.castled.allium.tradingcards.trade;

import codes.castled.allium.tradingcards.card.QualityBand;
import java.util.List;
import codes.castled.allium.tradingcards.item.TradingCardData;

/**
 * What a card is worth, and whether it may be traded.
 *
 * <p>The payout is a lookup, not a calculation. A card at 92% quality belongs
 * to the band that covers 92, and that band names its own head value — so
 * rebalancing the ladder is an edit to the band table rather than to a formula,
 * and a card's value can never disagree with the number its lore shows.
 *
 * <p>Band membership is derived from the stored integer, never stored as a
 * name, so a card minted before a boundary moved still trades for what its
 * current band is worth rather than for a frozen historical rate.
 */
public final class TradeQuote {

    /** Why a card cannot be traded right now. */
    public enum Denial {
        TRADE_DISABLED("Trading cards cannot be traded in."),
        BELOW_MINIMUM_QUALITY("This card is not in good enough condition to trade."),
        UNKNOWN_QUALITY("This card's quality falls outside every band, so it has no value."),
        BOUND("This card was crafted and cannot be traded back for heads."),
        NO_HEAD("No head is configured for this mob.");

        private final String message;

        Denial(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /**
     * A completed quote.
     *
     * @param heads how many heads this card pays — a band value, not a
     *              percentage, so 1..8 rather than 0.0..1.0
     */
    public record Quote(int heads, String bandId) {}

    private TradeResolver resolver;
    private boolean tradeEnabled = true;
    private int minimumHeads = 0;

    /**
     * @param requireMintOrBetter refuse anything below the second-highest band,
     *                            so the trash end of the ladder has no value
     */
    public void configure(TradeResolver resolver, boolean tradeEnabled,
                          boolean requireMintOrBetter) {
        this.resolver = resolver;
        this.tradeEnabled = tradeEnabled;
        this.minimumHeads = requireMintOrBetter ? mintThreshold(resolver) : 0;
    }

    /**
     * The head value a card must reach to be tradeable.
     *
     * <p>Derived from the band table rather than hardcoded, so raising the
     * top band's payout raises the floor with it and the two can never
     * disagree. Falls back to 0 when there are fewer than two bands, which
     * disables the gate rather than refusing everything.
     */
    private static int mintThreshold(TradeResolver resolver) {
        var bands = resolver == null ? List.<QualityBand>of() : resolver.bands();
        if (bands.size() < 2) {
            return 0;
        }
        // Sorted by lower bound, not taken from config order: an operator who
        // lists their bands out of order would otherwise have the threshold
        // picked from whichever band happens to sit second in the file.
        return QualityBand.sorted(bands).get(bands.size() - 2).heads();
    }

    /**
     * Values a card, or explains why it cannot be valued.
     *
     * <p>A bound card is refused before its band is even looked up: a crafted
     * card trades for heads, and heads craft cards, so a bound card that could
     * be traded would close an infinite loop.
     */
    public Result quote(TradingCardData card) {
        if (!tradeEnabled) {
            return Result.denied(Denial.TRADE_DISABLED);
        }
        if (card.bound()) {
            return Result.denied(Denial.BOUND);
        }
        if (resolver == null) {
            return Result.denied(Denial.UNKNOWN_QUALITY);
        }
        var band = card.band(resolver.bands());
        if (band == null) {
            return Result.denied(Denial.UNKNOWN_QUALITY);
        }
        if (band.heads() < minimumHeads) {
            return Result.denied(Denial.BELOW_MINIMUM_QUALITY);
        }
        return Result.quoted(new Quote(band.heads(), band.id()));
    }

    /** One of quoted-or-denied, so a caller never handles null. */
    public record Result(Quote quote, Denial denial) {
        public static Result quoted(Quote quote) {
            return new Result(quote, null);
        }

        public static Result denied(Denial denial) {
            return new Result(null, denial);
        }

        public boolean isQuoted() {
            return quote != null;
        }
    }

    /** Supplies the band table without the caller needing the config record. */
    @FunctionalInterface
    public interface TradeResolver {
        java.util.List<codes.castled.allium.tradingcards.card.QualityBand> bands();
    }
}
