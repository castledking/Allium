package codes.castled.allium.kit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable definition of a server kit loaded from {@code plugins/Allium/kits/<name>.yml}.
 *
 * <p>Per the S14 economy rule, {@code cost} is canonical decimal text ({@code '0.00'}), never a
 * binary floating-point {@code double}. Paid kits ({@code cost > 0.00}) are parsed and validated
 * now, and denied at claim time until M3 economy reservations exist.
 */
public record KitDefinition(
        String name,
        boolean enabled,
        long cooldownSeconds,
        boolean oneTime,
        String cost,
        List<String> items,
        String sourceFile
) {

    public static final long MAX_COOLDOWN_SECONDS = 31_536_000L; // 365 days
    public static final int MAX_ITEMS = 36;
    public static final String ZERO_COST = "0.00";
    private static final Pattern DECIMAL_COST = Pattern.compile("^\\d+(\\.\\d{1,2})?$");

    public KitDefinition {
        name = KitNames.validate(name);
        if (cooldownSeconds < 0L || cooldownSeconds > MAX_COOLDOWN_SECONDS) {
            throw new IllegalArgumentException(
                    "cooldownSeconds must be between 0 and " + MAX_COOLDOWN_SECONDS + ", got " + cooldownSeconds);
        }
        cost = normalizeCost(cost);
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("items must have at most " + MAX_ITEMS + " entries");
        }
        items = List.copyOf(items);
        Objects.requireNonNull(sourceFile, "sourceFile");
    }

    public static String normalizeCost(String rawCost) {
        if (rawCost == null) {
            throw new IllegalArgumentException("cost must not be null");
        }
        String trimmed = rawCost.trim();
        if (!DECIMAL_COST.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "cost must be non-negative decimal text with at most 2 fractional digits (e.g. '0.00'), got '" + rawCost + "'");
        }
        return new BigDecimal(trimmed).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    public boolean paid() {
        return new BigDecimal(cost).compareTo(BigDecimal.ZERO) > 0;
    }

    public long cooldownMillis() {
        return Math.multiplyExact(cooldownSeconds, 1000L);
    }
}
