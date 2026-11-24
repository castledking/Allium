package codes.castled.allium.item.spec;

import codes.castled.allium.item.ProviderKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Provider-aware allow/deny and per-item spawn permission policy for {@code /give} and {@code /i}.
 *
 * <p>Rules match canonical {@link ProviderKey} values ({@code <provider>:<id>}). Bare names without
 * a colon (except {@code *}) normalize to {@code minecraft:<pattern>}. More specific rules win over
 * broader wildcards; on a tie, {@code deny} wins (fail-closed).
 */
public final class GiveItemPolicy {

    public static final String PERMISSION_BYPASS = "allium.give.bypass.policy";
    public static final String PERMISSION_ITEM_PREFIX = "allium.give.item.";

    public enum Mode {
        DENYLIST("denylist"),
        ALLOWLIST("allowlist");

        private final String configName;

        Mode(String configName) {
            this.configName = configName;
        }

        public String configName() {
            return configName;
        }

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DENYLIST;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "denylist", "blacklist", "deny" -> DENYLIST;
                case "allowlist", "whitelist", "allow" -> ALLOWLIST;
                default -> throw new IllegalArgumentException(
                        "items.give.policy.mode must be denylist or allowlist, got '" + raw + "'");
            };
        }
    }

    public record Decision(boolean allowed, String reason, String matchedRule) {
        public static Decision permit() {
            return new Decision(true, "", "");
        }

        public static Decision refuse(String reason, String matchedRule) {
            return new Decision(false, Objects.requireNonNull(reason, "reason"),
                    matchedRule == null ? "" : matchedRule);
        }
    }

    /**
     * Compiled pattern matching a {@link ProviderKey}.
     *
     * @param raw          original normalized rule text
     * @param provider     provider name or {@code "*"}
     * @param idPattern    id glob pattern (containing zero or more {@code '*'} wildcards)
     * @param tier         3 = exact, 2 = scoped wildcard, 1 = provider wildcard, 0 = global wildcard
     * @param literalChars count of non-wildcard characters in {@code idPattern} for scoped tie-breaking
     */
    public record Rule(String raw, String provider, String idPattern, int tier, int literalChars) {

        public static Rule parse(String input) {
            if (input == null || input.isBlank()) {
                throw new IllegalArgumentException("items.give.policy rule must not be blank");
            }
            String trimmed = input.trim();
            if (trimmed.equals("*") || trimmed.equals("*:*")) {
                return new Rule("*:*", "*", "*", 0, 0);
            }
            int colon = trimmed.indexOf(':');
            String provider;
            String idPattern;
            if (colon < 0) {
                provider = ProviderKey.MINECRAFT;
                idPattern = trimmed.toLowerCase(Locale.ROOT);
            } else {
                if (colon == 0 || colon == trimmed.length() - 1) {
                    throw new IllegalArgumentException("malformed items.give.policy rule '" + input + "'");
                }
                provider = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                idPattern = trimmed.substring(colon + 1).trim();
                if (provider.equals("ci") || provider.equals("custom")) {
                    throw new IllegalArgumentException(
                            "legacy prefix '" + provider + ":' is retired in items.give.policy; use 'allium:" + idPattern + "'");
                }
                if (provider.equals("ce")) {
                    provider = ProviderKey.CRAFTENGINE;
                }
                if (provider.equals(ProviderKey.MINECRAFT)
                        || provider.equals(ProviderKey.ALLIUM)
                        || provider.equals(ProviderKey.CRAFTENGINE)) {
                    idPattern = idPattern.toLowerCase(Locale.ROOT);
                }
            }
            if (provider.isEmpty() || idPattern.isEmpty() || idPattern.startsWith(":") || idPattern.endsWith(":")) {
                throw new IllegalArgumentException("malformed items.give.policy rule '" + input + "'");
            }
            int tier;
            int literals = 0;
            if (provider.equals("*")) {
                tier = idPattern.equals("*") ? 0 : 2;
            } else if (idPattern.equals("*")) {
                tier = 1;
            } else if (idPattern.indexOf('*') >= 0) {
                tier = 2;
            } else {
                tier = 3;
            }
            for (int i = 0; i < idPattern.length(); i++) {
                if (idPattern.charAt(i) != '*') literals++;
            }
            return new Rule(provider + ":" + idPattern, provider, idPattern, tier, literals);
        }

        public boolean matches(ProviderKey key) {
            if (!provider.equals("*") && !provider.equals(key.provider())) {
                return false;
            }
            if (idPattern.equals("*")) {
                return true;
            }
            return globMatches(idPattern, key.id());
        }

        private int rankScore() {
            return tier * 10_000 + literalChars;
        }
    }

    private static final GiveItemPolicy ALLOW_ALL = new GiveItemPolicy(Mode.DENYLIST, List.of(), List.of(), false);

    private final Mode mode;
    private final List<Rule> deny;
    private final List<Rule> allow;
    private final boolean permissionBasedSpawn;

    public GiveItemPolicy(Mode mode, List<String> denyRules, List<String> allowRules, boolean permissionBasedSpawn) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.deny = compile(denyRules);
        this.allow = compile(allowRules);
        this.permissionBasedSpawn = permissionBasedSpawn;
    }

    public static GiveItemPolicy allowAll() {
        return ALLOW_ALL;
    }

    public Mode mode() {
        return mode;
    }

    public List<Rule> denyRules() {
        return deny;
    }

    public List<Rule> allowRules() {
        return allow;
    }

    public boolean permissionBasedSpawn() {
        return permissionBasedSpawn;
    }

    public boolean isDefaultPermissive() {
        return mode == Mode.DENYLIST && deny.isEmpty() && !permissionBasedSpawn;
    }

    /**
     * Evaluates whether {@code key} may be given under this policy.
     *
     * @param key           canonical resolved item key (after legacy alias expansion)
     * @param bypassPolicy  true for console/command blocks or players holding {@value #PERMISSION_BYPASS}
     * @param hasPermission permission predicate for the command sender
     */
    public Decision evaluate(ProviderKey key, boolean bypassPolicy, Predicate<String> hasPermission) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(hasPermission, "hasPermission");
        if (bypassPolicy) {
            return Decision.permit();
        }
        Rule bestDeny = bestMatch(deny, key);
        Rule bestAllow = bestMatch(allow, key);

        if (mode == Mode.DENYLIST) {
            if (bestDeny != null && (bestAllow == null || bestDeny.rankScore() >= bestAllow.rankScore())) {
                return Decision.refuse("blocked by deny rule '" + bestDeny.raw() + "'", bestDeny.raw());
            }
        } else {
            // ALLOWLIST mode: must match an allow rule, and no equally/more specific deny rule may override it.
            if (bestAllow == null) {
                return Decision.refuse("not permitted by allowlist", "mode:allowlist");
            }
            if (bestDeny != null && bestDeny.rankScore() >= bestAllow.rankScore()) {
                return Decision.refuse("blocked by deny rule '" + bestDeny.raw() + "'", bestDeny.raw());
            }
        }

        if (permissionBasedSpawn) {
            List<String> accepted = requiredPermissionNodes(key);
            for (String node : accepted) {
                if (hasPermission.test(node)) {
                    return Decision.permit();
                }
            }
            return Decision.refuse("requires permission " + accepted.get(0), accepted.get(0));
        }
        return Decision.permit();
    }

    /**
     * Returns the permission nodes accepted for {@code key} when {@code permission-based-spawn} is
     * enabled, ordered from most specific to broadest.
     */
    public static List<String> requiredPermissionNodes(ProviderKey key) {
        Objects.requireNonNull(key, "key");
        String provider = key.provider().toLowerCase(Locale.ROOT);
        String normalizedId = key.id().toLowerCase(Locale.ROOT).replace(':', '.');
        List<String> nodes = new ArrayList<>();
        nodes.add(PERMISSION_ITEM_PREFIX + provider + "." + normalizedId);
        if (ProviderKey.MINECRAFT.equals(provider)) {
            nodes.add(PERMISSION_ITEM_PREFIX + normalizedId);
        }
        int colon = key.id().indexOf(':');
        if (colon > 0) {
            String subNamespace = key.id().substring(0, colon).toLowerCase(Locale.ROOT);
            nodes.add(PERMISSION_ITEM_PREFIX + provider + "." + subNamespace + ".*");
        }
        nodes.add(PERMISSION_ITEM_PREFIX + provider + ".*");
        nodes.add(PERMISSION_ITEM_PREFIX + "*");
        return List.copyOf(nodes);
    }

    private static List<Rule> compile(List<String> rawRules) {
        if (rawRules == null || rawRules.isEmpty()) return List.of();
        List<Rule> compiled = new ArrayList<>(rawRules.size());
        for (String raw : rawRules) {
            compiled.add(Rule.parse(raw));
        }
        return List.copyOf(compiled);
    }

    private static Rule bestMatch(List<Rule> rules, ProviderKey key) {
        Rule best = null;
        for (Rule rule : rules) {
            if (rule.matches(key) && (best == null || rule.rankScore() > best.rankScore())) {
                best = rule;
            }
        }
        return best;
    }

    /** Linear-time wildcard matcher supporting arbitrary {@code '*'} segments without regex backtracking. */
    static boolean globMatches(String pattern, String text) {
        int p = 0, t = 0, star = -1, match = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                match = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++match;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
