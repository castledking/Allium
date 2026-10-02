package codes.castled.allium.tradingcards.card;

import codes.castled.allium.item.ItemRef;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything configured about one mob's card: which mob it represents, the
 * item each tier renders as, and how often it drops.
 *
 * <p>Tiers are weights rather than a fixed assignment, so a single mob can be
 * tuned across the whole ladder. Two mobs may weight the same tier very
 * differently without either being "wrong" — a chicken and a wither are not
 * the same proposition.
 *
 * @param id     lower-case config key, e.g. {@code chicken}
 * @param mob    Bukkit entity type name, e.g. {@code CHICKEN}
 * @param colour MiniMessage colour for the card's title, e.g. {@code aqua}. Lives
 *               here rather than in the item definition because the title moved
 *               out of the item name and into the tooltip's first lore line,
 *               and the name itself is now blank.
 * @param chance probability in (0,1] that a kill of this mob drops a card at all
 * @param tiers  weight per tier, ascending by {@link Tier#ordinal()}
 * @param items  the item each tier renders as, keyed by tier
 * @param head   item paid out on trade-in, or empty to fall back to Allium's
 *               own mob head for this mob
 */
public record CardDefinition(
    String id,
    String mob,
    String colour,
    double chance,
    Map<Tier, Double> tiers,
    Map<Tier, ItemRef> items,
    ItemRef head
) {

    public static final int DEFAULT_CHANCE_PRECISION = 6;

    /** Used when a mob sets no colour, so a title is never unstyled. */
    public static final String DEFAULT_COLOUR = "white";

    public CardDefinition {
        tiers = Map.copyOf(tiers);
        items = Map.copyOf(items);
        colour = colour == null || colour.isBlank() ? DEFAULT_COLOUR : colour;
    }

    /** Total of the tier weights, or zero when none are positive. */
    public double totalWeight() {
        return tiers.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** True when at least one tier can actually drop. */
    public boolean isDroppable() {
        return chance > 0.0 && totalWeight() > 0.0;
    }

    public double weightOf(Tier tier) {
        return tiers.getOrDefault(tier, 0.0);
    }

    /**
     * Chance a kill of this mob produces a card of {@code tier}, accounting for
     * both the drop roll and the tier's share of the weight.
     *
     * <p>Returned rather than composed at each call site so the real
     * difficulty of a specific outcome is inspectable — it is the number that
     * says whether a FABLED Emaculate chicken is a trophy or a myth.
     */
    public double chanceOf(Tier tier) {
        double total = totalWeight();
        if (total <= 0.0) return 0.0;
        return chance * (weightOf(tier) / total);
    }

    public ItemRef itemFor(Tier tier) {
        return items.get(tier);
    }

    /**
     * The tier this mob's {@code displayName} refers to, resolved case
     * insensitively. Returns null for an unrecognised name.
     */
    public static Tier parseTier(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Tier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A human-readable list of a tier map, for validation messages. */
    public static String describeWeights(Map<Tier, Double> weights) {
        return weights.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + trim(e.getValue()))
            .toList()
            .toString();
    }

    /** Renders a weight or chance without a trailing run of zeros. */
    public static String trim(double value) {
        String s = String.format(Locale.ROOT, "%.4f", value);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s + "0" : s;
    }

    /** The tiers with a positive weight, in ascending order. */
    public List<Tier> droppableTiers() {
        return tiers.entrySet().stream()
            .filter(e -> e.getValue() > 0.0)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }
}
