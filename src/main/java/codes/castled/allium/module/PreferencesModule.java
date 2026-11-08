package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.preference.PreferenceDefaults;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.preference.PreferenceStore;
import codes.castled.allium.storage.SqlDialect;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Player preferences (S03): the UUID repository with schema migrations, bounded async storage and
 * per-connection sessions. Commands that change preferences arrive with messaging and sounds.
 *
 * <p>A load starts at pre-login (off the server thread) and waits there up to
 * {@code preferences.preload-wait-millis}, so most players join with preferences ready. A slower
 * load leaves the session LOADING: enforcement must wait for it, and nothing writes defaults in
 * its place. Each session belongs to one connection, so a late quit of an older connection never
 * ends a newer login's session. A session begun at pre-login whose player never joins is ended
 * after a minute.
 */
public final class PreferencesModule extends AbstractModule implements Listener {

    public static final long SLOW_LOAD_FAULT_MILLIS = 6_000L;

    private PreferenceRepository repository;
    private PreferenceStore store;
    private PreferenceSessions sessions;
    private volatile PreferenceDefaults defaults;
    private long preloadWaitMillis;
    private long shutdownTimeoutMillis;
    /** Sessions begun at pre-login and not yet joined: generation and start time. */
    private final Map<UUID, long[]> loginGenerations = new ConcurrentHashMap<>();
    private codes.castled.allium.scheduler.TaskHandle sweeper;
    private final Map<Player, Long> connectionGenerations = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public PreferencesModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "preferences";
    }

    @Override
    public void onEnable() {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("preferences");
        defaults = readDefaults(config);
        // Bounded: the wait holds the joining player's login thread.
        preloadWaitMillis = config == null ? 2000L : Math.max(0L, Math.min(10_000L, config.getLong("preload-wait-millis", 2000L)));
        int queue = config == null ? 512 : Math.max(16, config.getInt("storage.queue-capacity", 512));
        shutdownTimeoutMillis = config == null ? 10_000L : Math.max(0L, Math.min(120_000L, config.getLong("storage.shutdown-timeout-millis", 10_000L)));
        PreferenceSessions.RetryPolicy retry = readRetry(config);
        SqlDialect dialect = plugin.getDatabaseManager().dialect();
        int lanes = dialect == SqlDialect.SQLITE ? 1 : Math.max(1, config == null ? 4 : config.getInt("storage.mysql-lanes", 4));
        repository = new PreferenceRepository(plugin.getDatabaseManager().connectionSource(), dialect, System::currentTimeMillis);
        int version;
        try {
            version = repository.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("preferences storage is unavailable: " + e.getMessage(), e);
        }
        store = new PreferenceStore(repository, lanes, queue, uuid -> {
            if (FaultInjection.trip(FaultInjection.PREFERENCES_LOAD_FAIL)) {
                throw new IllegalStateException("fault injection: preference load failed");
            }
            if (FaultInjection.trip(FaultInjection.PREFERENCES_LOAD_SLOW)) {
                try {
                    Thread.sleep(SLOW_LOAD_FAULT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, plugin.getScheduler()::boundedPool);
        sessions = new PreferenceSessions(store, () -> defaults, plugin.logger()::warning, retry,
                // A retry only queues a load on the store, so it runs on the scheduler's timer thread.
                (task, delayMillis) -> plugin.getScheduler().runAsyncLater(task, delayMillis));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player online : Bukkit.getOnlinePlayers()) {
            connectionGenerations.put(online, sessions.begin(online.getUniqueId()));
        }
        // A login refused after pre-login (ban, whitelist, full) or a connection that drops before
        // join fires no quit event. (PlayerLoginEvent is not used: Paper deprecates it for removal.)
        sweeper = plugin.getScheduler().repeatOnServer(() -> {
            sweepAbandonedLogins();
            return true;
        }, 30_000L, 30_000L);
        plugin.logger().info("Preferences enabled: " + dialect + " schema v" + version + ", " + lanes + " storage lane(s), defaults "
                + defaults + ", pre-login wait " + preloadWaitMillis + " ms, load retries " + retry.retries()
                + ", shutdown timeout " + shutdownTimeoutMillis + " ms.");
    }

    private PreferenceSessions.RetryPolicy readRetry(ConfigurationSection config) {
        ConfigurationSection r = config == null ? null : config.getConfigurationSection("load-retry");
        int retries = r == null ? 4 : Math.max(0, Math.min(10, r.getInt("retries", 4)));
        long first = r == null ? 1_000L : Math.max(50L, Math.min(60_000L, r.getLong("first-delay-millis", 1_000L)));
        long max = r == null ? 15_000L : Math.max(first, Math.min(300_000L, r.getLong("max-delay-millis", 15_000L)));
        return new PreferenceSessions.RetryPolicy(retries, first, max);
    }

    private PreferenceDefaults readDefaults(ConfigurationSection config) {
        ConfigurationSection d = config == null ? null : config.getConfigurationSection("defaults");
        // The recipient side of a notification's volume; the message side is sounds.defaults.volume.
        double volume = codes.castled.allium.message.SoundSettings.read(plugin.getConfig(), plugin.logger()::warning).messageFallbackVolume();
        return new PreferenceDefaults(
                d == null || d.getBoolean("accept-messages", true),
                d == null || d.getBoolean("accept-payments", true),
                d == null || d.getBoolean("accept-teleport-requests", true),
                volume);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        loginGenerations.put(uuid, new long[]{sessions.begin(uuid), System.nanoTime()});
        if (preloadWaitMillis <= 0L) {
            return;
        }
        try {
            sessions.whenReady(uuid).get(preloadWaitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Joins while still LOADING; enforcement waits for it.
        } catch (Exception e) {
            // FAILED or replaced: reported by the session itself.
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        long[] login = loginGenerations.remove(uuid);
        connectionGenerations.put(event.getPlayer(), login == null ? sessions.begin(uuid) : login[0]);
    }

    private void sweepAbandonedLogins() {
        long cutoff = System.nanoTime() - TimeUnit.SECONDS.toNanos(60);
        loginGenerations.forEach((uuid, login) -> {
            if (login[1] < cutoff && Bukkit.getPlayer(uuid) == null && loginGenerations.remove(uuid, login)) {
                sessions.end(uuid, login[0]);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Long generation = connectionGenerations.remove(event.getPlayer());
        if (generation != null) {
            sessions.end(event.getPlayer().getUniqueId(), generation);
        }
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (sweeper != null) {
            sweeper.cancel();
        }
        if (store != null) {
            int queued = store.pending();
            if (queued > 0) {
                plugin.logger().info("Saving preferences: finishing " + queued + " queued storage task(s), waiting up to "
                        + shutdownTimeoutMillis + " ms.");
            }
            PreferenceStore.CloseReport report = store.close(shutdownTimeoutMillis);
            if (!report.completed()) {
                // Shutdown continues; these are exactly the changes that did not reach the database.
                plugin.logger().warning("Preference storage did not finish within " + shutdownTimeoutMillis + " ms. "
                        + report.lostWrites().size() + " preference write(s) were not saved"
                        + (report.inProgressWrites().isEmpty() ? "." : ", and " + report.inProgressWrites().size()
                        + " may or may not have been saved:"));
                report.lostWrites().forEach(w -> plugin.logger().warning("  not saved: " + w));
                report.inProgressWrites().forEach(w -> plugin.logger().warning("  in progress, outcome unknown: " + w));
                if (report.droppedLoads() > 0) {
                    plugin.logger().info(report.droppedLoads() + " queued preference load(s) were dropped.");
                }
            }
        }
        loginGenerations.clear();
        connectionGenerations.clear();
    }

    /**
     * Whether an online player accepts messages, payments or teleport requests. Anything but
     * ACCEPTS or REFUSES means their preferences are not loaded (or failed to load): refuse with
     * {@code Messages.PREFERENCES_UNAVAILABLE} ("try again shortly"), never fall back to defaults.
     */
    public PreferenceSessions.Acceptance acceptance(UUID uuid, codes.castled.allium.preference.PreferenceKey flag) {
        return sessions.acceptance(uuid, flag);
    }

    /**
     * A player's effective preferences once loaded. Empty while their session is LOADING or
     * FAILED, or when they have none: callers must then wait or refuse, never enforce defaults.
     */
    public java.util.Optional<codes.castled.allium.preference.EffectivePreferences> effective(UUID uuid) {
        return sessions.view(uuid).map(PreferenceSessions.View::effective);
    }

    public PreferenceSessions sessions() {
        return sessions;
    }

    public PreferenceRepository repository() {
        return repository;
    }

    public PreferenceDefaults defaults() {
        return defaults;
    }
}
