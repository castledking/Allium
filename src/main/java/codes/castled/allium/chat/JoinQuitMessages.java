package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.messaging.VanishSource;
import codes.castled.allium.module.ChatModule;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.text.FormattedText;
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
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public final class JoinQuitMessages implements Listener {
    static final long JOIN_IDENTITY_WAIT_MILLIS = 500L;

    private final PluginStart plugin;
    private final ChatDecorations decorations;
    private final BiFunction<Player, String, String> metaLookup;

    public JoinQuitMessages(PluginStart plugin) {
        this(plugin, null);
    }

    JoinQuitMessages(PluginStart plugin, @Nullable BiFunction<Player, String, String> metaLookup) {
        this.plugin = plugin;
        this.decorations = new ChatDecorations(plugin);
        this.metaLookup = metaLookup != null ? metaLookup : this::meta;
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
                || (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("moderation") instanceof ModerationModule module
                && module.isEnabled() && module.moderation().isVanished(player))) {
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
        ChatService chat = chatService();
        if ("join".equals(kind) && chat != null && !chat.ready(player.getUniqueId())) {
            setMessage.accept(null);
            chat.whenIdentityReady(player, JOIN_IDENTITY_WAIT_MILLIS, snapshot -> {
                if (!player.isOnline()) return;
                FormattedText formatted = buildMessage(player, template, snapshot);
                broadcast(player, formatted);
            });
            return;
        }
        ChatService.IdentitySnapshot snapshot = resolveSnapshot(player, chat);
        FormattedText formatted = buildMessage(player, template, snapshot);
        if (isWorldScoped()) {
            setMessage.accept(null);
            broadcastWorld(player, formatted);
        } else {
            setMessage.accept(formatted.toLegacy());
        }
    }

    @Nullable
    private ChatService chatService() {
        if (plugin.getModuleManager() == null) return null;
        return plugin.getModuleManager().getModule("chat") instanceof ChatModule module && module.isEnabled()
                ? module.chat() : null;
    }

    private ChatService.IdentitySnapshot resolveSnapshot(Player player, @Nullable ChatService chat) {
        if (chat != null) {
            return chat.identitySnapshot(player);
        }
        String display = player.getDisplayName();
        if (display == null || display.isEmpty()) display = player.getName();
        var placeholders = plugin.getPlaceholders();
        String gradient = placeholders == null ? display : placeholders.request(player, "gradientdisplayname");
        if (gradient == null || gradient.isBlank()) gradient = display;
        return new ChatService.IdentitySnapshot(display, gradient, true);
    }

    private FormattedText buildMessage(Player player, String template, ChatService.IdentitySnapshot snapshot) {
        var metadata = PlayerMeta.of(plugin, player);
        String display = snapshot.display();
        if (display == null || display.isEmpty()) display = player.getName();
        String gradient = snapshot.gradient();
        if (gradient == null || gradient.isBlank()) gradient = display;
        String expanded = template.replace("%player%", player.getName()).replace("%name%", display)
                .replace("%prefix%", metadata.prefix()).replace("%suffix%", metadata.suffix())
                .replace("%allium_nickname%", display).replace("%allium_gradientdisplayname%", gradient);
        return TextFormatter.parse(decorations.expand(player, expanded));
    }

    private boolean isWorldScoped() {
        return plugin.getConfig().getString("join-and-quit-messages.broadcast-scope", "server").equalsIgnoreCase("world");
    }

    private void broadcast(Player player, FormattedText formatted) {
        if (isWorldScoped()) {
            broadcastWorld(player, formatted);
            return;
        }
        for (Player viewer : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            UtilitySupport.onOwner(plugin, viewer, () -> {
                if (viewer.isOnline() && viewer.canSee(player) && PlayerNameVisibility.canSeeTarget(viewer, player)) {
                    TextSender.send(viewer, formatted);
                }
            });
        }
    }

    private void broadcastWorld(Player player, FormattedText formatted) {
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
    }

    private String format(Player player, String kind, String fallback) {
        var server = plugin.getServer();
        var services = server == null ? null : server.getServicesManager();
        var provider = services == null ? null : services.load(codes.castled.allium.api.JoinQuitMessageSelection.class);
        var selection = provider == null ? null : provider.selected(player, kind);
        if (selection != null && selection.template() != null) return selection.template();
        String prefix = "allium." + kind + "_message.";
        for (var entry : player.getEffectivePermissions()) {
            if (!entry.getValue() || !entry.getPermission().startsWith(prefix)) continue;
            if (selection != null && entry.getPermission().equals(prefix + "vip")) continue;
            String value = metaLookup.apply(player, entry.getPermission());
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
