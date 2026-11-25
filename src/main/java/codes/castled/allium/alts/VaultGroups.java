package codes.castled.allium.alts;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.jetbrains.annotations.Nullable;

/**
 * Vault's group API, isolated so Vault classes load only when Vault is installed: {@link AltService}
 * itself never names a Vault type, so its listener registers on servers without Vault.
 */
public final class VaultGroups {

    private final Permission permission;

    private VaultGroups(Permission permission) {
        this.permission = permission;
    }

    /** The Vault group API, or null without Vault or without a provider that supports groups. */
    @Nullable
    public static VaultGroups find(Server server) {
        if (server.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        try {
            Permission permission = server.getServicesManager().load(Permission.class);
            return permission != null && permission.hasGroupSupport() ? new VaultGroups(permission) : null;
        } catch (LinkageError missing) {
            return null;
        }
    }

    public boolean has(OfflinePlayer player, String group) {
        return permission.playerInGroup(null, player, group);
    }

    boolean add(OfflinePlayer player, String group) {
        return permission.playerAddGroup(null, player, group);
    }

    void remove(OfflinePlayer player, String group) {
        permission.playerRemoveGroup(null, player, group);
    }
}
