package codes.castled.allium.rtp;

import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/** Pure maths and matching for random teleports, so they can be tested without a server. */
public final class RtpGeometry {

    private RtpGeometry() {
    }

    /**
     * A random {x, z} offset from the centre, evenly spread over the ring between {@code min} and
     * {@code max}. Square: Essentials' method, a rectangle {@code [min, max] x [-min, max]} rotated a
     * random quarter turn, which tiles the square ring exactly. Circle: a uniform angle and a radius
     * weighted by area.
     */
    public static double[] offset(RtpSettings.Shape shape, double min, double max, RandomGenerator random) {
        if (shape == RtpSettings.Shape.CIRCLE) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double radius = Math.sqrt(random.nextDouble() * (max * max - min * min) + min * min);
            return new double[]{Math.cos(angle) * radius, Math.sin(angle) * radius};
        }
        double rectX = random.nextDouble() * (max - min) + min;
        double rectZ = random.nextDouble() * (max + min) - min;
        return switch (random.nextInt(4)) {
            case 0 -> new double[]{rectX, rectZ};
            case 1 -> new double[]{-rectZ, rectX};
            case 2 -> new double[]{-rectX, -rectZ};
            default -> new double[]{rectZ, -rectX};
        };
    }

    /**
     * Whether {@code material} (a Bukkit name such as {@code OAK_LEAVES}) matches one of
     * {@code patterns}: exact names, or one {@code *} wildcard ({@code *_LEAVES}, {@code STRIPPED_*}).
     */
    public static boolean matches(List<String> patterns, String material) {
        String name = material.toUpperCase(Locale.ROOT);
        for (String pattern : patterns) {
            int star = pattern.indexOf('*');
            if (star < 0 ? name.equals(pattern)
                    : name.length() >= pattern.length() - 1
                    && name.startsWith(pattern.substring(0, star)) && name.endsWith(pattern.substring(star + 1))) {
                return true;
            }
        }
        return false;
    }
}
