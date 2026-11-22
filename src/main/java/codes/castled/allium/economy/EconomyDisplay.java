package codes.castled.allium.economy;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/** Display settings shared by signs, chat and Vault. Ledger amounts remain exact minor units. */
public record EconomyDisplay(String symbol, boolean suffix) {
    public static final EconomyDisplay DEFAULT = new EconomyDisplay("$", false);

    public static EconomyDisplay read(ConfigurationSection config) {
        if (config == null) return DEFAULT;
        return new EconomyDisplay(config.getString("economy.currency-symbol", "$"),
                config.getBoolean("economy.currency-symbol-suffix", false));
    }

    public String format(Money amount) { return format(amount.decimal()); }

    public String format(BigDecimal amount) {
        var numbers = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        String value = numbers.format(amount.abs());
        return (amount.signum() < 0 ? "-" : "") + (suffix ? value + symbol : symbol + value);
    }

    /** Formats a price for a sign line: when {@code shortForm} is true, values above $999 render as 1K, 1.1K, 1M, etc. */
    public String formatSign(Money amount, boolean shortForm) {
        if (!shortForm) return format(amount);
        return formatShort(amount.decimal());
    }

    public String formatShort(BigDecimal amount) {
        BigDecimal abs = amount.abs();
        if (abs.compareTo(BigDecimal.valueOf(1_000L)) < 0) return format(amount);
        BigDecimal divisor;
        String unit;
        if (abs.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) >= 0) {
            divisor = BigDecimal.valueOf(1_000_000_000_000L);
            unit = "T";
        } else if (abs.compareTo(BigDecimal.valueOf(1_000_000_000L)) >= 0) {
            divisor = BigDecimal.valueOf(1_000_000_000L);
            unit = "B";
        } else if (abs.compareTo(BigDecimal.valueOf(1_000_000L)) >= 0) {
            divisor = BigDecimal.valueOf(1_000_000L);
            unit = "M";
        } else {
            divisor = BigDecimal.valueOf(1_000L);
            unit = "K";
        }
        BigDecimal scaled = abs.divide(divisor, 2, java.math.RoundingMode.DOWN).stripTrailingZeros();
        String value = scaled.toPlainString() + unit;
        return (amount.signum() < 0 ? "-" : "") + (suffix ? value + symbol : symbol + value);
    }

    public Money parsePrice(String line) {
        String text = line.trim();
        if (text.isEmpty()) return new Money(0);
        if (!symbol.isEmpty()) {
            if (suffix && text.endsWith(symbol)) text = text.substring(0, text.length() - symbol.length()).trim();
            else if (!suffix && text.startsWith(symbol)) text = text.substring(symbol.length()).trim();
        }
        if (text.matches("[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?")) text = text.replace(",", "");
        return Money.parseCommand(text, false);
    }
}
