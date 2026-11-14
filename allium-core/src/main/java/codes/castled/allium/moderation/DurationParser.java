package codes.castled.allium.moderation;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses compact human duration strings (such as {@code 30s}, {@code 10m}, {@code 2h},
 * {@code 7d}, {@code 1w}, or compound forms like {@code 1h30m}) and formats remaining durations
 * for moderation messages.
 */
public final class DurationParser {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);
    private static final long MAX_DURATION_MILLIS = 3650L * 86_400_000L; // 10 years cap

    private DurationParser() {
    }

    /**
     * Returns {@code true} when {@code raw} begins with an ASCII digit after trimming, indicating the
     * caller attempted to supply a duration token even if {@link #parseMillis(String)} rejects it.
     */
    public static boolean startsWithDigit(String raw) {
        if (raw == null) {
            return false;
        }
        String trimmed = raw.trim();
        return !trimmed.isEmpty() && trimmed.charAt(0) >= '0' && trimmed.charAt(0) <= '9';
    }

    /**
     * Parses a duration token into milliseconds. Returns empty if {@code raw} is not a positive
     * duration token in {@code <number><s|m|h|d|w>} format.
     */
    public static OptionalLong parseMillis(String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return OptionalLong.empty();
        }

        Matcher matcher = TOKEN_PATTERN.matcher(trimmed);
        int expectedPos = 0;
        long totalMillis = 0L;
        boolean matchedAny = false;

        while (matcher.find()) {
            if (matcher.start() != expectedPos) {
                return OptionalLong.empty();
            }
            matchedAny = true;
            expectedPos = matcher.end();
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ex) {
                return OptionalLong.empty();
            }
            if (amount <= 0L) {
                return OptionalLong.empty();
            }
            long unitMillis = switch (matcher.group(2)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 7L * 86_400_000L;
                default -> -1L;
            };
            if (unitMillis <= 0L || amount > MAX_DURATION_MILLIS / unitMillis) {
                return OptionalLong.empty();
            }
            long term = amount * unitMillis;
            if (totalMillis > MAX_DURATION_MILLIS - term) {
                return OptionalLong.empty();
            }
            totalMillis += term;
        }

        if (!matchedAny || expectedPos != trimmed.length() || totalMillis <= 0L) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(totalMillis);
    }

    /**
     * Formats a positive millisecond duration into up to two human-readable units
     * (e.g. {@code 2d 4h}, {@code 1h 30m}, {@code 4m 59s}, {@code 12s}).
     */
    public static String formatRemaining(long remainingMillis) {
        long totalSeconds = Math.max(1L, (remainingMillis + 999L) / 1_000L);
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (days > 0L) {
            return hours > 0L ? (days + "d " + hours + "h") : (days + "d");
        }
        if (hours > 0L) {
            return minutes > 0L ? (hours + "h " + minutes + "m") : (hours + "h");
        }
        if (minutes > 0L) {
            return seconds > 0L ? (minutes + "m " + seconds + "s") : (minutes + "m");
        }
        return seconds + "s";
    }
}
