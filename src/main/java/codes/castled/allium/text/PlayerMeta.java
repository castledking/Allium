package codes.castled.allium.text;

import codes.castled.allium.PluginStart;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A player's primary permission group, prefix and suffix for text files: from LuckPerms when it is
 * installed, else from Vault's permission and chat services, else empty. The optional APIs load only
 * inside the nested classes. Server thread.
 */
public final class PlayerMeta {

    public record Value(String group, String prefix, String suffix) {
        static final Value EMPTY = new Value("", "", "");
    }

    private PlayerMeta() {
    }

    public static String primaryGroup(PluginStart plugin, Player player) {
        return of(plugin, player).group();
    }

    public static Value of(PluginStart plugin, Player player) {
        PluginManager plugins = plugin.getServer() == null ? null : plugin.getServer().getPluginManager();
        if (plugins == null) {
            return Value.EMPTY;
        }
        try {
            if (plugins.isPluginEnabled("LuckPerms")) {
                Value value = LuckPerms.read(player);
                if (value != null) {
                    return value;
                }
            }
        } catch (LinkageError | RuntimeException unavailable) {
            // fall back to Vault
        }
        try {
            if (plugins.isPluginEnabled("Vault")) {
                return Vault.read(plugin, player);
            }
        } catch (LinkageError | RuntimeException unavailable) {
            // no group, prefix or suffix
        }
        return Value.EMPTY;
    }

    private static final class LuckPerms {
        @Nullable
        static Value read(Player player) {
            net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get().getUserManager()
                    .getUser(player.getUniqueId());
            if (user == null) {
                return null;
            }
            var meta = user.getCachedData().getMetaData();
            return new Value(Objects.toString(user.getPrimaryGroup(), ""), Objects.toString(meta.getPrefix(), ""),
                    Objects.toString(meta.getSuffix(), ""));
        }
    }

    private static final class Vault {
        static Value read(PluginStart plugin, Player player) {
            var services = plugin.getServer().getServicesManager();
            var chat = services.load(net.milkbowl.vault.chat.Chat.class);
            var permission = services.load(net.milkbowl.vault.permission.Permission.class);
            String group = permission != null && permission.hasGroupSupport() ? Objects.toString(permission.getPrimaryGroup(player), "") : "";
            return new Value(group, chat == null ? "" : Objects.toString(chat.getPlayerPrefix(player), ""),
                    chat == null ? "" : Objects.toString(chat.getPlayerSuffix(player), ""));
        }
    }
}
