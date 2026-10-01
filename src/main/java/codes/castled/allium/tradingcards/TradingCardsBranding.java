package codes.castled.allium.tradingcards;

/**
 * Single source of truth for every user-visible or persisted name the trading
 * card module uses. Rebranding the subsystem only requires touching this class,
 * plugin.yml, and the resource folder name.
 */
public final class TradingCardsBranding {

    /** Display name used in messages and logs. */
    public static final String DISPLAY_NAME = "Allium Trading Cards";

    /** Namespace used for PersistentDataContainer keys and NamespacedKeys. */
    public static final String NAMESPACE = "alliumcards";

    /** Root command label (must match plugin.yml). */
    public static final String COMMAND = "tradingcards";

    /** The morph command players use to toggle their disguise. */
    public static final String MORPH_COMMAND = "morph";

    /** Permission root, e.g. {@code allium.tradingcards.reload}. */
    public static final String PERMISSION_ROOT = "allium.tradingcards";

    /** Folder inside the plugin data folder holding all trading card config. */
    public static final String DATA_FOLDER = "tradingcards";

    /**
     * Relique slot id the card is equipped into. Must match the slot id
     * declared in Relique's own relic data folder.
     */
    public static final String RELIQUE_SLOT = "card";

    private TradingCardsBranding() {}
}
