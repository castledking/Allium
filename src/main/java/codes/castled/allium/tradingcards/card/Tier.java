package codes.castled.allium.tradingcards.card;

/**
 * The five tiers a card can drop at, in ascending order of rarity.
 *
 * <p>Order is the only thing that matters for comparison: a card's tier decides
 * which boost list it draws from, which signature pool a reroll may reach, and
 * the reroll price multiplier. Nothing anywhere compares tiers by weight — a
 * tier's weight is a per-mob drop frequency, and two mobs may weight the same
 * tier very differently.
 */
public enum Tier {

    SIMPLE,
    ELITE,
    ULTIMATE,
    LEGENDARY,
    FABLED;

    /**
     * True when this tier is at least {@code other}. Used for every gate in
     * the feature — morph access, which signature pool a reroll draws from —
     * so a gate never has to know the numeric ordering itself.
     */
    public boolean atLeast(Tier other) {
        return other == null || ordinal() >= other.ordinal();
    }

    /**
     * A filled block and four empty ones, for the tier pips in card lore. The
     * number of filled pips is the tier's position, so a FABLED card reads
     * five filled pips and a SIMPLE card one.
     */
    public String pips() {
        int filled = ordinal() + 1;
        return "◆".repeat(filled) + "◇".repeat(values().length - filled);
    }

    /** The pips followed by this tier's position, e.g. {@code ◆◇◇◇◇  (1/5)}. */
    public String pipsWithPosition() {
        return pips() + "  (" + (ordinal() + 1) + "/" + values().length + ")";
    }
}
