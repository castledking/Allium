package codes.castled.allium.item.spec;

import codes.castled.allium.item.ProviderKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
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
    private final Predicate<String> hasVariants;

    public ItemSpecParser(boolean legacySemicolonSyntax) {
        this(legacySemicolonSyntax, ignored -> false);
    }

    public ItemSpecParser(boolean legacySemicolonSyntax, Predicate<String> hasVariants) {
        this.legacySemicolonSyntax = legacySemicolonSyntax;
        this.hasVariants = hasVariants;
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

        // A comma batch ("stick,gshovel" or "stick:1,diamond:2") gives each item its own amount;
        // item;meta forms only apply to a single item.
        boolean batch=ItemSpecLexer.splitTopLevel(itemSpec,',').size()>1;
        if(!batch&&ItemSpecLexer.indexOfTopLevel(itemSpec,';')>=0){
            var variant=ItemSpecLexer.splitTopLevel(itemSpec,';');String base=variant.get(0).toLowerCase(Locale.ROOT);
            if (hasVariants.test(base) && variant.size() >= 2 && !variant.get(1).contains(":")) {
                if (variant.size() != 2 || variant.get(1).isBlank()) {
                    throw new ItemSpecException("item variant syntax is <item>;<variant>");
                }
                itemSpec = variant.get(0);
                tokens.add(new MetaToken("variant", variant.get(1), false));
            } else if(Set.of("potion","splash_potion","lingering_potion","tipped_arrow","spawner","minecraft:potion","minecraft:splash_potion","minecraft:lingering_potion","minecraft:tipped_arrow","minecraft:spawner").contains(base)){
                itemSpec=variant.get(0);
                if(!base.endsWith("spawner")&&Set.of("splash","lingering").contains(variant.get(1).toLowerCase(Locale.ROOT))){itemSpec=variant.get(1).toLowerCase(Locale.ROOT)+"_potion";tokens.add(new MetaToken("potion","water",false));}
                else if(base.endsWith("spawner")){if(variant.size()!=2)throw new ItemSpecException("spawner syntax is spawner;entity");tokens.add(new MetaToken("spawner",variant.get(1),false));}
                else {if(variant.size()<2||variant.size()>6)throw new ItemSpecException("potion syntax is potion;effect;seconds;level;particles;icon");String seconds=variant.size()>2?variant.get(2):"0",level=variant.size()>3?variant.get(3):"1";if(Set.of("water","awkward","mundane","thick").contains(variant.get(1).toLowerCase(Locale.ROOT)))tokens.add(new MetaToken("potion",variant.get(1),false));else tokens.add(new MetaToken("legacy-effect",variant.get(1)+":"+seconds+":"+level+(variant.size()>4?":"+String.join(":",variant.subList(4,variant.size())):""),false));}
            }
        }
        if (!batch && ItemSpecLexer.indexOfTopLevel(itemSpec, ';') >= 0) {
            if (!legacySemicolonSyntax && ItemSpecLexer.splitTopLevel(itemSpec,';').stream().skip(1).anyMatch(p -> !p.contains(":"))) {
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
            items.add(parseCounted(reference, batch));
        }

        if (next < args.size() && AMOUNT.matcher(args.get(next)).matches()) {
            if (amount.isPresent() || !batch && items.get(0).amount().isPresent()) {
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

    /** Provider namespaces: {@code oraxen:12} names an item, it is not 12 of "oraxen". */
    private static final Set<String> PROVIDERS = Set.of(ProviderKey.MINECRAFT, ProviderKey.ALLIUM, ProviderKey.NEXO,
            ProviderKey.ORAXEN, ProviderKey.CRAFTENGINE, "ce");

    /**
     * One reference with an optional amount of its own: {@code stick:1}, {@code minecraft:stick:1},
     * {@code 1:1:5} (legacy id 1:1, five of it), or (legacy syntax, in a batch) {@code dirt;1}.
     */
    ItemReference parseCounted(String text, boolean batch) {
        String trimmed = text.trim();
        OptionalInt own = OptionalInt.empty();
        if (ItemSpecLexer.indexOfTopLevel(trimmed, ';') >= 0) {
            int semicolon = trimmed.lastIndexOf(';');
            String head = trimmed.substring(0, semicolon), tail = trimmed.substring(semicolon + 1);
            if (!batch || !legacySemicolonSyntax || head.contains(";") || !AMOUNT.matcher(tail).matches()) {
                throw new ItemSpecException("a comma batch takes item or item:amount entries ('" + trimmed
                        + "'); give key:value tokens after the batch instead");
            }
            trimmed = head;
            own = OptionalInt.of(amount(tail));
        } else if (ItemSpecLexer.indexOfOpenGroup(trimmed) < 0) {
            int colon = trimmed.lastIndexOf(':');
            // "1:1" is a legacy numeric id (granite), not one stone; "1:1:5" is five granite.
            String base = colon > 0 ? trimmed.substring(0, colon) : "";
            if (colon > 0 && AMOUNT.matcher(trimmed.substring(colon + 1)).matches()
                    && !PROVIDERS.contains(base.toLowerCase(Locale.ROOT)) && !base.chars().allMatch(Character::isDigit)) {
                own = OptionalInt.of(amount(trimmed.substring(colon + 1)));
                trimmed = trimmed.substring(0, colon);
            }
        }
        return parseReference(trimmed).withAmount(own);
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
        if(colon>0&&!Set.of("name","lore","effect","potion","owner","color","spawner","legacy-effect").contains(arg.substring(0,colon).toLowerCase(Locale.ROOT))){int last=arg.lastIndexOf(':');if(last>colon&&arg.substring(last+1).matches("[0-9]+"))colon=last;}

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
