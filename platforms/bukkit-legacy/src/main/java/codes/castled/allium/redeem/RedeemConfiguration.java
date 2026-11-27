package codes.castled.allium.redeem;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** The one-time claim format shared by both Bukkit runtimes. */
public final class RedeemConfiguration {
    private static final String PENDING = "redeem-settings.players-to-redeem";
    private static final String CLAIMED = "redeem-settings.claimed";

    private RedeemConfiguration() {}

    public static Optional<String> pending(ConfigurationSection config, UUID player) {
        List<String> claimed = config.getStringList(CLAIMED);
        for (String entry : config.getStringList(PENDING)) {
            String[] parts = strip(entry).split(":", 2);
            if (parts.length != 2 || !player.toString().equalsIgnoreCase(parts[0])) continue;
            String rank = parts[1].toLowerCase(Locale.ROOT);
            if (rank.matches("[a-z0-9_-]+") && claimed.stream().noneMatch(key(player, rank)::equalsIgnoreCase)) {
                return Optional.of(rank);
            }
        }
        return Optional.empty();
    }

    public static List<String> commands(ConfigurationSection config, String rank) {
        List<String> commands = new ArrayList<>();
        for (String value : config.getStringList("redeem-settings.commands-to-run." + rank)) {
            String command = strip(value);
            if (!command.isEmpty()) commands.add(command);
        }
        return commands;
    }

    public static void consume(ConfigurationSection config, UUID player, String rank) {
        String claim = key(player, rank);
        List<String> pending = new ArrayList<>(config.getStringList(PENDING));
        pending.removeIf(entry -> claim.equalsIgnoreCase(strip(entry)));
        List<String> claimed = new ArrayList<>(config.getStringList(CLAIMED));
        claimed.add(claim);
        config.set(PENDING, pending);
        config.set(CLAIMED, claimed);
    }

    public static void publishClaims(ConfigurationSection saved, ConfigurationSection active) {
        active.set(PENDING, saved.getStringList(PENDING));
        active.set(CLAIMED, saved.getStringList(CLAIMED));
    }

    public static String expand(String command, UUID player, String name) {
        String expanded = command.replace("{player}", name).replace("{uuid}", player.toString());
        return expanded.startsWith("/") ? expanded.substring(1) : expanded;
    }

    private static String key(UUID player, String rank) {
        return player + ":" + rank;
    }

    private static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) start++;
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }
}
