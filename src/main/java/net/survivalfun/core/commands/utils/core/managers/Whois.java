package net.survivalfun.core.commands.utils.core.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.Database.PlayerFlightData;
import net.survivalfun.core.managers.DB.Database.LocationType;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Statistic;
import org.bukkit.GameMode;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

@SuppressWarnings("deprecation") // Suppress deprecation for OfflinePlayer.getLastPlayed() and getFirstPlayed()
public class Whois implements CommandExecutor {

    private final PluginStart plugin;
    private final Lang lang;

    public Whois(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("allium.whois")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "cmd", command.getName());
            return true;
        }

        if (args.length == 0) {
            Text.sendErrorMessage(sender, "command-usage", lang, "cmd", command.getName(), "args", "<player>");
            return true;
        }

        String targetName = args[0];
        
        // Validate the username format first
        if (!isValidMinecraftUsername(targetName)) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "name", targetName);
            return true;
        }
        
        Player targetOnlinePlayer = Bukkit.getPlayerExact(targetName);
        
        // First check if player is online, which is the most reliable method
        if (targetOnlinePlayer != null) {
            // Player is online, proceed with the command
        } else {
            try {
                // Try to get offline player data
                OfflinePlayer targetOfflinePlayer = Bukkit.getOfflinePlayer(targetName);
                
                // Check if this player has ever played on the server
                if (!targetOfflinePlayer.hasPlayedBefore()) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "name", targetName);
                    return true;
                }
            } catch (Exception e) {
                // Handle the case when Mojang API can't find the player
                plugin.getLogger().warning("Failed to get player data for '" + targetName + "': " + e.getMessage());
                Text.sendErrorMessage(sender, "player-not-found", lang, "name", targetName);
                return true;
            }
        }
        
        // At this point, we know the player exists either online or has played before
        OfflinePlayer targetOfflinePlayer = targetOnlinePlayer != null ? targetOnlinePlayer : Bukkit.getOfflinePlayer(targetName);

        OfflinePlayer effectiveTarget = (targetOnlinePlayer != null) ? targetOnlinePlayer : targetOfflinePlayer;
        UUID targetUUID = effectiveTarget.getUniqueId();

        String headerTemplate = lang.getRaw("whois.header");
        String headerRaw = headerTemplate.replace("{player_name}", effectiveTarget.getName());
        sender.sendMessage(Text.colorize(headerRaw));

        // Create clickable UUID component with copy-to-clipboard functionality
        String uuidTemplate = lang.getRaw("whois.uuid");
        String uuid = effectiveTarget.getUniqueId().toString();
        String uuidRaw = uuidTemplate.replace("{uuid}", uuid);
        
        if (sender instanceof Player) {
            Component uuidComponent = Component.text()
                .append(Text.colorize(uuidRaw))
                .clickEvent(ClickEvent.copyToClipboard(uuid))
                .hoverEvent(HoverEvent.showText(Component.text("§aClick to copy UUID to clipboard")))
                .build();
            ((Player) sender).sendMessage(uuidComponent);
        } else {
            sender.sendMessage(Text.colorize(uuidRaw));
        }

        String prefixStr = "N/A";
        if (plugin.getVaultChat() != null && targetOnlinePlayer != null) {
            prefixStr = plugin.getVaultChat().getPlayerPrefix(targetOnlinePlayer);
        } else if (plugin.getVaultChat() != null && effectiveTarget instanceof Player) {
            String prefix = plugin.getVaultChat() != null ? plugin.getVaultChat().getPlayerPrefix((String) null, effectiveTarget.getName()) : lang.getRaw("whois.not_available_offline");
            if (prefix == null || prefix.isEmpty()) prefix = lang.getRaw("whois.prefix_none");
            prefixStr = prefix;
        }
        if (prefixStr == null || prefixStr.isEmpty()) prefixStr = "N/A";
        String prefixTemplate = lang.getRaw("whois.prefix");
        String prefixRaw = prefixTemplate.replace("{prefix}", prefixStr);
        sender.sendMessage(Text.colorize(prefixRaw));

        long firstPlayedTimestamp = effectiveTarget.getFirstPlayed();
        String firstJoinTemplate = lang.getRaw("whois.first-joined");
        String firstJoinRaw = firstJoinTemplate.replace("{first_join_date}", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(firstPlayedTimestamp)));
        sender.sendMessage(Text.colorize(firstJoinRaw));

        long playtimeTicks = effectiveTarget.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long playtimeSeconds = playtimeTicks / 20;
        String totalPlaytimeTemplate = lang.getRaw("whois.playtime");
        String totalPlaytimeRaw = totalPlaytimeTemplate.replace("{playtime}", Text.formatTime((int) playtimeSeconds));
        sender.sendMessage(Text.colorize(totalPlaytimeRaw));

        String statusKey;
        long durationMillis;
        String durationFormatted;
        if (targetOnlinePlayer != null) {
            statusKey = "whois.status-online";
            Map<UUID, Long> loginTimes = plugin.getPlayerLoginTimes();
            if (loginTimes != null && loginTimes.containsKey(targetUUID)) {
                long loginTime = loginTimes.get(targetUUID);
                durationMillis = System.currentTimeMillis() - loginTime;
                durationFormatted = Text.formatTime((int) (durationMillis / 1000));
            } else {
                // This case should ideally not be reached if PlayerConnectionListener is working correctly
                durationFormatted = "Unknown";
            }
            
            String onlineStatusTemplate = lang.getRaw(statusKey);
            String onlineStatusRaw = onlineStatusTemplate.replace("{duration}", durationFormatted);
            sender.sendMessage(Text.colorize(onlineStatusRaw));
        } else {
            statusKey = "whois.status-offline";
            
            // Try to get last seen date from database first
            java.sql.Timestamp lastSeenTimestamp = null;
            Database.PlayerLastSeenData lastSeenData = plugin.getDatabase().getPlayerLastSeen(targetUUID);
            if (lastSeenData != null && lastSeenData.lastSeenDate() != null) {
                lastSeenTimestamp = lastSeenData.lastSeenDate();
                durationMillis = System.currentTimeMillis() - lastSeenTimestamp.getTime();
            } else {
                // Fall back to Bukkit's getLastPlayed if database has no record
                long lastPlayedTimestamp = effectiveTarget.getLastPlayed();
                durationMillis = System.currentTimeMillis() - lastPlayedTimestamp;
            }
            
            durationFormatted = Text.formatTime((int) (durationMillis / 1000));
            
            // Format the last seen date
            String lastSeenDateFormatted = "Unknown";
            if (lastSeenTimestamp != null) {
                lastSeenDateFormatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(lastSeenTimestamp);
            } else if (effectiveTarget.getLastPlayed() > 0) {
                lastSeenDateFormatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(effectiveTarget.getLastPlayed()));
            }
            
            String onlineStatusTemplate = lang.getRaw(statusKey);
            String onlineStatusRaw = onlineStatusTemplate
                .replace("{duration_offline}", durationFormatted)
                .replace("{last_seen_date}", lastSeenDateFormatted);
            sender.sendMessage(Text.colorize(onlineStatusRaw));
        }

        boolean allowFlight = false;
        if (targetOnlinePlayer != null) {
            allowFlight = targetOnlinePlayer.getAllowFlight();
        } else {
            PlayerFlightData flightData = plugin.getDatabase().getPlayerFlightStatus(targetUUID);
            if (flightData != null) {
                allowFlight = flightData.allowFlight();
            }
        }
        String flyStatusText = allowFlight ? 
            lang.getStyle("styles.state.true", "&a&n") + "Enabled" : 
            lang.getStyle("styles.state.false", "&c&n") + "Disabled";
        String flyModeTemplate = lang.getRaw("whois.fly-mode");
        String flyModeRaw = flyModeTemplate.replace("{fly_status}", flyStatusText);
        sender.sendMessage(Text.colorize(flyModeRaw));

        boolean isGodMode = false;
        if (targetOnlinePlayer != null) {
            isGodMode = targetOnlinePlayer.isInvulnerable();
        } else {
            isGodMode = false;
        }
        String godStatusText = isGodMode ? 
            lang.getStyle("styles.state.true", "&a&n") + "Enabled" : 
            lang.getStyle("styles.state.false", "&c&n") + "Disabled";
        String godModeTemplate = lang.getRaw("whois.god-mode");
        String godModeRaw = godModeTemplate.replace("{god_status}", godStatusText);
        sender.sendMessage(Text.colorize(godModeRaw));

        GameMode gameMode = null;
        if (targetOnlinePlayer != null) {
            gameMode = targetOnlinePlayer.getGameMode();
        } else {
            try {
                gameMode = plugin.getDatabase().getPlayerGameMode(targetOfflinePlayer.getUniqueId());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to get gamemode for " + targetName, e);
            }
        }
        if (gameMode != null) {
            String gamemode = gameMode.name().toLowerCase();
            gamemode = gamemode.substring(0, 1).toUpperCase() + gamemode.substring(1);
            sender.sendMessage(Text.parseColors(lang.get("whois.gamemode").replace("{gamemode}", gamemode)));
        }

        if (targetOnlinePlayer != null) {
            Location loc = targetOnlinePlayer.getLocation();
            String worldPosMsgTemplate = lang.getRaw("whois.world-position");
            String worldPosMsgRaw = worldPosMsgTemplate
                    .replace("{world}", loc.getWorld().getName())
                    .replace("{x}", String.format("%.2f", loc.getX()))
                    .replace("{y}", String.format("%.2f", loc.getY()))
                    .replace("{z}", String.format("%.2f", loc.getZ()));
            Component worldPosMsg = Text.colorize(worldPosMsgRaw)
                    .clickEvent(ClickEvent.runCommand("/tppos " + loc.getX() + " " + loc.getY() + " " + loc.getZ() + " " + loc.getWorld().getName()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to this location")));
            sender.sendMessage(worldPosMsg);
        } else {
            // For offline players, try to get their last logout location from database
            Location lastLogoutLocation = plugin.getDatabase().getPlayerLocation(targetUUID, LocationType.LOGOUT);
            if (lastLogoutLocation != null) {
                String worldPosMsgTemplate = lang.getRaw("whois.world-position-offline");
                if (worldPosMsgTemplate.equals("whois.world-position-offline")) {
                    // Fallback if lang key doesn't exist
                    worldPosMsgTemplate = "&eLocation: &f{world} ({x}, {y}, {z}) &7(offline)";
                }
                String worldPosMsgRaw = worldPosMsgTemplate
                        .replace("{world}", lastLogoutLocation.getWorld().getName())
                        .replace("{x}", String.format("%.2f", lastLogoutLocation.getX()))
                        .replace("{y}", String.format("%.2f", lastLogoutLocation.getY()))
                        .replace("{z}", String.format("%.2f", lastLogoutLocation.getZ()));
                
                if (sender instanceof Player && sender.hasPermission("allium.teleport")) {
                    Component worldPosMsg = Text.colorize(worldPosMsgRaw)
                            .clickEvent(ClickEvent.runCommand("/tppos " + lastLogoutLocation.getX() + " " + lastLogoutLocation.getY() + " " + lastLogoutLocation.getZ() + " " + lastLogoutLocation.getWorld().getName()))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to this location")));
                    sender.sendMessage(worldPosMsg);
                } else {
                    sender.sendMessage(Text.colorize(worldPosMsgRaw));
                }
            } else {
                // No logout location found in database
                String unknownLocationTemplate = "&eLocation: &fUnknown (player offline)";
                sender.sendMessage(Text.colorize(unknownLocationTemplate));
            }
        }

        Location deathLoc = effectiveTarget.getLastDeathLocation();
        if (deathLoc != null) {
            String deathLocMsgTemplate = lang.getRaw("whois.death-location");
            String deathLocMsgRaw = deathLocMsgTemplate
                    .replace("{world}", deathLoc.getWorld().getName())
                    .replace("{x}", String.format("%.2f", deathLoc.getX()))
                    .replace("{y}", String.format("%.2f", deathLoc.getY()))
                    .replace("{z}", String.format("%.2f", deathLoc.getZ()));
            Component deathLocMsg = Text.colorize(deathLocMsgRaw)
                    .clickEvent(ClickEvent.runCommand("/tppos " + deathLoc.getX() + " " + deathLoc.getY() + " " + deathLoc.getZ() + " " + deathLoc.getWorld().getName()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to last death location")));
            sender.sendMessage(deathLocMsg);
        } else {
            sender.sendMessage(Text.colorize(lang.getRaw("whois.no-death-location")));
        }

        String actionsHeaderRaw = lang.getRaw("whois.actions-header");
        sender.sendMessage(Text.colorize(actionsHeaderRaw));

        String coreProtectCmd = "/co l u:" + effectiveTarget.getName() + " b:diamond_ore t:4w r:#global";
        Component coreProtectMsg = Text.colorize(lang.getRaw("whois.action-coreprotect-diamonds"))
                .clickEvent(ClickEvent.runCommand(coreProtectCmd))
                .hoverEvent(HoverEvent.showText(Component.text("Click to run CoreProtect lookup for mined diamonds.")));
        sender.sendMessage(coreProtectMsg);

        String liteBansCmd = "/history " + effectiveTarget.getName();
        String liteBansHistoryTemplate = lang.getRaw("whois.action-ban-history");
        String liteBansHistoryText = liteBansHistoryTemplate.replace("{player_name}", effectiveTarget.getName());
        Component liteBansHistory = Text.colorize(liteBansHistoryText)
                .clickEvent(ClickEvent.runCommand(liteBansCmd));
        // Use a default hover text since the key might not exist in lang.yml
        String liteBansHoverText = "Click to view ban history for " + effectiveTarget.getName();
        liteBansHistory = liteBansHistory.hoverEvent(HoverEvent.showText(Component.text(liteBansHoverText)));
        sender.sendMessage(liteBansHistory);

        String chatSentryCmd = "/kcs lookup player:" + effectiveTarget.getName();
        Component chatSentryMsg = Text.colorize(lang.getRaw("whois.action-chat-sentry"))
                .clickEvent(ClickEvent.runCommand(chatSentryCmd))
                .hoverEvent(HoverEvent.showText(Component.text("Click to lookup chat violations (ChatSentry).")));
        sender.sendMessage(chatSentryMsg);

        String footerRaw = lang.getRaw("whois.footer");
        sender.sendMessage(Text.colorize(footerRaw));

        return true;
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
