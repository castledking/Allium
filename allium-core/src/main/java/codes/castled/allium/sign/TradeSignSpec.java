package codes.castled.allium.sign;

import codes.castled.allium.economy.Money;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pure parser and canonical representation for EssentialsX-compatible player-owned {@code [Trade]}
 * signs:
 *
 * <pre>
 *   Line 1: [Trade]
 *   Line 2: &lt;buyer pays&gt;             e.g. "$50" or "4 diamond"
 *   Line 3: &lt;owner gives&gt;[:&lt;stock&gt;]  e.g. "16 coal:64" or "$50:$500"
 *   Line 4: &lt;owner name&gt;             (written automatically on creation)
 * </pre>
 */
public record TradeSignSpec(Offer pay, Offer give, long initialStock) {

    public static final int MAX_ITEM_BATCH = 2304;
    public static final long MAX_ITEM_STOCK = 1_000_000L;

    public enum Kind {
        MONEY,
        ITEM;

        public String code() {
            return this == MONEY ? "M" : "I";
        }

        public static Kind fromCode(String raw) {
            if ("M".equalsIgnoreCase(raw) || "MONEY".equalsIgnoreCase(raw)) return MONEY;
            if ("I".equalsIgnoreCase(raw) || "ITEM".equalsIgnoreCase(raw)) return ITEM;
            throw new IllegalArgumentException("Unknown trade kind: " + raw);
        }
    }

    /**
     * One side of a trade. For {@link Kind#MONEY}, {@code amount} is minor currency units and
     * {@code itemSpec} is empty. For {@link Kind#ITEM}, {@code amount} is item count and
     * {@code itemSpec} is the item token.
     */
    public record Offer(Kind kind, String itemSpec, long amount) {
        public Offer {
            Objects.requireNonNull(kind, "kind");
            itemSpec = itemSpec == null ? "" : itemSpec.trim();
            if (amount <= 0) {
                throw new IllegalArgumentException("Trade amount must be positive");
            }
            if (kind == Kind.MONEY) {
                if (!itemSpec.isEmpty()) {
                    throw new IllegalArgumentException("Money offer cannot have an item spec");
                }
            } else {
                if (itemSpec.isEmpty() || itemSpec.contains("|") || itemSpec.contains(":")) {
                    throw new IllegalArgumentException("Invalid trade item spec: " + itemSpec);
                }
                if (amount > MAX_ITEM_BATCH) {
                    throw new IllegalArgumentException("Item trade amount must be 1.." + MAX_ITEM_BATCH);
                }
            }
        }

        public static Offer money(Money money) {
            Objects.requireNonNull(money, "money");
            return new Offer(Kind.MONEY, "", money.minor());
        }

        public static Offer item(String itemSpec, int amount) {
            return new Offer(Kind.ITEM, itemSpec, amount);
        }
    }

    public record Encoded(UUID ownerUuid, Offer pay, Offer give) {}

    public TradeSignSpec {
        Objects.requireNonNull(pay, "pay");
        Objects.requireNonNull(give, "give");
        if (pay.kind() == Kind.MONEY && give.kind() == Kind.MONEY) {
            throw new IllegalArgumentException("Cannot trade money for money");
        }
        if (initialStock < give.amount()) {
            throw new IllegalArgumentException("Initial stock must be at least one trade batch");
        }
        if (give.kind() == Kind.ITEM && initialStock > MAX_ITEM_STOCK) {
            throw new IllegalArgumentException("Initial item stock exceeds limit");
        }
    }

    /**
     * Parses Line 2 ({@code payLine}) and Line 3 ({@code giveLine}) of a {@code [Trade]} sign.
     *
     * @throws IllegalArgumentException with a concise user-facing reason if either line is invalid
     */
    public static TradeSignSpec parse(String payLine, String giveLine, String currencySymbol,
                                      Function<String, Money> moneyParser) {
        String sym = currencySymbol == null || currencySymbol.isBlank() ? "$" : currencySymbol.trim();
        Function<String, Money> parser = moneyParser != null ? moneyParser : token -> defaultParseMoney(token, sym);
        Offer pay = parseOffer(payLine, sym, parser);
        String trimmedGive = giveLine == null ? "" : giveLine.trim();
        if (trimmedGive.isEmpty()) {
            throw new IllegalArgumentException("Line 3 must specify what you give (for example '16 coal:64' or '$50:$500').");
        }
        int colon = trimmedGive.lastIndexOf(':');
        String offerPart = trimmedGive;
        String stockPart = null;
        if (colon > 0) {
            String candidateStock = trimmedGive.substring(colon + 1).trim();
            if (!candidateStock.isEmpty()) {
                offerPart = trimmedGive.substring(0, colon).trim();
                stockPart = candidateStock;
            }
        }
        Offer give = parseOffer(offerPart, sym, parser);
        if (pay.kind() == Kind.MONEY && give.kind() == Kind.MONEY) {
            throw new IllegalArgumentException("Cannot trade money for money.");
        }
        long initialStock;
        if (stockPart == null) {
            initialStock = give.amount();
        } else if (give.kind() == Kind.MONEY) {
            Money parsedStock = parseMoneyToken(stockPart, parser);
            initialStock = parsedStock.minor();
        } else {
            long parsedStock;
            try {
                parsedStock = Long.parseLong(stockPart);
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("Stock amount must be a whole number.");
            }
            if (parsedStock < give.amount() || parsedStock > MAX_ITEM_STOCK) {
                throw new IllegalArgumentException("Initial stock must be between " + give.amount() + " and " + MAX_ITEM_STOCK + ".");
            }
            initialStock = parsedStock;
        }
        if (initialStock < give.amount()) {
            throw new IllegalArgumentException("Initial stock must cover at least one trade.");
        }
        return new TradeSignSpec(pay, give, initialStock);
    }

    public static TradeSignSpec parse(String payLine, String giveLine) {
        return parse(payLine, giveLine, "$", null);
    }

    public static Offer parseOffer(String raw, String currencySymbol, Function<String, Money> moneyParser) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Enter an amount and item (e.g. '16 coal') or price (e.g. '$50').");
        }
        if (isMoneyToken(trimmed, currencySymbol)) {
            Money money = parseMoneyToken(trimmed, moneyParser);
            if (money.minor() <= 0) {
                throw new IllegalArgumentException("Price must be greater than zero.");
            }
            return Offer.money(money);
        }
        int space = trimmed.indexOf(' ');
        if (space <= 0 || space >= trimmed.length() - 1) {
            throw new IllegalArgumentException("Enter '<amount> <item>' (e.g. '16 coal') or a price (e.g. '$50').");
        }
        int amount;
        try {
            amount = Integer.parseInt(trimmed.substring(0, space).trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("Item amount must be a whole number from 1 to " + MAX_ITEM_BATCH + ".");
        }
        if (amount < 1 || amount > MAX_ITEM_BATCH) {
            throw new IllegalArgumentException("Item amount must be from 1 to " + MAX_ITEM_BATCH + ".");
        }
        String itemSpec = trimmed.substring(space + 1).trim();
        if (itemSpec.isEmpty() || itemSpec.contains("|") || itemSpec.contains(":")) {
            throw new IllegalArgumentException("Enter a valid item name.");
        }
        return Offer.item(itemSpec, amount);
    }

    private static boolean isMoneyToken(String token, String sym) {
        if (token.startsWith("$") || token.endsWith("$")) return true;
        return sym != null && !sym.isEmpty() && (token.startsWith(sym) || token.endsWith(sym));
    }

    private static Money defaultParseMoney(String token, String sym) {
        String s = token.trim();
        if (s.startsWith("$")) s = s.substring(1).trim();
        else if (s.endsWith("$")) s = s.substring(0, s.length() - 1).trim();
        if (sym != null && !sym.isEmpty()) {
            if (s.startsWith(sym)) s = s.substring(sym.length()).trim();
            else if (s.endsWith(sym)) s = s.substring(0, s.length() - sym.length()).trim();
        }
        return Money.parse(s, false);
    }

    private static Money parseMoneyToken(String token, Function<String, Money> parser) {
        try {
            return parser.apply(token);
        } catch (IllegalArgumentException | ArithmeticException bad) {
            throw new IllegalArgumentException("Enter a valid price greater than zero.");
        }
    }

    /** Encodes owner UUID + immutable trade terms into {@link SignIdentity#argument()}. */
    public static String encodeArgument(UUID ownerUuid, Offer pay, Offer give) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(pay, "pay");
        Objects.requireNonNull(give, "give");
        return ownerUuid + "|" + pay.kind().code() + "," + pay.amount() + "," + pay.itemSpec()
                + "|" + give.kind().code() + "," + give.amount() + "," + give.itemSpec();
    }

    public static Encoded decodeArgument(String argument) {
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException("Missing trade sign argument");
        }
        String[] parts = argument.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed trade sign argument");
        }
        UUID owner = UUID.fromString(parts[0]);
        Offer pay = decodeOffer(parts[1]);
        Offer give = decodeOffer(parts[2]);
        if (pay.kind() == Kind.MONEY && give.kind() == Kind.MONEY) {
            throw new IllegalArgumentException("Cannot trade money for money");
        }
        return new Encoded(owner, pay, give);
    }

    private static Offer decodeOffer(String part) {
        String[] fields = part.split(",", 3);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Malformed trade offer");
        }
        Kind kind = Kind.fromCode(fields[0]);
        long amount = Long.parseLong(fields[1]);
        return new Offer(kind, fields[2], amount);
    }

    /** Formats a sign line showing an offer (e.g. {@code $50} or {@code 16 coal}). */
    public static String formatOffer(Offer offer, Function<Money, String> moneyFormatter) {
        if (offer.kind() == Kind.MONEY) {
            return moneyFormatter.apply(new Money(offer.amount()));
        }
        return offer.amount() + " " + offer.itemSpec().toLowerCase(Locale.ROOT);
    }

    /** Formats Line 3 showing the give offer and remaining stock (e.g. {@code 16 coal:64} or {@code $50:$500}). */
    public static String formatStockLine(Offer give, long stockAmount, Function<Money, String> moneyFormatter) {
        String base = formatOffer(give, moneyFormatter);
        String stock = give.kind() == Kind.MONEY
                ? moneyFormatter.apply(new Money(Math.max(0L, stockAmount)))
                : Long.toString(Math.max(0L, stockAmount));
        return base + ":" + stock;
    }
}
