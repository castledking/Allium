package codes.castled.allium.harvest.util;

import java.util.Locale;

/**
 * Parses human friendly duration strings from configuration into
 * milliseconds. Supports compound values such as {@code "1h30m"} with the
 * units {@code d}, {@code h}, {@code m}, {@code s} and {@code ms}. A bare
 * number is treated as seconds.
 */
public final class Durations {

    private Durations() {}

    /**
     * @throws IllegalArgumentException if the string is empty, malformed, or
     *         resolves to a non-positive duration
     */
    public static long parseMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Duration is empty");
        }
        String input = raw.trim().toLowerCase(Locale.ROOT);
        long totalMs = 0L;
        int i = 0;
        while (i < input.length()) {
            int numStart = i;
            while (i < input.length() && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) {
                i++;
            }
            if (numStart == i) {
                throw new IllegalArgumentException("Invalid duration '" + raw + "'");
            }
            double value = Double.parseDouble(input.substring(numStart, i));
            int unitStart = i;
            while (i < input.length() && Character.isLetter(input.charAt(i))) {
                i++;
            }
            String unit = input.substring(unitStart, i);
            totalMs += switch (unit) {
                case "d" -> (long) (value * 86_400_000L);
                case "h" -> (long) (value * 3_600_000L);
                case "m" -> (long) (value * 60_000L);
                case "s", "" -> (long) (value * 1_000L);
                case "ms" -> (long) value;
                default -> throw new IllegalArgumentException(
                    "Unknown duration unit '" + unit + "' in '" + raw + "'");
            };
        }
        if (totalMs <= 0L) {
            throw new IllegalArgumentException("Duration '" + raw + "' must be positive");
        }
        return totalMs;
    }

    /** Formats milliseconds compactly for debug output, e.g. {@code 1h30m}. */
    public static String format(long millis) {
        if (millis < 0) return "-" + format(-millis);
        long s = millis / 1000L;
        long d = s / 86_400L; s %= 86_400L;
        long h = s / 3_600L; s %= 3_600L;
        long m = s / 60L; s %= 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append('d');
        if (h > 0) sb.append(h).append('h');
        if (m > 0) sb.append(m).append('m');
        if (s > 0 || sb.isEmpty()) sb.append(s).append('s');
        return sb.toString();
    }
}
