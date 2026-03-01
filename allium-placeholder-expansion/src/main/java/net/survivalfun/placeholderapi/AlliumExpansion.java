package net.survivalfun.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * Standalone PlaceholderAPI expansion for Allium.
 * Place this JAR in PlaceholderAPI/expansions/ folder.
 * Uses reflection to access Allium - no compile dependency required.
 */
public class AlliumExpansion extends PlaceholderExpansion {

    @Override
    public boolean canRegister() {
        return Bukkit.getPluginManager().getPlugin("Allium") != null
            && Bukkit.getPluginManager().getPlugin("Allium").isEnabled();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Allium";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return Arrays.asList(
            "nickname", "nickname_raw",
            "ping", "fly", "god", "reply", "mail_unread", "mail_gifts"
        );
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null || !offlinePlayer.isOnline()) {
            return "";
        }
        Player player = offlinePlayer.getPlayer();
        if (player == null) {
            return "";
        }

        if ("ping".equals(params)) {
            return "ok";
        }

        if ("nickname".equals(params) || "nickname_raw".equals(params)) {
            try {
                Object allium = Bukkit.getPluginManager().getPlugin("Allium");
                if (allium == null) return player.getName();

                Object nm = allium.getClass().getMethod("getNicknameManager").invoke(allium);
                if (nm == null) return player.getName();

                String stored = (String) nm.getClass().getMethod("getStoredNickname", Player.class).invoke(nm, player);
                String nicknameToUse = (stored != null && !stored.isEmpty()) ? stored : player.getName();

                if ("nickname_raw".equals(params)) {
                    return nicknameToUse;
                }

                String result = (String) nm.getClass().getMethod("getFormattedNickname", Player.class, String.class)
                    .invoke(nm, player, nicknameToUse);
                if (result == null || result.isEmpty()) return player.getName();
                // Formatted result may be only color codes - tab plugins show blank. Use raw if no visible text.
                if (stripColorCodes(result).isEmpty()) return nicknameToUse;
                // Tab/menu plugins expect & and &#xxxxxx, not §
                return toAmpersandFormat(result);
            } catch (Throwable t) {
                return player.getName();
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
        s = s.replaceAll("§#([0-9a-fA-F]{6})", "&#$1");
        s = s.replaceAll("§x§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])", "&#$1$2$3$4$5$6");
        s = s.replace("§", "&");
        return s;
    }
}
