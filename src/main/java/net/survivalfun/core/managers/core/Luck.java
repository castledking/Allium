package net.survivalfun.core.managers.core;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class Luck {
    private final JavaPlugin plugin;
    private LuckPerms luckPermsApi;
    private boolean isLuckPermsAvailable = false;

    public Luck(JavaPlugin plugin) {
        this.plugin = plugin;
        setupLuckPerms();
    }

    /**
     * Set up the LuckPerms API if the plugin is available
     */
    private void setupLuckPerms() {
        try {
            if (plugin.getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                luckPermsApi = LuckPermsProvider.get();
                isLuckPermsAvailable = true;
                plugin.getLogger().info("Successfully hooked into LuckPerms!");
            } else {
                plugin.getLogger().warning("LuckPerms not found. Group-based command blocking will be disabled.");
                isLuckPermsAvailable = false;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to hook into LuckPerms", e);
            isLuckPermsAvailable = false;
        }
    }

    /**
     * Get the LuckPerms UserManager, if available
     * @return The UserManager or null if LuckPerms is not available
     */
    public UserManager getUserManager() {
        if (!isLuckPermsAvailable || luckPermsApi == null) {
            return null;
        }
        return luckPermsApi.getUserManager();
    }

    /**
     * Get the raw LuckPerms API instance
     * @return The LuckPerms API instance or null if not available
     */
    public LuckPerms getLuckPermsApi() {
        return luckPermsApi;
    }


    /**
     * Check if LuckPerms is available
     * @return true if LuckPerms is available
     */
    public boolean isLuckPermsAvailable() {
        return isLuckPermsAvailable;
    }

    /**
     * Get all groups a player belongs to
     * @param player The player to check
     * @return A list of group names the player belongs to
     */
    public List<String> getPlayerGroups(Player player) {
        List<String> groups = new ArrayList<>();

        if (!isLuckPermsAvailable) {
            return groups;
        }

        try {
            UUID uuid = player.getUniqueId();
            User user = luckPermsApi.getUserManager().getUser(uuid);

            if (user != null) {
                // Add the primary group first
                String primaryGroup = user.getPrimaryGroup();
                groups.add(primaryGroup);

                // Add all other groups the player is in
                user.getInheritedGroups(user.getQueryOptions())
                        .stream()
                        .map(Group::getName)
                        .filter(name -> !name.equals(primaryGroup)) // Avoid duplicating primary group
                        .forEach(groups::add);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting groups for player " + player.getName(), e);
        }

        return groups;
    }

    /**
     * Get the primary group of a player
     * @param player The player to check
     * @return The primary group name, or "default" if not found
     */
    public String getPrimaryGroup(Player player) {
        if (!isLuckPermsAvailable) {
            return "default";
        }

        try {
            UUID uuid = player.getUniqueId();
            User user = luckPermsApi.getUserManager().getUser(uuid);

            if (user != null) {
                return user.getPrimaryGroup();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting primary group for player " + player.getName(), e);
        }

        return "default";
    }

    /**
     * Check if a player is in a specific group
     * @param player The player to check
     * @param group The group name to check for
     * @return true if the player is in the group
     */
    public boolean isPlayerInGroup(Player player, String group) {
        if (!isLuckPermsAvailable) {
            return false;
        }

        try {
            UUID uuid = player.getUniqueId();
            User user = luckPermsApi.getUserManager().getUser(uuid);

            if (user != null) {
                // Check if the player is directly in the group
                if (user.getPrimaryGroup().equalsIgnoreCase(group)) {
                    return true;
                }

                // Check if the player inherits the group
                Optional<Group> groupObj = Optional.ofNullable(luckPermsApi.getGroupManager().getGroup(group));
                if (groupObj.isPresent()) {
                    return user.getInheritedGroups(user.getQueryOptions())
                            .contains(groupObj.get());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error checking if player " + player.getName() + " is in group " + group, e);
        }

        return false;
    }
}
