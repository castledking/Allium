package codes.castled.allium;

/**
 * Semantic Minecraft version and JVM major-version parser compiled for Java 8 so the unified
 * Bukkit/Spigot/Paper/Folia jar can decide cleanly at startup between the modern 1.20+ runtime
 * and the 1.12.2-1.19.4 compatibility runtime.
 */
public final class ServerVersion implements Comparable<ServerVersion> {

    public final int major;
    public final int minor;
    public final int patch;
    public final String raw;

    public ServerVersion(int major, int minor, int patch, String raw) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.raw = raw == null ? (major + "." + minor + (patch > 0 ? "." + patch : "")) : raw;
    }

    /** Parses strings like {@code 1.12.2-R0.1-SNAPSHOT}, {@code 1.20-R0.1-SNAPSHOT}, or {@code 26.3-R0.1-SNAPSHOT}. */
    public static ServerVersion parse(String bukkitVersion) {
        if (bukkitVersion == null || bukkitVersion.trim().isEmpty()) {
            return new ServerVersion(0, 0, 0, "unknown");
        }
        String trimmed = bukkitVersion.trim();
        String core = trimmed.split("[-\\s]", 2)[0];
        String[] parts = core.split("\\.");
        int major = parsePart(parts, 0);
        int minor = parsePart(parts, 1);
        int patch = parsePart(parts, 2);
        return new ServerVersion(major, minor, patch, core);
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String token = parts[index];
        int end = 0;
        while (end < token.length() && Character.isDigit(token.charAt(end))) {
            end++;
        }
        if (end == 0) return 0;
        try {
            return Integer.parseInt(token.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Parses {@code java.specification.version} or {@code java.version} into a major feature number (8, 11, 17, 21, 25...). */
    public static int parseJavaMajor(String versionString) {
        if (versionString == null || versionString.trim().isEmpty()) return 8;
        String s = versionString.trim();
        if (s.startsWith("1.")) {
            s = s.substring(2);
        }
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == 0) return 8;
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 8;
        }
    }

    public static int currentJavaMajor() {
        String spec = System.getProperty("java.specification.version");
        if (spec == null || spec.trim().isEmpty()) {
            spec = System.getProperty("java.version", "8");
        }
        return parseJavaMajor(spec);
    }

    public boolean isAtLeast(int reqMajor, int reqMinor, int reqPatch) {
        if (this.major != reqMajor) return this.major > reqMajor;
        if (this.minor != reqMinor) return this.minor > reqMinor;
        return this.patch >= reqPatch;
    }

    /** Minimum supported legacy compatibility release: Minecraft 1.12.2. */
    public boolean isSupported() {
        return isAtLeast(1, 12, 2);
    }

    /** Full modern Allium runtime requires Minecraft 1.20+ and Java 17+. */
    public boolean supportsModernRuntime(int javaMajor) {
        return javaMajor >= 17 && isAtLeast(1, 20, 0);
    }

    @Override
    public int compareTo(ServerVersion other) {
        if (this.major != other.major) return Integer.compare(this.major, other.major);
        if (this.minor != other.minor) return Integer.compare(this.minor, other.minor);
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public String toString() {
        return raw;
    }
}
