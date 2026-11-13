package codes.castled.allium.naming;

import codes.castled.allium.PluginStart;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Shared known-player directory for mail and future payments. Startup loads at most the cache
 * capacity from storage; joins record names asynchronously. Completion reads the bounded index
 * and actual online players only, filtering each online target for the requesting sender.
 * No completion schedules work or reads storage, disk, network, or the offline-player list.
 */
public final class PlayerNameService implements Listener {
    public static final int COMPLETION_LIMIT = 50;

    private final PluginStart plugin;
    private final PlayerNameRepository repository;
    private final NameCache cache;
    private final Executor io;
    private volatile boolean enabled;

    public PlayerNameService(PluginStart plugin, PlayerNameRepository repository, int capacity, Executor io) {
        this.plugin = plugin;
        this.repository = repository;
        this.cache = new NameCache(capacity);
        this.io = io;
    }

    public void enable() throws SQLException {
        repository.migrate();
        enabled = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Reloads must also index players who are already online. Visibility is checked per request.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            record(player);
        }
        io.execute(() -> {
            try {
                for (NameCache.Entry e : repository.recent(cache.capacity())) {
                    if (!enabled) {
                        return;
                    }
                    cache.put(e.uuid(), e.name(), e.lastSeen());
                }
                plugin.logger().info("Player name index: " + cache.size() + " known names loaded for completion (capacity "
                        + cache.capacity() + ").");
            } catch (SQLException | RuntimeException e) {
                plugin.logger().warning("Player name index could not be loaded: " + e.getMessage());
            }
        });
    }

    public void disable() {
        enabled = false;
        HandlerList.unregisterAll(this);
        cache.clear();
    }

    public NameCache cache() {
        return cache;
    }

    /** Must run on the server thread: online visibility is evaluated for this sender and request. */
    public List<String> complete(CommandSender sender, String prefix, int limit) {
        int boundedLimit = Math.min(Math.max(0, limit), COMPLETION_LIMIT);
        if (boundedLimit == 0) {
            return List.of();
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        Map<UUID, OnlineName> onlineById = new HashMap<>();
        Map<String, Boolean> visibleByName = new HashMap<>();
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        // Include online targets even when the historical-name cache is smaller than the player count.
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            String name = target.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean visible = PlayerNameVisibility.canSee(sender, target);
            onlineById.put(target.getUniqueId(), new OnlineName(name, visible));
            visibleByName.put(lower, visible);
            if (visible && lower.startsWith(p) && suggestions.size() < boundedLimit) {
                suggestions.add(name);
            }
        }
        for (String name : cache.complete(prefix, boundedLimit, entry -> {
            OnlineName online = onlineById.get(entry.uuid());
            if (online != null) {
                // Suppress stale aliases, including aliases of currently hidden players.
                return online.visible() && online.name().equalsIgnoreCase(entry.name());
            }
            return visibleByName.getOrDefault(entry.name().toLowerCase(Locale.ROOT), true);
        })) {
            if (suggestions.size() == boundedLimit) {
                break;
            }
            if (suggestions.stream().noneMatch(name::equalsIgnoreCase)) {
                suggestions.add(name);
            }
        }
        return List.copyOf(suggestions);
    }

    private record OnlineName(String name, boolean visible) {
    }

    /** Exact online lookup first; otherwise every execution reads authoritative shared storage. */
    public CompletableFuture<Optional<NameCache.Entry>> resolve(String name) {
        // A live name takes precedence over an old cached owner after a rename/reuse.
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            NameCache.Entry entry = new NameCache.Entry(online.getUniqueId(), online.getName(), System.currentTimeMillis());
            cache.put(entry.uuid(), entry.name(), entry.lastSeen());
            return CompletableFuture.completedFuture(Optional.of(entry));
        }
        NameCache.Entry observed = cache.byName(name).orElse(null);
        CompletableFuture<Optional<NameCache.Entry>> result = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    Optional<NameCache.Entry> found = repository.byName(name);
                    // Suggestions never authorize a transfer: storage owns offline identity.
                    if (observed != null && (found.isEmpty() || !found.get().uuid().equals(observed.uuid()))) {
                        cache.invalidate(observed);
                    }
                    found.ifPresent(e -> cache.put(e.uuid(), e.name(), e.lastSeen()));
                    result.complete(found);
                } catch (SQLException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer());
    }

    private void record(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        cache.put(id, name, now);
        io.execute(() -> {
            try {
                repository.record(id, name, now);
            } catch (SQLException e) {
                plugin.logger().warning("Could not record the name of " + name + ": " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cache.put(event.getPlayer().getUniqueId(), event.getPlayer().getName(), System.currentTimeMillis());
    }
}
