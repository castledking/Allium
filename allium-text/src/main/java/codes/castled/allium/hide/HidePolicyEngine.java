package codes.castled.allium.hide;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java 8 immutable command-path hide policy engine shared between the modern 1.20+ runtime
 * and the 1.12.2-1.19.4 Bukkit compatibility runtime.
 *
 * <p>Carefully separates raw command-line normalization ({@link #normalize(String)}, which strips
 * exactly one leading dispatch slash so double-slash commands like WorldEdit's {@code //wand}
 * become {@code /wand}) from already-normalized path operations ({@link #normalizePath(String)},
 * {@link #rootOfPath(String)}, {@link #plainOfPath(String)}, {@link #matchesNormalized}) so the
 * remaining slash on {@code /wand} is never stripped a second time.
 */
public final class HidePolicyEngine {

    public static final class Definition {
        private final String name;
        private final boolean whitelist;
        private final List<String> commands;
        private final List<String> completions;
        private final List<String> commandParents;
        private final List<String> completionParents;

        public Definition(String name, boolean whitelist, List<String> commands, List<String> completions,
                          List<String> commandParents, List<String> completionParents) {
            this.name = validateGroupName(name);
            this.whitelist = whitelist;
            this.commands = normalizeRules(commands);
            this.completions = normalizeRules(completions);
            this.commandParents = copyList(commandParents);
            this.completionParents = copyList(completionParents);
        }

        public String name() { return name; }
        public boolean whitelist() { return whitelist; }
        public List<String> commands() { return commands; }
        public List<String> completions() { return completions; }
        public List<String> commandParents() { return commandParents; }
        public List<String> completionParents() { return completionParents; }
    }

    public static final class Group {
        private final String name;
        private final boolean whitelist;
        private final List<String> commands;
        private final List<String> inheritedAllowed;
        private final List<String> completions;

        public Group(String name, boolean whitelist, List<String> commands, List<String> inheritedAllowed,
                     List<String> completions) {
            this.name = name;
            this.whitelist = whitelist;
            this.commands = copyList(commands);
            this.inheritedAllowed = copyList(inheritedAllowed);
            this.completions = copyList(completions);
        }

        public String name() { return name; }
        public boolean whitelist() { return whitelist; }
        public List<String> commands() { return commands; }
        public List<String> inheritedAllowed() { return inheritedAllowed; }
        public List<String> completions() { return completions; }

        /** Evaluates a raw (possibly leading-slash) command line against this group. */
        public boolean allows(String rawPath, boolean completion) {
            return allowsNormalized(normalize(rawPath), completion);
        }

        /** Evaluates an already-normalized path (preserving e.g. WorldEdit {@code /wand}). */
        public boolean allowsNormalized(String normalizedPath, boolean completion) {
            if (completion) {
                return whitelist == matchesNormalized(completions, normalizedPath, whitelist);
            }
            if (matchesNormalized(commands, normalizedPath, false)) {
                return whitelist;
            }
            return !whitelist && (inheritedAllowed.isEmpty() || matchesNormalized(inheritedAllowed, normalizedPath, false));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Group)) return false;
            Group other = (Group) o;
            return whitelist == other.whitelist
                    && name.equals(other.name)
                    && commands.equals(other.commands)
                    && inheritedAllowed.equals(other.inheritedAllowed)
                    && completions.equals(other.completions);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + (whitelist ? 1 : 0);
            result = 31 * result + commands.hashCode();
            result = 31 * result + inheritedAllowed.hashCode();
            result = 31 * result + completions.hashCode();
            return result;
        }
    }

    private final Map<String, Group> groups;

    public HidePolicyEngine(Collection<Definition> definitions) {
        if (definitions.size() > 128) {
            throw new IllegalArgumentException("too many hide groups");
        }
        Map<String, Definition> raw = new LinkedHashMap<String, Definition>();
        for (Definition d : definitions) {
            if (raw.put(d.name(), d) != null) {
                throw new IllegalArgumentException("duplicate hide group");
            }
        }
        Map<String, Group> compiled = new LinkedHashMap<String, Group>();
        for (String name : raw.keySet()) {
            compile(name, raw, compiled, new HashSet<String>());
        }
        this.groups = Collections.unmodifiableMap(compiled);
    }

    private static Group compile(String name, Map<String, Definition> raw, Map<String, Group> result,
                                 Set<String> visiting) {
        name = validateGroupName(name);
        if (result.containsKey(name)) return result.get(name);
        Definition d = raw.get(name);
        if (d == null) {
            throw new IllegalArgumentException("unknown inherited hide group: " + name);
        }
        if (!visiting.add(name)) {
            throw new IllegalArgumentException("cyclic hide group inheritance: " + name);
        }
        Set<String> commands = new LinkedHashSet<String>(d.commands());
        Set<String> allowed = new LinkedHashSet<String>();
        Set<String> tabs = new LinkedHashSet<String>(d.completions());
        for (String parent : d.commandParents()) {
            Group g = compile(parent, raw, result, visiting);
            if (d.whitelist()) {
                commands.addAll(g.commands());
            } else if (g.whitelist()) {
                allowed.addAll(g.commands());
                allowed.addAll(g.inheritedAllowed());
            }
        }
        for (String parent : d.completionParents()) {
            tabs.addAll(compile(parent, raw, result, visiting).completions());
        }
        if (tabs.remove("^")) {
            tabs.addAll(commands);
        }
        if (commands.contains("^")) {
            throw new IllegalArgumentException("^ is only valid in tabcompletes");
        }
        Group g = new Group(name, d.whitelist(),
                new ArrayList<String>(commands),
                new ArrayList<String>(allowed),
                new ArrayList<String>(tabs));
        visiting.remove(name);
        result.put(name, g);
        return g;
    }

    public Set<String> groupNames() {
        return groups.keySet();
    }

    public List<Group> select(Collection<String> memberships) {
        List<Group> selected = new ArrayList<Group>();
        if (memberships != null) {
            for (String name : memberships) {
                if (name == null) continue;
                Group g = groups.get(name.toLowerCase(Locale.ROOT));
                if (g != null && !selected.contains(g)) selected.add(g);
            }
        }
        if (selected.isEmpty() && groups.containsKey("default")) {
            selected.add(groups.get("default"));
        }
        return Collections.unmodifiableList(selected);
    }

    public static boolean allows(List<Group> selected, String rawPath, boolean completion) {
        return allowsNormalized(selected, normalize(rawPath), completion);
    }

    public static boolean allowsNormalized(List<Group> selected, String normalizedPath, boolean completion) {
        String p = normalizePath(normalizedPath);
        if (p.isEmpty()) return false;
        boolean anyWhitelist = false;
        for (Group g : selected) {
            if (!g.whitelist() && !g.allowsNormalized(p, completion)) return false;
            if (g.whitelist()) anyWhitelist = true;
        }
        if (!anyWhitelist) return true;
        for (Group g : selected) {
            if (g.whitelist() && g.allowsNormalized(p, completion)) return true;
        }
        return false;
    }

    /**
     * Normalizes a raw command line or config rule by stripping at most ONE leading slash
     * ({@code /help -> help}, {@code //wand -> /wand}), collapsing whitespace, and lowercasing.
     */
    public static String normalize(String value) {
        if (value == null) return "";
        value = value.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Idempotent path normalization for already-stripped command labels/paths (e.g. from Bukkit's
     * {@code CommandMap}, {@code PlayerCommandSendEvent}, or an already-normalized command line).
     * Never strips a leading slash, preserving WorldEdit labels like {@code /wand} and {@code /set}.
     */
    public static String normalizePath(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Root token of a raw command line (strips at most one leading slash). */
    public static String root(String value) {
        return rootOfPath(normalize(value));
    }

    /** Root token of an already-normalized command path (preserves leading slash like {@code /wand}). */
    public static String rootOfPath(String path) {
        String p = normalizePath(path);
        int end = p.indexOf(' ');
        return end < 0 ? p : p.substring(0, end);
    }

    /** Plain (un-namespaced) form of a raw command line. */
    public static String plain(String value) {
        return plainOfPath(normalize(value));
    }

    /** Plain (un-namespaced) form of an already-normalized command path (e.g. {@code worldedit:/wand -> /wand}). */
    public static String plainOfPath(String path) {
        String p = normalizePath(path);
        int colon = rootOfPath(p).indexOf(':');
        return colon < 0 ? p : p.substring(colon + 1);
    }

    /** Matches raw (possibly slash-prefixed) rules against a raw command line. */
    public static boolean matches(Collection<String> rawRules, String rawValue, boolean ancestors) {
        String p = normalize(rawValue);
        if (p.isEmpty()) return false;
        String plain = plainOfPath(p);
        String root = rootOfPath(p);
        for (String raw : rawRules) {
            String r = normalize(raw);
            if (ruleMatches(r, p, plain, root, ancestors)) return true;
        }
        return false;
    }

    /** Matches already-normalized rules against an already-normalized command path. */
    public static boolean matchesNormalized(Collection<String> normalizedRules, String normalizedPath, boolean ancestors) {
        String p = normalizePath(normalizedPath);
        if (p.isEmpty()) return false;
        String plain = plainOfPath(p);
        String root = rootOfPath(p);
        for (String raw : normalizedRules) {
            String r = normalizePath(raw);
            if (ruleMatches(r, p, plain, root, ancestors)) return true;
        }
        return false;
    }

    private static boolean ruleMatches(String r, String p, String plain, String root, boolean ancestors) {
        if (r.isEmpty()) return false;
        if ("*".equals(r)) return true;
        if (r.endsWith(":*") && root.startsWith(r.substring(0, r.length() - 1))) return true;
        return branch(p, r) || branch(plain, r) || (ancestors && (branch(r, p) || branch(r, plain)));
    }

    /** Reconstructs the normalized command path for a tab-completion candidate, preserving {@code //}. */
    public static String suggestionPath(String buffer, String candidate, int offset) {
        String buf = buffer == null ? "" : buffer;
        String cand = candidate == null ? "" : candidate;
        if (offset < 0 || offset > buf.length()) {
            offset = buf.lastIndexOf(' ') + 1;
            if (offset == 0 && buf.startsWith("/")) offset = 1;
        }
        boolean doubleSlashRoot = offset == 1 && buf.startsWith("//");
        String replacement = (offset <= 1 && !doubleSlashRoot && cand.startsWith("/"))
                ? cand.substring(1)
                : cand;
        return normalize(buf.substring(0, offset) + replacement);
    }

    /** Fuzzy Levenshtein suggestion (edit distance <= 2) for unknown commands. */
    public static String closest(String input, Collection<String> allowed) {
        if (input == null || input.isEmpty() || input.length() > 64) return null;
        String best = null;
        int score = 3;
        for (String candidate : allowed) {
            if (candidate == null || candidate.contains(" ") || candidate.equals(input)
                    || Math.abs(candidate.length() - input.length()) > 2) {
                continue;
            }
            int[] prev = new int[candidate.length() + 1];
            for (int j = 0; j < prev.length; j++) prev[j] = j;
            for (int i = 1; i <= input.length(); i++) {
                int[] next = new int[prev.length];
                next[0] = i;
                for (int j = 1; j < next.length; j++) {
                    int cost = input.charAt(i - 1) == candidate.charAt(j - 1) ? 0 : 1;
                    next[j] = Math.min(Math.min(next[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                }
                prev = next;
            }
            int distance = prev[candidate.length()];
            if (distance < score) {
                score = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean branch(String value, String parent) {
        return value.equals(parent) || value.startsWith(parent + " ");
    }

    private static String validateGroupName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid hide group");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeRules(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        if (values.size() > 2048) {
            throw new IllegalArgumentException("too many hide rules");
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String v : values) {
            String p = normalize(v);
            if (p.isEmpty() || p.length() > 512) {
                throw new IllegalArgumentException("invalid hide rule");
            }
            out.add(p);
        }
        return Collections.unmodifiableList(new ArrayList<String>(out));
    }

    private static List<String> copyList(List<String> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<String>(input));
    }
}
