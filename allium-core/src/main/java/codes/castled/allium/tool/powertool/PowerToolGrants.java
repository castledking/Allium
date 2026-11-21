package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * The {@code grant} action's value: permission nodes, each optionally weighted
 * ({@code 10:allium.glow.aqua 1:allium.glow.black}; no weight means 1), separated by spaces or
 * commas. Only the nodes a player lacks are candidates; {@code pick: random} (the default) gives one
 * of them by weight, {@code pick: all} every one.
 */
public final class PowerToolGrants {

    public static final String PARAM_PICK = "pick";
    public static final String PICK_RANDOM = "random";
    public static final String PICK_ALL = "all";
    public static final int MAX_WEIGHT = 1_000_000;

    private static final Pattern NODE = Pattern.compile("^[a-z0-9][a-z0-9_.*-]{0,127}$");

    /** One node and its weight. */
    public record Choice(int weight, String node) {
    }

    private PowerToolGrants() {
    }

    /** The choices in order; a node listed twice keeps its first weight. Invalid entries are left out. */
    public static List<Choice> parse(@Nullable String value) {
        Map<String, Choice> choices = new LinkedHashMap<>();
        for (String token : tokens(value)) {
            Choice choice = choice(token);
            if (choice != null) choices.putIfAbsent(choice.node(), choice);
        }
        return List.copyOf(choices.values());
    }

    /** The first entry that is not {@code node} or {@code weight:node}, or null when all are. */
    @Nullable
    public static String firstInvalid(@Nullable String value) {
        List<String> tokens = tokens(value);
        if (tokens.isEmpty()) return value == null ? "" : value.trim();
        for (String token : tokens) {
            if (choice(token) == null) return token;
        }
        return null;
    }

    /** Whether {@code params.pick} asks for every missing node. */
    public static boolean pickAll(Map<String, Object> params) {
        Object pick = params.get(PARAM_PICK);
        return pick != null && PICK_ALL.equalsIgnoreCase(String.valueOf(pick).trim());
    }

    /**
     * What to give: the choices {@code held} rejects are left out, then either all of the rest or
     * one of them picked by weight. Empty when the player already has every node.
     */
    public static List<Choice> select(List<Choice> choices, Predicate<String> held, boolean all, RandomGenerator random) {
        List<Choice> missing = new ArrayList<>();
        long total = 0L;
        for (Choice choice : choices) {
            if (held.test(choice.node())) continue;
            missing.add(choice);
            total += choice.weight();
        }
        if (missing.isEmpty() || all) return List.copyOf(missing);
        long roll = random.nextLong(total);
        for (Choice choice : missing) {
            roll -= choice.weight();
            if (roll < 0L) return List.of(choice);
        }
        return List.of(missing.get(missing.size() - 1));
    }

    private static List<String> tokens(@Nullable String value) {
        List<String> tokens = new ArrayList<>();
        if (value == null) return tokens;
        for (String token : value.trim().split("[\\s,]+")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }

    @Nullable
    private static Choice choice(String token) {
        int weight = 1;
        String node = token;
        int colon = token.indexOf(':');
        if (colon >= 0) {
            try {
                weight = Integer.parseInt(token.substring(0, colon).trim());
            } catch (NumberFormatException notWeighted) {
                return null;
            }
            node = token.substring(colon + 1);
        }
        node = node.trim().toLowerCase(Locale.ROOT);
        if (weight < 1 || weight > MAX_WEIGHT || !NODE.matcher(node).matches()) return null;
        return new Choice(weight, node);
    }
}
