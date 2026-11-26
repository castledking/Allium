package codes.castled.allium.portable;

/** Shared command output for adapters that run on different Java and Minecraft versions. */
public final class UtilityMessages {
    public static final String COMMANDS = "Allium commands: /allium version, /ping, /coords, /sethome, /home, /delhome, /homes";

    private UtilityMessages() {}

    public static String version(String version, String platform, String game) {
        return "Allium " + version + " on " + platform + " (Minecraft " + game + ")";
    }

    public static String ping(String player, int latency) {
        return player + " has " + latency + " ms latency.";
    }

    public static String coordinates(String player, int x, int y, int z, String world) {
        return player + " is at " + x + ", " + y + ", " + z + " in " + world + ".";
    }
}
