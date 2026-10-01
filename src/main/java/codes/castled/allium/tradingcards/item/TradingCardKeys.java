package codes.castled.allium.tradingcards.item;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import org.bukkit.NamespacedKey;

/**
 * PersistentDataContainer keys carried by a trading card item.
 *
 * <p>Everything about a card's identity and state lives here rather than in its
 * display name or lore, so a card survives a rename, a retexture, and a
 * rebalance of its tier's config. The lore is derived from these values on
 * every read; it is never the source of truth.
 *
 * <p>The key names are a persistence format. Renaming one orphans every card
 * already in circulation, so treat them as frozen.
 */
public final class TradingCardKeys {

    /**
     * Marks a stack as a trading card. Without it, an item that happens to
     * carry the other keys (or a base material a card renders as) would be
     * mistaken for one.
     */
    public static final NamespacedKey CARD = key("card");

    /** The mob this card depicts, as a Bukkit entity type name. */
    public static final NamespacedKey MOB = key("mob");

    /** The config id of the card definition, e.g. {@code chicken}. */
    public static final NamespacedKey CARD_ID = key("card_id");

    /** Tier name, one of the {@code Tier} constants. */
    public static final NamespacedKey TIER = key("tier");

    /** Current level, 0..maximum. */
    public static final NamespacedKey LEVEL = key("level");

    /**
     * Quality as a plain integer 1..100, never a band name. The band is
     * derived on read so a card dropped under old band boundaries still
     * reports correctly after they move.
     */
    public static final NamespacedKey QUALITY = key("quality");

    /**
     * Unlocked signature boosts, as a comma-separated list of boost ids in
     * unlock order. The order is the display order, so a card reads
     * "Luck, Strength, Speed, Toughness" however it was rolled.
     */
    public static final NamespacedKey SIGNATURES = key("signatures");

    /** How many times this card has been rerolled, for the escalating price. */
    public static final NamespacedKey REROLLS = key("rerolls");

    /**
     * Set on a crafted card. A bound card cannot be traded back in, which is
     * what stops eight heads from becoming a card that buys back eight heads.
     */
    public static final NamespacedKey BOUND = key("bound");

    private TradingCardKeys() {}

    private static NamespacedKey key(String name) {
        return new NamespacedKey(TradingCardsBranding.NAMESPACE, name);
    }
}
