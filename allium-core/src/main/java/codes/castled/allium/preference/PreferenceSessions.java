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
 *   <li>Until the load completes the session is {@code LOADING}: callers must not enforce
 *       defaults in its place, and toggles (which need the current value) are refused.</li>
 *   <li>{@link #change} persists a field-level patch at any time. A session shows the last row
 *       the database confirmed with every patch still in flight laid over it, in order: a patch
 *       made during {@code LOADING} is never hidden by the loaded row, an earlier write's result
 *       never hides a later pending patch, and a failed write disappears from the local view.</li>
 * </ul>
 */
public final class PreferenceSessions {

    public enum State {
        LOADING,
        READY,
        FAILED
    }

    /** A snapshot of one session; {@code stored} and {@code effective} are only set when READY. */
    public record View(UUID uuid, State state, long generation, @Nullable StoredPreferences stored,
                       @Nullable EffectivePreferences effective, @Nullable String failure) {
    }

    /** Counters since startup. */
    public record Stats(long loads, long staleLoadsDiscarded, long loadFailures, long writes, long writeFailures) {
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
            return new View(uuid, state, generation, stored, stored == null ? null : stored.effective(defaults.get()), failure);
        }
    }

    private final PreferenceStore store;
    private final Supplier<PreferenceDefaults> defaults;
    private final Consumer<String> diagnostics;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong loadFailures = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();

    public PreferenceSessions(PreferenceStore store, Supplier<PreferenceDefaults> defaults, Consumer<String> diagnostics) {
        this.store = Objects.requireNonNull(store, "store");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
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
                diagnostics.accept("Could not load preferences for " + uuid + ": " + error
                        + ". Their preferences are not enforced from defaults; they load on the next join.");
                session.ready.completeExceptionally(error);
                return;
            }
            loads.incrementAndGet();
            session.confirm(row.orElse(StoredPreferences.empty(uuid)));
            session.state = State.READY;
            session.ready.complete(session.view(uuid));
        }
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

    /** Completes when the current session is READY; fails if it fails or is replaced first. */
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
                diagnostics.accept("Could not save preferences for " + uuid + " " + change + ": " + error);
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
        return new Stats(loads.get(), stale.get(), loadFailures.get(), writes.get(), writeFailures.get());
    }
}
