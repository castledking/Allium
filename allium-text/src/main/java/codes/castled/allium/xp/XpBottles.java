package codes.castled.allium.xp;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.IntBinaryOperator;

/** Item keys and amount syntax used by XP bottles on both Bukkit runtimes. */
public final class XpBottles {
    public static final String KEY_AMOUNT = "xp_voucher_amount";
    public static final String KEY_SIGNER = "xp_voucher_signer";

    private XpBottles() {}

    /**
     * Parses a positive XP point count, level count ({@code 10L}), or {@code min-max} range when
     * {@code allowRange} is true.
     */
    public static OptionalInt parseAmount(String raw, boolean allowRange, IntBinaryOperator randomInRange) {
        if (raw == null || raw.trim().isEmpty()) return OptionalInt.empty();
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int dash = s.indexOf('-');
        if (dash > 0) {
            if (!allowRange) return OptionalInt.empty();
            OptionalInt min = parseSingle(s.substring(0, dash).trim());
            OptionalInt max = parseSingle(s.substring(dash + 1).trim());
            if (!min.isPresent() || !max.isPresent() || min.getAsInt() > max.getAsInt()) return OptionalInt.empty();
            return OptionalInt.of(randomInRange.applyAsInt(min.getAsInt(), max.getAsInt()));
        }
        return parseSingle(s);
    }

    private static OptionalInt parseSingle(String raw) {
        if (raw.isEmpty()) return OptionalInt.empty();
        boolean levels = raw.endsWith("l");
        String digits = levels ? raw.substring(0, raw.length() - 1) : raw;
        if (!digits.matches("\\d{1,10}")) return OptionalInt.empty();
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
        if (value <= 0) return OptionalInt.empty();
        if (levels) {
            if (value > Experience.MAX_LEVEL) return OptionalInt.empty();
            long points = Experience.totalForLevel((int) value);
            if (points <= 0 || points > Integer.MAX_VALUE) return OptionalInt.empty();
            return OptionalInt.of((int) points);
        }
        if (value > Integer.MAX_VALUE || value > Experience.MAX_POINTS) return OptionalInt.empty();
        return OptionalInt.of((int) value);
    }

}
