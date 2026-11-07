package codes.castled.allium.item.library;

import codes.castled.allium.core.FaultInjection;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Resolves {@code owner:<name>} skull profiles without blocking the server thread.
 *
 * <p>An online player's profile is used at once. Otherwise a bounded LRU cache (with expiry) is
 * checked, concurrent requests for one name share a lookup, and lookups run on Allium's own
 * bounded executor (two threads, 32 queued) with an explicit timeout. Futures from
 * {@link #resolve} always complete normally: on timeout, failure or a full queue they carry a
 * name-only profile and a warning, never an exception.
 */
public final class SkullProfiles implements AutoCloseable {

    /** A resolved profile; {@code warning} is set when it is only a name-only fallback. */
    public record Resolved(PlayerProfile profile, String warning) {
    }

    /** Remote lookup by name (production: {@code Bukkit.createPlayerProfile(name).update()}). */
    @FunctionalInterface
    public interface Lookup {
        CompletableFuture<PlayerProfile> lookup(String name);
    }

    public static final long SLOW_FAULT_MILLIS = 2_000L;

    private final Function<String, Player> onlinePlayers;
    private final Function<String, PlayerProfile> nameOnly;
    private final Lookup lookup;
    private final long timeoutMillis;
    private final long ttlMillis;
    private final int capacity;
    private final LongSupplier clock;
    private final ThreadPoolExecutor executor;
    private final Map<String, CompletableFuture<Resolved>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, long[]> expiry = new ConcurrentHashMap<>();
    private final Map<String, PlayerProfile> cache;

    public SkullProfiles(Function<String, Player> onlinePlayers, Function<String, PlayerProfile> nameOnly, Lookup lookup,
                         long timeoutMillis, long ttlMillis, int capacity, LongSupplier clock) {
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.nameOnly = Objects.requireNonNull(nameOnly, "nameOnly");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.timeoutMillis = timeoutMillis;
        this.ttlMillis = ttlMillis;
        this.capacity = Math.max(1, capacity);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PlayerProfile> eldest) {
                boolean evict = size() > SkullProfiles.this.capacity;
                if (evict) expiry.remove(eldest.getKey());
                return evict;
            }
        };
        this.executor = new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "Allium-SkullLookup");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    public CompletableFuture<Resolved> resolve(String rawName) {
        String name = rawName.trim();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            throw new ItemBuildException("owner: '" + rawName + "' is not a valid player name");
        }
        Player online = onlinePlayers.apply(name);
        if (online != null) {
            return CompletableFuture.completedFuture(new Resolved(online.getPlayerProfile(), null));
        }
        String key = name.toLowerCase(Locale.ROOT);
        PlayerProfile cached = cached(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(new Resolved(cached, null));
        }
        return inFlight.computeIfAbsent(key, k -> start(name, k));
    }

    private CompletableFuture<Resolved> start(String name, String key) {
        CompletableFuture<Resolved> result = new CompletableFuture<>();
        result.whenComplete((r, e) -> inFlight.remove(key));
        try {
            executor.execute(() -> {
                try {
                    PlayerProfile profile = remote(name).get(timeoutMillis, TimeUnit.MILLISECONDS);
                    if (profile == null) {
                        result.complete(fallback(name, "no profile was found"));
                        return;
                    }
                    store(key, profile);
                    result.complete(new Resolved(profile, null));
                } catch (java.util.concurrent.TimeoutException e) {
                    result.complete(fallback(name, "the lookup timed out after " + timeoutMillis + " ms"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.complete(fallback(name, "the lookup was interrupted"));
                } catch (Exception e) {
                    result.complete(fallback(name, "the lookup failed (" + e.getClass().getSimpleName() + ")"));
                }
            });
        } catch (RejectedExecutionException e) {
            result.complete(fallback(name, "too many skull lookups are queued"));
        }
        return result;
    }

    /** Test-only fault points simulate a slow lookup or one that never answers. */
    private CompletableFuture<PlayerProfile> remote(String name) {
        if (FaultInjection.trip(FaultInjection.SKULL_LOOKUP_HANG)) {
            return new CompletableFuture<>();
        }
        if (FaultInjection.trip(FaultInjection.SKULL_LOOKUP_SLOW)) {
            Executor delayed = CompletableFuture.delayedExecutor(SLOW_FAULT_MILLIS, TimeUnit.MILLISECONDS);
            return CompletableFuture.supplyAsync(() -> nameOnly.apply(name), delayed);
        }
        return lookup.lookup(name);
    }

    private Resolved fallback(String name, String reason) {
        return new Resolved(nameOnly.apply(name), "the skin for " + name + " could not be resolved (" + reason
                + "); the head was given without it");
    }

    private synchronized PlayerProfile cached(String key) {
        long[] until = expiry.get(key);
        if (until != null && until[0] < clock.getAsLong()) {
            cache.remove(key);
            expiry.remove(key);
            return null;
        }
        return cache.get(key);
    }

    private synchronized void store(String key, PlayerProfile profile) {
        cache.put(key, profile);
        expiry.put(key, new long[]{clock.getAsLong() + ttlMillis});
    }

    public int cachedCount() {
        synchronized (this) {
            return cache.size();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
