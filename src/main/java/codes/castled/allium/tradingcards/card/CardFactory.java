package codes.castled.allium.tradingcards.card;

import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.List;
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

    public CardFactory(ItemResolverChain items) {
        this.items = items;
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
        return TradingCardData.create(items, rolled.definition(), rolled.tier(),
            config.levelling().startLevel(), rolled.quality(), BASE_SIGNATURES, false);
    }

    /**
     * Builds a card directly, for the give command and for tests.
     *
     * @param tier     tier to mint; the definition must have an item for it
     * @param quality  1..100
     */
    public Optional<ItemStack> create(CardDefinition definition, Tier tier, int level,
                                      int quality, List<String> signatures, boolean bound,
                                      TradingCardsConfig config) {
        int clampedQuality = Math.max(QualityBand.ROLL_MIN,
            Math.min(QualityBand.ROLL_MAX, quality));
        return TradingCardData.create(items, definition, tier, level, clampedQuality,
            signatures, bound);
    }

    /** The signatures a card of this tier starts with. */
    public static List<String> startingSignatures() {
        return BASE_SIGNATURES;
    }
}
