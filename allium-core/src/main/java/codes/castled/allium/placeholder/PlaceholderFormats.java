package codes.castled.allium.placeholder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * The text formats Allium's PlaceholderAPI placeholders use, matching legacy Allium's so existing
 * scoreboard and tab configurations keep working: amounts, ranks, the in-game clock and date, and
 * durations.
 */
public final class PlaceholderFormats {

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final int[] DAYS_PER_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private static final long TICKS_PER_DAY = 24_000;
    private static final long TICKS_PER_HOUR = 1_000;

    private PlaceholderFormats() {
    }

    /** {@code 1234.50}: two decimals, no grouping or exponent. */
    public static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** {@code 1234}: whole units, truncated as legacy Allium did. */
    public static String fixed(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.DOWN).toPlainString();
    }

    /** {@code 1,234.5}: grouped, up to two decimals. */
    public static String commas(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(amount);
    }

    /** {@code 10.0k}, {@code 1.5M}, {@code 2.3B}; below a thousand, whole units. */
    public static String shortened(BigDecimal amount) {
        double value = amount.doubleValue();
        if (Math.abs(value) >= 1_000_000_000) {
            return String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000);
        }
        if (Math.abs(value) >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000);
        }
        if (Math.abs(value) >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000);
        }
        return fixed(amount);
    }

    /** {@code 1st}, {@code 2nd}, {@code 13th}, {@code 22nd}. */
    public static String ordinal(int rank) {
        int lastTwo = rank % 100;
        if (lastTwo >= 11 && lastTwo <= 13) {
            return rank + "th";
        }
        return switch (rank % 10) {
            case 1 -> rank + "st";
            case 2 -> rank + "nd";
            case 3 -> rank + "rd";
            default -> rank + "th";
        };
    }

    /** {@code 6:00 AM} at world time 0. */
    public static String clock12(long worldTime) {
        long hours = hoursOf(worldTime);
        String half = hours >= 12 ? "PM" : "AM";
        long shown = hours % 12 == 0 ? 12 : hours % 12;
        return String.format(Locale.ROOT, "%d:%02d %s", shown, minutesOf(worldTime), half);
    }

    /** {@code 06:00} at world time 0. */
    public static String clock24(long worldTime) {
        return String.format(Locale.ROOT, "%02d:%02d", hoursOf(worldTime), minutesOf(worldTime));
    }

    /** {@code Jan 1, 0}: the calendar date of a world's full time, 365-day years. */
    public static String date(long fullTime) {
        long days = Math.max(0, fullTime) / TICKS_PER_DAY;
        long year = days / 365;
        int day = (int) (days % 365) + 1;
        int month = 0;
        while (month < 11 && day > DAYS_PER_MONTH[month]) {
            day -= DAYS_PER_MONTH[month];
            month++;
        }
        return MONTHS[month] + " " + day + ", " + year;
    }

    /** {@code 1d 2h 30m 45s}, leaving out zero parts; {@code 0s} for nothing. */
    public static String duration(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long secs = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append("d ");
        if (hours > 0) out.append(hours).append("h ");
        if (minutes > 0) out.append(minutes).append("m ");
        if (secs > 0) out.append(secs).append('s');
        return out.toString().trim();
    }

    /** The two largest parts of {@link #duration}: {@code 12d 6h}, {@code 6h 30m}, {@code 45s}. */
    public static String durationShort(long seconds) {
        String[] parts = duration(seconds).split(" ");
        return parts.length <= 2 ? String.join(" ", parts) : parts[0] + " " + parts[1];
    }

    private static long hoursOf(long worldTime) {
        return (Math.floorMod(worldTime, TICKS_PER_DAY) / TICKS_PER_HOUR + 6) % 24;
    }

    private static long minutesOf(long worldTime) {
        return Math.floorMod(worldTime, TICKS_PER_HOUR) * 60 / TICKS_PER_HOUR;
    }
}
