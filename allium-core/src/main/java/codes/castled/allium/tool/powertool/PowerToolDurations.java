package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats compact human durations for powertool cooldowns and delays
 * ({@code 500ms}, {@code 20t}, {@code 5s}, {@code 1m30s}, {@code 2h}, {@code 0}).
 */
public final class PowerToolDurations {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)(ms|s|m|h|d|t)");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("^\\d+$");

    private PowerToolDurations() {
    }

    /**
     * Parses a duration string into milliseconds. Plain numbers are treated as seconds.
     *
     * @throws IllegalArgumentException if the input is not a valid non-negative duration
     */
    public static long parseMillis(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (PLAIN_NUMBER.matcher(text).matches()) {
            return Math.multiplyExact(Long.parseLong(text), 1000L);
        }
        Matcher matcher = TOKEN.matcher(text);
        int cursor = 0;
        long total = 0L;
        while (matcher.find()) {
            if (matcher.start() != cursor) {
                throw new IllegalArgumentException("invalid duration '" + raw + "'");
            }
            long amount = Long.parseLong(matcher.group(1));
            long factor = switch (matcher.group(2)) {
                case "ms" -> 1L;
                case "t" -> 50L;
                case "s" -> 1000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> throw new IllegalArgumentException("invalid unit in '" + raw + "'");
            };
            total = Math.addExact(total, Math.multiplyExact(amount, factor));
            cursor = matcher.end();
        }
        if (cursor != text.length()) {
            throw new IllegalArgumentException("invalid duration '" + raw + "'");
        }
        return total;
    }

    public static long millisToTicks(long millis) {
        if (millis <= 0L) {
            return 0L;
        }
        return Math.max(1L, (millis + 49L) / 50L);
    }

    public static String formatMillis(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        if (millis < 1000L) {
            return millis + "ms";
        }
        if (millis < 60_000L) {
            long seconds = millis / 1000L;
            long rem = millis % 1000L;
            if (rem == 0L) {
                return seconds + "s";
            }
            return String.format(Locale.ROOT, "%.1fs", millis / 1000.0D);
        }
        long hours = millis / 3_600_000L;
        long minutes = (millis % 3_600_000L) / 60_000L;
        long seconds = (millis % 60_000L) / 1000L;
        StringBuilder out = new StringBuilder();
        if (hours > 0L) {
            out.append(hours).append('h');
        }
        if (minutes > 0L) {
            out.append(minutes).append('m');
        }
        if (seconds > 0L && hours == 0L) {
            out.append(seconds).append('s');
        }
        return out.toString();
    }

    public static String formatTicks(long ticks) {
        if (ticks <= 0L) {
            return "0t";
        }
        if (ticks % 20L == 0L) {
            return (ticks / 20L) + "s";
        }
        return ticks + "t";
    }
}
