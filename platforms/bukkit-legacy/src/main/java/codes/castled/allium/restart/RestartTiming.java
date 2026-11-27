package codes.castled.allium.restart;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RestartTiming {
    private static final Pattern DELAY = Pattern.compile("(\\d+)([smhd]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK_DELAY = Pattern.compile("(\\d{1,2}):([0-5]\\d)");

    private RestartTiming() {}

    public static long delaySeconds(String raw) {
        if (raw.equalsIgnoreCase("now")) return 0;
        Matcher clock = CLOCK_DELAY.matcher(raw);
        if (clock.matches()) {
            int hour = Integer.parseInt(clock.group(1));
            if (hour < 24) return hour * 3600L + Integer.parseInt(clock.group(2)) * 60L;
        }
        Matcher duration = DELAY.matcher(raw);
        if (!duration.matches()) throw new IllegalArgumentException("invalid delay");
        long number = Long.parseLong(duration.group(1));
        long factor;
        switch (duration.group(2).toLowerCase(Locale.ROOT)) {
            case "m": factor = 60; break;
            case "h": factor = 3_600; break;
            case "d": factor = 86_400; break;
            default: factor = 1;
        }
        if (number > 31_536_000L / factor) throw new IllegalArgumentException("delay too large");
        return number * factor;
    }

    public static long secondsLeft(Instant now, Instant deadline) {
        return Math.max(0, (Duration.between(now, deadline).toMillis() + 999) / 1000);
    }

    public static String format(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + seconds % 3600 / 60 + "m " + seconds % 60 + "s";
    }
}
