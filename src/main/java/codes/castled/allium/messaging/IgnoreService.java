package codes.castled.allium.messaging;

import codes.castled.allium.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Online players' ignore lists. A list loads at pre-login (off the server thread, like
 * preferences) and is dropped at quit. Until it has loaded, {@link #ignores} answers UNKNOWN, so
 * messages to that player fail closed. Changes apply at once and are written on one bounded
 * storage thread; a failed write is undone.
 */
public final class IgnoreService implements Listener {

    public static final int MAX_ENTRIES = 500;

    private final PluginStart plugin;
    private final codes.castled.allium.messaging.IgnoreRepository repository;
    private final ThreadPoolExecutor writes;
    private final Map<UUID, Set<UUID>> lists = new ConcurrentHashMap<>();
    /** Loaded at pre-login but not yet joined, with the time they loaded; swept after a minute. */
    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();
    private codes.castled.allium.scheduler.TaskHandle sweeper;

    public IgnoreService(PluginStart plugin, codes.castled.allium.messaging.IgnoreRepository repository, ThreadPoolExecutor writes) {
        this.plugin = plugin;
        this.repository = repository;
        this.writes = writes;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            submit(() -> load(id));
        }
        sweeper = plugin.getScheduler().repeatOnServer(() -> {
            long cutoff = System.nanoTime() - TimeUnit.SECONDS.toNanos(60);
            pending.forEach((id, at) -> {
                if (at < cutoff && Bukkit.getPlayer(id) == null && pending.remove(id, at)) {
                    lists.remove(id);
                }
            });
            return true;
        }, 30_000L, 30_000L);
    }

    private void load(UUID id) {
        try {
            lists.put(id, Collections.synchronizedSet(new LinkedHashSet<>(repository.load(id))));
        } catch (SQLException e) {
            lists.remove(id);
            plugin.logger().warning("Could not load the ignore list of " + id + ": " + e.getMessage()
                    + ". Messages to them are refused until it loads (they rejoin).");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            load(event.getUniqueId());
            pending.put(event.getUniqueId(), System.nanoTime());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        if (!lists.containsKey(id)) {
            submit(() -> load(id));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lists.remove(event.getPlayer().getUniqueId());
    }

    /** Whether {@code owner} ignores {@code other}; UNKNOWN while owner's list is not loaded. */
    public MessagePolicy.Ignore ignores(UUID owner, UUID other) {
        Set<UUID> list = lists.get(owner);
        if (list == null) {
            return MessagePolicy.Ignore.UNKNOWN;
        }
        return list.contains(other) ? MessagePolicy.Ignore.YES : MessagePolicy.Ignore.NO;
    }

    /** {@code null} while the list is not loaded. */
    public Set<UUID> list(UUID owner) {
        Set<UUID> list = lists.get(owner);
        if (list == null) {
            return null;
        }
        synchronized (list) {
            return Set.copyOf(list);
        }
    }

    public enum Change {
        DONE,
        UNCHANGED,
        FULL,
        NOT_LOADED
    }

    /** Adds at once; the returned future completes with false if the write failed (and was undone). */
    public Change add(UUID owner, UUID ignored, java.util.function.Consumer<Boolean> saved) {
        Set<UUID> list = lists.get(owner);
        if (list == null) {
            return Change.NOT_LOADED;
        }
        if (list.size() >= MAX_ENTRIES) {
            return Change.FULL;
        }
        if (!list.add(ignored)) {
            return Change.UNCHANGED;
        }
        write(() -> repository.add(owner, ignored), () -> list.remove(ignored), saved);
        return Change.DONE;
    }

    public Change remove(UUID owner, UUID ignored, java.util.function.Consumer<Boolean> saved) {
        Set<UUID> list = lists.get(owner);
        if (list == null) {
            return Change.NOT_LOADED;
        }
        if (!list.remove(ignored)) {
            return Change.UNCHANGED;
        }
        write(() -> repository.remove(owner, ignored), () -> list.add(ignored), saved);
        return Change.DONE;
    }

    @FunctionalInterface
    private interface Write {
        boolean run() throws SQLException;
    }

    private void write(Write write, Runnable undo, java.util.function.Consumer<Boolean> saved) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        if (!submit(() -> {
            try {
                write.run();
                done.complete(true);
            } catch (SQLException e) {
                plugin.logger().warning("Could not save an ignore list change: " + e.getMessage());
                done.complete(false);
            }
        })) {
            done.complete(false);
        }
        done.thenAccept(ok -> plugin.getScheduler().runOnServer(() -> {
            if (!ok) {
                undo.run();
            }
            saved.accept(ok);
        }));
    }

    private boolean submit(Runnable task) {
        try {
            writes.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        if (sweeper != null) {
            sweeper.cancel();
        }
        writes.shutdown();
        try {
            if (!writes.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.logger().warning("Ignore list writes did not finish within 5 s; " + writes.getQueue().size() + " were dropped.");
                writes.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lists.clear();
        pending.clear();
    }
}
