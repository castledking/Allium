package codes.castled.allium.item.spec;

import codes.castled.allium.item.ProviderKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses {@code <item-spec> [amount] [tokens | flags...]} over {@link ProviderKey}.
 *
 * <ul>
 *   <li>{@code <item-spec>} is one reference or a comma-separated batch. Each reference is a
 *       provider key (bare names are vanilla), optionally followed by exactly one raw
 *       {@code [...]} component or {@code {...}} NBT group that ends the reference.</li>
 *   <li>{@code [amount]} is taken when the next argument is a plain positive integer.</li>
 *   <li>Remaining arguments are {@code key:value} or bare tokens, or flags ({@code -e},
 *       {@code -equip}, {@code --overflow=<policy>}).</li>
 * </ul>
 *
 * Commas, semicolons, colons and spaces inside brackets, braces and quotes never split anything.
 * Provider names are case-insensitive; provider-local ids keep their case (Oraxen ids are
 * case-sensitive).
 * The legacy {@code item;meta;amount} form is rejected unless {@code legacySemicolonSyntax} is on.
 */
public final class ItemSpecParser {

    public static final int MAX_AMOUNT = 2304;
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,9}");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_.\\-/:]+");
    private static final Pattern TOKEN_KEY = Pattern.compile("[a-z0-9_.\\-]+(:[a-z0-9_.\\-/]+)?");

    private final boolean legacySemicolonSyntax;

    public ItemSpecParser(boolean legacySemicolonSyntax) {
        this.legacySemicolonSyntax = legacySemicolonSyntax;
    }

    public GiveSpec parse(String arguments) {
        List<String> args = ItemSpecLexer.splitArguments(arguments == null ? "" : arguments);
        if (args.isEmpty()) {
            throw new ItemSpecException("an item is required");
        }
        String itemSpec = args.get(0);
        int next = 1;
        OptionalInt amount = OptionalInt.empty();
        List<MetaToken> tokens = new ArrayList<>();
        boolean equip = false;
        Optional<String> overflow = Optional.empty();

        if (ItemSpecLexer.indexOfTopLevel(itemSpec, ';') >= 0) {
            if (!legacySemicolonSyntax) {
                throw new ItemSpecException("semicolon item syntax (item;meta;amount) is disabled; write "
                        + "<item> [amount] [key:value...] instead");
            }
            List<String> parts = ItemSpecLexer.splitTopLevel(itemSpec, ';');
            itemSpec = parts.get(0);
            for (String part : parts.subList(1, parts.size())) {
                if (AMOUNT.matcher(part).matches() && amount.isEmpty()) {
                    amount = OptionalInt.of(amount(part));
                } else if (!part.isBlank()) {
                    tokens.add(token(part));
                }
            }
        }
        List<ItemReference> items = new ArrayList<>();
        for (String reference : ItemSpecLexer.splitTopLevel(itemSpec, ',')) {
            items.add(parseReference(reference));
        }

        if (next < args.size() && AMOUNT.matcher(args.get(next)).matches()) {
            if (amount.isPresent()) {
                throw new ItemSpecException("the amount is given twice");
            }
            amount = OptionalInt.of(amount(args.get(next)));
            next++;
        }
        Set<String> seen = new HashSet<>();
        tokens.forEach(t -> seen.add(t.key()));
        for (String arg : args.subList(next, args.size())) {
            if (arg.startsWith("--overflow=")) {
                overflow = Optional.of(arg.substring("--overflow=".length()).toLowerCase(Locale.ROOT));
            } else if (arg.equals("-e") || arg.equals("-equip") || arg.equals("--equip")) {
                equip = true;
            } else if (arg.startsWith("-")) {
                throw new ItemSpecException("unknown flag '" + arg + "'");
            } else {
                MetaToken token = token(arg);
                if (!seen.add(token.key())) {
                    throw new ItemSpecException("'" + token.key() + "' is given twice");
                }
                tokens.add(token);
            }
        }
        return new GiveSpec(items, amount, tokens, equip, overflow);
    }

    /** Parses one reference, optionally ending in a single raw {@code [...]} or {@code {...}} group. */
    public ItemReference parseReference(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new ItemSpecException("empty item reference");
        }
        int open = ItemSpecLexer.indexOfOpenGroup(trimmed);
        String reference = open < 0 ? trimmed : trimmed.substring(0, open);
        Optional<RawItemData> raw = Optional.empty();
        if (open >= 0) {
            int end = ItemSpecLexer.endOfGroup(trimmed, open);
            if (end != trimmed.length()) {
                throw new ItemSpecException("unexpected text after the raw data group in '" + trimmed + "'", end);
            }
            RawItemData.Kind kind = trimmed.charAt(open) == '[' ? RawItemData.Kind.COMPONENTS : RawItemData.Kind.NBT;
            raw = Optional.of(new RawItemData(kind, trimmed.substring(open)));
        }
        if (reference.isEmpty()) {
            throw new ItemSpecException("missing item id before the raw data group in '" + trimmed + "'");
        }
        if (!REFERENCE.matcher(reference).matches()) {
            throw new ItemSpecException("'" + reference + "' is not an item reference (use letters, digits, _ . - / and :)");
        }
        ProviderKey key;
        try {
            // Only the provider is case-insensitive; provider ids such as Oraxen's keep their case.
            key = ProviderKey.parse(reference);
        } catch (IllegalArgumentException e) {
            throw new ItemSpecException(e.getMessage());
        }
        return new ItemReference(trimmed, key, raw);
    }

    private static MetaToken token(String arg) {
        int colon = ItemSpecLexer.indexOfTopLevel(arg, ':');
        String key = (colon < 0 ? arg : arg.substring(0, colon)).toLowerCase(Locale.ROOT);
        if (!TOKEN_KEY.matcher(key).matches()) {
            throw new ItemSpecException("'" + arg + "' is not a key:value token");
        }
        if (colon < 0) {
            return new MetaToken(key, null, false);
        }
        String value = arg.substring(colon + 1);
        if (value.isEmpty()) {
            throw new ItemSpecException("'" + key + "' needs a value");
        }
        boolean quoted = ItemSpecLexer.isQuoted(value);
        return new MetaToken(key, quoted ? ItemSpecLexer.unquote(value) : value, quoted);
    }

    private static int amount(String raw) {
        long value = Long.parseLong(raw);
        if (value < 1 || value > MAX_AMOUNT) {
            throw new ItemSpecException("the amount must be between 1 and " + MAX_AMOUNT);
        }
        return (int) value;
    }
}
