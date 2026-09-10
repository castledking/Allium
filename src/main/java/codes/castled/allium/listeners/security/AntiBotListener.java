package codes.castled.allium.listeners.security;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import codes.castled.allium.PluginStart;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punishes first-time joiners whose very first command is a known bot probe
 * (e.g. /plugins, /pl, /version). Legitimate players never run these as their
 * opening command; bot clients do it immediately after spawning.
 *
 * Config:
 * <pre>
 * anti-bot:
 *   commands-to-punish: [plugins, pl, version]
 *   punishment-command: ["ban %player% -s [SC] Bots aren't allowed on this server"]
 * </pre>
 */
public class AntiBotListener implements Listener {

    private final PluginStart plugin;

    /** UUIDs of players currently in their first-ever session on the server. */
    private final Set<UUID> firstSessionPlayers = ConcurrentHashMap.newKeySet();

    public AntiBotListener(PluginStart plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // hasPlayedBefore() is false only for a player's first-ever session
        if (!player.hasPlayedBefore()) {
            firstSessionPlayers.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!firstSessionPlayers.remove(player.getUniqueId())) {
            return;
        }

        if (player.hasPermission("allium.antibot.bypass")) {
            return;
        }

        String rootCommand = extractRootCommand(event.getMessage());
        List<String> triggers = plugin.getConfig().getStringList("anti-bot.commands-to-punish");
        if (rootCommand == null || triggers == null || triggers.isEmpty()) {
            return;
        }

        for (String trigger : triggers) {
            if (trigger != null && rootCommand.equalsIgnoreCase(trigger.trim())) {
                event.setCancelled(true);
                punish(player);
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        firstSessionPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void punish(Player player) {
        List<String> commands = plugin.getConfig().getStringList("anti-bot.punishment-command");
        if (commands == null || commands.isEmpty()) {
            plugin.getLogger().warning(
                "[AntiBot] Blocked first-command probe from new player " + player.getName()
                    + " but punishment-command list is empty");
            return;
        }

        plugin.getLogger().warning(
            "[AntiBot] New player " + player.getName() + " ran a bot probe command; punishing");

        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String processed = command.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
        }
    }

    /**
     * Extracts the root command from a raw chat command message.
     * Strips the leading slash and any plugin namespace prefix ("minecraft:x" -> "x").
     */
    private static String extractRootCommand(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        String root = trimmed.split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0 && colon < root.length() - 1) {
            root = root.substring(colon + 1);
        }
        return root;
    }
}
