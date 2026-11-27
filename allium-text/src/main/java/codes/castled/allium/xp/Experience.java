package codes.castled.allium.xp;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vanilla experience arithmetic (1.8+ curve), done from the level and progress bar rather than
 * Bukkit's {@code getTotalExperience()}, which counts every point ever gained and drifts after
 * enchanting. Totals stay within an {@code int}, which caps levels at {@link #MAX_LEVEL}.
 */
public final class Experience {

    /** The highest level whose total still fits Bukkit's {@code int} experience total. */
    public static final int MAX_LEVEL = 21863;
    public static final long MAX_POINTS = totalForLevel(MAX_LEVEL);

    private static final Pattern AMOUNT = Pattern.compile("([+-]?)(\\d{1,10})([lL]?)");

    private Experience() {
    }

    /** Points needed to go from {@code level} to {@code level + 1}. */
    public static int pointsToNextLevel(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        if (level >= 15) {
            return 37 + (level - 15) * 5;
        }
        return 7 + Math.max(0, level) * 2;
    }

    /** Points a player at {@code level} with an empty bar has. */
    public static long totalForLevel(int level) {
        long l = Math.max(0, level);
        if (l <= 16) {
            return l * l + 6 * l;
        }
        if (l <= 31) {
            return (5 * l * l - 81 * l + 720) / 2;
        }
        return (9 * l * l - 325 * l + 4440) / 2;
    }

    /** A level and the points gained towards the next one. */
    public static final class State {
        private final int level;
        private final int points;

        public State(int level, int points) {
            this.level = level;
            this.points = points;
        }

        public int level() { return level; }
        public int points() { return points; }

        /** The bar's fill, {@code 0 <= progress < 1}, as Bukkit's {@code setExp} takes it. */
        public float progress() {
            return points / (float) pointsToNextLevel(level);
        }

        public long total() {
            return totalForLevel(level) + points;
        }

        public int toNextLevel() {
            return pointsToNextLevel(level) - points;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof State)) return false;
            State state = (State) other;
            return level == state.level && points == state.points;
        }

        @Override
        public int hashCode() {
            return 31 * level + points;
        }

        @Override
        public String toString() {
            return "State[level=" + level + ", points=" + points + "]";
        }
    }

    /** The state holding {@code total} points, clamped to {@code 0..MAX_POINTS}. */
    public static State fromTotal(long total) {
        long clamped = Math.max(0, Math.min(MAX_POINTS, total));
        int low = 0;
        int high = MAX_LEVEL;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (totalForLevel(mid) <= clamped) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return new State(low, (int) (clamped - totalForLevel(low)));
    }

    /** The state a player's level and progress bar show; a bar outside {@code 0..1} is clamped. */
    public static State fromBar(int level, float progress) {
        int lvl = Math.max(0, Math.min(MAX_LEVEL, level));
        int need = pointsToNextLevel(lvl);
        float bar = Float.isFinite(progress) ? Math.max(0f, Math.min(1f, progress)) : 0f;
        int points = Math.min(need - 1, Math.round(bar * need));
        return lvl == MAX_LEVEL ? new State(lvl, 0) : new State(lvl, Math.max(0, points));
    }

    /**
     * The same bar fill at another level, as vanilla's {@code /xp add ... levels} keeps it;
     * the level is clamped to {@code 0..MAX_LEVEL}.
     */
    public static State withLevel(State state, long level) {
        int lvl = (int) Math.max(0, Math.min(MAX_LEVEL, level));
        return fromBar(lvl, state.progress());
    }

    /** {@code 250}, {@code 5L}, {@code -3l}: a point or level count with an optional sign. */
    public static final class Amount {
        private final long value;
        private final boolean levels;

        public Amount(long value, boolean levels) {
            this.value = value;
            this.levels = levels;
        }

        public long value() { return value; }
        public boolean levels() { return levels; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Amount)) return false;
            Amount amount = (Amount) other;
            return value == amount.value && levels == amount.levels;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(value) + Boolean.hashCode(levels);
        }

        @Override
        public String toString() {
            return "Amount[value=" + value + ", levels=" + levels + "]";
        }
    }

    /** Parses an amount; a trailing {@code levels}/{@code points} word ({@code /xp add @p 5 levels}) may override the unit. */
    public static Optional<Amount> parse(String raw, String unitWord) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = AMOUNT.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        long value = Long.parseLong(m.group(2));
        if (value > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        boolean levels = !m.group(3).isEmpty();
        if (unitWord != null) {
            String unit = unitWord.toLowerCase(Locale.ROOT);
            if (unit.equals("levels") || unit.equals("level") || unit.equals("l")) {
                levels = true;
            } else if (unit.equals("points") || unit.equals("point")) {
                if (levels) {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(new Amount(m.group(1).equals("-") ? -value : value, levels));
    }

    /** {@code current} after adding {@code amount} (negative to take). */
    public static State add(State current, Amount amount) {
        if (amount.levels()) {
            return withLevel(current, (long) current.level() + amount.value());
        }
        return fromTotal(current.total() + amount.value());
    }

    /** {@code current} set to {@code amount}; a negative amount sets zero. */
    public static State set(State current, Amount amount) {
        if (amount.levels()) {
            return withLevel(current, Math.max(0, amount.value()));
        }
        return fromTotal(Math.max(0, amount.value()));
    }
}
