package codes.castled.allium.item.library;

import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.scheduler.BoundedPools;
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
 * {@link #resolve} always complete normally: on timeout, failure, a full queue or the per-minute
 * remote lookup limit they carry a name-only profile marked {@code degraded} and a warning, never
 * an exception.
 */
public final class SkullProfiles implements AutoCloseable {

    /**
     * A resolved profile. {@code degraded} means it is only a name-only fallback (no skin), and
     * {@code warning} says why.
     */
    public record Resolved(PlayerProfile profile, String warning, boolean degraded) {
        public Resolved(PlayerProfile profile) {
            this(profile, null, false);
        }
    }

    /** Counters since startup, for diagnostics and the live gate. */
    public record Stats(long onlineHits, long cacheHits, long sharedLookups, long remoteLookups, long resolved,
                        long noSkin, long timeouts, long failures, long rateLimited, long queueRejected) {
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
    private final Map<String, Resolved> cache;
    private final int maxLookupsPerMinute;
    private final java.util.function.Predicate<PlayerProfile> hasSkin;
    private final java.util.concurrent.atomic.AtomicLong noSkin = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.ArrayDeque<Long> recentLookups = new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicLong onlineHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sharedLookups = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong remoteLookups = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong resolvedCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong timeouts = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong failures = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rateLimited = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong queueRejected = new java.util.concurrent.atomic.AtomicLong();

    public SkullProfiles(Function<String, Player> onlinePlayers, Function<String, PlayerProfile> nameOnly, Lookup lookup,
                         long timeoutMillis, long ttlMillis, int capacity, LongSupplier clock) {
        this(onlinePlayers, nameOnly, lookup, timeoutMillis, ttlMillis, capacity, clock, 0, profile -> true);
    }

    public SkullProfiles(Function<String, Player> onlinePlayers, Function<String, PlayerProfile> nameOnly, Lookup lookup,
                         long timeoutMillis, long ttlMillis, int capacity, LongSupplier clock, int maxLookupsPerMinute) {
        this(onlinePlayers, nameOnly, lookup, timeoutMillis, ttlMillis, capacity, clock, maxLookupsPerMinute, profile -> true);
    }

    /**
     * @param maxLookupsPerMinute remote lookups started per rolling minute; 0 or less is unlimited
     * @param hasSkin             whether a profile carries a skin texture; one without is degraded.
     *                            An offline-mode server resolves names to offline profiles, which have none.
     */
    public SkullProfiles(Function<String, Player> onlinePlayers, Function<String, PlayerProfile> nameOnly, Lookup lookup,
                         long timeoutMillis, long ttlMillis, int capacity, LongSupplier clock, int maxLookupsPerMinute,
                         java.util.function.Predicate<PlayerProfile> hasSkin) {
        this(onlinePlayers, nameOnly, lookup, timeoutMillis, ttlMillis, capacity, clock, maxLookupsPerMinute, hasSkin,
                BoundedPools.create(POOL_NAME, POOL_THREADS, POOL_QUEUE));
    }

    /** @param executor the bounded lookup pool (the plugin's comes from its {@code TaskScheduler}); closed with this */
    public SkullProfiles(Function<String, Player> onlinePlayers, Function<String, PlayerProfile> nameOnly, Lookup lookup,
                         long timeoutMillis, long ttlMillis, int capacity, LongSupplier clock, int maxLookupsPerMinute,
                         java.util.function.Predicate<PlayerProfile> hasSkin, ThreadPoolExecutor executor) {
        this.maxLookupsPerMinute = maxLookupsPerMinute;
        this.hasSkin = Objects.requireNonNull(hasSkin, "hasSkin");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.nameOnly = Objects.requireNonNull(nameOnly, "nameOnly");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.timeoutMillis = timeoutMillis;
        this.ttlMillis = ttlMillis;
        this.capacity = Math.max(1, capacity);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Resolved> eldest) {
                boolean evict = size() > SkullProfiles.this.capacity;
                if (evict) expiry.remove(eldest.getKey());
                return evict;
            }
        };
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public static final String POOL_NAME = "Allium-SkullLookup";
    public static final int POOL_THREADS = 2;
    public static final int POOL_QUEUE = 32;

    public CompletableFuture<Resolved> resolve(String rawName) {
        String name = rawName.trim();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            throw new ItemBuildException("owner: '" + rawName + "' is not a valid player name");
        }
        Player online = onlinePlayers.apply(name);
        if (online != null) {
            onlineHits.incrementAndGet();
            return CompletableFuture.completedFuture(checked(name, online.getPlayerProfile()));
        }
        String key = name.toLowerCase(Locale.ROOT);
        Resolved cached = cached(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<Resolved> mine;
        synchronized (inFlight) {
            CompletableFuture<Resolved> existing = inFlight.get(key);
            if (existing != null) {
                sharedLookups.incrementAndGet();
                return existing;
            }
            mine = new CompletableFuture<>();
            inFlight.put(key, mine);
        }
        // Registered outside the map so a synchronous completion cannot re-enter it.
        mine.whenComplete((r, e) -> inFlight.remove(key, mine));
        start(name, key, mine);
        return mine;
    }

    /** Whether another remote lookup may start now (rolling one-minute window). */
    private synchronized boolean admitLookup() {
        if (maxLookupsPerMinute <= 0) {
            return true;
        }
        long now = clock.getAsLong();
        while (!recentLookups.isEmpty() && recentLookups.peekFirst() <= now - 60_000L) {
            recentLookups.pollFirst();
        }
        if (recentLookups.size() >= maxLookupsPerMinute) {
            return false;
        }
        recentLookups.addLast(now);
        return true;
    }

    private void start(String name, String key, CompletableFuture<Resolved> result) {
        if (!admitLookup()) {
            rateLimited.incrementAndGet();
            result.complete(fallback(name, "the limit of " + maxLookupsPerMinute + " profile lookups per minute was reached"));
            return;
        }
        remoteLookups.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    PlayerProfile profile = remote(name).get(timeoutMillis, TimeUnit.MILLISECONDS);
                    if (profile == null) {
                        failures.incrementAndGet();
                        result.complete(fallback(name, "no profile was found"));
                        return;
                    }
                    Resolved resolved = checked(name, profile);
                    // Profiles without a skin are cached too, so a repeated give does not look up again.
                    store(key, resolved);
                    resolvedCount.incrementAndGet();
                    result.complete(resolved);
                } catch (java.util.concurrent.TimeoutException e) {
                    timeouts.incrementAndGet();
                    result.complete(fallback(name, "the lookup timed out after " + timeoutMillis + " ms"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                    result.complete(fallback(name, "the lookup was interrupted"));
                } catch (Exception e) {
                    failures.incrementAndGet();
                    Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
                    result.complete(fallback(name, "the lookup failed (" + cause.getClass().getSimpleName() + ")"));
                }
            });
        } catch (RejectedExecutionException e) {
            queueRejected.incrementAndGet();
            result.complete(fallback(name, "too many skull lookups are queued"));
        }
    }

    public Stats stats() {
        return new Stats(onlineHits.get(), cacheHits.get(), sharedLookups.get(), remoteLookups.get(), resolvedCount.get(),
                noSkin.get(), timeouts.get(), failures.get(), rateLimited.get(), queueRejected.get());
    }

    private Resolved checked(String name, PlayerProfile profile) {
        if (hasSkin.test(profile)) {
            return new Resolved(profile);
        }
        noSkin.incrementAndGet();
        return new Resolved(profile, "the skin for " + name + " could not be resolved (the server returned a profile without "
                + "one; an offline-mode server resolves names to offline profiles)", true);
    }

    /** Test-only fault points simulate a slow lookup or one that never answers. */
    private CompletableFuture<PlayerProfile> remote(String name) {
        if (FaultInjection.trip(FaultInjection.SKULL_LOOKUP_HANG)) {
            return new CompletableFuture<>();
        }
        if (FaultInjection.trip(FaultInjection.SKULL_LOOKUP_SLOW)) {
            // Answers after the delay; completing a future needs no task thread.
            return new CompletableFuture<PlayerProfile>().completeOnTimeout(nameOnly.apply(name), SLOW_FAULT_MILLIS, TimeUnit.MILLISECONDS);
        }
        return lookup.lookup(name);
    }

    private Resolved fallback(String name, String reason) {
        return new Resolved(nameOnly.apply(name), "the skin for " + name + " could not be resolved (" + reason + ")", true);
    }

    private synchronized Resolved cached(String key) {
        long[] until = expiry.get(key);
        if (until != null && until[0] < clock.getAsLong()) {
            cache.remove(key);
            expiry.remove(key);
            return null;
        }
        return cache.get(key);
    }

    private synchronized void store(String key, Resolved profile) {
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
