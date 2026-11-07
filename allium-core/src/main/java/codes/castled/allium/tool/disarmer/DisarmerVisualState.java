package codes.castled.allium.tool.disarmer;

/**
 * Which model a built-in Mob Disarmer shows for its state. Keys match the core pack manifest's
 * state keys. Charges between empty and full show the nearest third, never full or empty.
 */
public enum DisarmerVisualState {
    FULL("full"),
    TWO_THIRDS("two_thirds"),
    ONE_THIRD("one_third"),
    EMPTY("empty"),
    BROKEN("broken");

    private final String key;

    DisarmerVisualState(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** Uses the stored state as-is: a finished recharge shows full once it is settled and written. */
    public static DisarmerVisualState of(DisarmerCharges charges, int maxCharges) {
        if (charges.broken()) {
            return BROKEN;
        }
        if (charges.charges() <= 0) {
            return EMPTY;
        }
        if (charges.charges() >= maxCharges) {
            return FULL;
        }
        long thirds = Math.round(3.0D * charges.charges() / maxCharges);
        return thirds >= 2 ? TWO_THIRDS : ONE_THIRD;
    }
}
