package codes.castled.allium.tradingcards.card;

import java.util.List;
import java.util.Locale;

/**
 * One condition band of the 1..100 quality roll, e.g. "Damaged, 6-15".
 *
 * <p>A card stores its quality as a plain integer, never as a band name, and
 * the band is derived from that integer on read. Storing the name instead
 * would freeze every card already in circulation into whatever the band
 * boundaries were on the day it dropped — moving a boundary would then
 * require rewriting the cards themselves. Deriving means rebalancing a band
 * retroactively relabels existing cards correctly.
 *
 * <p>The bands must together partition 1..100 exactly. {@link #validate}
 * checks that, because a gap or an overlap is silent: a card would roll a
 * quality with no band, and its lore would have no colour and no head payout.
 */
public record QualityBand(
    String id,
    int min,
    int max,
    int heads,
    String colour
) {

    public static final int ROLL_MIN = 1;
    public static final int ROLL_MAX = 100;

    public QualityBand {
        if (min < ROLL_MIN || max > ROLL_MAX || max < min) {
            throw new IllegalArgumentException(
                "Quality band '" + id + "' range " + min + ".." + max
                    + " is outside " + ROLL_MIN + ".." + ROLL_MAX);
        }
        if (heads < 1) {
            throw new IllegalArgumentException(
                "Quality band '" + id + "' pays " + heads + " heads; must be at least 1");
        }
        if (colour == null || colour.isBlank()) {
            throw new IllegalArgumentException("Quality band '" + id + "' has no colour");
        }
    }

    /** The band containing {@code roll}, or null when the bands do not cover it. */
    public QualityBand bandOf(int roll) {
        if (roll < min || roll > max) return null;
        return this;
    }

    public boolean contains(int roll) {
        return roll >= min && roll <= max;
    }

    /**
     * Checks that the bands partition {@link #ROLL_MIN}..{@link #ROLL_MAX}
     * with no gaps and no overlaps.
     *
     * <p>Returns an empty list when they do. Overlaps are reported by name
     * because a duplicate id is usually a copy-paste error, and a gap is
     * reported with the exact range that went uncovered so the operator can see
     * which end of the scale they forgot.
     */
    public static List<String> validate(List<QualityBand> bands) {
        if (bands.isEmpty()) {
            return List.of("no quality bands configured");
        }
        List<String> problems = new java.util.ArrayList<>();

        for (int i = 0; i < bands.size(); i++) {
            QualityBand a = bands.get(i);
            for (int j = i + 1; j < bands.size(); j++) {
                QualityBand b = bands.get(j);
                if (a.overlaps(b)) {
                    problems.add("bands '" + a.id() + "' (" + a.min() + ".." + a.max()
                        + ") and '" + b.id() + "' (" + b.min() + ".." + b.max() + ") overlap");
                }
            }
        }

        // Walk the range and report every uncovered stretch, so a config with
        // two separate gaps names both rather than only the first.
        int cursor = ROLL_MIN;
        for (QualityBand band : sorted(bands)) {
            if (band.min() > cursor) {
                problems.add("quality range " + cursor + ".." + (band.min() - 1)
                    + " is not covered by any band");
            }
            cursor = Math.max(cursor, band.max() + 1);
        }
        if (cursor <= ROLL_MAX) {
            problems.add("quality range " + cursor + ".." + ROLL_MAX
                + " is not covered by any band");
        }
        return problems;
    }

    /** The band containing {@code roll} in {@code bands}, or null if none does. */
    public static QualityBand find(List<QualityBand> bands, int roll) {
        for (QualityBand band : bands) {
            if (band.contains(roll)) return band;
        }
        return null;
    }

    private boolean overlaps(QualityBand other) {
        return min <= other.max && other.min <= max;
    }

    /** Bands ordered by their lower bound, so the coverage walk is linear. */
    public static List<QualityBand> sorted(List<QualityBand> bands) {
        return bands.stream()
            .sorted(java.util.Comparator.comparingInt(QualityBand::min))
            .toList();
    }

    /** Parses a colour string, falling back to white when unparseable. */
    public static String parseColour(String raw) {
        if (raw == null || raw.isBlank()) return "white";
        return raw.trim();
    }

    /** Normalises a band id for use as a lore label, e.g. {@code okay} -> {@code Okay}. */
    public static String displayId(String id) {
        if (id == null || id.isBlank()) return "Unknown";
        String[] parts = id.trim().toLowerCase(Locale.ROOT).split("[_\\s-]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() == 0 ? "Unknown" : sb.toString();
    }
}
