package codes.castled.allium.essentials;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure parser and speed-curve conversion for {@code /speed [walk|fly] <0..10|reset> [player]}.
 */
public final class SpeedSpecParser {

    public static final float DEFAULT_DISPLAY_SPEED = 1.0f;
    public static final float MAX_DISPLAY_SPEED = 10.0f;
    public static final float DEFAULT_BUKKIT_WALK_SPEED = 0.2f;
    public static final float DEFAULT_BUKKIT_FLY_SPEED = 0.1f;

    public enum SpeedType {
        WALK("Walk"),
        FLY("Fly");

        private final String displayName;

        SpeedType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * @param explicitType  {@code null} when omitted (caller resolves from flying state)
     * @param displaySpeed  normalized speed in {@code [0, 10]}
     * @param reset         true if {@code reset} was supplied
     * @param targetArgIdx  argument index for target player name, or {@code -1} for self
     */
    public record ParsedSpeed(SpeedType explicitType, float displaySpeed, boolean reset, int targetArgIdx) {
    }

    private SpeedSpecParser() {
    }

    public static Optional<ParsedSpeed> parse(String[] args) {
        if (args == null || args.length == 0 || args.length > 3) {
            return Optional.empty();
        }
        SpeedType explicitType = parseType(args[0]);
        int speedIndex = explicitType != null ? 1 : 0;
        if (args.length <= speedIndex) {
            return Optional.empty();
        }
        String rawSpeed = args[speedIndex];
        boolean reset = "reset".equalsIgnoreCase(rawSpeed);
        Float parsedValue = reset ? Float.valueOf(DEFAULT_DISPLAY_SPEED) : parseDisplaySpeed(rawSpeed);
        if (parsedValue == null) {
            return Optional.empty();
        }
        int remaining = args.length - (speedIndex + 1);
        if (remaining > 1) {
            return Optional.empty();
        }
        int targetIdx = remaining == 1 ? speedIndex + 1 : -1;
        return Optional.of(new ParsedSpeed(explicitType, parsedValue, reset, targetIdx));
    }

    public static SpeedType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "walk", "walking" -> SpeedType.WALK;
            case "fly", "flying" -> SpeedType.FLY;
            default -> null;
        };
    }

    public static Float parseDisplaySpeed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            float value = Float.parseFloat(raw.trim());
            if (!Float.isFinite(value) || value < 0.0f || value > MAX_DISPLAY_SPEED) {
                return null;
            }
            return value == -0.0f ? 0.0f : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Maps user speed {@code 0..10} onto Bukkit's {@code 0.0..1.0} scale so {@code 1.0} equals
     * vanilla walk ({@code 0.2}) or fly ({@code 0.1}) speed and {@code 10.0} equals {@code 1.0}.
     */
    public static float toBukkitSpeed(SpeedType type, float displaySpeed) {
        float clamped = Math.max(0.0f, Math.min(MAX_DISPLAY_SPEED, displaySpeed));
        float defaultMove = type == SpeedType.FLY ? DEFAULT_BUKKIT_FLY_SPEED : DEFAULT_BUKKIT_WALK_SPEED;
        float result;
        if (clamped < DEFAULT_DISPLAY_SPEED) {
            result = defaultMove * clamped;
        } else {
            float ratio = (clamped - DEFAULT_DISPLAY_SPEED) / (MAX_DISPLAY_SPEED - DEFAULT_DISPLAY_SPEED);
            result = ratio * (1.0f - defaultMove) + defaultMove;
        }
        return Math.max(0.0f, Math.min(1.0f, result));
    }

    public static String formatSpeed(float value) {
        if (Math.abs(value - Math.rint(value)) < 1e-5) {
            return Long.toString(Math.round(value));
        }
        return BigDecimal.valueOf(value)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
