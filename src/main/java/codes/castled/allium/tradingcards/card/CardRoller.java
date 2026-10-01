package codes.castled.allium.tradingcards.card;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The two rolls a card drop makes, and nothing else.
 *
 * <p>Both are single draws that pick exactly one outcome. A card drop is not
 * the place for independent per-entry rolls: two tiers firing from one kill
 * would hand out two cards, and two quality bands from one roll would leave the
 * card with no single describable condition.
 *
 * <p>This class holds no Bukkit types so the rolls can be tested exhaustively
 * against a seeded generator — which is the only way to prove a weighted
 * distribution is actually weighted.
 */
public final class CardRoller {

    private final Map<String, CardDefinition> cards;
    private final List<QualityBand> bands;

    public CardRoller(Map<String, CardDefinition> cards, List<QualityBand> bands) {
        this.cards = Map.copyOf(cards);
        this.bands = List.copyOf(bands);
    }

    /** The outcome of a successful drop. */
    public record RolledCard(CardDefinition definition, Tier tier, int quality, QualityBand band) {}

    /**
     * Rolls a card for a mob kill, or empty when the kill drops nothing.
     *
     * <p>Two independent rolls, in this order: does the mob drop a card at
     * all, and which tier. The quality is a third independent roll that always
     * happens once a card exists, so it is rolled here rather than by the
     * caller.
     */
    public Optional<RolledCard> roll(String mobType, RandomGenerator random) {
        Optional<CardDefinition> found = definitionFor(mobType);
        if (found.isEmpty() || !found.get().isDroppable()) {
            return Optional.empty();
        }
        CardDefinition definition = found.get();
        if (random.nextDouble() >= definition.chance()) {
            return Optional.empty();
        }
        Optional<Tier> tier = rollTier(definition, random);
        if (tier.isEmpty()) {
            return Optional.empty();
        }
        int quality = QualityBand.ROLL_MIN
            + random.nextInt(QualityBand.ROLL_MAX - QualityBand.ROLL_MIN + 1);
        QualityBand band = QualityBand.find(bands, quality);
        if (band == null) {
            // The bands were validated at load, so this is unreachable unless
            // the config was reloaded to a broken state underneath us. Refusing
            // the drop is better than issuing a card with no describable
            // condition.
            return Optional.empty();
        }
        return Optional.of(new RolledCard(definition, tier.get(), quality, band));
    }

    /**
     * Draws one tier from a mob's weights.
     *
     * <p>One number is drawn across the summed weight and the entries are
     * walked until it lands, so exactly one tier can win. Tiers are visited in
     * ascending order, which makes the distribution reproducible for a given
     * seed rather than dependent on map iteration order.
     */
    public Optional<Tier> rollTier(CardDefinition definition, RandomGenerator random) {
        double total = definition.totalWeight();
        if (total <= 0.0) {
            return Optional.empty();
        }
        double roll = random.nextDouble() * total;
        double cursor = 0.0;
        for (Tier tier : definition.droppableTiers()) {
            cursor += definition.weightOf(tier);
            if (roll < cursor) {
                return Optional.of(tier);
            }
        }
        // Floating point can leave roll marginally above the total after the
        // additions, which would otherwise silently drop a card whose tier was
        // rolled. The last droppable tier is the correct answer here.
        List<Tier> droppable = definition.droppableTiers();
        return droppable.isEmpty() ? Optional.empty() : Optional.of(droppable.getLast());
    }

    /** The tier weights of every configured mob, for an inspect listing. */
    public Map<String, Map<Tier, Double>> weightsByCard() {
        Map<String, Map<Tier, Double>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, CardDefinition> entry : cards.entrySet()) {
            Map<Tier, Double> weights = new EnumMap<>(Tier.class);
            for (Tier tier : Tier.values()) {
                double weight = entry.getValue().weightOf(tier);
                if (weight > 0.0) {
                    weights.put(tier, weight);
                }
            }
            out.put(entry.getKey(), weights);
        }
        return out;
    }

    /**
     * The card configured for a mob type name, or null when that mob has none.
     *
     * <p>Keyed by lower-case mob name so callers can pass either
     * {@code CHICKEN} or {@code chicken}.
     */
    public Optional<CardDefinition> definitionFor(String mobType) {
        if (mobType == null) return Optional.empty();
        return Optional.ofNullable(cards.get(mobType.toLowerCase(java.util.Locale.ROOT)));
    }
}
