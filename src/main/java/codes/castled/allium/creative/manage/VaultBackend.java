package codes.castled.allium.creative.manage;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Creative flags through Vault's permission API, for servers without LuckPerms. Isolated so Vault
 * classes load only when Vault is installed. Vault reports only whether a flag applies, not whether
 * the holder sets it itself, and its calls run on the server thread.
 */
final class VaultBackend implements PermissionBackend {

    private final Server server;
    private final Permission permission;

    private VaultBackend(Server server, Permission permission) {
        this.server = server;
        this.permission = permission;
    }

    /** The backend, or null without Vault or without a permission provider behind it. */
    @Nullable
    static VaultBackend find(Server server) {
        if (server.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        try {
            Permission permission = server.getServicesManager().load(Permission.class);
            return permission == null || !permission.isEnabled() ? null : new VaultBackend(server, permission);
        } catch (LinkageError missing) {
            return null;
        }
    }

    @Override
    public String name() {
        return "Vault (" + permission.getName() + ")";
    }

    @Override
    public boolean canWrite() {
        return true;
    }

    @Override
    public boolean knowsSetNodes() {
        return false;
    }

    @Override
    public List<String> groups() {
        if (!permission.hasGroupSupport()) {
            return List.of();
        }
        String[] groups = permission.getGroups();
        return groups == null ? List.of() : Arrays.stream(groups).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    @Override
    @Nullable
    public String group(String name) {
        return groups().stream().filter(group -> group.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    @Override
    public CompletableFuture<Map<CreativeFlag, FlagValue>> read(CreativeHolder holder) {
        try {
            Map<CreativeFlag, FlagValue> out = new EnumMap<>(CreativeFlag.class);
            String group = holder.isGroup() ? group(holder.name()) : null;
            if (holder.isGroup() && group == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("no group " + holder.name()));
            }
            OfflinePlayer player = holder.isGroup() ? null : server.getOfflinePlayer(holder.uuid());
            for (CreativeFlag flag : CreativeFlag.values()) {
                boolean has = holder.isGroup()
                        ? permission.groupHas((String) null, group, flag.node())
                        : permission.playerHas(null, player, flag.node());
                out.put(flag, new FlagValue(null, has, false, ""));
            }
            return CompletableFuture.completedFuture(out);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletableFuture<Void> writeNode(CreativeHolder holder, String node, boolean give, Actor actor) {
        try {
            boolean changed;
            if (holder.isGroup()) {
                String group = group(holder.name());
                if (group == null) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("no group " + holder.name()));
                }
                changed = give ? permission.groupAdd((String) null, group, node)
                        : permission.groupRemove((String) null, group, node);
            } else {
                OfflinePlayer player = server.getOfflinePlayer(holder.uuid());
                changed = give ? permission.playerAdd(null, player, node)
                        : permission.playerRemove(null, player, node);
            }
            return changed ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IllegalStateException(name() + " refused the change"));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
