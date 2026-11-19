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
