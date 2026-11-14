package codes.castled.allium.info;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Optional Vault permission-group lookup isolated behind an inner class so Vault API classes are
 * only loaded when Vault is present on the server.
 */
final class VaultGroupResolver {

    @FunctionalInterface
    interface Lookup {
        Optional<String> primaryGroup(Player player);
    }

    private static final Lookup NO_OP = player -> Optional.empty();
    private final Lookup lookup;

    private VaultGroupResolver(Lookup lookup) {
        this.lookup = lookup;
    }

    static VaultGroupResolver detect() {
        try {
            if (Bukkit.getPluginManager() == null || Bukkit.getPluginManager().getPlugin("Vault") == null) {
                return new VaultGroupResolver(NO_OP);
            }
            return new VaultGroupResolver(VaultBacked::primaryGroup);
        } catch (LinkageError | RuntimeException ignored) {
            return new VaultGroupResolver(NO_OP);
        }
    }

    static VaultGroupResolver disabled() {
        return new VaultGroupResolver(NO_OP);
    }

    Optional<String> primaryGroup(@Nullable Player player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            return lookup.primaryGroup(player);
        } catch (LinkageError | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static final class VaultBacked {
        private VaultBacked() {
        }

        static Optional<String> primaryGroup(Player player) {
            try {
                RegisteredServiceProvider<Permission> registration =
                        Bukkit.getServicesManager().getRegistration(Permission.class);
                if (registration == null) {
                    return Optional.empty();
                }
                Permission provider = registration.getProvider();
                if (provider == null || !provider.hasGroupSupport()) {
                    return Optional.empty();
                }
                String group = provider.getPrimaryGroup(player);
                if (group == null || group.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(group.trim());
            } catch (LinkageError | RuntimeException ignored) {
                return Optional.empty();
            }
        }
    }
}
