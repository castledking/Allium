package codes.castled.allium.chat;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java 8 thread-safe evaluator for opt-in chat phrase, repeat-similarity, and rate-limit
 * rules ({@code chat.filter.*}). Shared by both the modern 1.20+ runtime and the 1.12.2-1.19.4
 * Bukkit compatibility runtime.
 */
public final class ChatFilterEngine {

    private static final int MAX_HISTORY_PER_PLAYER = 64;
    private static final Pattern COLOR_CODES = Pattern.compile(
            "(?i)(?:\u00a7x(?:\u00a7[0-9a-f]){6}|&#[0-9a-f]{6}|[\u00a7&][0-9a-fk-or])");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}'@$]+(?:![\\p{L}\\p{N}'@$]+)*");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final List<String> DEFAULT_REPEAT_WHITELIST = Collections.unmodifiableList(
            Arrays.asList("gg", "wb", "ty", "welcome"));

    public enum Mode {
        BLOCK,
        ALERT_ONLY;

        public static Mode parse(String raw) {
            if (isBlank(raw)) return BLOCK;
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if ("block".equals(normalized)) return BLOCK;
            if ("alert-only".equals(normalized) || "alert_only".equals(normalized) || "shadow".equals(normalized)) {
                return ALERT_ONLY;
            }
            throw new IllegalArgumentException("invalid chat.filter.mode: " + raw + " (use block or alert-only)");
        }
    }

    public enum Kind {
        PHRASE,
        REPEAT,
        RATE_LIMIT
    }

    public static final class Decision {
        private static final Decision ALLOW = new Decision(false, false, null, "", 0L);

        private final boolean blocked;
        private final boolean alerted;
        private final Kind kind;
        private final String ruleDetail;
        private final long retryAfterMillis;

        public Decision(boolean blocked, boolean alerted, Kind kind, String ruleDetail, long retryAfterMillis) {
            this.blocked = blocked;
            this.alerted = alerted;
            this.kind = kind;
            this.ruleDetail = ruleDetail == null ? "" : ruleDetail;
            this.retryAfterMillis = Math.max(0L, retryAfterMillis);
        }

        public static Decision allow() {
            return ALLOW;
        }

        public static Decision block(Kind kind, String detail, long retryAfterMillis) {
            return new Decision(true, true, Objects.requireNonNull(kind, "kind"), detail, retryAfterMillis);
        }

        public static Decision alertOnly(Kind kind, String detail) {
            return new Decision(false, true, Objects.requireNonNull(kind, "kind"), detail, 0L);
        }

        public boolean blocked() {
            return blocked;
        }

        public boolean alerted() {
            return alerted;
        }

        public Kind kind() {
            return kind;
        }

        public String ruleDetail() {
            return ruleDetail;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }
    }

    public static final class PhraseConfig {
        private static final PhraseConfig DEFAULTS = new PhraseConfig(
                true, true, true,
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList());

        private final boolean enabled;
        private final boolean normalizeSubstitutions;
        private final boolean joinSplitLetters;
        private final List<String> tokens;
        private final List<String> phrases;
        private final List<String> substrings;
        private final List<String> exceptions;

        public PhraseConfig(
                boolean enabled,
                boolean normalizeSubstitutions,
                boolean joinSplitLetters,
                List<String> tokens,
                List<String> phrases,
                List<String> substrings,
                List<String> exceptions
        ) {
            this.enabled = enabled;
            this.normalizeSubstitutions = normalizeSubstitutions;
            this.joinSplitLetters = joinSplitLetters;
            this.tokens = copyNonBlank(tokens);
            this.phrases = copyNonBlank(phrases);
            this.substrings = copyNonBlank(substrings);
            this.exceptions = copyNonBlank(exceptions);
        }

        public static PhraseConfig defaults() {
            return DEFAULTS;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean normalizeSubstitutions() {
            return normalizeSubstitutions;
        }

        public boolean joinSplitLetters() {
            return joinSplitLetters;
        }

        public List<String> tokens() {
            return tokens;
        }

        public List<String> phrases() {
            return phrases;
        }

        public List<String> substrings() {
            return substrings;
        }

        public List<String> exceptions() {
            return exceptions;
        }
    }

    public static final class RepeatConfig {
        private static final RepeatConfig DEFAULTS = new RepeatConfig(true, 15, 2, 0.85d, true, DEFAULT_REPEAT_WHITELIST);

        private final boolean enabled;
        private final int windowSeconds;
        private final int maxSimilar;
        private final double similarityThreshold;
        private final boolean stripFloodSuffix;
        private final List<String> whitelist;

        public RepeatConfig(
                boolean enabled,
                int windowSeconds,
                int maxSimilar,
                double similarityThreshold,
                boolean stripFloodSuffix,
                List<String> whitelist
        ) {
            if (windowSeconds < 1 || windowSeconds > 3600) {
                throw new IllegalArgumentException("invalid chat.filter.repeat.window-seconds (must be 1..3600)");
            }
            if (maxSimilar < 0 || maxSimilar > 50) {
                throw new IllegalArgumentException("invalid chat.filter.repeat.max-similar (must be 0..50)");
            }
            if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0.0d || similarityThreshold > 1.0d) {
                throw new IllegalArgumentException("invalid chat.filter.repeat.similarity-threshold (must be 0.0..1.0)");
            }
            this.enabled = enabled;
            this.windowSeconds = windowSeconds;
            this.maxSimilar = maxSimilar;
            this.similarityThreshold = similarityThreshold;
            this.stripFloodSuffix = stripFloodSuffix;
            this.whitelist = copyNonBlank(whitelist);
        }

        public static RepeatConfig defaults() {
            return DEFAULTS;
        }

        public static List<String> defaultWhitelist() {
            return DEFAULT_REPEAT_WHITELIST;
        }

        public boolean enabled() {
            return enabled;
        }

        public int windowSeconds() {
            return windowSeconds;
        }

        public int maxSimilar() {
            return maxSimilar;
        }

        public double similarityThreshold() {
            return similarityThreshold;
        }

        public boolean stripFloodSuffix() {
            return stripFloodSuffix;
        }

        public List<String> whitelist() {
            return whitelist;
        }
    }

    public static final class RateLimitConfig {
        private static final RateLimitConfig DEFAULTS = new RateLimitConfig(true, 1000L, 5, 10);

        private final boolean enabled;
        private final long minIntervalMillis;
        private final int burstMax;
        private final int burstWindowSeconds;

        public RateLimitConfig(boolean enabled, long minIntervalMillis, int burstMax, int burstWindowSeconds) {
            if (minIntervalMillis < 0L || minIntervalMillis > 60_000L) {
                throw new IllegalArgumentException("invalid chat.filter.rate-limit.min-interval-millis (must be 0..60000)");
            }
            if (burstMax < 0 || burstMax > 100) {
                throw new IllegalArgumentException("invalid chat.filter.rate-limit.burst-max (must be 0..100)");
            }
            if (burstWindowSeconds < 1 || burstWindowSeconds > 300) {
                throw new IllegalArgumentException("invalid chat.filter.rate-limit.burst-window-seconds (must be 1..300)");
            }
            this.enabled = enabled;
            this.minIntervalMillis = minIntervalMillis;
            this.burstMax = burstMax;
            this.burstWindowSeconds = burstWindowSeconds;
        }

        public static RateLimitConfig defaults() {
            return DEFAULTS;
        }

        public boolean enabled() {
            return enabled;
        }

        public long minIntervalMillis() {
            return minIntervalMillis;
        }

        public int burstMax() {
            return burstMax;
        }

        public int burstWindowSeconds() {
            return burstWindowSeconds;
        }
    }

    public static final class Config {
        private static final Config DISABLED = new Config(
                false, Mode.BLOCK, true, true, true,
                PhraseConfig.defaults(), RepeatConfig.defaults(), RateLimitConfig.defaults());

        private final boolean enabled;
        private final Mode mode;
        private final boolean exemptStaffChannel;
        private final boolean notifyStaff;
        private final boolean auditToModeration;
        private final PhraseConfig phrases;
        private final RepeatConfig repeat;
        private final RateLimitConfig rateLimit;

        public Config(
                boolean enabled,
                Mode mode,
                boolean exemptStaffChannel,
                boolean notifyStaff,
                boolean auditToModeration,
                PhraseConfig phrases,
                RepeatConfig repeat,
                RateLimitConfig rateLimit
        ) {
            this.enabled = enabled;
            this.mode = mode == null ? Mode.BLOCK : mode;
            this.exemptStaffChannel = exemptStaffChannel;
            this.notifyStaff = notifyStaff;
            this.auditToModeration = auditToModeration;
            this.phrases = phrases == null ? PhraseConfig.defaults() : phrases;
            this.repeat = repeat == null ? RepeatConfig.defaults() : repeat;
            this.rateLimit = rateLimit == null ? RateLimitConfig.defaults() : rateLimit;
        }

        public static Config disabled() {
            return DISABLED;
        }

        public boolean enabled() {
            return enabled;
        }

        public Mode mode() {
            return mode;
        }

        public boolean exemptStaffChannel() {
            return exemptStaffChannel;
        }

        public boolean notifyStaff() {
            return notifyStaff;
        }

        public boolean auditToModeration() {
            return auditToModeration;
        }

        public PhraseConfig phrases() {
            return phrases;
        }

        public RepeatConfig repeat() {
            return repeat;
        }

        public RateLimitConfig rateLimit() {
            return rateLimit;
        }
    }

    private static final class MessageEntry {
        final String normalized;
        final long timestamp;

        MessageEntry(String normalized, long timestamp) {
            this.normalized = normalized;
            this.timestamp = timestamp;
        }
    }

    private static final class PlayerWindow {
        final Deque<Long> timestamps = new ArrayDeque<Long>();
        final Deque<MessageEntry> messages = new ArrayDeque<MessageEntry>();
    }

    private static final class CompiledPhrases {
        final PhraseConfig source;
        final List<String> tokens;
        final List<List<String>> phrases;
        final List<String> substrings;
        final List<String> exceptions;

        CompiledPhrases(PhraseConfig source, List<String> tokens, List<List<String>> phrases,
                        List<String> substrings, List<String> exceptions) {
            this.source = source;
            this.tokens = tokens;
            this.phrases = phrases;
            this.substrings = substrings;
            this.exceptions = exceptions;
        }
    }

    private final LongSupplier clock;
    private final Map<UUID, PlayerWindow> windows = new ConcurrentHashMap<UUID, PlayerWindow>();
    private volatile CompiledPhrases cachedPhrases;

    public ChatFilterEngine() {
        this(System::currentTimeMillis);
    }

    public ChatFilterEngine(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Decision evaluate(UUID playerId, String rawMessage, Config settings) {
        if (settings == null || !settings.enabled() || playerId == null || isBlank(rawMessage)) {
            return Decision.allow();
        }
        String stripped = stripFormatting(rawMessage).trim();
        if (stripped.isEmpty()) {
            return Decision.allow();
        }
        long now = clock.getAsLong();
        PlayerWindow window = windows.computeIfAbsent(playerId, ignored -> new PlayerWindow());
        synchronized (window) {
            Decision triggered = checkRateLimit(window, now, settings.rateLimit());
            if (triggered == null) {
                triggered = checkPhrases(stripped, settings.phrases());
            }
            String repeatNormalized = settings.repeat().enabled()
                    ? normalizeForSimilarity(stripped, settings.repeat().stripFloodSuffix())
                    : "";
            if (triggered == null && settings.repeat().enabled()) {
                triggered = checkRepeat(window, now, repeatNormalized, settings.repeat());
            }

            if (triggered != null) {
                if (settings.mode() == Mode.BLOCK) {
                    return triggered;
                }
                recordAccepted(window, now, repeatNormalized, settings);
                return Decision.alertOnly(triggered.kind(), triggered.ruleDetail());
            }
            recordAccepted(window, now, repeatNormalized, settings);
            return Decision.allow();
        }
    }

    public void forget(UUID playerId) {
        if (playerId != null) windows.remove(playerId);
    }

    public void clear() {
        windows.clear();
    }

    public static String formatRetrySeconds(long millis) {
        if (millis <= 0L) return "1";
        long tenths = (millis + 99L) / 100L;
        if (tenths % 10L == 0L) return Long.toString(tenths / 10L);
        return (tenths / 10L) + "." + (tenths % 10L);
    }

    public static String stripFormatting(String input) {
        if (input == null) return "";
        return COLOR_CODES.matcher(input).replaceAll("");
    }

    public static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0d;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0d;
        return Math.max(0.0d, 1.0d - ((double) levenshtein(a, b) / (double) max));
    }

    private static Decision checkRateLimit(PlayerWindow window, long now, RateLimitConfig rate) {
        if (!rate.enabled()) return null;
        long burstWindowMillis = rate.burstWindowSeconds() * 1000L;
        long keepMillis = Math.max(burstWindowMillis, rate.minIntervalMillis());
        long cutoff = now - keepMillis;
        while (!window.timestamps.isEmpty() && window.timestamps.peekFirst().longValue() < cutoff) {
            window.timestamps.removeFirst();
        }
        if (rate.minIntervalMillis() > 0L && !window.timestamps.isEmpty()) {
            long elapsed = now - window.timestamps.peekLast().longValue();
            if (elapsed >= 0L && elapsed < rate.minIntervalMillis()) {
                return Decision.block(Kind.RATE_LIMIT, "min-interval", rate.minIntervalMillis() - elapsed);
            }
        }
        if (rate.burstMax() > 0) {
            long burstCutoff = now - burstWindowMillis;
            int inBurst = 0;
            long oldestInBurst = now;
            for (Long tsObj : window.timestamps) {
                long ts = tsObj.longValue();
                if (ts >= burstCutoff) {
                    if (inBurst == 0) oldestInBurst = ts;
                    inBurst++;
                }
            }
            if (inBurst >= rate.burstMax()) {
                long retry = Math.max(1L, burstWindowMillis - (now - oldestInBurst));
                return Decision.block(Kind.RATE_LIMIT,
                        "burst=" + rate.burstMax() + "/" + rate.burstWindowSeconds() + "s", retry);
            }
        }
        return null;
    }

    private Decision checkPhrases(String stripped, PhraseConfig cfg) {
        if (!cfg.enabled()) return null;
        if (cfg.tokens().isEmpty() && cfg.phrases().isEmpty() && cfg.substrings().isEmpty()) return null;
        CompiledPhrases compiled = compile(cfg);

        List<String> rawTokens = tokenize(stripped);
        if (rawTokens.isEmpty()) return null;
        List<String> normalizedTokens = new ArrayList<String>(rawTokens.size());
        for (String t : rawTokens) {
            String n = normalizeToken(t, cfg.normalizeSubstitutions());
            if (!n.isEmpty()) normalizedTokens.add(n);
        }
        if (normalizedTokens.isEmpty()) return null;

        if (!compiled.exceptions.isEmpty()) {
            String whole = joinSpaces(normalizedTokens);
            for (String ex : compiled.exceptions) {
                if (whole.contains(ex)) return null;
            }
        }

        Set<String> joinedRuns = cfg.joinSplitLetters() ? joinedLetterRuns(normalizedTokens) : Collections.<String>emptySet();

        for (String target : compiled.tokens) {
            for (String token : normalizedTokens) {
                if (target.equals(token)) {
                    return Decision.block(Kind.PHRASE, "token=" + target, 0L);
                }
            }
            for (String run : joinedRuns) {
                if (target.equals(run)) {
                    return Decision.block(Kind.PHRASE, "split-token=" + target, 0L);
                }
            }
        }

        for (List<String> phrase : compiled.phrases) {
            if (containsSequence(normalizedTokens, phrase)) {
                return Decision.block(Kind.PHRASE, "phrase=" + joinSpaces(phrase), 0L);
            }
        }

        for (String sub : compiled.substrings) {
            for (String token : normalizedTokens) {
                if (token.contains(sub)) {
                    return Decision.block(Kind.PHRASE, "substring=" + sub, 0L);
                }
            }
            for (String run : joinedRuns) {
                if (run.contains(sub)) {
                    return Decision.block(Kind.PHRASE, "split-substring=" + sub, 0L);
                }
            }
        }
        return null;
    }

    private static Decision checkRepeat(PlayerWindow window, long now, String normalized, RepeatConfig repeat) {
        if (normalized.isEmpty()) return null;
        long cutoff = now - (repeat.windowSeconds() * 1000L);
        while (!window.messages.isEmpty() && window.messages.peekFirst().timestamp < cutoff) {
            window.messages.removeFirst();
        }
        if (isWhitelisted(normalized, repeat)) return null;

        int similar = 0;
        for (MessageEntry prior : window.messages) {
            if (similarity(prior.normalized, normalized) >= repeat.similarityThreshold()) {
                similar++;
            }
        }
        if (similar >= repeat.maxSimilar()) {
            return Decision.block(Kind.REPEAT, "similar=" + similar + "/" + repeat.maxSimilar(), 0L);
        }
        return null;
    }

    private static void recordAccepted(PlayerWindow window, long now, String repeatNormalized, Config settings) {
        if (settings.rateLimit().enabled()) {
            window.timestamps.addLast(Long.valueOf(now));
            while (window.timestamps.size() > MAX_HISTORY_PER_PLAYER) window.timestamps.removeFirst();
        }
        if (settings.repeat().enabled() && !repeatNormalized.isEmpty()) {
            window.messages.addLast(new MessageEntry(repeatNormalized, now));
            while (window.messages.size() > MAX_HISTORY_PER_PLAYER) window.messages.removeFirst();
        }
    }

    private CompiledPhrases compile(PhraseConfig cfg) {
        CompiledPhrases current = cachedPhrases;
        if (current != null && current.source == cfg) return current;

        List<String> tokens = new ArrayList<String>();
        for (String t : cfg.tokens()) {
            String n = normalizeToken(t, cfg.normalizeSubstitutions());
            if (!n.isEmpty()) tokens.add(n);
        }
        List<List<String>> phrases = new ArrayList<List<String>>();
        for (String p : cfg.phrases()) {
            List<String> pt = new ArrayList<String>();
            for (String tok : tokenize(p)) {
                String n = normalizeToken(tok, cfg.normalizeSubstitutions());
                if (!n.isEmpty()) pt.add(n);
            }
            if (!pt.isEmpty()) phrases.add(Collections.unmodifiableList(pt));
        }
        List<String> substrings = new ArrayList<String>();
        for (String s : cfg.substrings()) {
            String n = normalizeToken(s, cfg.normalizeSubstitutions());
            if (!n.isEmpty()) substrings.add(n);
        }
        List<String> exceptions = new ArrayList<String>();
        for (String ex : cfg.exceptions()) {
            List<String> et = new ArrayList<String>();
            for (String tok : tokenize(ex)) {
                String n = normalizeToken(tok, cfg.normalizeSubstitutions());
                if (!n.isEmpty()) et.add(n);
            }
            if (!et.isEmpty()) exceptions.add(joinSpaces(et));
        }
        CompiledPhrases compiled = new CompiledPhrases(
                cfg,
                Collections.unmodifiableList(tokens),
                Collections.unmodifiableList(phrases),
                Collections.unmodifiableList(substrings),
                Collections.unmodifiableList(exceptions));
        cachedPhrases = compiled;
        return compiled;
    }

    private static List<String> tokenize(String input) {
        Matcher matcher = TOKEN_PATTERN.matcher(input);
        List<String> tokens = new ArrayList<String>();
        while (matcher.find()) {
            String tok = trimApostrophes(matcher.group());
            if (!tok.isEmpty()) tokens.add(tok);
        }
        return tokens;
    }

    private static String normalizeToken(String input, boolean substitutions) {
        String s = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = trimApostrophes(s);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (substitutions) c = substitute(c);
            if (Character.isLetterOrDigit(c) || c == '\'') {
                out.append(c);
            }
        }
        return trimApostrophes(out.toString());
    }

    private static char substitute(char c) {
        switch (c) {
            case '@':
            case '4':
                return 'a';
            case '3':
                return 'e';
            case '0':
                return 'o';
            case '1':
            case '!':
                return 'i';
            case '5':
            case '$':
                return 's';
            case '7':
                return 't';
            default:
                return c;
        }
    }

    private static String trimApostrophes(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && input.charAt(start) == '\'') start++;
        while (end > start && input.charAt(end - 1) == '\'') end--;
        return input.substring(start, end);
    }

    private static Set<String> joinedLetterRuns(List<String> tokens) {
        Set<String> runs = new LinkedHashSet<String>();
        StringBuilder current = new StringBuilder();
        for (String tok : tokens) {
            if (tok.length() == 1) {
                current.append(tok);
            } else {
                if (current.length() >= 2) runs.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() >= 2) runs.add(current.toString());
        return runs;
    }

    private static boolean containsSequence(List<String> haystack, List<String> needle) {
        if (haystack.size() < needle.size()) return false;
        for (int i = 0; i <= haystack.size() - needle.size(); i++) {
            boolean match = true;
            for (int j = 0; j < needle.size(); j++) {
                if (!needle.get(j).equals(haystack.get(i + j))) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private static String normalizeForSimilarity(String input, boolean stripFloodSuffix) {
        String collapsed = collapseRepeatedChars(input, 3, 1);
        String s = Normalizer.normalize(collapsed, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^\\p{L}\\p{N}\\s'@]", " ");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        if (stripFloodSuffix && !s.isEmpty()) {
            String[] parts = s.split(" ");
            if (parts.length > 1 && isLikelyFloodSuffix(parts[parts.length - 1])) {
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) joined.append(' ');
                    joined.append(parts[i]);
                }
                s = joined.toString();
            }
        }
        return s;
    }

    private static boolean isLikelyFloodSuffix(String token) {
        if (token.isEmpty() || token.length() > 6) return false;
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isDigit(c)) hasDigit = true;
            else if (Character.isLetter(c)) hasLetter = true;
        }
        return (hasDigit && hasLetter) || (hasDigit && token.length() <= 4);
    }

    private static String collapseRepeatedChars(String input, int threshold, int keep) {
        StringBuilder out = new StringBuilder(input.length());
        char prev = 0;
        int run = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.toLowerCase(c) == Character.toLowerCase(prev)) {
                run++;
            } else {
                prev = c;
                run = 1;
            }
            if (run <= keep || run < threshold) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isWhitelisted(String normalized, RepeatConfig repeat) {
        for (String phrase : repeat.whitelist()) {
            String normPhrase = normalizeForSimilarity(phrase, false);
            if (!normPhrase.isEmpty() && similarity(normPhrase, normalized) >= repeat.similarityThreshold()) {
                return true;
            }
        }
        return false;
    }

    private static int levenshtein(String left, String right) {
        int[] costs = new int[right.length() + 1];
        for (int j = 0; j < costs.length; j++) costs[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            costs[0] = i;
            int northwest = i - 1;
            for (int j = 1; j <= right.length(); j++) {
                int current = costs[j];
                int value = left.charAt(i - 1) == right.charAt(j - 1)
                        ? northwest
                        : 1 + Math.min(Math.min(costs[j - 1], current), northwest);
                costs[j] = value;
                northwest = current;
            }
        }
        return costs[right.length()];
    }

    private static String joinSpaces(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static List<String> copyNonBlank(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>(raw.size());
        for (String item : raw) {
            if (!isBlank(item)) out.add(item.trim());
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
