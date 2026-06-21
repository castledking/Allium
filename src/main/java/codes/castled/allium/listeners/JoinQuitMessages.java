package codes.castled.allium.listeners;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.chat.Chat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.managers.core.VanishManager;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.util.List;

/**
 * Handles join and quit messages with permission-based format overrides.
 *
 * <p>Players with the permission {@code allium.join_message.<format>} or
 * {@code allium.quit_message.<format>} will see their join/quit message
 * rendered using the format string from that permission's value (via Vault
 * permission metadata or the permission string itself).</p>
 *
 * <p>Permission-based formats use the same placeholder system as the default
 * config format: {@code %name%}, {@code %player%}, {@code %prefix%},
 * {@code %suffix%}.</p>
 */
public class JoinQuitMessages implements Listener {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacySection();

    private final PluginStart plugin;
    private final VanishManager vanishManager;

    public JoinQuitMessages(PluginStart plugin, VanishManager vanishManager) {
        this.plugin = plugin;
        this.vanishManager = vanishManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (vanishManager != null && vanishManager.isVanished(event.getPlayer())) {
            return;
        }

        var joinConfig = plugin.getConfig().getConfigurationSection("join-and-quit-messages.join");
        if (joinConfig == null || !joinConfig.getBoolean("enabled", true)) {
            return;
        }

        String format = resolveFormat(event.getPlayer(), "join");
        if (format == null || format.isEmpty()) {
            event.setJoinMessage(null);
            return;
        }

        String message = applyPlaceholders(event.getPlayer(), format);
        event.setJoinMessage(message);

        Text.sendDebugLog(INFO, "Join message for " + event.getPlayer().getName() + ": " + message);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (vanishManager != null && vanishManager.isVanished(event.getPlayer())) {
            return;
        }

        var quitConfig = plugin.getConfig().getConfigurationSection("join-and-quit-messages.quit");
        if (quitConfig == null || !quitConfig.getBoolean("enabled", true)) {
            return;
        }

        String format = resolveFormat(event.getPlayer(), "quit");
        if (format == null || format.isEmpty()) {
            event.setQuitMessage(null);
            return;
        }

        String message = applyPlaceholders(event.getPlayer(), format);
        event.setQuitMessage(message);

        Text.sendDebugLog(INFO, "Quit message for " + event.getPlayer().getName() + ": " + message);
    }

    /**
     * Resolves the format string for a player.
     *
     * <p>Checks for permission-based formats first. The permission node
     * {@code allium.join_message.<text>} or {@code allium.quit_message.<text>}
     * is checked; if the player has it, the permission's metadata value
     * (via Vault Chat's permission metadata) is used as the format string.
     * If no metadata is set, the permission suffix itself is not used —
     * instead the default config format is used.</p>
     *
     * <p>If no permission-based format is found, falls back to the default
     * format from config.yml.</p>
     *
     * @param player the player
     * @param type   "join" or "quit"
     * @return the resolved format string, or the default from config
     */
    private String resolveFormat(Player player, String type) {
        String defaultFormat = plugin.getConfig().getString(
                "join-and-quit-messages." + type + ".format",
                "&e%name% &7has " + (type.equals("join") ? "joined" : "left") + " the server"
        );

        // Check for permission-based format overrides.
        // Permission: allium.join_message.<text> or allium.quit_message.<text>
        // We look for any permission matching the prefix and use the first one found.
        String permPrefix = "allium." + type + "_message.";

        // Iterate through player's effective permissions to find a matching one.
        // We check permissions the player actually has, so we iterate known permissions.
        for (var permAttachment : player.getEffectivePermissions()) {
            if (!permAttachment.getValue()) continue;
            String perm = permAttachment.getPermission();
            if (perm.startsWith(permPrefix)) {
                // Found a matching permission. Try to get the format from Vault metadata.
                String formatFromPerm = getFormatFromPermissionMetadata(player, perm);
                if (formatFromPerm != null && !formatFromPerm.isEmpty()) {
                    Text.sendDebugLog(INFO, "Using permission-based " + type + " format for "
                            + player.getName() + " from " + perm);
                    return formatFromPerm;
                }
            }
        }

        return defaultFormat;
    }

    /**
     * Attempts to retrieve a format string from a permission's metadata via Vault Chat.
     *
     * @param player the player
     * @param perm   the permission node
     * @return the metadata value, or null if unavailable
     */
    private String getFormatFromPermissionMetadata(Player player, String perm) {
        Chat chat = plugin.getVaultChat();
        if (chat == null) {
            return null;
        }
        try {
            // Vault Chat provides permission metadata via getPermissionInfoString
            // or we can use the permission info methods.
            // Try to get the info string for this permission node.
            String info = chat.getPlayerInfoString(player, perm, null);
            if (info != null && !info.isEmpty()) {
                return info;
            }
        } catch (UnsupportedOperationException e) {
            // Some Vault implementations don't support this
            Text.sendDebugLog(WARN, "Vault Chat does not support permission info strings for metadata");
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to get permission metadata: " + e.getMessage());
        }
        return null;
    }

    /**
     * Applies placeholders to a format string.
     *
     * <p>Supported placeholders:</p>
     * <ul>
     *   <li>{@code %name%} - Player's display name (respects /nick)</li>
     *   <li>{@code %player%} - Player's real name</li>
     *   <li>{@code %prefix%} - Player's Vault prefix</li>
     *   <li>{@code %suffix%} - Player's Vault suffix</li>
     * </ul>
     *
     * @param player the player
     * @param format the format string
     * @return the formatted string with colors parsed
     */
    private String applyPlaceholders(Player player, String format) {
        String result = format;

        // %name% - display name (respects /nick)
        String displayName = player.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = player.getName();
        }
        result = result.replace("%name%", displayName);

        // %player% - real name
        result = result.replace("%player%", player.getName());

        // %prefix% - Vault prefix
        String prefix = "";
        Chat chat = plugin.getVaultChat();
        if (chat != null) {
            try {
                prefix = chat.getPlayerPrefix(player);
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to get prefix for " + player.getName() + ": " + e.getMessage());
            }
        }
        if (prefix == null) prefix = "";
        result = result.replace("%prefix%", prefix);

        // %suffix% - Vault suffix
        String suffix = "";
        if (chat != null) {
            try {
                suffix = chat.getPlayerSuffix(player);
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Failed to get suffix for " + player.getName() + ": " + e.getMessage());
            }
        }
        if (suffix == null) suffix = "";
        result = result.replace("%suffix%", suffix);

        // Parse color codes (&-style) into section-color format
        return Text.parseColors(result);
    }
}
