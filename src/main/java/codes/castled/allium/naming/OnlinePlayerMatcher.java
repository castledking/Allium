package codes.castled.allium.naming;

import codes.castled.allium.PluginStart;
import codes.castled.allium.module.ChatModule;
import codes.castled.allium.portable.PlayerNameSearch;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.text.TextFormatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Online player fuzzy matching combining Essentials-style exact/prefix/nickname/substring
 * resolution with bounded Damerau-Levenshtein edit-distance fallback.
 *
 * <p>Resolution priority (best visible match wins):
 * <ol>
 *   <li>Exact UUID</li>
 *   <li>Exact real username (case-insensitive)</li>
 *   <li>Exact stripped nickname (case-insensitive)</li>
 *   <li>Real-name prefix (shortest then alphabetical)</li>
 *   <li>Nickname prefix (shortest then alphabetical)</li>
 *   <li>Real-name substring contains (query length &ge; 2)</li>
 *   <li>Nickname substring contains (query length &ge; 2)</li>
 *   <li>Full-string Damerau-Levenshtein fuzzy match (query length &ge; 3)</li>
 *   <li>Substring-window Damerau-Levenshtein fuzzy match (query length &ge; 4)</li>
 * </ol>
 */
public final class OnlinePlayerMatcher {

    private OnlinePlayerMatcher() {
    }

    /**
     * Resolves {@code query} to the best visible online player using {@code plugin}'s server
     * player list and chat nickname snapshots.
     */
    @Nullable
    public static Player match(
            @Nullable PluginStart plugin,
            @Nullable Collection<? extends Player> onlinePlayers,
            @Nullable String query,
            Predicate<Player> visible
    ) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        if (plugin != null && plugin.getServer() != null) {
            Player exact = plugin.getServer().getPlayerExact(trimmed);
            if (exact != null && visible.test(exact)) {
                return exact;
            }
        }
        List<Player> ranked = rankedMatches(onlinePlayers, trimmed, visible, player -> nickname(plugin, player));
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * Returns all matching players ordered from best match to worst match.
     *
     * <p>On Folia, callers may pass a non-entity-touching predicate here and verify per-entity
     * visibility on each candidate's owning region thread before selecting the first visible match.
     */
    public static List<Player> rankedMatches(
            @Nullable Collection<? extends Player> onlinePlayers,
            @Nullable String query,
            Predicate<Player> filter,
            Function<Player, String> nicknameProvider
    ) {
        return PlayerNameSearch.rankedMatches(onlinePlayers, query, filter, Player::getUniqueId, Player::getName,
                player -> safeStripped(nicknameProvider == null ? null : nicknameProvider.apply(player)));
    }

    /**
     * Returns visible online player usernames matching {@code token} (prefix, nickname, substring,
     * and fuzzy Levenshtein matches ranked best-first).
     */
    public static List<String> tabComplete(PluginStart plugin, CommandSender sender, String token) {
        if (plugin == null || plugin.getServer() == null || plugin.getServer().getOnlinePlayers() == null) {
            return List.of();
        }
        if (token == null || token.isBlank()) {
            List<String> all = new ArrayList<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player != null && PlayerNameVisibility.canSee(sender, player) && player.getName() != null) {
                    all.add(player.getName());
                }
            }
            all.sort(String.CASE_INSENSITIVE_ORDER);
            return List.copyOf(all);
        }
        List<Player> matched = rankedMatches(
                plugin.getServer().getOnlinePlayers(),
                token,
                player -> PlayerNameVisibility.canSee(sender, player),
                player -> nickname(plugin, player)
        );
        List<String> names = new ArrayList<>(matched.size());
        for (Player player : matched) {
            String name = player.getName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Extracts a player's plain nickname/display name without touching region-owned state on Folia.
     */
    public static String nickname(@Nullable PluginStart plugin, Player player) {
        if (player == null) {
            return "";
        }
        if (plugin != null && plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("chat") instanceof ChatModule chatModule
                && chatModule.isEnabled() && chatModule.chat() != null) {
            String fromChat = chatModule.chat().placeholder(player, false);
            if (fromChat != null && !fromChat.isEmpty()) {
                return safeStripped(fromChat);
            }
        }
        if (!BukkitServerThread.isFolia()) {
            String display = player.getDisplayName();
            if (display != null && !display.isEmpty()) {
                return safeStripped(display);
            }
        }
        return "";
    }

    public static int damerauLevenshtein(String a, String b) {
        return PlayerNameSearch.damerauLevenshtein(a, b);
    }

    private static String safeStripped(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            String plain = TextFormatter.parse(raw).toPlainText();
            return plain == null ? "" : plain.trim();
        } catch (RuntimeException ignored) {
            return raw.trim();
        }
    }

}
