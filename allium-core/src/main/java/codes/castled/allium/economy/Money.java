package codes.castled.allium.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact signed minor units. No formatting or binary floating point enters storage. */
public record Money(long minor) implements Comparable<Money> {
    public static final long HARD = 1_000_000_000_000_000L;
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
