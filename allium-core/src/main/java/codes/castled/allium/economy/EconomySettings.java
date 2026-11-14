package codes.castled.allium.economy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Validated immutable Phase 1 settings; install only after admission is drained. */
public record EconomySettings(Money starting, Money minimum, Money maximum, Money minimumPay,
                              boolean offlinePay, boolean excludesIgnore) {
    public EconomySettings {
        if (minimum.minor() > 0 || maximum.minor() < 0 || minimum.compareTo(maximum) >= 0
                || starting.compareTo(minimum) < 0 || starting.compareTo(maximum) > 0
                || minimumPay.minor() < 1 || minimumPay.compareTo(maximum) > 0)
            throw new IllegalArgumentException("invalid economy bounds or grant/minimum pay");
    }
    public static EconomySettings defaults() {
        return new EconomySettings(new Money(10_000), new Money(0), new Money(Money.HARD), new Money(1), true, true);
    }
    public String fingerprint() {
        String canonical = "v1|"+starting.minor()+"|"+minimum.minor()+"|"+maximum.minor()+"|"+minimumPay.minor()+"|"+offlinePay+"|"+excludesIgnore;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
