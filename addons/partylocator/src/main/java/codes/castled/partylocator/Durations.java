package codes.castled.partylocator;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Legacy /locatorbar durations: plain seconds, or a number with s, m, h or d. */
final class Durations {
    private static final Pattern UNIT = Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);

    private Durations() {}

    /** Seconds, or -1 when {@code text} is not a duration. */
    static long parse(String text) {
        if (text == null || text.isBlank()) return -1;
        try {
            long seconds = Long.parseLong(text.trim());
            return seconds > 0 ? seconds : -1;
        } catch (NumberFormatException notPlain) {
            Matcher m = UNIT.matcher(text.trim());
            if (!m.matches()) return -1;
            long value;
            try {
                value = Long.parseLong(m.group(1));
            } catch (NumberFormatException tooBig) {
                return -1;
            }
            long scale = switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> 1;
                case "m" -> 60;
                case "h" -> 3_600;
                default -> 86_400;
            };
            return value <= 0 || value > Long.MAX_VALUE / scale / 1000 ? -1 : value * scale;
        }
    }

    static String format(long seconds) {
        if (seconds < 60) return plural(seconds, "second");
        if (seconds < 3_600) return plural(seconds / 60, "minute");
        if (seconds < 86_400) return plural(seconds / 3_600, "hour");
        return plural(seconds / 86_400, "day");
    }

    private static String plural(long value, String unit) {
        return value + " " + unit + (value == 1 ? "" : "s");
    }
}
