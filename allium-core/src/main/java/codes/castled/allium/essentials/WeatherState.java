package codes.castled.allium.essentials;

import java.util.Locale;
import java.util.Optional;

/**
 * Pure parser for {@code /weather <clear|sun|rain|storm|thunder> [duration] [world|*]}.
 */
public final class WeatherState {

    public static final int DEFAULT_DURATION_SECONDS = 300;
    public static final int MAX_DURATION_SECONDS = 1_000_000;

    public enum Condition {
        CLEAR(false, false, "clear"),
        RAIN(true, false, "rain"),
        THUNDER(true, true, "thunder");

        private final boolean storm;
        private final boolean thunder;
        private final String displayName;

        Condition(boolean storm, boolean thunder, String displayName) {
            this.storm = storm;
            this.thunder = thunder;
            this.displayName = displayName;
        }

        public boolean storm() {
            return storm;
        }

        public boolean thunder() {
            return thunder;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record ParsedWeather(Condition condition, Integer durationSeconds, String worldSelector) {
    }

    private WeatherState() {
    }

    public static Optional<ParsedWeather> parse(String[] args) {
        if (args == null || args.length == 0 || args.length > 3) {
            return Optional.empty();
        }
        Condition condition = parseCondition(args[0]);
        if (condition == null) {
            return Optional.empty();
        }
        if (args.length == 1) {
            return Optional.of(new ParsedWeather(condition, null, null));
        }
        if (args.length == 2) {
            String second = args[1];
            if (looksLikeNumeric(second)) {
                Integer duration = parseDurationSeconds(second);
                if (duration == null) {
                    return Optional.empty();
                }
                return Optional.of(new ParsedWeather(condition, duration, null));
            }
            return Optional.of(new ParsedWeather(condition, null, second));
        }
        Integer duration = parseDurationSeconds(args[1]);
        if (duration == null) {
            return Optional.empty();
        }
        return Optional.of(new ParsedWeather(condition, duration, args[2]));
    }

    public static Condition parseCondition(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "clear", "sun", "sunny" -> Condition.CLEAR;
            case "rain", "storm", "rainy" -> Condition.RAIN;
            case "thunder", "thunderstorm" -> Condition.THUNDER;
            default -> null;
        };
    }

    public static Integer parseDurationSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(raw.trim());
            if (seconds < 1 || seconds > MAX_DURATION_SECONDS) {
                return null;
            }
            return seconds;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean looksLikeNumeric(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        int start = raw.charAt(0) == '-' || raw.charAt(0) == '+' ? 1 : 0;
        if (start >= raw.length()) {
            return false;
        }
        return Character.isDigit(raw.charAt(start));
    }
}
