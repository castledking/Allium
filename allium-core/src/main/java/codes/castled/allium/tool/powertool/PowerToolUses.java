package codes.castled.allium.tool.powertool;

/**
 * Use/charge limits for a powertool (unlimited when {@code max <= 0}).
 */
public record PowerToolUses(
        int max,
        boolean consumeOnEmpty,
        int rechargeSeconds,
        boolean requireSingleItem
) {
    public static final PowerToolUses UNLIMITED = new PowerToolUses(-1, false, 0, false);

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
