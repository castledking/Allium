package codes.castled.allium.tool.powertool;

/**
 * Use/charge limits for a powertool (unlimited when {@code max <= 0}).
 */
public record PowerToolUses(
        int max,
        boolean consumeOnEmpty,
        int rechargeSeconds,
        boolean requireSingleItem,
        boolean bypassable
) {
    public static final PowerToolUses UNLIMITED = new PowerToolUses(-1, false, 0, false);

    /**
     * Uses that the bypass permission skips. With {@code bypassable} false, even holders of
     * {@code allium.powertool.bypass} (operators, by default) spend them: a voucher is used up by
     * whoever redeems it.
     */
    public PowerToolUses(int max, boolean consumeOnEmpty, int rechargeSeconds, boolean requireSingleItem) {
        this(max, consumeOnEmpty, rechargeSeconds, requireSingleItem, true);
    }

    public PowerToolUses {
        if (max == 0) {
            max = -1;
        }
        if (rechargeSeconds < 0) {
            rechargeSeconds = 0;
        }
    }

    public boolean isLimited() {
        return max > 0;
    }

    public long rechargeMillis() {
        return rechargeSeconds * 1000L;
    }
}
