package codes.castled.allium.economy;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code /sell} receipt kept in the player's own data: {@code saleId:cents} for each sale whose
 * removed items are saved with the player's inventory, comma-separated. A sale is paid exactly when
 * the saved file lists it; the deposit's operation ID comes from the sale ID, so paying it again (after
 * a crash) replays the first payment instead of paying twice.
 */
public final class SaleReceipts {

    private SaleReceipts() {
    }

    /** The sales in {@code raw}; a malformed entry makes the whole receipt unreadable. */
    public static Map<UUID, Money> parse(@Nullable String raw) {
        Map<UUID, Money> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split(",", -1)) {
            int colon = part.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("malformed sale receipt");
            long cents = Long.parseLong(part.substring(colon + 1));
            if (cents <= 0) throw new IllegalArgumentException("malformed sale receipt");
            out.put(UUID.fromString(part.substring(0, colon)), new Money(cents));
        }
        return out;
    }

    /** The receipt text for {@code sales}, or null when there are none (the key is then removed). */
    @Nullable
    public static String format(Map<UUID, Money> sales) {
        if (sales.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (var sale : sales.entrySet()) {
            if (!out.isEmpty()) out.append(',');
            out.append(sale.getKey()).append(':').append(sale.getValue().minor());
        }
        return out.toString();
    }

    /** The deposit's operation ID for a sale, so it can be replayed but never paid twice. */
    public static UUID operation(UUID sale) {
        return UUID.nameUUIDFromBytes(("Allium-Sale:" + sale).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
