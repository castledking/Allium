package codes.castled.allium.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Exact signed minor units. No formatting or binary floating point enters storage. */
public record Money(long minor) implements Comparable<Money> {
    public static final long HARD = 1_000_000_000_000_000L;
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal BILLION = BigDecimal.valueOf(1_000_000_000L);
    private static final BigDecimal TRILLION = BigDecimal.valueOf(1_000_000_000_000L);

    /** Inclusive positive amount range for admin cheques (for example {@code 500-1000} or {@code 1k-5k}). */
    public record Range(Money min, Money max) {
        public Range {
            Objects.requireNonNull(min);
            Objects.requireNonNull(max);
            min.positive();
            max.positive();
            if (min.compareTo(max) > 0) throw new IllegalArgumentException("range minimum exceeds maximum");
        }

        public boolean isRange() {
            return !min.equals(max);
        }

        /** Whole currency units stay whole when both bounds have zero cents; otherwise samples minor units. */
        public Money sample(RandomGenerator random) {
            Objects.requireNonNull(random);
            if (min.equals(max)) return min;
            if (min.minor() % 100 == 0 && max.minor() % 100 == 0) {
                long lo = min.minor() / 100;
                long hi = max.minor() / 100;
                return new Money(Math.multiplyExact(random.nextLong(lo, Math.addExact(hi, 1L)), 100L));
            }
            return new Money(random.nextLong(min.minor(), Math.addExact(max.minor(), 1L)));
        }
    }

    public Money {
        if (minor < -HARD || minor > HARD) throw new IllegalArgumentException("money exceeds hard bound");
    }
    public static Money exact(BigDecimal amount) {
        return new Money(amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact());
    }
    public static Money parse(String text, boolean signed) {
        if (text == null || text.length() > 64 || !text.matches(signed ? "-?[0-9]+(\\.[0-9]+)?" : "[0-9]+(\\.[0-9]+)?"))
            throw new IllegalArgumentException("plain decimal text required");
        return exact(new BigDecimal(text));
    }
    /** Command input: exact decimal with optional k/m/b/t suffix (case-insensitive). */
    public static Money parseCommand(String text, boolean signed) {
        if (text == null || text.isEmpty() || text.length() > 64)
            throw new IllegalArgumentException("amount text required");
        if (!text.matches((signed ? "-?" : "") + "[0-9]+(\\.[0-9]+)?[kKmMbBtT]?"))
            throw new IllegalArgumentException("decimal amount or k/m/b/t shorthand required");
        char last = text.charAt(text.length() - 1);
        BigDecimal multiplier = switch (Character.toLowerCase(last)) {
            case 'k' -> THOUSAND;
            case 'm' -> MILLION;
            case 'b' -> BILLION;
            case 't' -> TRILLION;
            default -> null;
        };
        String number = multiplier == null ? text : text.substring(0, text.length() - 1);
        BigDecimal value = new BigDecimal(number);
        if (multiplier != null) value = value.multiply(multiplier);
        return exact(value);
    }
    /** Positive command amount or inclusive min-max range (e.g. {@code 500}, {@code 100k}, {@code 500-1000}). */
    public static Range parseCommandRange(String text) {
        if (text == null || text.isEmpty() || text.length() > 64 || text.startsWith("-"))
            throw new IllegalArgumentException("positive amount or min-max range required");
        int dash = text.indexOf('-');
        if (dash < 0) {
            Money single = parseCommand(text, false).positive();
            return new Range(single, single);
        }
        if (dash == 0 || dash == text.length() - 1 || text.indexOf('-', dash + 1) >= 0)
            throw new IllegalArgumentException("invalid amount range");
        Money min = parseCommand(text.substring(0, dash), false).positive();
        Money max = parseCommand(text.substring(dash + 1), false).positive();
        return new Range(min, max);
    }
    public BigDecimal decimal() { return BigDecimal.valueOf(minor, 2); }
    public Money plus(Money other) { return new Money(Math.addExact(minor, other.minor)); }
    public Money minus(Money other) { return new Money(Math.subtractExact(minor, other.minor)); }
    public Money positive() {
        if (minor <= 0) throw new IllegalArgumentException("positive minor units required");
        return this;
    }
    @Override public int compareTo(Money other) { return Long.compare(minor, other.minor); }
    @Override public String toString() { return decimal().toPlainString(); }
}
