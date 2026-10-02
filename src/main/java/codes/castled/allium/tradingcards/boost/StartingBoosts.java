package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Where a card's starting boosts get their numbers.
 *
 * <p>A starting boost's value is the sum of three rolls:
 *
 * <ul>
 *   <li>a base rolled from the range its quality band names — a rotten card
 *       starts near nothing, an immaculate one starts high;
 *   <li>a share of the card's tier bonus, which is more points the rarer the
 *       tier, dropped on the boosts at random so two cards of the same tier do
 *       not read identically;
 *   <li>one point per level, each level landing on one boost chosen at random.
 * </ul>
 *
 * <h2>Derived, not stored</h2>
 *
 * <p>None of that is written to the card. It is re-derived from the card's
 * immutable identity — its id, mob, quality and tier — every time it is read, so
 * a card's numbers cannot drift from its own record, re-rendering a card
 * reproduces the same numbers, and no card already in circulation needs
 * migrating when a range is rebalanced. A merge raises the tier, and the tier
 * bonus follows it, which is what a player who merged two cards expects.
 *
 * <p>The cost is that rebalancing a band moves every card in that band at once.
 * That is the point of a range rather than a stored value: the ladder is the
 * thing being tuned.
 *
 * <p>Derived from {@link String#hashCode} over the identity, which is specified
 * by the language and therefore stable across JVMs and restarts. A card written
 * today reads the same in six months.
 */
public final class StartingBoosts {

    /** Points added per level, and which boosts grow. */
    public record Rules(double perLevel, int boostsPerLevel) {
        public Rules {
            perLevel = Math.max(0.0, perLevel);
            boostsPerLevel = Math.max(1, boostsPerLevel);
        }
    }

    /**
     * One starting boost's numbers.
     *
     * @param base  the value before any level points, quality roll plus tier share
     * @param points how many level-ups have landed on this boost
     */
    public record Value(double base, int points) {
        public double total(Rules rules) {
            return base + points * rules.perLevel();
        }

        /** The {@code (+N)} suffix, or empty when nothing has levelled yet. */
        public String pointsSuffix() {
            // Every text segment is wrapped in its own tag pair rather than
            // nested. MiniMessage collapses a redundant nested tag into a bare
            // node with no colour of its own, and the client resolves that to
            // the default lore colour instead of the dark grey enclosing it.
            return points <= 0 ? ""
                : "<dark_gray>(</dark_gray><white>+" + points + "</white><dark_gray>)</dark_gray>";
        }
    }

    /** Tier bonus in points, rarest tier highest. */
    private static final Map<Tier, Integer> TIER_BONUS = tierBonus();

    private static Map<Tier, Integer> tierBonus() {
        Map<Tier, Integer> map = new EnumMap<>(Tier.class);
        map.put(Tier.SIMPLE, 1);
        map.put(Tier.ELITE, 2);
        map.put(Tier.ULTIMATE, 3);
        map.put(Tier.LEGENDARY, 4);
        map.put(Tier.FABLED, 5);
        return Map.copyOf(map);
    }

    /** Points a card of this tier gains on top of its quality roll. */
    public static int tierBonus(Tier tier) {
        return TIER_BONUS.get(tier == null ? Tier.SIMPLE : tier);
    }

    private final List<QualityBand> bands;
    private final Rules rules;

    public StartingBoosts(List<QualityBand> bands, Rules rules) {
        this.bands = bands == null ? List.of() : List.copyOf(bands);
        this.rules = rules == null ? new Rules(1.0, 1) : rules;
    }

    public Rules rules() {
        return rules;
    }

    /**
     * The numbers for every starting boost on a card, in the order the card
     * lists them.
     *
     * <p>Every roll is taken from one generator seeded by the card's identity,
     * in a fixed order, so the same card always produces the same numbers.
     */
    public List<Value> valuesFor(TradingCardData card, List<QualityBand> cardBands) {
        List<String> signatures = card.signatures();
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }
        int n = signatures.size();
        Random rng = new Random(seed(card));

        QualityBand band = bandFor(card.quality(), cardBands);
        int low = band == null ? 1 : band.boostMin();
        int high = band == null ? 1 : band.boostMax();
        int span = Math.max(0, high - low);

        int[] points = new int[n];
        double[] base = new double[n];
        for (int i = 0; i < n; i++) {
            base[i] = low + (span == 0 ? 0 : rng.nextInt(span + 1));
        }

        // The tier bonus is dropped on the boosts at random, one point at a time,
        // so a rarer card is strictly stronger and no two cards share a shape.
        int bonus = tierBonus(card.tier());
        for (int b = 0; b < bonus; b++) {
            base[rng.nextInt(n)] += 1.0;
        }

        // Each level lands on boostsPerLevel of them, without replacement within
        // the level, so one level cannot pile several points onto a single boost.
        int level = Math.max(0, card.level());
        for (int l = 0; l < level; l++) {
            int[] picked = new int[Math.min(rules.boostsPerLevel(), n)];
            for (int i = 0; i < picked.length; i++) {
                picked[i] = rng.nextInt(n);
                for (int j = 0; j < i; j++) {
                    if (picked[j] == picked[i]) {
                        picked[i] = rng.nextInt(n);
                        j = -1;
                    }
                }
            }
            for (int p : picked) {
                points[p]++;
            }
        }

        Value[] out = new Value[n];
        for (int i = 0; i < n; i++) {
            out[i] = new Value(base[i], points[i]);
        }
        return List.of(out);
    }

    /** The value for one signature, by its position on the card. */
    public Value valueAt(TradingCardData card, List<QualityBand> cardBands, int index) {
        List<Value> values = valuesFor(card, cardBands);
        if (index < 0 || index >= values.size()) {
            return new Value(0.0, 0);
        }
        return values.get(index);
    }

    public QualityBand bandFor(int quality, List<QualityBand> cardBands) {
        List<QualityBand> source = cardBands == null || cardBands.isEmpty() ? bands : cardBands;
        for (QualityBand band : source) {
            if (band != null && band.contains(quality)) {
                return band;
            }
        }
        return null;
    }

    /**
     * A stable seed for a card's identity.
     *
     * <p>{@code String.hashCode} is specified by the language, so this does not
     * change between JVMs the way an identity hash would. Mixed once because
     * hashCode clusters similar strings, and the identity strings differ only in
     * a mob name or a small integer.
     */
    private static long seed(TradingCardData card) {
        String identity = String.join("|",
            card.cardId() == null ? "" : card.cardId(),
            card.mob() == null ? "" : card.mob(),
            String.valueOf(card.quality()),
            card.tier() == null ? Tier.SIMPLE.name() : card.tier().name());
        long h = 1125899906842597L;
        for (byte b : identity.getBytes(StandardCharsets.UTF_8)) {
            h = 31 * h + (b & 0xff);
        }
        return h ^ (h >>> 32);
    }
}