package codes.castled.allium.importer;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Coordinates all state-changing data imports (legacy Allium, Essentials/EssentialsX, etc.).
 *
 * <p>Holds a single exclusive apply lease across every importer so two sources can never mutate
 * overlapping destination tables at once, tracks in-flight logins between
 * {@link AsyncPlayerPreLoginEvent} and {@link PlayerJoinEvent} so an empty-server check cannot race
 * an already-admitted handshake, and keeps the login fence active until the importer releases its
 * lease after publishing refreshed in-memory state.
 */
public final class ImportApplyCoordinator implements Listener {

    public static final long DEFAULT_IN_FLIGHT_TTL_MILLIS = 60_000L;

    public sealed interface Acquisition permits Acquired, Refused {}
    public record Acquired(Lease lease) implements Acquisition {}
    public record Refused(String reason) implements Acquisition {}

    public final class Lease implements AutoCloseable {
        private final String source;
        private final String runId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(String source, String runId) {
            this.source = source;
            this.runId = runId;
        }

        public String source() { return source; }
        public String runId() { return runId; }
        public boolean isReleased() { return released.get(); }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            synchronized (gate) {
                if (active == this) active = null;
            }
        }
    }

    private final PluginStart plugin;
    private final LongSupplier clock;
    private final long inFlightTtlMillis;
    private final Object gate = new Object();
    private final Map<UUID, Long> inFlightLogins = new HashMap<>();
    private Lease active;

    public ImportApplyCoordinator(PluginStart plugin) {
        this(plugin, System::currentTimeMillis, DEFAULT_IN_FLIGHT_TTL_MILLIS);
    }

    public ImportApplyCoordinator(PluginStart plugin, LongSupplier clock, long inFlightTtlMillis) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.inFlightTtlMillis = Math.max(1L, inFlightTtlMillis);
    }

    /**
     * Acquires the single server-wide apply lease if no other apply is active, no players are
     * online, and no logins are currently in flight between pre-login and join.
     */
    public Acquisition tryAcquireApply(String source, String runId) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(runId, "runId");
        synchronized (gate) {
            pruneExpiredLocked();
            if (active != null) {
                return new Refused("another import apply (" + active.source() + " " + active.runId() + ") is already running");
            }
            if (!plugin.getServer().getOnlinePlayers().isEmpty()) {
                return new Refused("players are online; apply runs on an empty server (logins are refused while it runs)");
            }
            if (!inFlightLogins.isEmpty()) {
                return new Refused("a player login is in progress; wait a moment and retry on an empty server");
            }
            active = new Lease(source, runId);
            return new Acquired(active);
        }
    }

    public boolean isApplying() {
        synchronized (gate) {
            return active != null;
        }
    }

    public String activeDescription() {
        synchronized (gate) {
            return active == null ? null : active.source() + " " + active.runId();
        }
    }

    public boolean hasInFlightLogins() {
        synchronized (gate) {
            pruneExpiredLocked();
            return !inFlightLogins.isEmpty();
        }
    }

    private void pruneExpiredLocked() {
        long now = clock.getAsLong();
        inFlightLogins.values().removeIf(ts -> now - ts >= inFlightTtlMillis);
    }

    private String fenceMessage() {
        return ChatColor.translateAlternateColorCodes('&', plugin.getMessageManager().resolve(Messages.IMPORT_LEGACY_FENCE));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        synchronized (gate) {
            if (active != null) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, fenceMessage());
                return;
            }
            if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                inFlightLogins.put(event.getUniqueId(), clock.getAsLong());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLoginMonitor(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            synchronized (gate) {
                inFlightLogins.remove(event.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        synchronized (gate) {
            if (active != null) {
                inFlightLogins.remove(id);
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, fenceMessage());
                return;
            }
            if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) {
                inFlightLogins.put(id, clock.getAsLong());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginMonitor(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            synchronized (gate) {
                inFlightLogins.remove(event.getPlayer().getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        boolean kick;
        synchronized (gate) {
            inFlightLogins.remove(event.getPlayer().getUniqueId());
            kick = active != null;
        }
        if (kick) {
            event.getPlayer().kickPlayer(fenceMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        synchronized (gate) {
            inFlightLogins.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        synchronized (gate) {
            inFlightLogins.remove(event.getPlayer().getUniqueId());
        }
    }
}
