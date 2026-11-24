package codes.castled.allium.chat;

import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * Thread-safe evaluator for opt-in chat phrase, repeat-similarity, and rate-limit rules.
 *
 * <p>Holds bounded per-player sliding windows and caches compiled phrase rules per immutable
 * {@link ChatFilterSettings.PhraseSettings} instance. Safe to invoke across Folia region threads.
 */
public final class ChatFilterPolicy {

    private static final int MAX_HISTORY_PER_PLAYER = 64;
    private static final Pattern COLOR_CODES = Pattern.compile(
            "(?i)(?:§x(?:§[0-9a-f]){6}|&#[0-9a-f]{6}|[§&][0-9a-fk-or])");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}'@$]+(?:![\\p{L}\\p{N}'@$]+)*");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public enum Kind {
        PHRASE,
        REPEAT,
        RATE_LIMIT
    }

    public record Decision(
            boolean blocked,
            boolean alerted,
            @Nullable Kind kind,
            String ruleDetail,
            long retryAfterMillis
    ) {
        private static final Decision ALLOW = new Decision(false, false, null, "", 0L);

        public static Decision allow() {
            return ALLOW;
        }

        public static Decision block(Kind kind, String detail, long retryAfterMillis) {
            return new Decision(true, true, Objects.requireNonNull(kind, "kind"), detail, Math.max(0L, retryAfterMillis));
        }

        public static Decision alertOnly(Kind kind, String detail) {
            return new Decision(false, true, Objects.requireNonNull(kind, "kind"), detail, 0L);
        }
    }

    private record MessageEntry(String normalized, long timestamp) {}

    private static final class PlayerWindow {
        final Deque<Long> timestamps = new ArrayDeque<>();
        final Deque<MessageEntry> messages = new ArrayDeque<>();
    }

    private record CompiledPhrases(
            ChatFilterSettings.PhraseSettings source,
            List<String> tokens,
            List<List<String>> phrases,
            List<String> substrings,
            List<String> exceptions
    ) {}

    private final LongSupplier clock;
    private final Map<UUID, PlayerWindow> windows = new ConcurrentHashMap<>();
    private volatile @Nullable CompiledPhrases cachedPhrases;

    public ChatFilterPolicy() {
        this(System::currentTimeMillis);
    }

    public ChatFilterPolicy(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Decision evaluate(UUID playerId, String rawMessage, ChatFilterSettings settings) {
        if (settings == null || !settings.enabled() || playerId == null || rawMessage == null || rawMessage.isBlank()) {
            return Decision.allow();
        }
        String stripped = stripFormatting(rawMessage).trim();
        if (stripped.isBlank()) {
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
                if (settings.mode() == ChatFilterSettings.Mode.BLOCK) {
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

    @Nullable
    private static Decision checkRateLimit(PlayerWindow window, long now, ChatFilterSettings.RateLimitSettings rate) {
        if (!rate.enabled()) return null;
        long burstWindowMillis = rate.burstWindowSeconds() * 1000L;
        long keepMillis = Math.max(burstWindowMillis, rate.minIntervalMillis());
        long cutoff = now - keepMillis;
        while (!window.timestamps.isEmpty() && window.timestamps.peekFirst() < cutoff) {
            window.timestamps.removeFirst();
        }
        if (rate.minIntervalMillis() > 0L && !window.timestamps.isEmpty()) {
            long elapsed = now - window.timestamps.peekLast();
            if (elapsed >= 0L && elapsed < rate.minIntervalMillis()) {
                return Decision.block(Kind.RATE_LIMIT, "min-interval", rate.minIntervalMillis() - elapsed);
            }
        }
        if (rate.burstMax() > 0) {
            long burstCutoff = now - burstWindowMillis;
            int inBurst = 0;
            long oldestInBurst = now;
            for (long ts : window.timestamps) {
                if (ts >= burstCutoff) {
                    if (inBurst == 0) oldestInBurst = ts;
                    inBurst++;
                }
            }
            if (inBurst >= rate.burstMax()) {
                long retry = Math.max(1L, burstWindowMillis - (now - oldestInBurst));
                return Decision.block(Kind.RATE_LIMIT, "burst=" + rate.burstMax() + "/" + rate.burstWindowSeconds() + "s", retry);
            }
        }
        return null;
    }

    @Nullable
    private Decision checkPhrases(String stripped, ChatFilterSettings.PhraseSettings cfg) {
        if (!cfg.enabled()) return null;
        if (cfg.tokens().isEmpty() && cfg.phrases().isEmpty() && cfg.substrings().isEmpty()) return null;
        CompiledPhrases compiled = compile(cfg);

        List<String> rawTokens = tokenize(stripped);
        if (rawTokens.isEmpty()) return null;
        List<String> normalizedTokens = new ArrayList<>(rawTokens.size());
        for (String t : rawTokens) {
            String n = normalizeToken(t, cfg.normalizeSubstitutions());
            if (!n.isEmpty()) normalizedTokens.add(n);
        }
        if (normalizedTokens.isEmpty()) return null;

        if (!compiled.exceptions().isEmpty()) {
            String whole = String.join(" ", normalizedTokens);
            for (String ex : compiled.exceptions()) {
                if (whole.contains(ex)) return null;
            }
        }

        Set<String> joinedRuns = cfg.joinSplitLetters() ? joinedLetterRuns(normalizedTokens) : Set.of();

        for (String target : compiled.tokens()) {
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

        for (List<String> phrase : compiled.phrases()) {
            if (containsSequence(normalizedTokens, phrase)) {
                return Decision.block(Kind.PHRASE, "phrase=" + String.join(" ", phrase), 0L);
            }
        }

        for (String sub : compiled.substrings()) {
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

    @Nullable
    private static Decision checkRepeat(PlayerWindow window, long now, String normalized, ChatFilterSettings.RepeatSettings repeat) {
        if (normalized.isEmpty()) return null;
        long cutoff = now - (repeat.windowSeconds() * 1000L);
        while (!window.messages.isEmpty() && window.messages.peekFirst().timestamp() < cutoff) {
            window.messages.removeFirst();
        }
        if (isWhitelisted(normalized, repeat)) return null;

        int similar = 0;
        for (MessageEntry prior : window.messages) {
            if (similarity(prior.normalized(), normalized) >= repeat.similarityThreshold()) {
                similar++;
            }
        }
        if (similar >= repeat.maxSimilar()) {
            return Decision.block(Kind.REPEAT, "similar=" + similar + "/" + repeat.maxSimilar(), 0L);
        }
        return null;
    }

    private static void recordAccepted(PlayerWindow window, long now, String repeatNormalized, ChatFilterSettings settings) {
        if (settings.rateLimit().enabled()) {
            window.timestamps.addLast(now);
            while (window.timestamps.size() > MAX_HISTORY_PER_PLAYER) window.timestamps.removeFirst();
        }
        if (settings.repeat().enabled() && !repeatNormalized.isEmpty()) {
            window.messages.addLast(new MessageEntry(repeatNormalized, now));
            while (window.messages.size() > MAX_HISTORY_PER_PLAYER) window.messages.removeFirst();
        }
    }

    private CompiledPhrases compile(ChatFilterSettings.PhraseSettings cfg) {
        CompiledPhrases current = cachedPhrases;
        if (current != null && current.source() == cfg) return current;

        List<String> tokens = new ArrayList<>();
        for (String t : cfg.tokens()) {
            String n = normalizeToken(t, cfg.normalizeSubstitutions());
            if (!n.isEmpty()) tokens.add(n);
        }
        List<List<String>> phrases = new ArrayList<>();
        for (String p : cfg.phrases()) {
            List<String> pt = tokenize(p).stream()
                    .map(tok -> normalizeToken(tok, cfg.normalizeSubstitutions()))
                    .filter(tok -> !tok.isEmpty())
                    .toList();
            if (!pt.isEmpty()) phrases.add(pt);
        }
        List<String> substrings = new ArrayList<>();
        for (String s : cfg.substrings()) {
            String n = normalizeToken(s, cfg.normalizeSubstitutions());
            if (!n.isEmpty()) substrings.add(n);
        }
        List<String> exceptions = new ArrayList<>();
        for (String ex : cfg.exceptions()) {
            List<String> et = tokenize(ex).stream()
                    .map(tok -> normalizeToken(tok, cfg.normalizeSubstitutions()))
                    .filter(tok -> !tok.isEmpty())
                    .toList();
            if (!et.isEmpty()) exceptions.add(String.join(" ", et));
        }
        CompiledPhrases compiled = new CompiledPhrases(cfg, List.copyOf(tokens), List.copyOf(phrases),
                List.copyOf(substrings), List.copyOf(exceptions));
        cachedPhrases = compiled;
        return compiled;
    }

    static String stripFormatting(String input) {
        return COLOR_CODES.matcher(input).replaceAll("");
    }

    private static List<String> tokenize(String input) {
        Matcher matcher = TOKEN_PATTERN.matcher(input);
        List<String> tokens = new ArrayList<>();
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
        return switch (c) {
            case '@', '4' -> 'a';
            case '3' -> 'e';
            case '0' -> 'o';
            case '1', '!' -> 'i';
            case '5', '$' -> 's';
            case '7' -> 't';
            default -> c;
        };
    }

    private static String trimApostrophes(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && input.charAt(start) == '\'') start++;
        while (end > start && input.charAt(end - 1) == '\'') end--;
        return input.substring(start, end);
    }

    private static Set<String> joinedLetterRuns(List<String> tokens) {
        Set<String> runs = new LinkedHashSet<>();
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

    private static boolean isWhitelisted(String normalized, ChatFilterSettings.RepeatSettings repeat) {
        for (String phrase : repeat.whitelist()) {
            String normPhrase = normalizeForSimilarity(phrase, false);
            if (!normPhrase.isEmpty() && similarity(normPhrase, normalized) >= repeat.similarityThreshold()) {
                return true;
            }
        }
        return false;
    }

    static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0d;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0d;
        return Math.max(0.0d, 1.0d - ((double) levenshtein(a, b) / (double) max));
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
}
