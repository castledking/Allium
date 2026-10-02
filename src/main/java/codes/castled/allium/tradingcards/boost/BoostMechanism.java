package codes.castled.allium.tradingcards.boost;

/**
 * What actually implements a boost.
 *
 * <p>The distinction that matters is ownership of cleanup. {@code ATTRIBUTE}
 * modifiers are torn down by the engine along with the modifier itself, so
 * nothing needs to remember them. The three {@code AURASKILL_*} and
 * {@code CARD_PERMISSION} mechanisms are not: they are held by another plugin
 * and must be removed explicitly, or the boost outlives the card.
 */
public enum BoostMechanism {

    /**
     * One of AuraSkills' nine stats, added flat.
     *
     * <p>Hard requirement for signature boosts: a signature that resolved to
     * anything else would put an AuraSkills stat and a Bukkit attribute in the
     * same column of the card, both labelled "+5", and the player's skills
     * panel could not explain where half their number came from.
     */
    AURASKILL_STAT,

    /** An AuraSkills trait, for boosting one gathering skill rather than all. */
    AURASKILL_TRAIT,

    /**
     * A vanilla Bukkit attribute modifier. Engine-owned: no cleanup needed.
     */
    ATTRIBUTE,

    /**
     * Player scale, as a factor on the player's own size.
     *
     * <p>Never incrementable — "+0.1x player scale" is not a quantity — and
     * always clamped, because unclamped it is a griefing tool: a large player
     * does not fit through doors, a small one is a free win in PvP.
     */
    CARD_SCALE,

    /** Flight speed while a card granting it is equipped. */
    CARD_FLIGHT_SPEED,

    /** Chance to disarm a hostile mob on a landed melee hit. */
    CARD_DISARM,

    /** Flat blocks added to the transmit/waypoint radius. */
    CARD_WAYPOINT_RANGE,

    /** Multiplier on the transmit/waypoint radius, applied before the flat add. */
    CARD_WAYPOINT_MULTIPLIER,

    /** Multiplier on experience gained. */
    CARD_XP_MULTIPLIER,

    /** Multiplier on sell value. */
    CARD_SELL_MULTIPLIER,

    /**
     * Bias toward higher crop quality, registered into Allium's harvest API.
     *
     * <p>Our own because AuraSkills has no notion of a crop: its closest is
     * the FARMING_LUCK trait, which is a per-block drop chance and not a
     * quality roll.
     */
    CARD_CROP_QUALITY,

    /** Money paid on quest completion. */
    CARD_MONEY_ON_QUEST,

    /** Tokens paid on quest completion. */
    CARD_TOKENS_ON_QUEST,

    /** Added chance of a token drop. */
    CARD_TOKEN_DROP_CHANCE,

    /** Multiplier on token drops. */
    CARD_TOKEN_MULTIPLIER,

    /**
     * A permission node held while equipped.
     *
     * <p>Not AuraSkills' doing: its permission nodes only multiply XP and never
     * grant a stat, so this drives the permissions plugin directly.
     */
    CARD_PERMISSION;

    /**
     * True when the amount scales something rather than adding to it.
     *
     * <p>Only affects how the value is written on a card's lore — x1.25 against
     * +2.0 — because a player reading "Experience +1.25" has no way to know
     * whether that is 1.25 times their xp or 1.25 more of it.
     */
    public boolean isMultiplier() {
        return this == CARD_XP_MULTIPLIER
            || this == CARD_SELL_MULTIPLIER
            || this == CARD_WAYPOINT_MULTIPLIER
            || this == CARD_TOKEN_MULTIPLIER
            || this == AURASKILL_TRAIT;
    }

    /** True when this mechanism holds state that must be removed on unequip. */
    public boolean needsExplicitCleanup() {
        return this == AURASKILL_STAT
            || this == AURASKILL_TRAIT
            || this == CARD_PERMISSION;
    }

    /** True when a boost using this mechanism may be a card's signature. */
    public boolean canBeSignature() {
        return this == AURASKILL_STAT;
    }
}
