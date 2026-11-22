package codes.castled.allium.modguard;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

public final class Detection {

    private Detection() {
    }

    public static Pattern glob(String value) {
        StringJoiner out = new StringJoiner(".*", "^", "$");
        for (String part : value.split("\\*", -1)) {
            out.add(Pattern.quote(part));
        }
        return Pattern.compile(out.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        s.codePoints()
                .filter(c -> !Character.isISOControl(c) && c != 127 && Character.getType(c) != Character.FORMAT)
                .limit(max)
                .forEach(b::appendCodePoint);
        return b.toString().replace('§', '?');
    }

    public static boolean positiveProbe(String key, String fallback, String response, boolean controlWorked) {
        return controlWorked && response != null && !response.isBlank()
                && !response.equals(key) && !response.equals(fallback);
    }

    public record Rule(
            String id,
            String name,
            Severity severity,
            List<Pattern> brands,
            List<Pattern> channels,
            List<Pattern> mods
    ) {
        public Rule {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(severity, "severity");
            brands = brands == null ? List.of() : List.copyOf(brands);
            channels = channels == null ? List.of() : List.copyOf(channels);
            mods = mods == null ? List.of() : List.copyOf(mods);
        }

        public boolean matches(Kind kind, String value) {
            if (value == null || value.isEmpty()) {
                return false;
            }
            List<Pattern> list = switch (kind) {
                case BRAND -> this.brands;
                case CHANNEL -> this.channels;
                case MOD -> this.mods;
            };
            return list.stream().anyMatch(p -> p.matcher(value).matches());
        }
    }

    public record Finding(
            String rule,
            String name,
            Severity severity,
            String source,
            String evidence,
            String confidence,
            Instant at
    ) {
    }

    public record Mod(
            String id,
            String name,
            String version,
            String type
    ) {
    }

    public enum Kind {
        BRAND,
        CHANNEL,
        MOD
    }

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL;

        public static Severity parse(String s) {
            if (s == null || s.isBlank()) {
                return INFO;
            }
            try {
                return Severity.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return INFO;
            }
        }
    }
}
