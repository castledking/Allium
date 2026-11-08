package codes.castled.allium.preference;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Per-connection preference sessions: preferences load before they are enforced, and nothing
 * here ever writes a default.
 *
 * <ul>
 *   <li>{@link #begin} starts a load and returns a generation. A load that completes after its
 *       session ended (a reconnect while it was slow) is discarded, never applied to the newer
 *       session.</li>
 *   <li>Until a load succeeds the session is {@code LOADING} or {@code FAILED}. Either way there
 *       are no effective preferences: callers fail closed ({@link #acceptance} is
 *       {@code UNAVAILABLE}) and never enforce defaults in their place. A failed load is retried
 *       in the background with exponential backoff, a bounded number of times.</li>
 *   <li>{@link #change} persists a field-level patch at any time. A session shows the last row
 *       the database confirmed with every patch still in flight laid over it, in order: a patch
 *       made before the load finished is never hidden by the loaded row, an earlier write's
 *       result never hides a later pending patch, and a failed write disappears from the local
 *       view.</li>
 * </ul>
 */
public final class PreferenceSessions {

    public enum State {
        LOADING,
        READY,
        FAILED
    }

    /** Whether a player accepts something, for services that must fail closed. */
    public enum Acceptance {
        ACCEPTS,
        REFUSES,
        /** Not loaded yet, or the load failed: refuse and tell the sender to try again shortly. */
        UNAVAILABLE
    }

    /** Runs a task once after a delay, off the server thread (the plugin's scheduler). */
    @FunctionalInterface
    public interface Delayer {
        void schedule(Runnable task, long delayMillis);
    }

    /**
     * Background retries after a failed load: up to {@code retries} more attempts, the first after
     * {@code firstDelayMillis}, each later one twice as long, capped at {@code maxDelayMillis}.
     */
    public record RetryPolicy(int retries, long firstDelayMillis, long maxDelayMillis) {
        public static final RetryPolicy NONE = new RetryPolicy(0, 0L, 0L);

        public RetryPolicy {
            if (retries < 0 || firstDelayMillis < 0L || maxDelayMillis < firstDelayMillis) {
                throw new IllegalArgumentException("invalid retry policy " + retries + "/" + firstDelayMillis + "/" + maxDelayMillis);
            }
        }

        /** The wait before retry number {@code retry} (1-based). */
        public long delayMillis(int retry) {
            long delay = firstDelayMillis;
            for (int i = 1; i < retry && delay < maxDelayMillis; i++) {
                delay *= 2L;
            }
            return Math.min(delay, maxDelayMillis);
        }
    }

    /**
     * A snapshot of one session; {@code stored} and {@code effective} are only set when READY.
     *
     * @param loadAttempts loads tried for this session so far (1 plus retries)
     */
    public record View(UUID uuid, State state, long generation, @Nullable StoredPreferences stored,
                       @Nullable EffectivePreferences effective, @Nullable String failure, int loadAttempts) {
    }

    /** Counters since startup. */
    public record Stats(long sessionsBegun, long loads, long staleLoadsDiscarded, long loadFailures, long loadRetries,
                        long loadsRecovered, long writes, long writeFailures) {
    }

    private static final class Pending {
        final PreferenceChange change;

        Pending(PreferenceChange change) {
            this.change = change;
        }
    }

    private final class Session {
        final long generation;
        State state = State.LOADING;
        /** The newest row the database returned (load or write); null until one arrives. */
        StoredPreferences confirmed;
        /** Patches submitted but not yet answered, in submission order. */
        final Deque<Pending> inFlight = new ArrayDeque<>();
        String failure;
        int attempts = 1;
        final CompletableFuture<View> ready = new CompletableFuture<>();

        Session(long generation) {
            this.generation = generation;
        }

        StoredPreferences current() {
            StoredPreferences out = confirmed;
            for (Pending pending : inFlight) {
                out = out.with(pending.change);
            }
            return out;
        }

        void confirm(StoredPreferences row) {
            if (confirmed == null || row.version() >= confirmed.version()) {
                confirmed = row;
            }
        }

        View view(UUID uuid) {
            StoredPreferences stored = state == State.READY ? current() : null;
            return new View(uuid, state, generation, stored, stored == null ? null : stored.effective(defaults.get()), failure, attempts);
        }
    }

    private final PreferenceStore store;
    private final Supplier<PreferenceDefaults> defaults;
    private final Consumer<String> diagnostics;
    private final RetryPolicy retry;
    private final Delayer delayer;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong loadFailures = new AtomicLong();
    private final AtomicLong loadRetries = new AtomicLong();
    private final AtomicLong recovered = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();

    /** Sessions without load retries. */
    public PreferenceSessions(PreferenceStore store, Supplier<PreferenceDefaults> defaults, Consumer<String> diagnostics) {
        this(store, defaults, diagnostics, RetryPolicy.NONE, (task, delay) -> {
            throw new IllegalStateException("no retries configured");
        });
    }

    public PreferenceSessions(PreferenceStore store, Supplier<PreferenceDefaults> defaults, Consumer<String> diagnostics,
                              RetryPolicy retry, Delayer delayer) {
        this.store = Objects.requireNonNull(store, "store");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.delayer = Objects.requireNonNull(delayer, "delayer");
    }

    /** Starts a new session (replacing any earlier one for this UUID) and its load. */
    public long begin(UUID uuid) {
        Session session = new Session(generations.incrementAndGet());
        sessions.put(uuid, session);
        store.load(uuid).whenComplete((row, error) -> loaded(uuid, session, row, error));
        return session.generation;
    }

    private void loaded(UUID uuid, Session session, Optional<StoredPreferences> row, Throwable error) {
        synchronized (session) {
            if (sessions.get(uuid) != session) {
                stale.incrementAndGet();
                session.ready.completeExceptionally(new IllegalStateException("session ended before its load finished"));
                return;
            }
            if (error != null) {
                loadFailures.incrementAndGet();
                session.state = State.FAILED;
                session.failure = String.valueOf(error.getMessage());
                int retriesUsed = session.attempts - 1;
                if (retriesUsed < retry.retries() && !(error instanceof PreferenceStore.DroppedAtShutdownException)) {
                    long delay = retry.delayMillis(retriesUsed + 1);
                    if (retriesUsed == 0) {
                        diagnostics.accept("Could not load preferences for " + uuid + ": " + error + ". Retrying up to "
                                + retry.retries() + " time(s), first in " + delay + " ms; until then they are treated as "
                                + "unavailable, never as defaults.");
                    }
                    delayer.schedule(() -> retryLoad(uuid, session), delay);
                    return;
                }
                diagnostics.accept((retry.retries() > 0 ? "Gave up loading preferences for " + uuid + " after "
                        + session.attempts + " attempts: " : "Could not load preferences for " + uuid + ": ") + error
                        + ". They stay unavailable (never defaults) until they rejoin.");
                session.ready.completeExceptionally(error);
                return;
            }
            loads.incrementAndGet();
            if (session.attempts > 1) {
                recovered.incrementAndGet();
                diagnostics.accept("Loaded preferences for " + uuid + " on attempt " + session.attempts + ".");
            }
            session.confirm(row.orElse(StoredPreferences.empty(uuid)));
            session.state = State.READY;
            session.failure = null;
            session.ready.complete(session.view(uuid));
        }
    }

    private void retryLoad(UUID uuid, Session session) {
        synchronized (session) {
            if (sessions.get(uuid) != session) {
                session.ready.completeExceptionally(new IllegalStateException("session ended before its load succeeded"));
                return;
            }
            session.attempts++;
        }
        loadRetries.incrementAndGet();
        store.load(uuid).whenComplete((row, error) -> loaded(uuid, session, row, error));
    }

    /** Ends a session if it is still the current one (a later reconnect is never ended by a stale quit). */
    public void end(UUID uuid, long generation) {
        sessions.computeIfPresent(uuid, (k, session) -> session.generation == generation ? null : session);
    }

    public Optional<View> view(UUID uuid) {
        Session session = sessions.get(uuid);
        if (session == null) {
            return Optional.empty();
        }
        synchronized (session) {
            return Optional.of(session.view(uuid));
        }
    }

    /**
     * Whether {@code uuid} accepts what {@code flag} governs (messages, payments or teleport
     * requests). Anything but a READY session is {@link Acceptance#UNAVAILABLE}: the caller refuses
     * and asks the sender to try again shortly. A player with no session here (offline, or on
     * another server) is also UNAVAILABLE; such callers must read the authoritative row instead.
     */
    public Acceptance acceptance(UUID uuid, PreferenceKey flag) {
        if (flag.kind() != PreferenceKey.Kind.BOOLEAN) {
            throw new IllegalArgumentException(flag + " is not an accept flag");
        }
        EffectivePreferences effective = view(uuid).map(View::effective).orElse(null);
        if (effective == null) {
            return Acceptance.UNAVAILABLE;
        }
        boolean accepts = switch (flag) {
            case ACCEPT_MESSAGES -> effective.acceptMessages();
            case ACCEPT_PAYMENTS -> effective.acceptPayments();
            case ACCEPT_TELEPORT_REQUESTS -> effective.acceptTeleportRequests();
            default -> throw new IllegalArgumentException(flag + " is not an accept flag");
        };
        return accepts ? Acceptance.ACCEPTS : Acceptance.REFUSES;
    }

    /** Completes when the current session is READY; fails if its loads give up or it is replaced first. */
    public CompletableFuture<View> whenReady(UUID uuid) {
        Session session = sessions.get(uuid);
        return session == null ? CompletableFuture.failedFuture(new IllegalStateException("no session for " + uuid)) : session.ready;
    }

    /**
     * Persists a patch, online or offline. A live session sees it at once (laid over the load if
     * still LOADING).
     */
    public CompletableFuture<StoredPreferences> change(UUID uuid, PreferenceChange change) {
        Session session = sessions.get(uuid);
        // One token per call, so a completion removes exactly its own patch.
        Pending pending = new Pending(change);
        if (session != null) {
            synchronized (session) {
                session.inFlight.addLast(pending);
            }
        }
        writes.incrementAndGet();
        return store.apply(uuid, change).whenComplete((stored, error) -> {
            if (error != null) {
                writeFailures.incrementAndGet();
                // Writes dropped by a shutdown are listed by the store's close report instead.
                if (!(error instanceof PreferenceStore.DroppedAtShutdownException)) {
                    diagnostics.accept("Could not save preferences for " + uuid + " " + change.describe() + ": " + error);
                }
            }
            if (session != null) {
                synchronized (session) {
                    session.inFlight.removeIf(c -> c == pending);
                    if (error == null) {
                        session.confirm(stored);
                    }
                }
            }
        });
    }

    /** {@code /mfx toggle} on the loaded value; refused until the session is READY. */
    public CompletableFuture<MessageSoundRules.Toggle> toggleSound(UUID uuid) {
        Session session = sessions.get(uuid);
        MessageSoundRules.Toggle toggle;
        if (session == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no session for " + uuid));
        }
        synchronized (session) {
            if (session.state != State.READY) {
                return CompletableFuture.failedFuture(new IllegalStateException("preferences for " + uuid + " are " + session.state));
            }
            toggle = MessageSoundRules.toggle(session.current(), defaults.get());
        }
        return change(uuid, toggle.change()).thenApply(stored -> toggle);
    }

    public Stats stats() {
        return new Stats(generations.get(), loads.get(), stale.get(), loadFailures.get(), loadRetries.get(),
                recovered.get(), writes.get(), writeFailures.get());
    }
}
