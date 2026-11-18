package codes.castled.allium.teleport;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code <x> <y> <z> [yaw] [pitch] [world]} for {@code /tp} and {@code /tppos}, as vanilla reads
 * them: {@code ~} and {@code ~<offset>} are relative to the player, and a whole-number x or z is the
 * block's center (100 means 100.5), as vanilla {@code /tp} does.
 */
public final class CoordinateArgs {

    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");
    /** Vanilla's horizontal bound for teleports. */
    public static final double HORIZONTAL_LIMIT = 30_000_000;
    public static final double VERTICAL_LIMIT = 20_000_000;

    /** A resolved destination; {@code world} is empty when the player's own world is meant. */
    public record Position(double x, double y, double z, float yaw, float pitch, Optional<String> world) {
    }

    private CoordinateArgs() {
    }

    /** True for {@code 12}, {@code -3.5}, {@code ~} and {@code ~-2}. */
    public static boolean isCoordinate(String token) {
        if (token == null || token.isEmpty()) return false;
        if (token.startsWith("~")) return token.length() == 1 || NUMBER.matcher(token.substring(1)).matches();
        return NUMBER.matcher(token).matches();
    }

    /** True when {@code args} start with three coordinates (the form {@code /tp x y z ...}). */
    public static boolean startsWithPosition(List<String> args) {
        return args.size() >= 3 && isCoordinate(args.get(0)) && isCoordinate(args.get(1)) && isCoordinate(args.get(2));
    }

    /**
     * Parses {@code args} (3 to 6 entries) against the player's current position.
     *
     * @throws IllegalArgumentException with a message for the player when the input is invalid
     */
    public static Position parse(List<String> args, double x, double y, double z, float yaw, float pitch) {
        if (args.size() < 3 || args.size() > 6) {
            throw new IllegalArgumentException("Give x y z, then optionally yaw, pitch and a world.");
        }
        double nx = resolve(args.get(0), x, true, "x");
        double ny = resolve(args.get(1), y, false, "y");
        double nz = resolve(args.get(2), z, true, "z");
        if (Math.abs(nx) > HORIZONTAL_LIMIT || Math.abs(nz) > HORIZONTAL_LIMIT || Math.abs(ny) > VERTICAL_LIMIT) {
            throw new IllegalArgumentException("Those coordinates are outside the world.");
        }
        float nyaw = args.size() > 3 ? (float) resolve(args.get(3), yaw, false, "yaw") : yaw;
        float npitch = args.size() > 4 ? (float) resolve(args.get(4), pitch, false, "pitch") : pitch;
        if (npitch < -90 || npitch > 90) {
            throw new IllegalArgumentException("Pitch must be between -90 and 90.");
        }
        Optional<String> world = args.size() > 5 ? Optional.of(args.get(5)) : Optional.empty();
        return new Position(nx, ny, nz, wrapYaw(nyaw), npitch, world);
    }

    static double resolve(String token, double base, boolean center, String axis) {
        if (!isCoordinate(token)) {
            throw new IllegalArgumentException("'" + token + "' is not a number for " + axis + " (use a number, ~ or ~<offset>).");
        }
        double value;
        if (token.startsWith("~")) {
            value = base + (token.length() == 1 ? 0 : Double.parseDouble(token.substring(1)));
        } else {
            value = Double.parseDouble(token);
            if (center && !token.contains(".")) value += 0.5;
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("'" + token + "' is not a number for " + axis + ".");
        }
        return value;
    }

    private static float wrapYaw(float yaw) {
        float wrapped = yaw % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }

    /** Lowercase helper for world-name completion. */
    public static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }
}
