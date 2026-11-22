package codes.castled.curios;

import java.util.*;
import java.util.function.Predicate;

/**
 * Parses and completes the admin give command.
 *
 * <ul>
 *   <li>{@code /cards give [player] <entity> [rarity] [level] [quality]}: a trading card by mob,
 *       as legacy's {@code /tradingcards give}. Rarity defaults to simple, level to 0 and quality
 *       to 100.</li>
 *   <li>{@code /relics give [player] <relic> [level]}: a relic by its definition id.</li>
 *   <li>{@code /curios give ...}: either form.</li>
 * </ul>
 *
 * The label decides what is suggested and accepted, so {@code /cards give} never lists relics.
 */
final class GiveCommand {

    enum Scope {
        CARDS, RELICS, ALL;

        /** The scope for the label a command was run with ({@code cards}, {@code curios:relics}, ...). */
        static Scope of(String label) {
            String bare = label == null ? "" : label.toLowerCase(Locale.ROOT);
            bare = bare.substring(bare.indexOf(':') + 1);
            return switch (bare) {
                case "cards", "tradingcards" -> CARDS;
                case "relics", "reliques" -> RELICS;
                default -> ALL;
            };
        }

        boolean cards() { return this != RELICS; }
        boolean relics() { return this != CARDS; }
    }

    /** A parsed give: {@code target} is null for the sender, {@code state} the curio state to create. */
    record Request(String target, Catalog.Entry entry, Map<String, String> state) {}

    /** Either a request or the message explaining why there is none. */
    record Result(Request request, String error) {
        static Result error(String message) { return new Result(null, message); }
    }

    private static final List<String> QUALITIES = List.of("1", "50", "85", "100");

    private final Map<String, EnumMap<CardRules.Tier, Catalog.Entry>> cards = new TreeMap<>();
    private final Map<String, Catalog.Entry> relics = new TreeMap<>();

    GiveCommand(Catalog catalog) {
        for (Catalog.Entry entry : catalog.entries.values()) {
            if (entry.legacyCard().isEmpty()) {
                relics.put(entry.id(), entry);
            } else {
                cards.computeIfAbsent(entry.legacyCard().toLowerCase(Locale.ROOT), mob -> new EnumMap<>(CardRules.Tier.class))
                        .put(CardRules.Tier.parse(entry.legacyTier()), entry);
            }
        }
    }

    static String usage(String label, Scope scope) {
        String command = "/" + (label == null || label.isBlank() ? "curios" : label) + " give [player] ";
        return switch (scope) {
            case CARDS -> command + "<entity> [rarity] [level] [quality]";
            case RELICS -> command + "<relic> [level]";
            case ALL -> command + "<entity|relic> [rarity|level] [level] [quality]";
        };
    }

    /** Names this scope can give: card entities, relic ids, or both. */
    List<String> names(Scope scope) {
        List<String> out = new ArrayList<>();
        if (scope.cards()) out.addAll(cards.keySet());
        if (scope.relics()) out.addAll(relics.keySet());
        return out;
    }

    private boolean gives(String name, Scope scope) {
        String key = name.toLowerCase(Locale.ROOT);
        return scope.cards() && cards.containsKey(key) || scope.relics() && relics.containsKey(key);
    }

    /**
     * @param args     the arguments after {@code give}
     * @param isPlayer whether a name is an online player the sender may target
     */
    Result parse(String label, String[] args, Scope scope, Predicate<String> isPlayer) {
        if (args.length == 0) return Result.error("Usage: " + usage(label, scope));
        String target = null;
        int at = 0;
        if (args.length >= 2 && isPlayer.test(args[0]) && gives(args[1], scope)) {
            target = args[0];
            at = 1;
        } else if (!gives(args[0], scope)) {
            if (!isPlayer.test(args[0])) return Result.error("Unknown " + noun(scope) + " '" + args[0] + "'.");
            if (args.length < 2) return Result.error("Usage: " + usage(label, scope));
            return Result.error("Unknown " + noun(scope) + " '" + args[1] + "'.");
        }
        String name = args[at].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, at + 1, args.length);
        if (scope.cards() && cards.containsKey(name)) return card(label, target, name, rest, scope);
        return relic(label, target, relics.get(name), rest, scope);
    }

    private Result card(String label, String target, String mob, String[] rest, Scope scope) {
        if (rest.length > 3) return Result.error("Usage: " + usage(label, scope));
        CardRules.Tier tier = CardRules.Tier.SIMPLE;
        if (rest.length > 0) {
            tier = rarity(rest[0]);
            if (tier == null) {
                return Result.error("Unknown rarity '" + rest[0] + "'. Use simple, elite, ultimate, legendary or fabled.");
            }
        }
        Catalog.Entry entry = cards.get(mob).get(tier);
        if (entry == null) {
            return Result.error(CardRules.titleCase(mob) + " has no " + CardRules.titleCase(tier.name()) + " card.");
        }
        int level = rest.length > 1 ? CardRules.parseIntSafe(rest[1], -1) : 0;
        if (level < 0 || level > entry.maxLevel()) return Result.error("Level must be 0.." + entry.maxLevel() + ".");
        int quality = rest.length > 2 ? CardRules.parseIntSafe(rest[2], -1) : 100;
        if (quality < 1 || quality > 100) return Result.error("Quality must be 1..100.");
        return new Result(new Request(target, entry, Map.of("level", Integer.toString(level),
                "quality", Integer.toString(quality))), null);
    }

    private Result relic(String label, String target, Catalog.Entry entry, String[] rest, Scope scope) {
        if (rest.length > 1) return Result.error("Usage: " + usage(label, scope));
        int level = rest.length > 0 ? CardRules.parseIntSafe(rest[0], -1) : 0;
        if (level < 0 || level > entry.maxLevel()) return Result.error("Level must be 0.." + entry.maxLevel() + ".");
        return new Result(new Request(target, entry, Map.of("level", Integer.toString(level))), null);
    }

    /**
     * Suggestions for the argument being typed.
     *
     * @param args    the arguments after {@code give}, the last one partial
     * @param players online player names the sender may target
     */
    List<String> complete(String[] args, Scope scope, Predicate<String> isPlayer, Collection<String> players) {
        if (args.length == 0) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        if (args.length == 1) {
            out.addAll(players);
            out.addAll(names(scope));
        } else {
            // The first argument is the curio, or a player followed by the curio.
            if (gives(args[0], scope)) out.addAll(parameters(args[0], args.length - 2, scope));
            if (isPlayer.test(args[0])) {
                if (args.length == 2) out.addAll(names(scope));
                else if (gives(args[1], scope)) out.addAll(parameters(args[1], args.length - 3, scope));
            }
        }
        return out.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private List<String> parameters(String name, int position, Scope scope) {
        String key = name.toLowerCase(Locale.ROOT);
        var tiers = scope.cards() ? cards.get(key) : null;
        if (tiers != null) {
            return switch (position) {
                case 0 -> tiers.keySet().stream().map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
                case 1 -> levels(tiers.values().iterator().next().maxLevel());
                case 2 -> QUALITIES;
                default -> List.of();
            };
        }
        Catalog.Entry relic = relics.get(key);
        return relic != null && position == 0 ? levels(relic.maxLevel()) : List.of();
    }

    private static List<String> levels(int max) {
        return new LinkedHashSet<>(List.of("0", Integer.toString(max / 4), Integer.toString(max / 2),
                Integer.toString(max))).stream().toList();
    }

    private static CardRules.Tier rarity(String raw) {
        for (CardRules.Tier tier : CardRules.Tier.values()) {
            if (tier.name().equalsIgnoreCase(raw.trim())) return tier;
        }
        return null;
    }

    private static String noun(Scope scope) {
        return switch (scope) {
            case CARDS -> "card entity";
            case RELICS -> "relic";
            case ALL -> "card entity or relic";
        };
    }
}
