package codes.castled.allium.economy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable validated cost and cooldown policy for explicit outcomes-aware convenience commands.
 *
 * <p>Costs and cooldowns apply only when a command actually delivers its effect (never merely
 * because a command was parsed or refused).
 */
public final class CommandActionPolicy {

    public static final String PERMISSION_BYPASS_COST = "allium.command.bypass.cost";
    public static final String PERMISSION_BYPASS_COOLDOWN = "allium.command.bypass.cooldown";
    public static final long MAX_COOLDOWN_MILLIS = 30L * 86_400_000L; // 30 days

    /** Supported canonical convenience command actions eligible for outcomes-aware policy. */
    public static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "repair",
            "repair-all",
            "heal",
            "feed",
            "ext",
            "hat",
            "workbench",
            "anvil",
            "disposal",
            "grindstone",
            "cartographytable",
            "stonecutter",
            "loom",
            "smithing"
    );

    public record Rule(Money cost, long cooldownMillis) {
        public static final Rule FREE = new Rule(new Money(0), 0L);

        public Rule {
            Objects.requireNonNull(cost, "cost");
            if (cost.minor() < 0) {
                throw new IllegalArgumentException("command-policy cost must be non-negative");
            }
            if (cooldownMillis < 0 || cooldownMillis > MAX_COOLDOWN_MILLIS) {
                throw new IllegalArgumentException("command-policy cooldown must be 0 to 2592000 seconds");
            }
        }

        public boolean isFree() {
            return cost.minor() == 0 && cooldownMillis == 0L;
        }
    }

    private static final CommandActionPolicy EMPTY = new CommandActionPolicy(Map.of());

    private final Map<String, Rule> rules;

    public CommandActionPolicy(Map<String, Rule> rules) {
        Objects.requireNonNull(rules, "rules");
        Map<String, Rule> copy = new LinkedHashMap<>();
        for (var entry : rules.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (!SUPPORTED_ACTIONS.contains(key)) {
                throw new IllegalArgumentException("unsupported command-policy action '" + entry.getKey()
                        + "'; supported: " + String.join(", ", SUPPORTED_ACTIONS.stream().sorted().toList()));
            }
            Rule rule = Objects.requireNonNull(entry.getValue(), "rule");
            if (!rule.isFree()) {
                copy.put(key, rule);
            }
        }
        this.rules = Map.copyOf(copy);
    }

    public static CommandActionPolicy empty() {
        return EMPTY;
    }

    public Rule ruleFor(String action) {
        if (action == null) return Rule.FREE;
        return rules.getOrDefault(normalizeKey(action), Rule.FREE);
    }

    public Map<String, Rule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public static String normalizeKey(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static String bypassCostPermission(String action) {
        return PERMISSION_BYPASS_COST + "." + normalizeKey(action);
    }

    public static String bypassCooldownPermission(String action) {
        return PERMISSION_BYPASS_COOLDOWN + "." + normalizeKey(action);
    }
}
