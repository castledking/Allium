package codes.castled.allium.api;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The Allium API versions an addon accepts, declared in its {@code plugin.yml} as
 * {@code allium-api: "<range>"}:
 * <ul>
 *   <li>{@code "1.2"}, {@code "^1.2"} or {@code "^1.2.3"}: that version or any later one with the
 *       same major ({@code >=1.2.0 <2.0.0}). For major 0 the minor is fixed instead.</li>
 *   <li>{@code ">=1.2 <3.0"}: an explicit range, lower bound included, upper bound excluded.</li>
 * </ul>
 *
 * @param maxExclusive {@code null} for no upper bound (allowed only in the explicit form)
 */
public record ApiRange(ApiVersion min, @Nullable ApiVersion maxExclusive, String declared) {

    public ApiRange {
        Objects.requireNonNull(min, "min");
        if (maxExclusive != null && maxExclusive.compareTo(min) <= 0) {
            throw new IllegalArgumentException("empty API range '" + declared + "'");
        }
    }

    public static ApiRange parse(String text) {
        String t = Objects.requireNonNull(text, "text").trim();
        if (t.startsWith(">=")) {
            String[] parts = t.substring(2).trim().split("\\s+");
            ApiVersion min = ApiVersion.parse(parts[0]);
            if (parts.length == 1) {
                return new ApiRange(min, null, t);
            }
            if (parts.length != 2 || !parts[1].startsWith("<") || parts[1].startsWith("<=")) {
                throw new IllegalArgumentException("not an API range: '" + text + "' (expected \">=A <B\")");
            }
            return new ApiRange(min, ApiVersion.parse(parts[1].substring(1)), t);
        }
        ApiVersion min = ApiVersion.parse(t.startsWith("^") ? t.substring(1) : t);
        ApiVersion max = min.major() > 0 ? new ApiVersion(min.major() + 1, 0, 0) : new ApiVersion(0, min.minor() + 1, 0);
        return new ApiRange(min, max, t);
    }

    public boolean contains(ApiVersion version) {
        return version.compareTo(min) >= 0 && (maxExclusive == null || version.compareTo(maxExclusive) < 0);
    }

    /** {@code >=1.2.0 <2.0.0}. */
    public String describe() {
        return ">=" + min + (maxExclusive == null ? "" : " <" + maxExclusive);
    }
}
