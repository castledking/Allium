package codes.castled.allium.essentials;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Pure parser and formatter for {@code /time}, {@code /day}, and {@code /night}.
 */
public final class WorldTimeParser {

    public static final long DAY_TICKS = 24_000L;
    public static final long MAX_ADD_TICKS = 2_400_000L;

    public enum Action {
        QUERY,
        SET,
        ADD,
        PAUSE
    }

    public record ParsedTimeCommand(
            Action action,
            long ticks,
            String worldSelector,
            Boolean pauseState
    ) {
    }

    private WorldTimeParser() {
    }

    public static Optional<ParsedTimeCommand> parseTimeCommand(String[] args) {
        if (args == null || args.length == 0) {
            return Optional.of(new ParsedTimeCommand(Action.QUERY, 0L, null, null));
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if ("pause".equals(first) || "freeze".equals(first) || "unpause".equals(first)) {
            return parsePause(first, args);
        }
        if ("set".equals(first)) {
            if (args.length < 2 || args.length > 3) {
                return Optional.empty();
            }
            OptionalLong ticks = parseTimeTicks(args[1]);
            if (ticks.isEmpty()) {
                return Optional.empty();
            }
            String world = args.length == 3 ? args[2] : null;
            return Optional.of(new ParsedTimeCommand(Action.SET, ticks.getAsLong(), world, null));
        }
        if ("add".equals(first)) {
            if (args.length < 2 || args.length > 3) {
                return Optional.empty();
            }
            OptionalLong delta = parseAddTicks(args[1]);
            if (delta.isEmpty()) {
                return Optional.empty();
            }
            String world = args.length == 3 ? args[2] : null;
            return Optional.of(new ParsedTimeCommand(Action.ADD, delta.getAsLong(), world, null));
        }
        // Shorthand: /time <value> [world|*]
        if (args.length <= 2) {
            OptionalLong ticks = parseTimeTicks(args[0]);
            if (ticks.isPresent()) {
                String world = args.length == 2 ? args[1] : null;
                return Optional.of(new ParsedTimeCommand(Action.SET, ticks.getAsLong(), world, null));
            }
            // /time <world|*> query
            if (args.length == 1 && !looksLikeFailedNumberOrClock(args[0])) {
                return Optional.of(new ParsedTimeCommand(Action.QUERY, 0L, args[0], null));
            }
        }
        return Optional.empty();
    }

    private static Optional<ParsedTimeCommand> parsePause(String verb, String[] args) {
        if ("unpause".equals(verb)) {
            if (args.length > 2) {
                return Optional.empty();
            }
            return Optional.of(new ParsedTimeCommand(Action.PAUSE, 0L, args.length == 2 ? args[1] : null, Boolean.FALSE));
        }
        if (args.length == 1) {
            return Optional.of(new ParsedTimeCommand(Action.PAUSE, 0L, null, null));
        }
        if (args.length == 2) {
            Boolean explicit = parseOnOff(args[1]);
            if (explicit != null) {
                return Optional.of(new ParsedTimeCommand(Action.PAUSE, 0L, null, explicit));
            }
            return Optional.of(new ParsedTimeCommand(Action.PAUSE, 0L, args[1], null));
        }
        if (args.length == 3) {
            Boolean explicit = parseOnOff(args[1]);
            if (explicit == null) {
                return Optional.empty();
            }
            return Optional.of(new ParsedTimeCommand(Action.PAUSE, 0L, args[2], explicit));
        }
        return Optional.empty();
    }

    private static Boolean parseOnOff(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable" -> Boolean.TRUE;
            case "off", "false", "disable" -> Boolean.FALSE;
            default -> null;
        };
    }

    public static OptionalLong parseTimeTicks(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        Long named = switch (lower) {
            case "day", "morning" -> 1000L;
            case "noon", "midday" -> 6000L;
            case "afternoon" -> 9000L;
            case "sunset", "dusk" -> 12000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            case "sunrise", "dawn" -> 23000L;
            default -> null;
        };
        if (named != null) {
            return OptionalLong.of(named);
        }
        if (lower.contains(":")) {
            return parseClock(lower);
        }
        String digits = lower.endsWith("t") ? lower.substring(0, lower.length() - 1) : lower;
        if (digits.isEmpty() || !allDigits(digits)) {
            return OptionalLong.empty();
        }
        try {
            long ticks = Long.parseLong(digits);
            if (ticks < 0 || ticks > MAX_ADD_TICKS) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(ticks % DAY_TICKS);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public static OptionalLong parseAddTicks(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        String digits = lower.endsWith("t") ? lower.substring(0, lower.length() - 1) : lower;
        if (digits.isEmpty() || !allDigits(digits)) {
            return OptionalLong.empty();
        }
        try {
            long ticks = Long.parseLong(digits);
            if (ticks < 0 || ticks > MAX_ADD_TICKS) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(ticks);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static OptionalLong parseClock(String raw) {
        String[] parts = raw.split(":", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()
                || !allDigits(parts[0]) || !allDigits(parts[1])) {
            return OptionalLong.empty();
        }
        try {
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
                return OptionalLong.empty();
            }
            long ticksFromMidnight = hours * 1000L + Math.round((minutes * 1000.0d) / 60.0d);
            long normalized = ((ticksFromMidnight - 6000L) % DAY_TICKS + DAY_TICKS) % DAY_TICKS;
            return OptionalLong.of(normalized);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public static String formatTime(long worldTime) {
        long normalized = ((worldTime % DAY_TICKS) + DAY_TICKS) % DAY_TICKS;
        long totalMinutes = Math.round(((normalized + 6000L) % DAY_TICKS) * 60.0d / 1000.0d) % (24L * 60L);
        long hour = totalMinutes / 60L;
        long minute = totalMinutes % 60L;
        return String.format(Locale.ROOT, "%d ticks (%02d:%02d)", normalized, hour, minute);
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeFailedNumberOrClock(String raw) {
        if (raw.contains(":") || raw.startsWith("-")) {
            return true;
        }
        return !raw.isEmpty() && Character.isDigit(raw.charAt(0));
    }
}
