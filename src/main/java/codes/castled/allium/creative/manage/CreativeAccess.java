package codes.castled.allium.creative.manage;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reads and writes creative flags for {@code /creativemanager}: through LuckPerms when it runs, else
 * Vault, else read-only (online players' permissions and offline players' op status). Results come
 * back on the server thread. Online players' effective values are what Bukkit itself decides, so op
 * defaults and child nodes of {@code allium.gamemode.creative.bypass} are counted.
 */
public final class CreativeAccess {

    public static final String PERMISSION_MANAGE = "allium.gamemode.creative.manage";

    private final PluginStart plugin;

    public CreativeAccess(PluginStart plugin) {
        this.plugin = plugin;
    }

    /** The backend to use now; LuckPerms and Vault are looked up on each call so a reload is noticed. */
    public PermissionBackend backend() {
        return current(plugin.getServer());
    }

    /**
     * The permissions plugin Allium can write to now: LuckPerms, else Vault with a permissions plugin,
     * else a read-only backend that refuses writes. Used by the creative manager and the powertool
     * {@code grant} action.
     */
    public static PermissionBackend current(org.bukkit.Server server) {
        var plugins = server.getPluginManager();
        var luckPerms = plugins.getPlugin("LuckPerms");
        if (luckPerms != null && luckPerms.isEnabled()) {
            try {
                PermissionBackend found = LuckPermsBackend.find(server);
                if (found != null) {
                    return found;
                }
            } catch (LinkageError unavailable) {
                // An incompatible LuckPerms: try Vault.
            }
        }
        if (plugins.getPlugin("Vault") != null) {
            try {
                PermissionBackend found = VaultBackend.find(server);
                if (found != null) {
                    return found;
                }
            } catch (LinkageError unavailable) {
                // Vault without its API classes.
            }
        }
        return ReadOnly.INSTANCE;
    }

    /** Reads flags on the holder's region and returns the result on the requester's scheduler. */
    public void read(CommandSender viewer, CreativeHolder holder, BiConsumer<Map<CreativeFlag, FlagValue>, Throwable> done) {
        PermissionBackend backend = backend();
        onHolder(viewer, holder, online -> {
            CompletableFuture<Map<CreativeFlag, FlagValue>> values;
            try {
                values = backend.read(holder);
            } catch (RuntimeException failure) {
                values = CompletableFuture.failedFuture(failure);
            }
            values.whenComplete((read, error) -> {
                Throwable cause = unwrap(error);
                if (cause != null) {
                    reply(viewer, () -> done.accept(null, cause));
                } else if (!BukkitServerThread.isFolia()) {
                    reply(viewer, () -> done.accept(resolve(holder, read, backend, onlinePlayer(holder)), null));
                } else if (online == null) {
                    reply(viewer, () -> done.accept(resolve(holder, read, backend, null), null));
                } else {
                    UtilitySupport.onTarget(plugin, viewer, online, () -> {
                        var resolved = resolve(holder, read, backend, online);
                        reply(viewer, () -> done.accept(resolved, null));
                    }, () -> reply(viewer, () -> done.accept(null, disconnected())));
                }
            });
        }, () -> reply(viewer, () -> done.accept(null, disconnected())));
    }

    private Player onlinePlayer(CreativeHolder holder) {
        return holder.isGroup() ? null : plugin.getServer().getPlayer(holder.uuid());
    }

    private void onHolder(CommandSender viewer, CreativeHolder holder, Consumer<Player> action, Runnable offline) {
        Player online = BukkitServerThread.isFolia() ? onlinePlayer(holder) : null;
        if (online == null) action.accept(null);
        else UtilitySupport.onTarget(plugin, viewer, online, () -> action.accept(online), offline);
    }

    private void reply(CommandSender viewer, Runnable action) {
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), viewer, () -> {
            if (!BukkitServerThread.isFolia() || !(viewer instanceof Player player) || player.isOnline()) action.run();
        }, () -> {}, 0L);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static IllegalStateException disconnected() {
        return new IllegalStateException("the player disconnected before their flags could be accessed");
    }

    /** Fills in what Bukkit decides: an online player's own permissions, else an offline player's op status. */
    private Map<CreativeFlag, FlagValue> resolve(CreativeHolder holder, Map<CreativeFlag, FlagValue> read,
                                                PermissionBackend backend, @Nullable Player online) {
        if (holder.isGroup()) {
            return read;
        }
        OfflinePlayer offline = online != null ? online : plugin.getServer().getOfflinePlayer(holder.uuid());
        Map<CreativeFlag, FlagValue> out = new EnumMap<>(CreativeFlag.class);
        for (CreativeFlag flag : CreativeFlag.values()) {
            FlagValue value = read.get(flag);
            boolean unresolved = value.effective() == null || value.source().equals("default");
            Boolean effective = value.effective();
            String source = value.source();
            if (online != null) {
                effective = online.hasPermission(flag.node());
                if (unresolved || !backend.knowsSetNodes()) {
                    source = unresolved && online.isOp() ? "op default" : backend.knowsSetNodes() ? "default" : "online";
                }
            } else if (unresolved) {
                // Every creative-manager node defaults to op.
                effective = offline.isOp();
                source = effective ? "op default" : "default";
            }
            out.put(flag, new FlagValue(value.set(), effective, value.setKnown(), source));
        }
        return out;
    }

    /**
     * Gives ({@code give}) or removes the holder's own flag. {@code done} runs on the server thread with
     * null on success or the error.
     */
    public void write(CommandSender actor, CreativeHolder holder, CreativeFlag flag, boolean give, Consumer<Throwable> done) {
        PermissionBackend backend = backend();
        if (!backend.canWrite()) {
            done.accept(new UnsupportedOperationException("no permissions plugin"));
            return;
        }
        PermissionBackend.Actor who = new PermissionBackend.Actor(
                actor instanceof Player player ? player.getUniqueId() : null, actor.getName());
        onHolder(actor, holder, online -> {
            CompletableFuture<Void> change;
            try {
                change = backend.write(holder, flag, give, who);
            } catch (RuntimeException failure) {
                change = CompletableFuture.failedFuture(failure);
            }
            change.whenComplete((ignored, error) -> {
                Throwable cause = unwrap(error);
                if (cause == null) {
                    plugin.logger().info("Creative manager: " + who.name() + (give ? " gave " : " removed ") + flag.node()
                            + (give ? " to " : " from ") + holder.describe() + " (" + backend.name() + ").");
                }
                reply(actor, () -> done.accept(cause));
            });
        }, () -> reply(actor, () -> done.accept(disconnected())));
    }

    /** Without LuckPerms or Vault: no groups, nothing writable, players read as Bukkit sees them. */
    static final class ReadOnly implements PermissionBackend {
        static final ReadOnly INSTANCE = new ReadOnly();

        @Override
        public String name() {
            return "none";
        }

        @Override
        public boolean canWrite() {
            return false;
        }

        @Override
        public boolean knowsSetNodes() {
            return false;
        }

        @Override
        public List<String> groups() {
            return List.of();
        }

        @Override
        @Nullable
        public String group(String name) {
            return null;
        }

        @Override
        public CompletableFuture<Map<CreativeFlag, FlagValue>> read(CreativeHolder holder) {
            if (holder.isGroup()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("no group " + holder.name()));
            }
            Map<CreativeFlag, FlagValue> out = new EnumMap<>(CreativeFlag.class);
            for (CreativeFlag flag : CreativeFlag.values()) {
                out.put(flag, new FlagValue(null, null, false, "default"));
            }
            return CompletableFuture.completedFuture(out);
        }

        @Override
        public CompletableFuture<Void> writeNode(CreativeHolder holder, String node, boolean give, Actor actor) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("no permissions plugin"));
        }
    }
}
