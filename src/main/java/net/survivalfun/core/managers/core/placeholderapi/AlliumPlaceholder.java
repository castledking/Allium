package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Master PlaceholderAPI expansion that delegates to specific placeholder handlers
 * This consolidates all %allium_*% placeholders into a single registered expansion
 * while keeping the logic organized in separate classes.
 */
public class AlliumPlaceholder extends PlaceholderExpansion {

    private final PluginStart plugin;
    
    // Delegate placeholder handlers
    private final GeneralPlaceholder generalPlaceholder;
    private final ViolationPlaceholder violationPlaceholder;
    private final BaltopPlaceholder baltopPlaceholder;
    private final SpyPlaceholder spyPlaceholder;
    private final TimePlaceholder timePlaceholder;
    private final TeleportPlaceholder teleportPlaceholder;
    private final LocationPlaceholder locationPlaceholder;
    private final HomePlaceholder homePlaceholder;
    private final CommandPlaceholder commandPlaceholder;
    private final TeamPlaceholder teamPlaceholder;
    private final NicknamePlaceholder nicknamePlaceholder;

    public AlliumPlaceholder(PluginStart plugin) {
        this.plugin = plugin;
        
        // Initialize all delegate placeholder handlers
        this.generalPlaceholder = new GeneralPlaceholder(plugin);
        this.violationPlaceholder = new ViolationPlaceholder();
        this.baltopPlaceholder = new BaltopPlaceholder(plugin);
        this.spyPlaceholder = new SpyPlaceholder(plugin);
        this.timePlaceholder = new TimePlaceholder(plugin);
        // Create empty maps for teleport placeholder (these were previously in PluginStart)
        Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();
        Map<UUID, Long> teleportRequestTimestamps = new ConcurrentHashMap<>();
        Map<UUID, UUID> teleportRequests = new ConcurrentHashMap<>();
        this.teleportPlaceholder = new TeleportPlaceholder(plugin, teleportCooldowns, teleportRequestTimestamps, teleportRequests);
        this.locationPlaceholder = new LocationPlaceholder(plugin);
        this.homePlaceholder = new HomePlaceholder(plugin);
        this.commandPlaceholder = new CommandPlaceholder(plugin);
        this.teamPlaceholder = new TeamPlaceholder(plugin);
        this.nicknamePlaceholder = new NicknamePlaceholder(plugin);
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
    public boolean canRegister() {
        return Bukkit.getPluginManager().getPlugin("Allium") != null
            && Bukkit.getPluginManager().getPlugin("Allium").isEnabled();
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return Arrays.asList(
            "ping", "nickname", "nickname_raw",
            "fly", "god", "reply", "mail_unread", "mail_gifts",
            "spy", "spy_target",
            "world_date", "world_time", "world_time_24h",
            "teleport_cooldown", "teleport_request_timestamp", "teleport_requests", "teleport_toggled",
            "spectator_location", "logout_location", "spawn_location", "back_location",
            "home_max", "home_set",
            "baltop_balance_1", "baltop_balance_commas_1", "baltop_balance_formatted_1", "baltop_balance_fixed_1",
            "baltop_player_1", "baltop_player_stripped_1"
        );
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) return "";
        // Nickname placeholders work for offline players (stored name or default from UUID/name)
        if ("nickname".equals(params) || "nickname_raw".equals(params)) {
            String result = nicknamePlaceholder.onRequest(offlinePlayer, params);
            return (result != null && !result.isEmpty()) ? result : (offlinePlayer.getName() != null ? offlinePlayer.getName() : "");
        }
        if (!offlinePlayer.isOnline()) {
            return "";
        }
        Player p = offlinePlayer.getPlayer();
        if (p == null) return "";
        try {
            return onPlaceholderRequest(p, params);
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI allium error for '" + params + "': " + t.getMessage());
            if (plugin.isDebugMode()) t.printStackTrace();
            return "";
        }
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        // Delegate to appropriate placeholder handler based on the parameter
        String result;
        
        // Diagnostic: %allium_ping% returns "ok" if expansion is working
        if ("ping".equals(params)) {
            return "ok";
        }

        /*
          Placeholders:
          %allium_fly% - Returns "yes" if the player has flight enabled, "no" otherwise.
          %allium_god% - Returns "yes" if the player has god mode enabled, "no" otherwise.
          %allium_reply% - Returns the name of the player that the specified player can reply to.
          %allium_mail_unread% - Returns the number of unread mail messages for a player.
          %allium_mail_gifts% - Returns the number of unclaimed gifts for a player.
        */
        result = generalPlaceholder.onPlaceholderRequest(player, params);
        if (result != null) {
            return result;
        }

        result = teamPlaceholder.onPlaceholderRequest(player, params);
        if (result != null) {
            return result;
        }

        /*
          Placeholders:
          %allium_<violation>% - Returns a string.
        */
        result = violationPlaceholder.onPlaceholderRequest(player, params);
        if (result != null) {
            return result;
        }
        
        /*
          Placeholders:
          %allium_baltop_balance_<rank>% - Returns the balance of the specified rank.
          %allium_baltop_balance_commas_<rank>% - Returns the balance of the specified rank with commas.
          %allium_baltop_balance_formatted_<rank>% - Returns formatted balance of the specified rank.
          %allium_baltop_balance_fixed_<rank>% - Returns fixed balance of the specified rank.
          %allium_baltop_player_<rank>% - Returns the nickname of player of the specified rank.
          %allium_baltop_player_stripped_<rank>% - Returns the raw player name of the specified rank.
        */
        result = baltopPlaceholder.onPlaceholderRequest(player, params);
        if (result != null) {
            return result;
        }
        
        /*
          Placeholders:
          %allium_spy% - Returns the name of the player that the specified player is spying on.
          %allium_spy_target% - Returns the name of the player that the specified player is spying on.
        */
        // Spy placeholders use OfflinePlayer-based onRequest()
        result = spyPlaceholder.onRequest(player, params);
        if (result != null) {
            return result;
        }
        
        /*
          Placeholders:
          %allium_world_date% - Returns the current date.
          %allium_world_time% - Returns the current time in 12-hour format.
          %allium_world_time_24h% - Returns the current time in 24-hour format.
        */
        // Time placeholders use OfflinePlayer-based onRequest()
        result = timePlaceholder.onRequest(player, params);
        if (result != null) {
            return result;
        }
        
        /*
          Placeholders:
          %allium_teleport_cooldown% - Returns the cooldown time for the specified player.
          %allium_teleport_request_timestamp% - Returns the timestamp of the teleport request.
          %allium_teleport_requests% - Returns the list of teleport requests.
          %allium_teleport_toggled% - Returns the toggle status of the specified player.
        */
        // Teleport placeholders use OfflinePlayer-based onRequest()
        result = teleportPlaceholder.onRequest(player, params);
        if (result != null) {
            return result;
        }
        
        /*
          Placeholders:
          %allium_spectator_location% - Returns the spectator location of the specified player.
          %allium_logout_location% - Returns the logout location of the specified player.
          %allium_spawn_location% - Returns the spawn location of the specified player.
          %allium_back_location% - Returns the back location of the specified player.
        */
        // Location placeholders use OfflinePlayer-based onRequest()
        result = locationPlaceholder.onRequest(player, params);
        if (result != null) {
            return result;
        }
        
        /*
          Placeholders:
          %allium_home_max% - Returns the maximum number of homes the player can have.
          %allium_home_<#>_<w|x|y|z>% - Returns the world, x, y, or z coordinate of the specified player.
          %allium_home_<homename>_<w|x|y|z>% - Returns the world, x, y, or z coordinate of the specified player.
          %allium_home_<#>_location% - Returns the exact home location of the specified player.
          %allium_home_<homename>_location% - Returns the exact home location of the specified player.
          %allium_home_set% - Returns the number of homes the player has set.
        */
        // Home placeholders use OfflinePlayer-based onRequest()
        result = homePlaceholder.onRequest(player, params);
        if (result != null) {
            return result;
        }
        
        /*
          Placeholders:
          %allium_command_<command>_canuse% - Returns true if the player can use the specified command.
          %allium_command_<command>_cansee% - Returns true if the player can see the specified command.
        */
        result = commandPlaceholder.onPlaceholderRequest(player, params);
        if (result != null) {
            return result;
        }
        
        /*
          Placeholders:
          %allium_nickname% - Returns the player's formatted nickname.
          %allium_nickname_raw% - Returns the player's raw nickname without formatting.
        */
        result = nicknamePlaceholder.onPlaceholderRequest(player, params);
        if (result != null) {
            return result;
        }
        
        // No handler found - return null so PlaceholderAPI knows we didn't handle it (Essentials pattern)
        return null;
    }
}
