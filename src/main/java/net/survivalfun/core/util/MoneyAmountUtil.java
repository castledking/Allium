package net.survivalfun.core.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Parse money amounts: 1000, 1k, 1m, 1b, 1t and optional range (min-max) for admin/console.
 */
public final class MoneyAmountUtil {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal BILLION = BigDecimal.valueOf(1_000_000_000);
    private static final BigDecimal TRILLION = BigDecimal.valueOf(1_000_000_000_000L);

    private MoneyAmountUtil() {}

    /**
     * Parse amount string. Supports 1m, 1k, 1b, 1t and optional range "300-500".
     * Range is only valid when used with a target player (for /cheque &lt;player&gt; 300-500).
     *
     * @param amountStr e.g. "1000", "1m", "300-500"
     * @param allowRange whether to allow min-max range (e.g. for console/admin giving to player)
     * @return parsed amount, or random in range if allowRange and string contains "-"
     */
    public static BigDecimal parse(String amountStr, boolean allowRange) {
        if (amountStr == null || amountStr.isEmpty()) {
            throw new IllegalArgumentException("Amount cannot be empty");
        }
        String s = amountStr.trim();
        if (allowRange && s.contains("-") && !s.startsWith("-")) {
            String[] parts = s.split("-", 2);
            if (parts.length == 2) {
                BigDecimal min = parseSingle(parts[0].trim());
                BigDecimal max = parseSingle(parts[1].trim());
                if (min.compareTo(BigDecimal.ZERO) < 0 || max.compareTo(BigDecimal.ZERO) < 0 || min.compareTo(max) > 0) {
                    throw new IllegalArgumentException("Invalid range");
                }
                if (min.equals(max)) return min;
                int minInt = min.intValue();
                int maxInt = max.intValue();
                int rand = ThreadLocalRandom.current().nextInt(minInt, maxInt + 1);
                return BigDecimal.valueOf(rand);
            }
        }
        return parseSingle(s);
    }

    public static BigDecimal parse(String amountStr) {
        return parse(amountStr, false);
    }

    private static BigDecimal parseSingle(String s) {
        String sanitized = s.replaceAll("[^0-9.]", "");
        String suffix = s.replace(sanitized, "").toUpperCase(Locale.ENGLISH);
        BigDecimal multiplier = null;
        switch (suffix) {
            case "K": multiplier = THOUSAND; break;
            case "M": multiplier = MILLION; break;
            case "B": multiplier = BILLION; break;
            case "T": multiplier = TRILLION; break;
            default:
                if (!suffix.isEmpty()) throw new IllegalArgumentException("Unknown suffix: " + suffix);
        }
        if (sanitized.isEmpty()) throw new IllegalArgumentException("Invalid amount: " + s);
        BigDecimal amount = new BigDecimal(sanitized);
        if (multiplier != null) amount = amount.multiply(multiplier);
        return amount;
    }
}
