package codes.castled.allium.player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.flight.FlightTimeRepository;
import codes.castled.allium.message.Messages;
import codes.castled.allium.placeholder.PlaceholderFormats;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Timed flight, as legacy Allium's tfly: a player without {@code allium.fly} who has banked
 * seconds turns flight on with {@code /fly}. The time runs down from then until they turn it off,
 * or, with {@code timed-flight.count-only-while-flying}, only while they are in the air; either
 * way only in survival or adventure. Warnings come at 60, 30 and 10 seconds and each of the last 5; at 0
 * flight ends with slow falling if they are in the air ({@link FlightRestoration}). Staff set,
 * add, take or reset anyone's time. The bank lives in {@code allium_flight_time}: spent time is
 * written as a relative change every {@value #SAVE_MILLIS} ms and on quit, so time given on
 * another server sharing the database still counts. Timed flight is never switched back on at
 * join; the player turns it on again, as in legacy Allium.
 */
public final class TimedFlight implements Listener, AutoCloseable {

    public static final String PERMISSION = "allium.tfly";
    public static final String ONLY_WHILE_FLYING = "timed-flight.count-only-while-flying";
    static final long SAVE_MILLIS = 30_000;
    static final Set<Long> WARNINGS = Set.of(60L, 30L, 10L, 5L, 4L, 3L, 2L, 1L);

    public enum Toggle { ON, OFF, NONE, LOADING }

    public enum Change { SET, ADD, TAKE, RESET }

    static final class Session {
        final Player player;
        volatile long seconds;
        volatile boolean loaded;
        volatile boolean active;
        long unsaved;

        Session(Player player) {
            this.player = player;
        }
    }

    private final PluginStart plugin;
    private final FlightTimeRepository repository;
    private final ThreadPoolExecutor io;
    private final FlightRestoration flights;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private TaskHandle tick = TaskHandle.NONE;
    private TaskHandle save = TaskHandle.NONE;
    private volatile boolean closed;
    private volatile boolean onlyWhileFlying;

    public TimedFlight(PluginStart plugin, FlightTimeRepository repository, ThreadPoolExecutor io, FlightRestoration flights) {
        this.plugin = plugin;
        this.repository = repository;
        this.io = io;
        this.flights = flights;
    }

    public void enable() {
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            join(p);
        }
        tick = plugin.getScheduler().repeatOnServer(() -> {
            if (closed) return false;
            tick();
            return true;
        }, 1_000, 1_000);
        save = plugin.getScheduler().repeatOnServer(() -> {
            if (closed) return false;
            sessions.values().forEach(this::flush);
            return true;
        }, SAVE_MILLIS, SAVE_MILLIS);
    }

    @Override
    public void close() {
        if (closed) return;
        sessions.values().forEach(this::flush);
        closed = true;
        tick.cancel();
        save.cancel();
        HandlerList.unregisterAll(this);
        for (Session s : sessions.values()) {
            if (s.active && s.player.isOnline()) {
                stopFlying(s);
            }
        }
        sessions.clear();
        io.shutdown();
        try {
            if (!io.awaitTermination(3, TimeUnit.SECONDS)) {
                plugin.logger().warning("Timed flight shutdown timed out; spent time may not all be saved.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Re-reads {@value #ONLY_WHILE_FLYING} (startup and {@code /allium reload}). */
    public void reload() {
        onlyWhileFlying = plugin.getConfig().getBoolean(ONLY_WHILE_FLYING, false);
    }

    /** Whether the time runs only while a player is in the air, rather than from {@code /fly}. */
    public boolean countsOnlyWhileFlying() {
        return onlyWhileFlying;
    }

    /**
     * Whether an online player's timed flight is spending time right now: it is on, they are in
     * survival or adventure and, with {@value #ONLY_WHILE_FLYING}, in the air.
     */
    public boolean running(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s != null && s.player == p && s.active && spends(p);
    }

    private boolean spends(Player p) {
        return p.getAllowFlight() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && (!onlyWhileFlying || p.isFlying());
    }

    /** Banked seconds of an online player; 0 while loading or for anyone else. */
    public long seconds(UUID player) {
        Session s = sessions.get(player);
        return s == null ? 0 : s.seconds;
    }

    /** Whether an online player's flight is timed flight right now. */
    public boolean active(UUID player) {
        Session s = sessions.get(player);
        return s != null && s.active;
    }

    /** {@code /fly} for a player without {@code allium.fly}: turns timed flight on or off. */
    public Toggle toggle(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.player != player || !s.loaded) {
            return Toggle.LOADING;
        }
        if (s.active) {
            stopFlying(s);
            flush(s);
            return Toggle.OFF;
        }
        if (s.seconds <= 0) {
            return Toggle.NONE;
        }
        s.active = true;
        player.setAllowFlight(true);
        player.setFallDistance(0);
        flights.changed(player);
        return Toggle.ON;
    }

    /**
     * Staff change of any player's bank, online or not; {@code done} gets the new total on the
     * server thread, or empty when storage failed.
     */
    public void change(UUID player, Change change, long amount, Consumer<OptionalLong> done) {
        Session s = sessions.get(player);
        long unsaved = 0;
        if (s != null) {
            unsaved = s.unsaved;
            s.unsaved = 0;
        }
        final long spent = unsaved;
        try {
            io.execute(() -> {
                long next;
                try {
                    next = switch (change) {
                        case SET -> repository.set(player, amount);
                        case RESET -> repository.set(player, 0);
                        // Unsaved spent time rides along, so the bank ends where the player sees it.
                        case ADD -> repository.add(player, amount - spent);
                        case TAKE -> repository.add(player, -amount - spent);
                    };
                } catch (SQLException | RuntimeException failed) {
                    plugin.logger().warning("Timed flight storage failed for " + player + ": " + failed.getMessage());
                    server(() -> {
                        if (s != null) s.unsaved += spent;
                        done.accept(OptionalLong.empty());
                    });
                    return;
                }
                server(() -> {
                    Session now = sessions.get(player);
                    if (now != null) {
                        now.seconds = next;
                        now.loaded = true;
                        if (next == 0 && now.active) {
                            stopFlying(now);
                            send(now.player, Messages.TFLY_EXPIRED);
                        }
                    }
                    done.accept(OptionalLong.of(next));
                });
            });
        } catch (RejectedExecutionException busy) {
            if (s != null) s.unsaved += spent;
            done.accept(OptionalLong.empty());
        }
    }

    /** One second: time runs down for each timed flier whose time is running ({@link #running}). */
    void tick() {
        for (Session s : sessions.values()) {
            if (!s.active) continue;
            Player p = s.player;
            if (!p.isOnline()) continue;
            if (!p.getAllowFlight()) {
                // Something else took flight away (a gamemode or world change): timed flight is off.
                s.active = false;
                continue;
            }
            if (!spends(p)) continue;
            s.seconds = Math.max(0, s.seconds - 1);
            s.unsaved++;
            if (s.seconds == 0) {
                stopFlying(s);
                send(p, Messages.TFLY_EXPIRED);
                flush(s);
            } else if (WARNINGS.contains(s.seconds)) {
                send(p, Messages.TFLY_WARNING, "{time}", PlaceholderFormats.duration(s.seconds));
            }
        }
    }

    private void stopFlying(Session s) {
        s.active = false;
        Player p = s.player;
        if (p.hasPermission("allium.fly")) return;
        p.setFlying(false);
        p.setAllowFlight(false);
        flights.changed(p);
    }

    /** Writes the spent seconds as a relative change; they come back for the next try on failure. */
    void flush(Session s) {
        long spent = s.unsaved;
        if (spent <= 0 || closed) return;
        s.unsaved = 0;
        UUID id = s.player.getUniqueId();
        try {
            io.execute(() -> {
                try {
                    repository.add(id, -spent);
                } catch (SQLException | RuntimeException failed) {
                    plugin.logger().warning("Could not save spent flight time for " + id + ": " + failed.getMessage());
                    server(() -> s.unsaved += spent);
                }
            });
        } catch (RejectedExecutionException busy) {
            s.unsaved += spent;
        }
    }

    private void join(Player player) {
        Session s = new Session(player);
        sessions.put(player.getUniqueId(), s);
        try {
            io.execute(() -> {
                try {
                    long seconds = repository.seconds(player.getUniqueId());
                    server(() -> {
                        if (sessions.get(player.getUniqueId()) == s) {
                            s.seconds = seconds;
                            s.loaded = true;
                        }
                    });
                } catch (SQLException | RuntimeException failed) {
                    plugin.logger().warning("Could not load flight time for " + player.getUniqueId() + ": " + failed.getMessage());
                }
            });
        } catch (RejectedExecutionException busy) {
            plugin.logger().warning("Timed flight storage is busy; " + player.getName() + "'s flight time is unavailable.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && s.player == event.getPlayer()) {
            flush(s);
            sessions.remove(event.getPlayer().getUniqueId(), s);
        }
    }

    private void server(Runnable task) {
        if (!closed) plugin.getScheduler().runOnServer(() -> {
            if (!closed) task.run();
        });
    }

    private void send(Player p, Messages message, String... args) {
        if (p.isOnline()) plugin.getMessageManager().send(p, message, args);
    }
}
