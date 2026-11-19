package codes.castled.allium.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable tax configuration and exact minor-unit tax calculation. */
public record TaxSettings(
        boolean enabled,
        BigDecimal percent,
        String accountName,
        boolean applyToPay,
        boolean applyToCheques,
        RoundingMode roundMode,
        Money minimumTax
) {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public TaxSettings {
        Objects.requireNonNull(percent, "percent");
        Objects.requireNonNull(accountName, "accountName");
        Objects.requireNonNull(roundMode, "roundMode");
        Objects.requireNonNull(minimumTax, "minimumTax");
        percent = percent.stripTrailingZeros();
        if (percent.scale() < 0) percent = percent.setScale(0, RoundingMode.UNNECESSARY);
        if (percent.scale() > 2 || percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("tax percent must be 0..100 with at most 2 decimal places");
        }
        if (!accountName.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("tax account-name must be 1..16 letters, digits or underscores");
        }
        if (minimumTax.minor() < 0) {
            throw new IllegalArgumentException("minimum-tax must not be negative");
        }
        if (roundMode != RoundingMode.HALF_UP && roundMode != RoundingMode.FLOOR && roundMode != RoundingMode.CEILING) {
            throw new IllegalArgumentException("round-mode must be nearest, floor or ceil");
        }
    }

    public static TaxSettings defaults() {
        return new TaxSettings(true, BigDecimal.valueOf(5), "Tax", true, false, RoundingMode.HALF_UP, new Money(0));
    }

    public static TaxSettings disabled() {
        return new TaxSettings(false, BigDecimal.ZERO, "Tax", false, false, RoundingMode.HALF_UP, new Money(0));
    }

    public static RoundingMode parseRoundMode(String raw) {
        if (raw == null || raw.isBlank()) return RoundingMode.HALF_UP;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "nearest", "half_up", "half-up" -> RoundingMode.HALF_UP;
            case "floor", "down" -> RoundingMode.FLOOR;
            case "ceil", "ceiling", "up" -> RoundingMode.CEILING;
            default -> throw new IllegalArgumentException("unknown tax round-mode: " + raw);
        };
    }

    /** Parses a percentage token (0..100, up to 2 decimal places) as basis points (0..10000). */
    public static int parsePercentBps(String raw, boolean allowZero) {
        if (raw == null || raw.isBlank() || raw.length() > 16 || !raw.trim().matches("[0-9]+(\\.[0-9]+)?")) {
            throw new IllegalArgumentException("percentage must be a plain number between 0 and 100");
        }
        BigDecimal value = new BigDecimal(raw.trim()).setScale(2, RoundingMode.UNNECESSARY);
        int bps = value.movePointRight(2).intValueExact();
        if (bps < (allowZero ? 0 : 1) || bps > 10_000) {
            throw new IllegalArgumentException("percentage must be between " + (allowZero ? "0" : "0.01") + " and 100");
        }
        return bps;
    }

    /** Exact tax minor units for a percentage expressed in basis points (100 bps = 1%). */
    public static Money calculateFromBps(Money gross, int percentBps, RoundingMode mode) {
        if (gross.minor() <= 0 || percentBps <= 0) return new Money(0);
        BigDecimal raw = BigDecimal.valueOf(gross.minor())
                .multiply(BigDecimal.valueOf(percentBps))
                .divide(BigDecimal.valueOf(10_000L), 0, mode);
        long minor = Math.min(gross.minor(), raw.longValueExact());
        return new Money(minor);
    }

    public static String formatBps(int percentBps) {
        BigDecimal pct = BigDecimal.valueOf(percentBps, 2).stripTrailingZeros();
        if (pct.scale() < 0) pct = pct.setScale(0, RoundingMode.UNNECESSARY);
        return pct.toPlainString() + "%";
    }

    /** Stable offline-style UUID matching Bukkit's {@code OfflinePlayer:<accountName>} convention. */
    public UUID accountUuid() {
        return accountUuidFor(accountName);
    }

    public static UUID accountUuidFor(String accountName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + accountName).getBytes(StandardCharsets.UTF_8));
    }

    public boolean active() {
        return enabled && percent.signum() > 0;
    }

    public boolean appliesToPay() {
        return active() && applyToPay;
    }

    public boolean appliesToCheques() {
        return active() && applyToCheques;
    }

    public int percentBps() {
        return percent.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).intValueExact();
    }

    public String formattedPercent() {
        return formatBps(percentBps());
    }

    public Money calculateTax(Money gross) {
        if (!active() || gross.minor() <= 0) return new Money(0);
        BigDecimal raw = BigDecimal.valueOf(gross.minor())
                .multiply(percent)
                .divide(HUNDRED, 0, roundMode);
        long minor = raw.longValueExact();
        if (minor > 0 && minimumTax.minor() > 0) {
            minor = Math.max(minor, minimumTax.minor());
        }
        if (minor > gross.minor()) {
            minor = gross.minor();
        }
        return new Money(minor);
    }

    public String fingerprint() {
        return enabled + "|" + percentBps() + "|" + accountName + "|" + applyToPay + "|" + applyToCheques
                + "|" + roundMode.name() + "|" + minimumTax.minor();
    }
}
