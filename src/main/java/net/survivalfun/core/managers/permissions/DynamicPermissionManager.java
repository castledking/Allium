package net.survivalfun.core.managers.permissions;

import net.survivalfun.core.PluginStart;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages dynamic permission registration based on configuration.
 * 
 * Config entries in the "commands" list are automatically prefixed with "allium."
 * For example: "help" becomes "allium.help", "tpa.nodelay" becomes "allium.tpa.nodelay"
 * 
 * If a permission has children defined in plugin.yml, those children are also
 * included in the allium.player permission.
 */
public class DynamicPermissionManager {
    
    private final PluginStart plugin;
    private final PluginManager pluginManager;
    private Permission alliumPlayerPermission;
    private final Set<String> managedPermissionNodes = new HashSet<>();
    private final Set<String> createdPermissions = new HashSet<>();
    
    public DynamicPermissionManager(PluginStart plugin) {
        this.plugin = plugin;
        this.pluginManager = plugin.getServer().getPluginManager();
    }
    
    /**
     * Registers the allium.player permission with children from config.
     * Each config entry is prefixed with "allium." and if the permission
     * exists in plugin.yml with children, those children are also included.
     */
    public void registerDynamicPermissions() {
        // Unregister existing permission if it exists
        if (alliumPlayerPermission != null) {
            pluginManager.removePermission(alliumPlayerPermission);
            alliumPlayerPermission = null;
        }

        resetManagedPermissions();

        // Get commands from config
        List<String> configEntries = plugin.getConfig().getStringList("commands");

        if (configEntries.isEmpty()) {
            Text.sendDebugLog(INFO, "No commands found in config.yml - skipping allium.player permission registration");
            return;
        }

        // Create children permissions map
        Map<String, Boolean> children = new HashMap<>();

        for (String entry : configEntries) {
            if (entry == null) {
                continue;
            }
            String normalizedEntry = entry.trim().toLowerCase();
            if (normalizedEntry.isEmpty()) {
                continue;
            }

            // Auto-prefix with "allium." if not already prefixed
            String permissionNode;
            if (normalizedEntry.startsWith("allium.")) {
                permissionNode = normalizedEntry;
            } else {
                permissionNode = "allium." + normalizedEntry;
            }

            // Add the main permission
            children.put(permissionNode, true);
            managedPermissionNodes.add(permissionNode);

            // Look up the permission in plugin.yml and add its children recursively
            collectChildrenPermissions(permissionNode, children);
        }

        if (children.isEmpty()) {
            Text.sendDebugLog(INFO, "No valid commands found in config.yml - skipping allium.player permission registration");
            return;
        }

        // Ensure all child permissions exist and have default false
        for (String childNode : children.keySet()) {
            ensurePermissionExists(childNode);
        }

        // Create the allium.player permission with dynamic children
        alliumPlayerPermission = new Permission(
            "allium.player",
            "Gives access to basic player commands as defined in config.yml",
            PermissionDefault.FALSE,
            children
        );

        // Register the permission
        try {
            // Remove existing allium.player if present
            Permission existing = pluginManager.getPermission("allium.player");
            if (existing != null) {
                pluginManager.removePermission(existing);
            }
            
            pluginManager.addPermission(alliumPlayerPermission);
            alliumPlayerPermission.recalculatePermissibles();
            Text.sendDebugLog(INFO, "Registered allium.player permission with " + children.size() + " children permissions:");
            for (String child : children.keySet()) {
                Text.sendDebugLog(INFO, "  - " + child);
            }
        } catch (StackOverflowError e) {
            Text.sendDebugLog(ERROR, "StackOverflowError during permission registration - this may indicate circular permission dependencies. Continuing without dynamic permission registration.");
            if (alliumPlayerPermission != null) {
                pluginManager.removePermission(alliumPlayerPermission);
                alliumPlayerPermission = null;
            }
        } catch (IllegalArgumentException e) {
            Text.sendDebugLog(WARN, "Failed to register allium.player permission: " + e.getMessage());
        }
    }

    /**
     * Recursively collects children permissions from plugin.yml definitions.
     * 
     * @param permissionNode The permission node to look up
     * @param children The map to add children to
     */
    private void collectChildrenPermissions(String permissionNode, Map<String, Boolean> children) {
        Permission permission = pluginManager.getPermission(permissionNode);
        if (permission == null) {
            return;
        }

        Map<String, Boolean> permChildren = permission.getChildren();
        if (permChildren == null || permChildren.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Boolean> childEntry : permChildren.entrySet()) {
            String childNode = childEntry.getKey();
            Boolean value = childEntry.getValue();
            
            // Skip if already processed to avoid infinite loops
            if (children.containsKey(childNode)) {
                continue;
            }
            
            // Only add children that are set to true
            if (Boolean.TRUE.equals(value)) {
                children.put(childNode, true);
                managedPermissionNodes.add(childNode);
                
                // Recursively collect children of this child
                collectChildrenPermissions(childNode, children);
            }
        }
    }

    /**
     * Reload dynamic permissions (called when config is reloaded)
     */
    public void reload() {
        Text.sendDebugLog(INFO, "Reloading dynamic permissions...");
        registerDynamicPermissions();
    }

    /**
     * Clean up permissions on plugin disable
     */
    public void cleanup() {
        if (alliumPlayerPermission != null) {
            pluginManager.removePermission(alliumPlayerPermission);
            alliumPlayerPermission = null;
        }
        
        // Remove permissions we created
        for (String permNode : createdPermissions) {
            Permission perm = pluginManager.getPermission(permNode);
            if (perm != null) {
                pluginManager.removePermission(perm);
            }
        }
        createdPermissions.clear();
        
        resetManagedPermissions();
    }

    private void resetManagedPermissions() {
        managedPermissionNodes.clear();
    }

    /**
     * Ensures a permission exists. If it doesn't exist in plugin.yml,
     * creates it with default FALSE.
     */
    private void ensurePermissionExists(String permissionNode) {
        Permission permission = pluginManager.getPermission(permissionNode);
        if (permission != null) {
            return;
        }

        // Permission doesn't exist, create it
        Permission newPermission = new Permission(permissionNode, PermissionDefault.FALSE);
        try {
            pluginManager.addPermission(newPermission);
            createdPermissions.add(permissionNode);
            Text.sendDebugLog(INFO, "Created missing permission: " + permissionNode);
        } catch (IllegalArgumentException e) {
            // Permission might have been added by another thread
            Text.sendDebugLog(WARN, "Could not create permission " + permissionNode + ": " + e.getMessage());
        } catch (StackOverflowError e) {
            Text.sendDebugLog(ERROR, "StackOverflowError while adding permission " + permissionNode);
        }
    }
}
