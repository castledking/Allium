package codes.castled.allium.item.library;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The running game version, from {@code Bukkit.getBukkitVersion()}
 * ({@code 1.20.1-R0.1-SNAPSHOT}, {@code 1.21.8-R0.1-SNAPSHOT}, {@code 26.3.build.141-beta}).
 */
public record ServerVersion(int major, int minor, int patch) implements Comparable<ServerVersion> {

    /** Data components replaced item NBT in 1.20.5. */
    public static final ServerVersion DATA_COMPONENTS = new ServerVersion(1, 20, 5);
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public static ServerVersion parse(String bukkitVersion) {
        Matcher m = VERSION.matcher(bukkitVersion == null ? "" : bukkitVersion.trim());
        if (!m.find()) {
            throw new IllegalArgumentException("unrecognized server version '" + bukkitVersion + "'");
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        // 26.3.build.141: the third group only counts when it is a plain number.
        int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return new ServerVersion(major, minor, patch);
    }

    public boolean hasDataComponents() {
        return compareTo(DATA_COMPONENTS) >= 0;
    }

    @Override
    public int compareTo(ServerVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) c = Integer.compare(minor, o.minor);
        return c == 0 ? Integer.compare(patch, o.patch) : c;
    }

    @Override
    public String toString() {
        return patch == 0 ? major + "." + minor : major + "." + minor + "." + patch;
    }
}
