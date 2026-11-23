package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.messaging.VanishSource;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.text.PlayerMeta;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.function.Consumer;

public final class JoinQuitMessages implements Listener {
    private final PluginStart plugin;
    private final ChatDecorations decorations;

    public JoinQuitMessages(PluginStart plugin) {
        this.plugin = plugin;
        decorations = new ChatDecorations(plugin);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (event.getJoinMessage() != null) render(event.getPlayer(), "join", event::setJoinMessage);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (event.getQuitMessage() != null) render(event.getPlayer(), "quit", event::setQuitMessage);
    }

    private void render(Player player, String kind, Consumer<String> setMessage) {
        if (VanishSource.METADATA.isVanished(player)
                || plugin.getModuleManager().getModule("moderation") instanceof ModerationModule module
                && module.isEnabled() && module.moderation().isVanished(player)) {
            setMessage.accept(null);
            return;
        }
        var config = plugin.getConfig().getConfigurationSection("join-and-quit-messages." + kind);
        if (config == null || !config.getBoolean("enabled", true)) return;
        String fallback = config.getString("format", "&e%name% &7has " + (kind.equals("join") ? "joined" : "left") + " the server");
        String template = format(player, kind, fallback);
        if (template.isEmpty()) {
            setMessage.accept(null);
            return;
        }
        var metadata = PlayerMeta.of(plugin, player);
        String display = player.getDisplayName();
        if (display == null || display.isEmpty()) display = player.getName();
        var placeholders = plugin.getPlaceholders();
        String gradient = placeholders == null ? display : placeholders.request(player, "gradientdisplayname");
        if (gradient == null || gradient.isBlank()) gradient = display;
        String expanded = template.replace("%player%", player.getName()).replace("%name%", display)
                .replace("%prefix%", metadata.prefix()).replace("%suffix%", metadata.suffix())
                .replace("%allium_nickname%", display).replace("%allium_gradientdisplayname%", gradient);
        var formatted = TextFormatter.parse(decorations.expand(player, expanded));
        if (plugin.getConfig().getString("join-and-quit-messages.broadcast-scope", "server").equalsIgnoreCase("world")) {
            setMessage.accept(null);
            if (BukkitServerThread.isFolia()) {
                World originWorld = player.getWorld();
                for (Player viewer : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                    UtilitySupport.onOwner(plugin, viewer, () -> {
                        if (viewer.isOnline() && originWorld != null && originWorld.equals(viewer.getWorld())
                                && viewer.canSee(player) && PlayerNameVisibility.canSeeTarget(viewer, player)) {
                            TextSender.send(viewer, formatted);
                        }
                    });
                }
            } else {
                for (Player viewer : player.getWorld().getPlayers()) {
                    if (viewer.canSee(player)) TextSender.send(viewer, formatted);
                }
            }
        } else {
            setMessage.accept(formatted.toLegacy());
        }
    }

    private String format(Player player, String kind, String fallback) {
        String prefix = "allium." + kind + "_message.";
        for (var entry : player.getEffectivePermissions()) {
            if (!entry.getValue() || !entry.getPermission().startsWith(prefix)) continue;
            String value = meta(player, entry.getPermission());
            if (value != null && !value.isEmpty()) return value;
        }
        return fallback;
    }

    private String meta(Player player, String key) {
        var manager = plugin.getServer().getPluginManager();
        try {
            if (manager.isPluginEnabled("LuckPerms")) {
                String value = LuckPerms.get(player, key);
                if (value != null) return value;
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // Vault is optional too.
        }
        try {
            if (manager.isPluginEnabled("Vault")) return Vault.get(plugin, player, key);
        } catch (RuntimeException | LinkageError unavailable) {
            // Keep the configured default.
        }
        return null;
    }

    private static final class LuckPerms {
        static String get(Player player, String key) {
            var user = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user == null) return null;
            return user.getCachedData().getMetaData(net.luckperms.api.query.QueryOptions.nonContextual()).getMetaValue(key);
        }
    }

    private static final class Vault {
        static String get(PluginStart plugin, Player player, String key) {
            var chat = plugin.getServer().getServicesManager().load(net.milkbowl.vault.chat.Chat.class);
            return chat == null ? null : chat.getPlayerInfoString(player, key, null);
        }
    }
}
