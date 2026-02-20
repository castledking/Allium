package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.survivalfun.core.PluginStart;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for nickname-related placeholders
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
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        if (plugin.getNicknameManager() == null) {
            return player.getName();
        }

        if (params.equalsIgnoreCase("nickname_raw")) {
            String stored = plugin.getNicknameManager().getStoredNickname(player);
            return stored != null && !stored.isEmpty() ? stored : player.getName();
        }

        if (params.equalsIgnoreCase("nickname")) {
            String stored = plugin.getNicknameManager().getStoredNickname(player);
            String nicknameToUse = (stored != null && !stored.isEmpty()) ? stored : player.getName();
            return plugin.getNicknameManager().getFormattedNickname(player, nicknameToUse);
        }

        return null;
    }
}
