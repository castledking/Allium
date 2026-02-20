package net.survivalfun.core.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.Database.LocationType;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.Collection;

public class Seen implements CommandExecutor {

    private final PluginStart plugin;
    private final Lang lang;

    public Seen(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Text.sendErrorMessage(sender, "player-only-command", lang, "use /{cmd}", "execute this command.", true);
            return true;
        }

        if (!player.hasPermission("allium.seen")) {
            Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "see player information.", true);
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Text.colorize("&cUsage: /seen <player>"));
            // Play sound effect
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            return true;
        }

        String targetName = args[0];

        // Validate the username format first
        if (!isValidMinecraftUsername(targetName)) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "name", targetName);
            return true;
        }

        // Resolve UUID, checking Floodgate for Bedrock players
        UUID targetUUID = resolvePlayerUUID(targetName);
        if (targetUUID == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "name", targetName);
            return true;
        }

        // Use resolved UUID to get player data
        Player targetOnlinePlayer = Bukkit.getPlayer(targetUUID);

        // First check if player is online, which is the most reliable method
        if (targetOnlinePlayer != null) {
            // Player is online, show current information
            showOnlinePlayerInfo(sender, targetOnlinePlayer);
        } else {
            try {
                // Try to get offline player data using resolved UUID
                OfflinePlayer targetOfflinePlayer = Bukkit.getOfflinePlayer(targetUUID);

                // Check if this player has ever played on the server
                if (!targetOfflinePlayer.hasPlayedBefore()) {
                    Text.sendErrorMessage(sender, "never-joined", lang, "name", targetName);
                    return true;
                }

                showOfflinePlayerInfo(sender, targetOfflinePlayer);

            } catch (Exception e) {
                // Handle the case when Mojang API can't find the player
                Text.sendDebugLog(Text.DebugSeverity.WARN, "Failed to get player data for '" + targetName + "': " + e.getMessage());
                Text.sendErrorMessage(sender, "player-not-found", lang, "name", targetName);
                return true;
            }
        }

        return true;
    }

    /**
     * Resolves player UUID, checking Floodgate for Bedrock players
     *
     * @param playerName The player name or Bedrock identifier
     * @return The resolved UUID or null if not found
     */
    private UUID resolvePlayerUUID(String playerName) {
        // First try standard Bukkit lookup
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        if (onlinePlayer != null) {
            return onlinePlayer.getUniqueId();
        }

        // Check if Floodgate is available and if this is a Bedrock player
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
                Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object floodgateApi = floodgateApiClass.getMethod("getInstance").invoke(null);

                // floodgateApi.getPlayers() -> Collection<?>
                Object playersObj = floodgateApiClass.getMethod("getPlayers").invoke(floodgateApi);
                if (playersObj instanceof Collection<?>) {
                    Collection<?> players = (Collection<?>) playersObj;
                    for (Object playerObj : players) {
                        String username = String.valueOf(playerObj.getClass().getMethod("getUsername").invoke(playerObj));
                        if (playerName.equals(username)) {
                            Object uuidObj = playerObj.getClass().getMethod("getJavaUniqueId").invoke(playerObj);
                            if (uuidObj instanceof UUID) {
                                return (UUID) uuidObj;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Floodgate not available or API changed; ignore and fall back
        }

        // Fall back to offline player lookup
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        return offlinePlayer.getUniqueId();
    }

    private void showOnlinePlayerInfo(CommandSender sender, Player targetPlayer) {
        Player player = (Player) sender;
        
        // Play sound effect
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        
        // Show particle effect
        if (player.hasPermission("allium.particles")) {
            try {
                player.spawnParticle(Particle.HAPPY_VILLAGER, 
                    player.getEyeLocation().add(0, 0.5, 0), 
                    10, 0.5, 0.5, 0.5, 0.1);
            } catch (Exception e) {
                // Particle effect failed, but we'll continue
                Text.sendDebugLog(Text.DebugSeverity.WARN, "Failed to spawn particles: " + e.getMessage());
            }
        }
        
        // Send header message with direct legacy color code handling
        String headerTemplate = lang.getRaw("seen.header");
        String header = headerTemplate
            .replace("&", "§")
            .replace("{player}", targetPlayer.getName());
        player.sendMessage(Text.colorize(header));

        // Show online status with duration
        Map<UUID, Long> loginTimes = plugin.getPlayerLoginTimes();
        if (loginTimes != null && loginTimes.containsKey(targetPlayer.getUniqueId())) {
            long loginTime = loginTimes.get(targetPlayer.getUniqueId());
            long durationMillis = System.currentTimeMillis() - loginTime;
            String durationFormatted = Text.formatTime((int) (durationMillis / 1000));

            String statusTemplate = lang.getRaw("seen.status-online");
            String statusRaw = statusTemplate.replace("{duration}", durationFormatted);
            sender.sendMessage(Text.colorize(statusRaw));
        }

        // Show first joined date
        long firstPlayedTimestamp = targetPlayer.getFirstPlayed();
        String firstJoinTemplate = lang.getRaw("seen.first-joined");
        String firstJoinRaw = firstJoinTemplate.replace("{first_join_date}",
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(firstPlayedTimestamp)));
        sender.sendMessage(Text.colorize(firstJoinRaw));

        // Show current location
        Location loc = targetPlayer.getLocation();
        String locationTemplate = lang.getRaw("seen.location");
        String locationRaw = locationTemplate
                .replace("{world}", loc.getWorld().getName())
                .replace("{x}", String.format("%.2f", loc.getX()))
                .replace("{y}", String.format("%.2f", loc.getY()))
                .replace("{z}", String.format("%.2f", loc.getZ()));

        if (sender instanceof Player && sender.hasPermission("allium.tp")) {
            Component locationMsg = Text.colorize(locationRaw)
                    .clickEvent(ClickEvent.runCommand("/tppos " + loc.getX() + " " + loc.getY() + " " + loc.getZ() + " " + loc.getWorld().getName()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to this location")));
            sender.sendMessage(locationMsg);
        } else {
            sender.sendMessage(Text.colorize(locationRaw));
        }

        // Show gamemode
        GameMode gameMode = targetPlayer.getGameMode();
        String gamemodeDisplay = gameMode.name().toLowerCase();
        gamemodeDisplay = gamemodeDisplay.substring(0, 1).toUpperCase() + gamemodeDisplay.substring(1);
        sender.sendMessage(Text.colorize(lang.get("seen.gamemode").replace("{gamemode}", gamemodeDisplay)));

        // Show fly mode
        boolean allowFlight = targetPlayer.getAllowFlight();
        String flyStatusText = allowFlight ?
            lang.getStyle("styles.state.true", "&a&n") + "Enabled" :
            lang.getStyle("styles.state.false", "&c&n") + "Disabled";
        String flyModeTemplate = lang.getRaw("seen.fly-mode");
        String flyModeRaw = flyModeTemplate.replace("{fly_status}", flyStatusText);
        sender.sendMessage(Text.colorize(flyModeRaw));

        // Show god mode (invulnerability)
        boolean isGodMode = targetPlayer.isInvulnerable();
        String godStatusText = isGodMode ?
            lang.getStyle("styles.state.true", "&a&n") + "Enabled" :
            lang.getStyle("styles.state.false", "&c&n") + "Disabled";
        String godModeTemplate = lang.getRaw("seen.god-mode");
        String godModeRaw = godModeTemplate.replace("{god_status}", godStatusText);
        sender.sendMessage(Text.colorize(godModeRaw));

        // Show IP address if sender has permission
        if (sender.hasPermission("allium.seen.ip")) {
            String ip = targetPlayer.getAddress() != null ?
                targetPlayer.getAddress().getAddress().getHostAddress() : "Unknown";
            sender.sendMessage(Text.colorize(lang.get("seen.ip-address").replace("{ip}", ip)));
        }
    }

    private void showOfflinePlayerInfo(CommandSender sender, OfflinePlayer targetPlayer) {
        Player player = (Player) sender;
        
        // Play a different sound for offline players
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
        
        // Show different particle effect for offline players
        if (player.hasPermission("allium.particles")) {
            try {
                player.spawnParticle(Particle.CLOUD, 
                    player.getEyeLocation().add(0, 0.5, 0), 
                    5, 0.3, 0.3, 0.3, 0.1);
            } catch (Exception e) {
                // Particle effect failed, but we'll continue
                Text.sendDebugLog(Text.DebugSeverity.WARN, "Failed to spawn particles: " + e.getMessage());
            }
        }
        
        // Send header message with direct legacy color code handling
        String headerTemplate = lang.getRaw("seen.header");
        String header = headerTemplate
            .replace("&", "§")
            .replace("{player}", targetPlayer.getName());
        player.sendMessage(Text.colorize(header));

        // Show offline status
        Database.PlayerLastSeenData lastSeenData = plugin.getDatabase().getPlayerLastSeen(targetPlayer.getUniqueId());
        if (lastSeenData != null && lastSeenData.lastSeenDate() != null) {
            Timestamp lastSeenTimestamp = lastSeenData.lastSeenDate();
            long durationMillis = System.currentTimeMillis() - lastSeenTimestamp.getTime();
            String durationFormatted = Text.formatTime((int) (durationMillis / 1000));

            String statusTemplate = lang.getRaw("seen.status");
            String statusRaw = statusTemplate
                .replace("{status}", "&cOffline")
                .replace("{duration}", durationFormatted)
                .replace("{last_seen_date}", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(lastSeenTimestamp));
            sender.sendMessage(Text.colorize(statusRaw));
        } else {
            // Fall back to Bukkit's getLastPlayed if database has no record
            long lastPlayedTimestamp = targetPlayer.getLastPlayed();
            if (lastPlayedTimestamp > 0) {
                long durationMillis = System.currentTimeMillis() - lastPlayedTimestamp;
                String durationFormatted = Text.formatTime((int) (durationMillis / 1000));

                String statusRaw = "&eStatus: &cOffline &f(since &6" +
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastPlayedTimestamp)) +
                    " &f- &6" + durationFormatted + "&f)";
                sender.sendMessage(Text.colorize(statusRaw));
            }
        }

        // Show first joined date
        long firstPlayedTimestamp = targetPlayer.getFirstPlayed();
        String firstJoinTemplate = lang.getRaw("seen.first-joined");
        String firstJoinRaw = firstJoinTemplate.replace("{first_join_date}",
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(firstPlayedTimestamp)));
        sender.sendMessage(Text.colorize(firstJoinRaw));

        // Show last location if available
        Location lastLogoutLocation = plugin.getDatabase().getPlayerLocation(targetPlayer.getUniqueId(), LocationType.LOGOUT);
        if (lastLogoutLocation != null) {
            String locationTemplate = lang.getRaw("seen.location");
            String locationRaw = locationTemplate
                    .replace("{world}", lastLogoutLocation.getWorld().getName())
                    .replace("{x}", String.format("%.2f", lastLogoutLocation.getX()))
                    .replace("{y}", String.format("%.2f", lastLogoutLocation.getY()))
                    .replace("{z}", String.format("%.2f", lastLogoutLocation.getZ()));

            if (sender instanceof Player && sender.hasPermission("allium.tp")) {
                Component locationMsg = Text.colorize(locationRaw)
                        .clickEvent(ClickEvent.runCommand("/tppos " + lastLogoutLocation.getX() + " " + lastLogoutLocation.getY() + " " + lastLogoutLocation.getZ() + " " + lastLogoutLocation.getWorld().getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to this location")));
                sender.sendMessage(locationMsg);
            } else {
                sender.sendMessage(Text.colorize(locationRaw));
            }
        }

        // Show last gamemode if available
        try {
            GameMode gameMode = plugin.getDatabase().getPlayerGameMode(targetPlayer.getUniqueId());
            if (gameMode != null) {
                String gamemodeDisplay = gameMode.name().toLowerCase();
                gamemodeDisplay = gamemodeDisplay.substring(0, 1).toUpperCase() + gamemodeDisplay.substring(1);
                sender.sendMessage(Text.colorize(lang.get("seen.gamemode").replace("{gamemode}", gamemodeDisplay)));
            }
        } catch (SQLException e) {
            Text.sendDebugLog(Text.DebugSeverity.WARN, "Failed to get gamemode for " + targetPlayer.getName(), e);
        }

        // Show IP address if sender has permission and it's available in database
        if (sender.hasPermission("allium.seen.ip")) {
            // Note: IP addresses are not stored in the current database schema
            // This would require additional database columns and tracking
            sender.sendMessage(Text.colorize(lang.get("seen.ip-address").replace("{ip}", "Not available")));
        }
    }

    /**
     * Validates if a string is a valid Minecraft username
     *
     * @param username The username to validate
     * @return true if the username is valid
     */
    private boolean isValidMinecraftUsername(String username) {
        // Check if username is null or empty
        if (username == null || username.isEmpty()) {
            return false;
        }

        // Check if username contains invalid characters
        // Minecraft usernames can only contain letters, numbers, and underscores
        // and must be between 3 and 16 characters long
        return username.matches("^[a-zA-Z0-9_]{3,16}$");
    }
}
