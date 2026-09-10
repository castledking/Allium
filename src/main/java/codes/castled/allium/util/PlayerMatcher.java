package codes.castled.allium.util;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import codes.castled.allium.PluginStart;
import me.croabeast.prismatic.PrismaticAPI;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Essentials-style fuzzy player matching for command arguments.
 *
 * <p>Resolution order mirrors Essentials' {@code matchUser}:
 * <ol>
 *   <li>exact UUID (when the term parses as one)</li>
 *   <li>exact real name, case-insensitive</li>
 *   <li>real-name prefix (shortest then alphabetical wins)</li>
 *   <li>nickname prefix</li>
 *   <li>nickname contains (Essentials' display-name fallback)</li>
 * </ol>
 *
 * <p>Players hidden from the sender via vanish are never matched unless the
 * sender outranks them ({@code VanishManager#canSee}). Console sees everyone.
 */
public final class PlayerMatcher {

    private static PluginStart plugin;

    private PlayerMatcher() {
    }

    /** Must be called once from PluginStart before any command uses the matcher. */
    public static void initialize(PluginStart instance) {
        plugin = instance;
    }

    /**
     * Resolves a search term to an online player using Essentials-style matching.
     *
     * @param sender     who is running the command; used for vanish visibility checks
     * @param searchTerm raw argument as typed by the user
     * @return the matched player, or null if no visible online player matches
     */
    public static Player match(CommandSender sender, String searchTerm) {
        return match(sender, searchTerm, true);
    }

    /**
     * Resolves a search term to an online player.
     *
     * @param allowNicknameFallback when false only real names are considered (UUID/exact/prefix)
     */
    public static Player match(CommandSender sender, String searchTerm, boolean allowNicknameFallback) {
        if (searchTerm == null || searchTerm.isEmpty()) {
            return null;
        }

        Player viewer = (sender instanceof Player p) ? p : null;
        String lower = searchTerm.toLowerCase(Locale.ENGLISH);

        // 1. Exact UUID
        try {
            Player uuidMatch = Bukkit.getPlayer(UUID.fromString(searchTerm));
            if (visible(viewer, uuidMatch)) {
                return uuidMatch;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID; continue with name matching
        }

        // 2. Exact real-name match (case-insensitive)
        Player exact = Bukkit.getPlayerExact(searchTerm);
        if (exact != null && visible(viewer, exact)) {
            return exact;
        }

        List<Player> namePrefix = new ArrayList<>();
        List<Player> nickPrefix = new ArrayList<>();
        List<Player> nickContains = new ArrayList<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!visible(viewer, online)) {
                continue;
            }
            String name = online.getName();
            if (name.toLowerCase(Locale.ENGLISH).startsWith(lower)) {
                namePrefix.add(online);
                continue;
            }
            if (!allowNicknameFallback) {
                continue;
            }
            String nick = strippedNickname(online);
            if (nick.isEmpty() || nick.equalsIgnoreCase(name)) {
                continue;
            }
            if (nick.toLowerCase(Locale.ENGLISH).startsWith(lower)) {
                nickPrefix.add(online);
            } else if (nick.toLowerCase(Locale.ENGLISH).contains(lower)) {
                nickContains.add(online);
            }
        }

        // 3. Real-name prefix — prefer the closest match: shortest name, then alphabetical
        if (!namePrefix.isEmpty()) {
            return bestMatch(namePrefix);
        }
        // 4. Nickname prefix
        if (!nickPrefix.isEmpty()) {
            return bestMatch(nickPrefix);
        }
        // 5. Nickname contains (Essentials' display-name fallback)
        if (!nickContains.isEmpty()) {
            return bestMatch(nickContains);
        }
        return null;
    }

    /**
     * Builds tab-completion candidates for a partially typed player name or nickname.
     * Returns real in-game names (the canonical form commands resolve against), sorted,
     * with nickname-only matches appended after direct name matches.
     *
     * @param sender who is completing; vanish-hidden players they cannot see are excluded
     * @param token  current partial argument
     * @return matching real player names, best matches first
     */
    public static List<String> tabComplete(CommandSender sender, String token) {
        if (token == null) {
            token = "";
        }
        String lower = token.toLowerCase(Locale.ENGLISH);

        Map<String, Integer> ranked = new LinkedHashMap<>();
        Player viewer = (sender instanceof Player p) ? p : null;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!visible(viewer, online)) {
                continue;
            }
            String name = online.getName();
            if (name.toLowerCase(Locale.ENGLISH).startsWith(lower)) {
                ranked.merge(name, 0, Integer::sum);
                continue;
            }
            String nick = strippedNickname(online);
            if (nick.equalsIgnoreCase(name)) {
                continue;
            }
            if (nick.toLowerCase(Locale.ENGLISH).startsWith(lower)
                    || nick.toLowerCase(Locale.ENGLISH).contains(lower)) {
                ranked.merge(name, 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(ranked.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            result.add(entry.getKey());
        }
        return result;
    }

    /** Picks the shortest (then alphabetically first) candidate so "j" prefers "joe" over "joey". */
    private static Player bestMatch(List<Player> candidates) {
        candidates.sort(Comparator.comparingInt((Player p) -> p.getName().length())
                .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return candidates.get(0);
    }

    private static boolean visible(Player viewer, Player target) {
        if (target == null || !target.isOnline()) {
            return false;
        }
        if (viewer == null || viewer.equals(target)) {
            return true;
        }
        return plugin == null || plugin.getVanishManager().canSee(viewer, target);
    }

    private static String strippedNickname(Player player) {
        try {
            if (plugin == null) {
                return "";
            }
            String stored = plugin.getNicknameManager().getStoredNickname(player);
            if (stored == null || stored.isEmpty()) {
                return "";
            }
            String stripped = PrismaticAPI.stripAll(stored);
            return stripped != null ? stripped.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
