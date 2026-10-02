package codes.castled.allium.tradingcards.card;

import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.bukkit.inventory.ItemStack;

/**
 * Turns a rolled card into an item, and rolls the boosts that item carries.
 *
 * <p>Split from the roller because this side touches the item resolver and the
 * config, while the roller stays a pure function of the card table — so the
 * weighted distributions can be tested without a server.
 */
public final class CardFactory {

    /** The three boosts every card drops with, at its tier's base amount. */
    public static final List<String> BASE_SIGNATURES = List.of("luck", "strength", "speed");

    private final ItemResolverChain items;

    /**
     * The bonus pool per tier, and how many to roll.
     *
     * <p>Set after construction rather than passed in, because the factory is
     * built with the card table and the boost catalogue loads later — it needs
     * the economy and the Relique slot in place first. A card that drops before
     * the catalogue arrives is built with no bonuses and never claims otherwise.
     */
    private Map<Tier, List<String>> bonusPool = Map.of();
    private int bonusRollCount = 3;

    public CardFactory(ItemResolverChain items) {
        this.items = items;
    }

    /** Supplies the bonus rolls, from the loaded boost catalogue. */
    public void bonuses(Map<Tier, List<String>> pool, int rollCount) {
        this.bonusPool = pool == null ? Map.of() : Map.copyOf(pool);
        this.bonusRollCount = Math.max(1, rollCount);
    }

    /**
     * Rolls a card's bonus boosts from its tier's pool.
     *
     * <p>Sampled without replacement, so a card never holds the same boost twice.
     * A pool smaller than the roll count yields the whole pool rather than
     * repeating — a duplicate bonus line is worse than a shorter card.
     */
    public List<String> rollBonuses(Tier tier, RandomGenerator random) {
        List<String> pool = bonusPool.get(tier);
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }
        List<String> remaining = new ArrayList<>(pool);
        List<String> rolled = new ArrayList<>();
        int wanted = Math.min(bonusRollCount, remaining.size());
        for (int i = 0; i < wanted; i++) {
            rolled.add(remaining.remove(random.nextInt(remaining.size())));
        }
        return List.copyOf(rolled);
    }

    /**
     * Builds the item for a rolled card: resolves the tier's item, stamps the
     * card state, and gives it its starting signatures.
     *
     * @return empty when the tier has no item or the item does not resolve.
     *         A drop that produced an unidentifiable stack would be worse than
     *         no drop, so the card is skipped rather than handed over broken.
     */
    public Optional<ItemStack> create(CardRoller.RolledCard rolled, TradingCardsConfig config,
                                      RandomGenerator random) {
        // No bonuses on the drop. A bonus slot is bought with its own money, and
        // a drop that handed one over for free made the price a suggestion. Every
        // slot starts empty and the tier decides how many are open to roll.
        return TradingCardData.create(items, rolled.definition(), rolled.tier(),
            config.levelling().startLevel(), rolled.quality(), BASE_SIGNATURES,
            List.of(), false);
    }

    /**
     * Builds a card directly, for the give command and for tests.
     *
     * @param tier     tier to mint; the definition must have an item for it
     * @param quality  1..100
     */
    public Optional<ItemStack> create(CardDefinition definition, Tier tier, int level,
                                      int quality, List<String> signatures, boolean bound,
                                      TradingCardsConfig config, RandomGenerator random) {
        int clampedQuality = Math.max(QualityBand.ROLL_MIN,
            Math.min(QualityBand.ROLL_MAX, quality));
        return TradingCardData.create(items, definition, tier, level, clampedQuality,
            signatures, List.of(), bound);
    }

    /** The signatures a card of this tier starts with. */
    public static List<String> startingSignatures() {
        return BASE_SIGNATURES;
    }
}
