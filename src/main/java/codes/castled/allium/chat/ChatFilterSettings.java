package codes.castled.allium.chat;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in chat phrase, repeat-similarity, and rate-limit filter policy ({@code chat.filter.*}).
 *
 * <p>Disabled by default ({@code chat.filter.enabled: false}). Validated eagerly during
 * {@link ChatSettings#read(ConfigurationSection)} so invalid settings abort reload before mutating
 * live chat state.
 */
public record ChatFilterSettings(
        boolean enabled,
        Mode mode,
        boolean exemptStaffChannel,
        boolean notifyStaff,
        boolean auditToModeration,
        PhraseSettings phrases,
        RepeatSettings repeat,
        RateLimitSettings rateLimit
) {
    public enum Mode {
        BLOCK,
        ALERT_ONLY;

        static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) return BLOCK;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "block" -> BLOCK;
                case "alert-only", "alert_only", "shadow" -> ALERT_ONLY;
                default -> throw new IllegalArgumentException("invalid chat.filter.mode: " + raw + " (use block or alert-only)");
            };
        }
    }

    public record PhraseSettings(
            boolean enabled,
            boolean normalizeSubstitutions,
            boolean joinSplitLetters,
            List<String> tokens,
            List<String> phrases,
            List<String> substrings,
            List<String> exceptions
    ) {
        public PhraseSettings {
            tokens = List.copyOf(tokens);
            phrases = List.copyOf(phrases);
            substrings = List.copyOf(substrings);
            exceptions = List.copyOf(exceptions);
        }

        public static PhraseSettings defaults() {
            return new PhraseSettings(true, true, true, List.of(), List.of(), List.of(), List.of());
        }
    }

    public record RepeatSettings(
            boolean enabled,
            int windowSeconds,
            int maxSimilar,
            double similarityThreshold,
            boolean stripFloodSuffix,
            List<String> whitelist
    ) {
        public RepeatSettings {
            if (windowSeconds < 1 || windowSeconds > 3600) {
                throw new IllegalArgumentException("invalid chat.filter.repeat.window-seconds (must be 1..3600)");
            }
            if (maxSimilar < 0 || maxSimilar > 50) {
                throw new IllegalArgumentException("invalid chat.filter.repeat.max-similar (must be 0..50)");
            }
            if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0.0d || similarityThreshold > 1.0d) {
                throw new IllegalArgumentException("invalid chat.filter.repeat.similarity-threshold (must be 0.0..1.0)");
            }
            whitelist = List.copyOf(whitelist);
        }

        public static RepeatSettings defaults() {
            return new RepeatSettings(true, 15, 2, 0.85d, true, List.of("gg", "wb", "ty", "welcome"));
        }
    }

    public record RateLimitSettings(
            boolean enabled,
            long minIntervalMillis,
            int burstMax,
            int burstWindowSeconds
    ) {
        public RateLimitSettings {
            if (minIntervalMillis < 0L || minIntervalMillis > 60_000L) {
                throw new IllegalArgumentException("invalid chat.filter.rate-limit.min-interval-millis (must be 0..60000)");
            }
            if (burstMax < 0 || burstMax > 100) {
                throw new IllegalArgumentException("invalid chat.filter.rate-limit.burst-max (must be 0..100)");
            }
            if (burstWindowSeconds < 1 || burstWindowSeconds > 300) {
                throw new IllegalArgumentException("invalid chat.filter.rate-limit.burst-window-seconds (must be 1..300)");
            }
        }

        public static RateLimitSettings defaults() {
            return new RateLimitSettings(true, 1000L, 5, 10);
        }
    }

    public static ChatFilterSettings disabled() {
        return new ChatFilterSettings(false, Mode.BLOCK, true, true, true,
                PhraseSettings.defaults(), RepeatSettings.defaults(), RateLimitSettings.defaults());
    }

    public static ChatFilterSettings read(ConfigurationSection root) {
        if (root == null) return disabled();
        ConfigurationSection section = root.getConfigurationSection("chat.filter");
        if (section == null) return disabled();

        boolean enabled = section.getBoolean("enabled", false);
        Mode mode = Mode.parse(section.getString("mode", "block"));
        boolean exemptStaff = section.getBoolean("exempt-staff-channel", true);
        boolean notifyStaff = section.getBoolean("notify-staff", true);
        boolean audit = section.getBoolean("audit-to-moderation", true);

        ConfigurationSection phraseSec = section.getConfigurationSection("phrases");
        PhraseSettings phrases = phraseSec == null ? PhraseSettings.defaults() : new PhraseSettings(
                phraseSec.getBoolean("enabled", true),
                phraseSec.getBoolean("normalize-substitutions", true),
                phraseSec.getBoolean("join-split-letters", true),
                cleanList(phraseSec.getStringList("tokens")),
                cleanList(phraseSec.getStringList("phrases")),
                cleanList(phraseSec.getStringList("substrings")),
                cleanList(phraseSec.getStringList("exceptions"))
        );

        ConfigurationSection repeatSec = section.getConfigurationSection("repeat");
        RepeatSettings repeat = repeatSec == null ? RepeatSettings.defaults() : new RepeatSettings(
                repeatSec.getBoolean("enabled", true),
                repeatSec.getInt("window-seconds", 15),
                repeatSec.getInt("max-similar", 2),
                repeatSec.getDouble("similarity-threshold", 0.85d),
                repeatSec.getBoolean("strip-flood-suffix", true),
                repeatSec.isList("whitelist") ? cleanList(repeatSec.getStringList("whitelist")) : List.of("gg", "wb", "ty", "welcome")
        );

        ConfigurationSection rateSec = section.getConfigurationSection("rate-limit");
        RateLimitSettings rateLimit = rateSec == null ? RateLimitSettings.defaults() : new RateLimitSettings(
                rateSec.getBoolean("enabled", true),
                rateSec.getLong("min-interval-millis", 1000L),
                rateSec.getInt("burst-max", 5),
                rateSec.getInt("burst-window-seconds", 10)
        );

        return new ChatFilterSettings(enabled, mode, exemptStaff, notifyStaff, audit, phrases, repeat, rateLimit);
    }

    private static List<String> cleanList(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String item : raw) {
            if (item != null && !item.isBlank()) out.add(item.trim());
        }
        return List.copyOf(out);
    }
}
