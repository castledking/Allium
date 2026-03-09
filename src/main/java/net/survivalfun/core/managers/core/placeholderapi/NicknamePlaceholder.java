package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.survivalfun.core.PluginStart;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for nickname-related placeholders.
 * %allium_nickname% and %allium_nickname_raw% always default to the player's name when unset.
 */
public class NicknamePlaceholder extends PlaceholderExpansion {

    private final PluginStart plugin;

    public NicknamePlaceholder(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) return "";
        if (!params.equalsIgnoreCase("nickname") && !params.equalsIgnoreCase("nickname_raw")) {
            return null;
        }
        String defaultName = offlinePlayer.getName();
        if (defaultName == null || defaultName.isEmpty()) {
            defaultName = offlinePlayer.getUniqueId().toString();
        }
        if (plugin.getNicknameManager() == null) {
            return defaultName;
        }
        String stored = plugin.getNicknameManager().getStoredNickname(offlinePlayer.getUniqueId(), defaultName);
        if (params.equalsIgnoreCase("nickname_raw")) {
            return (stored != null && !stored.isEmpty()) ? stored : defaultName;
        }
        if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
            return onPlaceholderRequest(offlinePlayer.getPlayer(), params);
        }
        return (stored != null && !stored.isEmpty()) ? stored : defaultName;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        String playerName = player.getName();
        if (playerName == null || playerName.isEmpty()) {
            playerName = player.getUniqueId().toString();
        }

        if (plugin.getNicknameManager() == null) {
            return playerName;
        }

        if (params.equalsIgnoreCase("nickname_raw")) {
            try {
                String stored = plugin.getNicknameManager().getStoredNickname(player);
                String out = (stored != null && !stored.isEmpty()) ? stored : playerName;
                return (out != null && !out.isEmpty()) ? out : playerName;
            } catch (Throwable t) {
                return playerName;
            }
        }

        if (params.equalsIgnoreCase("nickname")) {
            try {
                String stored = plugin.getNicknameManager().getStoredNickname(player);
                String nicknameToUse = (stored != null && !stored.isEmpty()) ? stored : playerName;
                String result = plugin.getNicknameManager().getFormattedNickname(player, nicknameToUse);
                if (result == null || result.isEmpty()) return playerName;
                String stripped = stripColorCodes(result);
                if (stripped.isEmpty()) return (nicknameToUse != null && !nicknameToUse.isEmpty()) ? nicknameToUse : playerName;
                return toAmpersandFormat(result);
            } catch (Throwable t) {
                plugin.getLogger().warning("Nickname placeholder error: " + t.getMessage());
                return playerName;
            }
        }

        return null;
    }

    private static String stripColorCodes(String text) {
        if (text == null) return "";
        return text.replaceAll("§x(§[0-9a-fA-F]){6}", "")
                   .replaceAll("§#[0-9a-fA-F]{6}", "")
                   .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                   .replaceAll("&[0-9a-fk-orA-FK-OR]", "")
                   .replaceAll("&#[0-9a-fA-F]{6}", "");
    }

    /** Converts § format to & format for PlaceholderAPI/tab/menu plugins. Hex: &#xxxxxx */
    private static String toAmpersandFormat(String text) {
        if (text == null) return "";
        String s = text;
        // §#rrggbb -> &#rrggbb
        s = s.replaceAll("§#([0-9a-fA-F]{6})", "&#$1");
        // §x§r§r§g§g§b§b (Minecraft internal hex) -> &#rrggbb
        s = s.replaceAll("§x§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])", "&#$1$2$3$4$5$6");
        // §a -> &a (single char codes)
        s = s.replace("§", "&");
        return s;
    }
}
