package codes.castled.allium.api;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic Allium API version, separate from the plugin version. A minor release only adds; a
 * major release may remove what earlier minors deprecated (see docs/addon-api.md).
 */
public record ApiVersion(int major, int minor, int patch) implements Comparable<ApiVersion> {

    private static final Pattern FORMAT = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public ApiVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("API version parts must not be negative");
        }
    }

    /** Parses {@code major.minor} or {@code major.minor.patch}. */
    public static ApiVersion parse(String text) {
        Matcher m = FORMAT.matcher(Objects.requireNonNull(text, "text").trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not an API version: '" + text + "' (expected major.minor or major.minor.patch)");
        }
        return new ApiVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
    }

    @Override
    public int compareTo(ApiVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c != 0 ? c : Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
