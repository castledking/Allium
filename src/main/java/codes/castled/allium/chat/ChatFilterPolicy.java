package codes.castled.allium.chat;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Thread-safe evaluator for opt-in chat phrase, repeat-similarity, and rate-limit rules.
 *
 * <p>Delegates to the shared Java 8 {@link ChatFilterEngine} so both the 1.20+ runtime and the
 * 1.12.2-1.19.4 compatibility runtime enforce identical filter semantics. Safe to invoke across
 * Folia region threads.
 */
public final class ChatFilterPolicy {

    public enum Kind {
        PHRASE,
        REPEAT,
        RATE_LIMIT;

        static @Nullable Kind fromEngine(@Nullable ChatFilterEngine.Kind kind) {
            if (kind == null) return null;
            return switch (kind) {
                case PHRASE -> PHRASE;
                case REPEAT -> REPEAT;
                case RATE_LIMIT -> RATE_LIMIT;
            };
        }
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

        static Decision fromEngine(ChatFilterEngine.Decision d) {
            if (!d.alerted() && !d.blocked()) return ALLOW;
            return new Decision(d.blocked(), d.alerted(), Kind.fromEngine(d.kind()), d.ruleDetail(), d.retryAfterMillis());
        }
    }

    private record CachedConfig(ChatFilterSettings source, ChatFilterEngine.Config compiled) {}

    private final ChatFilterEngine engine;
    private volatile @Nullable CachedConfig cachedConfig;

    public ChatFilterPolicy() {
        this(System::currentTimeMillis);
    }

    public ChatFilterPolicy(LongSupplier clock) {
        this.engine = new ChatFilterEngine(Objects.requireNonNull(clock, "clock"));
    }

    public Decision evaluate(UUID playerId, String rawMessage, ChatFilterSettings settings) {
        if (settings == null || !settings.enabled()) {
            return Decision.allow();
        }
        return Decision.fromEngine(engine.evaluate(playerId, rawMessage, toEngineConfig(settings)));
    }

    public void forget(UUID playerId) {
        engine.forget(playerId);
    }

    public void clear() {
        engine.clear();
    }

    static String stripFormatting(String input) {
        return ChatFilterEngine.stripFormatting(input);
    }

    static double similarity(String a, String b) {
        return ChatFilterEngine.similarity(a, b);
    }

    private ChatFilterEngine.Config toEngineConfig(ChatFilterSettings settings) {
        CachedConfig current = cachedConfig;
        if (current != null && current.source() == settings) {
            return current.compiled();
        }
        ChatFilterSettings.PhraseSettings p = settings.phrases();
        ChatFilterSettings.RepeatSettings r = settings.repeat();
        ChatFilterSettings.RateLimitSettings rl = settings.rateLimit();
        ChatFilterEngine.Config converted = new ChatFilterEngine.Config(
                settings.enabled(),
                settings.mode() == ChatFilterSettings.Mode.ALERT_ONLY
                        ? ChatFilterEngine.Mode.ALERT_ONLY
                        : ChatFilterEngine.Mode.BLOCK,
                settings.exemptStaffChannel(),
                settings.notifyStaff(),
                settings.auditToModeration(),
                new ChatFilterEngine.PhraseConfig(
                        p.enabled(), p.normalizeSubstitutions(), p.joinSplitLetters(),
                        p.tokens(), p.phrases(), p.substrings(), p.exceptions()),
                new ChatFilterEngine.RepeatConfig(
                        r.enabled(), r.windowSeconds(), r.maxSimilar(),
                        r.similarityThreshold(), r.stripFloodSuffix(), r.whitelist()),
                new ChatFilterEngine.RateLimitConfig(
                        rl.enabled(), rl.minIntervalMillis(), rl.burstMax(), rl.burstWindowSeconds())
        );
        cachedConfig = new CachedConfig(settings, converted);
        return converted;
    }
}
