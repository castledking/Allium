import java.math.BigDecimal;
import java.math.RoundingMode;

/** Design fixture only: command/core decimals remain strict; only Vault doubles get a narrow snap. */
public final class MoneyBoundary {
    static final long HARD = 1_000_000_000_000_000L;
    static final BigDecimal EPSILON_MAJOR = new BigDecimal("0.000001");
    static long exact(BigDecimal value) {
        long minor = value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        if (minor < -HARD || minor > HARD) throw new IllegalArgumentException("bound");
        return minor;
    }
    static long vault(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("finite nonnegative amount required");
        BigDecimal decimal = BigDecimal.valueOf(value);
        BigDecimal cents = decimal.setScale(2, RoundingMode.HALF_EVEN);
        if (decimal.subtract(cents).abs().compareTo(EPSILON_MAJOR) > 0)
            throw new IllegalArgumentException("real sub-cent amount");
        return exact(cents);
    }
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("accepted invalid input");
    }
    public static void main(String[] args) {
        require(vault(0.1 + 0.2) == 30);
        require(vault(0.30000000000000004) == 30);
        require(vault(1.2300009) == 123);
        require(vault(1.2299991) == 123);
        require(vault(1.230001) == 123); // tolerance is inclusive, in major units
        require(vault(1.229999) == 123);
        for (double invalid : new double[]{1.2300011, 1.2299989, 1.001, 0.001, 0.005,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0000001,
                10_000_000_000_000.01}) rejects(() -> vault(invalid));
        require(vault(-0.0) == 0); // mutation policy separately rejects minor == 0
        require(vault(0.0000009) == 0);
        require(exact(new BigDecimal("1.2300")) == 123);
        rejects(() -> exact(new BigDecimal("0.30000000000000004")));
        rejects(() -> exact(new BigDecimal("1.2300009")));
        rejects(() -> exact(new BigDecimal("0.001")));
        for (long cents : new long[]{0, 1, 29, 30, 31, 123, HARD-1, HARD}) {
            double output = BigDecimal.valueOf(cents, 2).doubleValue();
            require(vault(output) == cents);
        }
        System.out.println("money boundary fixtures passed; mutation positive/minimum checks remain separate");
    }
}
