package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.teleport.DestinationSafety;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.teleport.TeleportSettings;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextSender;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * UUID discipline state. JDBC runs only on the supplied bounded storage executor. Persistent
 * mutations and their repository-owned audit transaction finish before state or success is published.
 * Login threads read immutable ban snapshots; Bukkit authorization and movement run on the server.
 */
public final class DisciplineService implements Listener {
    private static final Map<PluginStart, DisciplineService> OWNERS = new WeakHashMap<>();
    public static final String BYPASS_PERMISSION = "allium.moderation.bypass";
    public static final String KICKALL_EXEMPT_PERMISSION = "allium.kickall.exempt";
    private static final List<String> DEFAULT_RANKS = List.of(
            "allium.mute,allium.unmute,allium.warn,allium.notes",
            "allium.freeze,allium.kick,allium.kickall",
            "allium.jail,allium.togglejail,allium.tempban",
            "allium.ban,allium.ipban,allium.tempbanip,allium.unbanip");
    // Safety is mandatory for jail placement even when ordinary teleport safety is disabled.
    private static final TeleportSettings JAIL_SAFETY = new TeleportSettings(
            0, 0, 1000, false, true, false, 0, 0, true, true, true, true, false, Map.of());

    private record Settings(boolean allowLoginFailure, boolean ipBans, String exemptPermission,
                            long preloadMillis, int retries, long retryMillis, long rateMillis,
                            List<String> ranks) {
        static Settings read(ConfigurationSection config) {
            String root = "moderation.discipline.";
            if (config == null) return new Settings(false, false, "allium.moderation.exempt", 2000, 4, 1000, 1000, DEFAULT_RANKS);
            for (String section : List.of("moderation", "moderation.discipline", "moderation.load-retry"))
                if (config.contains(section) && !config.isConfigurationSection(section))
                    throw new IllegalArgumentException(section + " must be a section");
            String policy = config.getString(root + "ban-load-failure-policy", "DENY");
            if (!"DENY".equalsIgnoreCase(policy) && !"ALLOW".equalsIgnoreCase(policy))
                throw new IllegalArgumentException(root + "ban-load-failure-policy must be DENY or ALLOW");
            if (config.contains(root + "ip-ban-enabled") && !config.isBoolean(root + "ip-ban-enabled"))
                throw new IllegalArgumentException(root + "ip-ban-enabled must be a boolean");
            if (config.contains(root + "exempt-permission") && !config.isString(root + "exempt-permission"))
                throw new IllegalArgumentException(root + "exempt-permission must be a permission string");
            if (config.contains(root + "staff-rank-permissions") && !config.isList(root + "staff-rank-permissions"))
                throw new IllegalArgumentException(root + "staff-rank-permissions must be a list");
            List<String> ranks = config.isList(root + "staff-rank-permissions")
                    ? List.copyOf(config.getStringList(root + "staff-rank-permissions")) : DEFAULT_RANKS;
            for (String rank : ranks) if (!rank.matches("[a-z0-9_.]+(,[a-z0-9_.]+)*"))
                throw new IllegalArgumentException("Staff ranks must contain comma-separated permission nodes");
            return new Settings("ALLOW".equalsIgnoreCase(policy),
                    config.getBoolean(root + "ip-ban-enabled", false),
                    config.getString(root + "exempt-permission", "allium.moderation.exempt"),
                    number(config, "moderation.preload-wait-millis", 2000, 0, 10_000),
                    (int) number(config, "moderation.load-retry.retries", 4, 0, 10),
                    number(config, "moderation.load-retry.first-delay-millis", 1000, 50, 60_000),
                    number(config, root + "rate-limit-millis", 1000, 0, 60_000), ranks);
        }
        private static long number(ConfigurationSection config, String path, long fallback, long min, long max) {
            Object raw = config.get(path);
            if (raw == null) return fallback;
            if (raw instanceof Number value && Double.isFinite(value.doubleValue())
                    && value.doubleValue() == value.longValue() && value.longValue() >= min && value.longValue() <= max) return value.longValue();
            throw new IllegalArgumentException(path + " must be an integer from " + min + " to " + max);
        }
    }

    private static final class Connection {
        final long createdNanos = System.nanoTime();
        final String address;
        volatile Player player;
        volatile boolean loaded;
        volatile BanRecord authoritativeLoginBan;
        volatile boolean failed;
        Connection(String address) { this.address = address; }
    }

    private record Actor(CommandSender sender, String permission, Player player, String name) {}
    private record ScopedMove(Player player, Location destination) {}
    private static final class PendingRelease {
        final Player player;
        final JailAssignment assignment;
        volatile boolean unsafeRespawn;
        PendingRelease(Player player, JailAssignment assignment) { this.player = player; this.assignment = assignment; }
    }
    @FunctionalInterface private interface SqlWork<T> { T run() throws SQLException; }
    @FunctionalInterface private interface Commit<T> { boolean apply(T value); }

    private final PluginStart plugin;
    private final ModerationRepository repository;
    private final Executor io;
    private final LongSupplier clock;
    private final Object stateLock = new Object();
    private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, FreezeRecord> frozen = new ConcurrentHashMap<>();
    private final Map<UUID, JailAssignment> prisoners = new ConcurrentHashMap<>();
    private final Map<UUID, Object> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> revisions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActions = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<codes.castled.allium.discord.StaffPosts.Notice> notices = notice -> { };
    private final Map<UUID, ScopedMove> scopedMoves = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRelease> releases = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> expiredReleasesInFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicLong stateRevision = new AtomicLong();
    private final DestinationSafety safety = new DestinationSafety();
    private volatile Map<UUID, BanRecord> bans = Map.of();
    private volatile Map<String, JailRecord> jails = Map.of();
    private volatile Settings settings;
    private volatile boolean catalogReady;
    private volatile boolean closed = true;
    private long lifecycle;
    private TaskHandle sweep;

    public DisciplineService(PluginStart plugin, ModerationRepository repository, Executor executor, LongSupplier clock) {
        this.plugin = Objects.requireNonNull(plugin);
        this.repository = Objects.requireNonNull(repository);
        this.io = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        settings = Settings.read(plugin.getConfig());
    }

    public void enable() {
        synchronized (OWNERS) {
            DisciplineService previous = OWNERS.get(plugin);
            if (previous != null && (!previous.closed || previous.io instanceof ExecutorService pool && !pool.isTerminated()))
                throw new IllegalStateException("Previous discipline service still owns unfinished storage");
            OWNERS.put(plugin, this);
        }
        if (writes.get() != 0) throw new IllegalStateException("Discipline writes still draining");
        closed = false;
        catalogReady = false;
        long epoch = ++lifecycle;
        reloadSettings();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        loadCatalog(epoch, 0);
        for (Player player : plugin.getServer().getOnlinePlayers()) onConnected(player);
        if (plugin.getScheduler() != null) sweep = plugin.getScheduler().repeatOnServer(() -> {
            if (closed) return false;
            connections.entrySet().removeIf(e -> e.getValue().player == null
                    && System.nanoTime() - e.getValue().createdNanos > TimeUnit.SECONDS.toNanos(60));
            tickExpiredJails();
            return true;
        }, 2_000, 2_000);
    }

    public void disable() {
        closed = true;
        lifecycle++;
        if (sweep != null) sweep.cancel();
        HandlerList.unregisterAll(this);
        connections.clear();
        lastActions.clear();
        scopedMoves.clear();
        expiredReleasesInFlight.clear();
        if (io instanceof ExecutorService pool) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    plugin.logger().severe("Discipline executor did not drain; unresolved UUIDs: " + pending.keySet());
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        // Checkpoints queued on the server cannot execute while disable owns this thread.
        if (!pending.isEmpty()) plugin.logger().warning("Discipline stopped with unresolved checkpoints: " + pending.keySet());
        if (!releases.isEmpty()) plugin.logger().severe("Discipline stopped with unresolved exact jail returns: " + releases.keySet());
        for (var entry : pending.entrySet()) finish(entry.getKey(), entry.getValue());
    }

    public void reloadSettings() {
        try { settings = Settings.read(plugin.getConfig()); }
        catch (IllegalArgumentException invalid) { plugin.logger().warning("Kept previous discipline settings: " + invalid.getMessage()); }
        if (settings.allowLoginFailure()) plugin.logger().warning(
                "DISCIPLINE BAN FAILURE POLICY IS ALLOW: unknown bans may enter during storage failures; known active bans still deny login.");
    }

    public boolean ready(UUID id) {
        Connection c = connections.get(id);
        return !closed && c != null && c.loaded && !pending.containsKey(id) && !releases.containsKey(id);
    }
    /** Receives each saved action (and staff note) for the Discord feeds. */
    public void setNotices(java.util.function.Consumer<codes.castled.allium.discord.StaffPosts.Notice> notices) {
        this.notices = notices == null ? notice -> { } : notices;
    }

    private static codes.castled.allium.discord.StaffPosts.Notice notice(String action, String staff, String player, String reason,
                                                                         Long expiresAt, String jail) {
        return new codes.castled.allium.discord.StaffPosts.Notice(action, staff, player, reason, expiresAt, jail);
    }

    public boolean isFrozen(UUID id) { return frozen.containsKey(id); }
    public boolean jailed(UUID id) {
        JailAssignment assignment = prisoners.get(id);
        return assignment != null && !assignment.isExpired(clock.getAsLong());
    }
    public Optional<JailAssignment> jailAssignment(UUID id) {
        JailAssignment assignment = prisoners.get(id);
        return assignment != null && !assignment.isExpired(clock.getAsLong()) ? Optional.of(assignment) : Optional.empty();
    }
    public boolean isBanned(UUID id) {
        BanRecord record = bans.get(id);
        return record != null && !record.isExpired(clock.getAsLong());
    }
    public boolean isIpBanned(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) return false;
        long now = clock.getAsLong();
        String trimmed = ipAddress.trim();
        for (BanRecord ban : bans.values()) {
            if (trimmed.equals(ban.ipAddress()) && !ban.isExpired(now)) return true;
        }
        return false;
    }
    public int pendingWrites() { return writes.get(); }
    public List<String> completeJails(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return jails.keySet().stream().filter(name -> name.startsWith(lower)).sorted().limit(50).toList();
    }

    /** Pure policy; offline permissions are unknown, so only console or explicit bypass may act. */
    public boolean canModerate(CommandSender actor, UUID id) {
        if (actor instanceof Player player && player.getUniqueId().equals(id)) return false;
        if (actor instanceof ConsoleCommandSender) return true;
        if (actor.hasPermission(BYPASS_PERMISSION)) return true;
        Player target = plugin.getServer().getPlayer(id);
        if (target == null || !target.isOnline() || !PlayerNameVisibility.canSeeTarget(actor, target)) return false;
        String exempt = settings.exemptPermission();
        if (exempt != null && !exempt.isBlank() && target.hasPermission(exempt)) return false;
        int targetRank = rank(target);
        return targetRank < 0 || targetRank < rank(actor);
    }

    /** Shared entry check for S17 and S19. Commit checks must call the pure canModerate again. */
    public boolean authorizeAction(CommandSender actor, UUID id) {
        if (!canModerate(actor, id)) { send(actor, Messages.DISCIPLINE_EXEMPT); return false; }
        return rate(actor);
    }

    private int rank(CommandSender actor) {
        List<String> ranks = settings.ranks();
        for (int i = ranks.size() - 1; i >= 0; i--)
            for (String permission : ranks.get(i).split(","))
                if (!permission.isBlank() && actor.hasPermission(permission.trim())) return i;
        return -1;
    }

    private boolean rate(CommandSender actor) {
        if (!(actor instanceof Player player)) return true;
        long now = clock.getAsLong();
        Long previous = lastActions.get(player.getUniqueId());
        if (previous != null && now - previous < settings.rateMillis()) {
            send(actor, Messages.DISCIPLINE_BUSY); return false;
        }
        lastActions.put(player.getUniqueId(), now);
        return true;
    }

    private void loadCatalog(long epoch, int attempt) {
        submit(() -> {
            try {
                Map<UUID, BanRecord> loadedBans = new HashMap<>();
                for (BanRecord b : repository.loadActiveBans()) loadedBans.put(b.playerUuid(), b);
                try {
                    java.nio.file.Path nativeFile = java.nio.file.Path.of("banned-players.json");
                    if (java.nio.file.Files.isRegularFile(nativeFile)) {
                        int count = NativeBanAudit.unknownCount(java.nio.file.Files.readString(nativeFile), loadedBans.keySet());
                        if (count > 0) plugin.logger().warning("NATIVE BAN DIVERGENCE: " + count
                                + " banned-players.json entries are unknown to Allium; native and Allium bans use independent storage. No entries imported.");
                    }
                } catch (java.io.IOException | RuntimeException error) {
                    plugin.logger().warning("Native ban divergence audit unavailable: " + error.getMessage());
                }
                List<FreezeRecord> loadedFrozen = repository.loadFrozenPlayers();
                List<JailAssignment> loadedPrisoners = repository.loadJailedPlayers();
                Map<String, JailRecord> loadedJails = jailMap(repository.loadJails());
                synchronized (stateLock) {
                    if (closed || lifecycle != epoch) return;
                    // Commands cannot write until this first snapshot is complete.
                    bans = Map.copyOf(loadedBans);
                    frozen.clear();
                    for (FreezeRecord f : loadedFrozen) frozen.put(f.playerUuid(), f);
                    prisoners.clear();
                    for (JailAssignment j : loadedPrisoners) prisoners.put(j.playerUuid(), j);
                    jails = loadedJails;
                    catalogReady = true;
                }
            } catch (SQLException | RuntimeException failure) {
                plugin.logger().warning("Discipline startup snapshot unavailable: " + failure.getMessage());
                retry(() -> loadCatalog(epoch, attempt + 1), attempt);
            }
        }, () -> {
            plugin.logger().warning("Discipline startup snapshot rejected by bounded storage executor");
            retry(() -> loadCatalog(epoch, attempt + 1), attempt);
        });
    }

    /** Publishes jails and jailed assignments loaded on an import worker before lifting the import fence. */
    public void publishImportedJails(List<JailRecord> loadedJails, List<JailAssignment> loadedPrisoners) {
        synchronized (stateLock) {
            if (closed) return;
            if (loadedJails != null) {
                jails = jailMap(loadedJails);
            }
            if (loadedPrisoners != null) {
                prisoners.clear();
                for (JailAssignment j : loadedPrisoners) prisoners.put(j.playerUuid(), j);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (closed || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String ip = event.getAddress().getHostAddress();
        BanRecord known;
        Connection c = new Connection(ip);
        connections.put(event.getUniqueId(), c);
        CompletableFuture<Void> first = new CompletableFuture<>();
        loadConnection(event.getUniqueId(), c, 0, first);
        try {
            if (settings.preloadMillis() > 0) first.get(settings.preloadMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // A timeout is unresolved state, evaluated below using the configured failure policy.
        }
        known = c.loaded ? c.authoritativeLoginBan : activeBan(event.getUniqueId(), ip);
        if (known != null) deny(event, known);
        else if (!c.loaded && !settings.allowLoginFailure()) event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER, text(Messages.DISCIPLINE_LOGIN_UNAVAILABLE, ""));
        else if (!c.loaded) plugin.logger().warning("ALLOW admitted unresolved discipline state for " + event.getUniqueId());
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) connections.remove(event.getUniqueId(), c);
    }

    private void deny(AsyncPlayerPreLoginEvent event, BanRecord ban) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, text(Messages.DISCIPLINE_BAN_DENIED, ban.reason()));
    }

    private BanRecord activeBan(UUID id, String ip) {
        Map<UUID, BanRecord> snapshot = bans;
        long now = clock.getAsLong();
        BanRecord own = snapshot.get(id);
        if (own != null && !own.isExpired(now)) return own;
        if (settings.ipBans() && ip != null)
            for (BanRecord ban : snapshot.values())
                if (ip.equals(ban.ipAddress()) && !ban.isExpired(now)) return ban;
        return null;
    }

    private void loadConnection(UUID id, Connection c, int attempt, CompletableFuture<Void> first) {
        if (!current(id, c)) { first.complete(null); return; }
        long revision = revisions.getOrDefault(id, 0L);
        long globalRevision = stateRevision.get();
        submit(() -> {
            try {
                BanRecord ban = repository.loadActiveBan(id).orElse(null);
                BanRecord ipBan = settings.ipBans() && c.address != null ? repository.loadBanByIp(c.address).orElse(null) : null;
                FreezeRecord freeze = repository.loadFrozen(id).orElse(null);
                JailAssignment jail = repository.loadJailed(id).orElse(null);
                Map<String, JailRecord> destinations = jailMap(repository.loadJails());
                synchronized (stateLock) {
                    if (!current(id, c)) return;
                    if (revision != revisions.getOrDefault(id, 0L) || globalRevision != stateRevision.get() || pending.containsKey(id)) {
                        // A local write outranks the earlier SQL read. Retry after its commit.
                        retry(() -> loadConnection(id, c, attempt + 1, first), attempt);
                        return;
                    }
                    Map<UUID, BanRecord> next = new HashMap<>(bans);
                    c.authoritativeLoginBan = ban != null ? ban : ipBan;
                    next.remove(id);
                    if (ban != null) next.put(id, ban);
                    if (ipBan != null) next.put(ipBan.playerUuid(), ipBan);
                    bans = Map.copyOf(next);
                    if (freeze == null) frozen.remove(id); else frozen.put(id, freeze);
                    if (jail == null) prisoners.remove(id); else prisoners.put(id, jail);
                    jails = destinations;
                    c.loaded = true;
                    c.failed = false;
                }
                server(() -> { if (currentPlayer(id, c)) enforceLoaded(c.player); });
            } catch (SQLException | RuntimeException failure) {
                if (current(id, c)) {
                    c.failed = true;
                    plugin.logger().warning("Discipline session load failed for " + id + ": " + failure.getMessage());
                    retry(() -> loadConnection(id, c, attempt + 1, first), attempt);
                }
            } finally { first.complete(null); }
        }, () -> {
            c.failed = true;
            first.complete(null);
            retry(() -> loadConnection(id, c, attempt + 1, first), attempt);
        });
    }

    private static Map<String, JailRecord> jailMap(List<JailRecord> records) {
        Map<String, JailRecord> out = new HashMap<>();
        for (JailRecord jail : records) out.put(jail.name(), jail);
        return Map.copyOf(out);
    }

    private boolean current(UUID id, Connection c) { return !closed && connections.get(id) == c; }
    private boolean currentPlayer(UUID id, Connection c) {
        return current(id, c) && c.player != null && c.player.isOnline() && plugin.getServer().getPlayer(id) == c.player;
    }

    private void onConnected(Player player) {
        UUID id = player.getUniqueId();
        Connection c = connections.get(id);
        if (c == null || c.player != null && c.player != player) {
            c = new Connection(player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress());
            connections.put(id, c);
            c.player = player;
            loadConnection(id, c, 0, new CompletableFuture<>());
        } else c.player = player;
        if (c.loaded) enforceLoaded(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) { onConnected(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Connection c = connections.get(player.getUniqueId());
        if (c != null && c.player == player) connections.remove(player.getUniqueId(), c);
        lastActions.remove(player.getUniqueId());
    }

    private void enforceLoaded(Player player) {
        if (BukkitServerThread.isFolia()) {
            UtilitySupport.onOwner(plugin, player, () -> enforceLoadedOnOwner(player));
            return;
        }
        enforceLoadedOnOwner(player);
    }

    private void enforceLoadedOnOwner(Player player) {
        Connection c = connections.get(player.getUniqueId());
        if (c == null || !c.loaded || !currentPlayer(player.getUniqueId(), c)) return;
        BanRecord ban = activeBan(player.getUniqueId(), c.address);
        if (ban != null) { player.kickPlayer(text(Messages.DISCIPLINE_BAN_DENIED, ban.reason())); return; }
        JailAssignment assignment = prisoners.get(player.getUniqueId());
        if (assignment != null) {
            PendingRelease release = releases.get(player.getUniqueId());
            if (release != null && release.player == player && !release.unsafeRespawn) return;
            if (assignment.isExpired(clock.getAsLong())) {
                releaseExpiredJail(player, assignment);
                return;
            }
            JailRecord jail = jails.get(assignment.jailName());
            if (jail == null) return;
            if (BukkitServerThread.isFolia()) {
                Location currentLoc = player.getLocation().clone();
                safeAsync(jail.location()).whenComplete((found, err) -> {
                    if (closed || err != null || found == null || found.isEmpty()) return;
                    Location dest = found.get();
                    if (!samePosition(currentLoc, dest)) moveRegional(player, dest);
                });
                return;
            }
            Location location = safe(jail.location());
            if (location != null && !samePosition(player.getLocation(), location)) move(player, location);
        }
    }

    /** Releases any online prisoners whose timed jail sentence has elapsed. */
    void tickExpiredJails() {
        long now = clock.getAsLong();
        for (JailAssignment assignment : List.copyOf(prisoners.values())) {
            if (!assignment.isExpired(now)) continue;
            UUID id = assignment.playerUuid();
            if (!ready(id)) continue;
            Player online = plugin.getServer().getPlayer(id);
            if (!sameSession(online)) continue;
            releaseExpiredJail(online, assignment);
        }
    }

    private void releaseExpiredJail(Player online, JailAssignment assignment) {
        UUID id = assignment.playerUuid();
        if (pending.containsKey(id) || releases.containsKey(id)) return;
        if (BukkitServerThread.isFolia()) {
            if (!expiredReleasesInFlight.add(id)) return;
            safeAsync(assignment.returnLocation()).whenComplete((found, err) -> {
                if (closed || err != null || found == null || found.isEmpty() || !sameSession(online)) {
                    expiredReleasesInFlight.remove(id);
                    return;
                }
                moveRegional(online, found.get()).whenComplete((moved, moveErr) -> {
                    expiredReleasesInFlight.remove(id);
                    if (closed || moveErr != null || !Boolean.TRUE.equals(moved) || !sameSession(online)) return;
                    commitExpiredJailRelease(online, assignment);
                });
            });
            return;
        }
        Location origin = safe(assignment.returnLocation());
        if (origin == null || !move(online, origin)) return;
        commitExpiredJailRelease(online, assignment);
    }

    private void commitExpiredJailRelease(Player online, JailAssignment assignment) {
        UUID id = assignment.playerUuid();
        if (pending.containsKey(id) || releases.containsKey(id)) return;
        PendingRelease release = new PendingRelease(online, assignment);
        if (releases.putIfAbsent(id, release) != null) return;
        Object token = new Object();
        if (writes.get() >= 256 || pending.putIfAbsent(id, token) != null) {
            releases.remove(id, release);
            return;
        }
        writes.incrementAndGet();
        revisions.merge(id, 1L, Long::sum);
        submit(() -> {
            if (closed) { finish(id, token); releases.remove(id, release); return; }
            try {
                if (release.unsafeRespawn) throw new SQLException("Return location became unsafe during expired release");
                boolean removed = repository.removeJailed(id, "System");
                if (removed) publish(() -> prisoners.remove(id, assignment));
                serverFor(online, () -> {
                    try {
                        if (release.unsafeRespawn && removed) {
                            publish(() -> prisoners.put(id, assignment));
                            restoreRelease(id, release, 0);
                            return;
                        }
                        releases.remove(id, release);
                        if (removed && sameSession(online)) {
                            send(online, Messages.DISCIPLINE_JAIL_RELEASED);
                        }
                    } finally { finish(id, token); }
                });
                if (closed) finish(id, token);
            } catch (SQLException | RuntimeException failure) {
                plugin.logger().warning("Expired jail release storage failed for " + id + ": " + failure.getMessage());
                finish(id, token);
                serverFor(online, () -> {
                    releases.remove(id, release);
                    if (sameSession(online)) enforceLoaded(online);
                });
            }
        }, () -> {
            finish(id, token);
            serverFor(online, () -> {
                releases.remove(id, release);
                if (sameSession(online)) enforceLoaded(online);
            });
        });
    }

    private boolean constrained(Player player) {
        UUID id = player.getUniqueId();
        Connection c = connections.get(id);
        return c == null || c.player != player || !c.loaded || isFrozen(id) || jailed(id) || releases.containsKey(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null) return;
        if (constrained(event.getPlayer()) && !samePosition(event.getFrom(), event.getTo())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        ScopedMove move = scopedMoves.get(event.getPlayer().getUniqueId());
        if (move != null && move.player() == event.getPlayer() && samePosition(move.destination(), event.getTo())) return;
        if (constrained(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && constrained(player)) event.setCancelled(true);
    }

    /** Whether {@code player} currently has an in-flight expired jail release returning them to origin. */
    public boolean hasPendingRelease(Player player) {
        if (player == null) return false;
        PendingRelease release = releases.get(player.getUniqueId());
        return release != null && release.player == player;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        PendingRelease release = releases.get(player.getUniqueId());
        boolean hasRelease = release != null && release.player == player;
        Connection c = connections.get(player.getUniqueId());
        JailAssignment assignment = prisoners.get(player.getUniqueId());
        boolean activeJail = c != null && c.loaded && c.player == player && assignment != null;
        var decision = codes.castled.allium.spawn.SpawnRespawnPolicy.decideRespawn(
                codes.castled.allium.spawn.SpawnRespawnPolicy.RespawnContext.builder()
                        .deathRespawn(event.getRespawnReason() == PlayerRespawnEvent.RespawnReason.DEATH)
                        .pendingJailRelease(hasRelease)
                        .jailed(activeJail)
                        .build()
        );
        if (decision.primary() == codes.castled.allium.spawn.SpawnRespawnPolicy.RespawnTarget.JAIL_RELEASE_RETURN) {
            Location origin = safeIfOwnedRegion(release.assignment.returnLocation());
            if (origin != null) { event.setRespawnLocation(origin); return; }
            // Do not discard the durable origin if it became unsafe during this release.
            release.unsafeRespawn = true;
            if (!activeJail) return;
        } else if (decision.primary() != codes.castled.allium.spawn.SpawnRespawnPolicy.RespawnTarget.JAIL) {
            return;
        }
        if (activeJail) {
            JailRecord jail = jails.get(assignment.jailName());
            if (jail != null) {
                Location location = safeIfOwnedRegion(jail.location());
                if (location != null) {
                    event.setRespawnLocation(location);
                } else if (BukkitServerThread.isFolia() && plugin.getScheduler() != null) {
                    BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
                        if (!closed && sameSession(player)) enforceLoaded(player);
                    }, () -> {}, 50L);
                }
            }
        }
    }

    private Location safeIfOwnedRegion(SavedLocation saved) {
        if (!BukkitServerThread.isFolia()) {
            return safe(saved);
        }
        World world = plugin.getServer().getWorld(saved.worldId());
        if (world == null) return null;
        int cx = (int) Math.floor(saved.x()) >> 4;
        int cz = (int) Math.floor(saved.z()) >> 4;
        if (!BukkitServerThread.region(plugin, world, cx, cz).isServerThread()) {
            return null;
        }
        return safe(saved);
    }

    private CompletableFuture<Boolean> moveRegional(Player player, Location destination) {
        CompletableFuture<Boolean> out = new CompletableFuture<>();
        UtilitySupport.onOwner(plugin, player, () -> {
            if (closed || !sameSession(player)) {
                out.complete(false);
                return;
            }
            player.leaveVehicle();
            ScopedMove token = new ScopedMove(player, destination.clone());
            scopedMoves.put(player.getUniqueId(), token);
            try {
                BukkitServerThread.teleportRegional(player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                        .whenComplete((ok, err) -> {
                            scopedMoves.remove(player.getUniqueId(), token);
                            out.complete(err == null && Boolean.TRUE.equals(ok));
                        });
            } catch (RuntimeException failure) {
                scopedMoves.remove(player.getUniqueId(), token);
                out.complete(false);
            }
        });
        return out;
    }

    private void kickOnOwner(Player target, String message) {
        UtilitySupport.onOwner(plugin, target, () -> {
            if (sameSession(target)) {
                target.kickPlayer(message);
            }
        });
    }

    private boolean move(Player player, Location destination) {
        player.leaveVehicle();
        ScopedMove token = new ScopedMove(player, destination.clone());
        scopedMoves.put(player.getUniqueId(), token);
        try { return player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN); }
        finally { scopedMoves.remove(player.getUniqueId(), token); }
    }

    private static boolean samePosition(Location a, Location b) {
        return a != null && b != null && Objects.equals(a.getWorld(), b.getWorld())
                && Double.compare(a.getX(), b.getX()) == 0 && Double.compare(a.getY(), b.getY()) == 0
                && Double.compare(a.getZ(), b.getZ()) == 0;
    }

    private SavedLocation saved(Location location) {
        World world = Objects.requireNonNull(location.getWorld());
        return new SavedLocation(world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private Location safe(SavedLocation saved) {
        World world = plugin.getServer().getWorld(saved.worldId());
        if (world == null) return null;
        return safety.resolve(new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch()), JAIL_SAFETY).orElse(null);
    }

    private CompletableFuture<Optional<Location>> safeAsync(SavedLocation saved) {
        World world = plugin.getServer().getWorld(saved.worldId());
        if (world == null) return CompletableFuture.completedFuture(Optional.empty());
        return safety.resolveAsync(plugin,
                new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch()), JAIL_SAFETY);
    }

    /** Parsing and authoritative async name resolution; command specs own registration and aliases. */
    public void dispatch(CommandSender sender, String label, String[] args) {
        String action = label.toLowerCase(Locale.ROOT);
        if (action.contains(":")) action = action.substring(action.indexOf(':') + 1);
        action = switch (action) {
            case "pardon" -> "unban";
            case "banip" -> "ipban";
            case "pardonip" -> "unbanip";
            case "tjail" -> "togglejail";
            case "remjail", "rmjail" -> "deljail";
            default -> action;
        };
        String permission = "allium." + action;
        Actor actor = new Actor(sender, permission, sender instanceof Player p ? p : null, plain(sender.getName()));
        if (!authorized(actor)) { send(sender, Messages.NO_PERMISSION); return; }
        if (closed || !catalogReady) { send(sender, Messages.DISCIPLINE_UNAVAILABLE); return; }
        if ((action.equals("ipban") || action.equals("tempbanip")) && !settings.ipBans()) {
            send(sender, Messages.DISCIPLINE_IP_DISABLED); return;
        }
        if (action.equals("banlist")) {
            if (args.length != 0) { usage(sender, "/banlist"); return; }
            listBans(sender); return;
        }
        if (action.equals("jails")) {
            if (args.length != 0) { usage(sender, "/jails"); return; }
            listJails(sender); return;
        }
        if (action.equals("jailedplayers")) {
            if (args.length != 0) { usage(sender, "/jailedplayers"); return; }
            listJailedPlayers(sender); return;
        }
        if (action.equals("setjail")) {
            if (args.length != 1 || !(sender instanceof Player)) { usage(sender, "/setjail <name>"); return; }
            setJail(actor, args[0]); return;
        }
        if (action.equals("deljail")) {
            if (args.length != 1) { usage(sender, "/deljail <jail>"); return; }
            deleteJail(actor, args[0]); return;
        }
        if (action.equals("kickall")) {
            kickAll(actor, args); return;
        }
        if (!List.of("kick", "ban", "tempban", "unban", "ipban", "tempbanip", "unbanip",
                "freeze", "jail", "unjail", "togglejail", "warn", "notes").contains(action)) {
            usage(sender, "/" + action); return;
        }
        if (args.length == 0
                || (action.equals("tempban") || action.equals("tempbanip")) && args.length < 2
                || action.equals("jail") && args.length < 2
                || action.equals("notes") && (args.length > 1 && !args[1].equalsIgnoreCase("list") && !args[1].equalsIgnoreCase("add")
                    || args.length > 2 && args[1].equalsIgnoreCase("list") || args.length == 2 && args[1].equalsIgnoreCase("add"))
                || (action.equals("unjail") || action.equals("unban") || action.equals("unbanip")) && args.length != 1) {
            usage(sender, usage(action)); return;
        }
        Long duration = null;
        if (action.equals("tempban") || action.equals("tempbanip")) {
            var parsed = DurationParser.parseMillis(args[1]);
            if (parsed.isEmpty() || parsed.getAsLong() > Long.MAX_VALUE - clock.getAsLong()) { usage(sender, usage(action)); return; }
            duration = parsed.getAsLong();
        }
        if (action.equals("tempbanip") && ModerationRepository.isLiteralIp(args[0])) {
            tempBanLiteralIp(actor, args[0].trim(), duration, reason(args, 2));
            return;
        }
        if (action.equals("unbanip") && ModerationRepository.isLiteralIp(args[0])) {
            unbanLiteralIp(actor, args[0].trim());
            return;
        }
        String finalAction = action;
        Long finalDuration = duration;
        if (plugin.getPlayerNames() == null) { send(sender, Messages.DISCIPLINE_UNAVAILABLE); return; }
        try {
            plugin.getPlayerNames().resolve(args[0]).whenComplete((found, error) -> serverFor(sender, () -> {
                if (!authorized(actor)) return;
                if (error != null) { send(sender, Messages.DISCIPLINE_UNAVAILABLE); return; }
                if (found == null || found.isEmpty()) { send(sender, Messages.PLAYER_NOT_FOUND, "{player}", MessagingService.safeName(args[0])); return; }
                NameCache.Entry target = found.get();
                if (finalAction.equals("notes") && (args.length == 1 || args[1].equalsIgnoreCase("list"))
                        || finalAction.equals("warn") && args.length == 2 && (args[1].equalsIgnoreCase("history") || args[1].equalsIgnoreCase("list"))) {
                    listHistory(actor, target, finalAction.equals("warn")); return;
                }
                if (!authorizeAction(sender, target.uuid())) return;
                mutate(actor, finalAction, target, args, finalDuration);
            }));
        } catch (RejectedExecutionException rejected) { send(sender, Messages.DISCIPLINE_UNAVAILABLE); }
    }

    private static String usage(String action) {
        return switch (action) {
            case "tempban" -> "/tempban <player> <duration> [reason]";
            case "tempbanip" -> "/tempbanip <player|ip> <duration> [reason]";
            case "unbanip" -> "/unbanip <player|ip>";
            case "jail" -> "/jail <player> <jail> [reason]";
            case "togglejail" -> "/togglejail <player> [jail] [time]";
            case "deljail" -> "/deljail <jail>";
            case "notes" -> "/notes <player> [list|add <note>]";
            case "freeze" -> "/freeze <player> [on|off] [reason]";
            case "unban", "unjail" -> "/" + action + " <player>";
            default -> "/" + action + " <player> [reason]";
        };
    }

    private void mutate(Actor actor, String action, NameCache.Entry target, String[] args, Long duration) {
        UUID id = target.uuid();
        Player online = plugin.getServer().getPlayer(id);
        // Hidden players are treated exactly like offline players for policy and command feedback.
        Player visible = online != null && online.isOnline() && PlayerNameVisibility.canSeeTarget(actor.sender(), online) ? online : null;
        String reason = reason(args, action.equals("tempban") || action.equals("tempbanip") || action.equals("jail") || action.equals("notes") ? 2 : 1);
        String actorName = actor.name();
        long now = clock.getAsLong();
        switch (action) {
            case "kick" -> {
                if (visible == null) { send(actor.sender(), Messages.PLAYER_NOT_FOUND, "{player}", target.name()); return; }
                write(actor, id, online, () -> { repository.recordAudit("KICK", actorName, id, target.name(), reason); return true; }, result -> {
                    if (sameSession(online)) kickOnOwner(online, text(Messages.DISCIPLINE_KICK_NOTICE, reason));
                    else return false;
                    return true;
                }, action, target.name(), notice("kick", actorName, target.name(), reason, null, null));
            }
            case "ban", "tempban", "ipban", "tempbanip" -> {
                boolean isIp = action.equals("ipban") || action.equals("tempbanip");
                String ip = null;
                if (isIp) {
                    if (visible != null && visible.getAddress() != null) {
                        ip = visible.getAddress().getAddress().getHostAddress();
                    } else if (action.equals("tempbanip")) {
                        BanRecord existing = bans.get(id);
                        if (existing != null && existing.ipAddress() != null) ip = existing.ipAddress();
                    }
                    if (ip == null) { send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return; }
                }
                String finalIp = ip;
                BanRecord ban = new BanRecord(id, target.name(), actorName, reason, now, duration == null ? null : now + duration, finalIp);
                write(actor, id, online, () -> {
                    repository.saveBan(ban);
                    publish(() -> { Map<UUID, BanRecord> next = new HashMap<>(bans); next.put(id, ban); bans = Map.copyOf(next); });
                    return ban;
                }, result -> {
                    if (sameSession(online)) kickOnOwner(online, text(Messages.DISCIPLINE_BAN_DENIED, result.reason()));
                    if (isIp && finalIp != null) kickMatchingIp(actor.sender(), finalIp, result.reason(), id);
                    return true;
                }, action, target.name(), notice(isIp ? "ipban" : action, actorName, target.name(), reason, ban.expiresAt(), null));
            }
            case "unban" -> write(actor, id, online, () -> {
                boolean removed = repository.removeBan(id, actorName);
                publish(() -> { Map<UUID, BanRecord> next = new HashMap<>(bans); next.remove(id); bans = Map.copyOf(next); });
                return removed;
            }, removed -> removed, action, target.name(), notice("unban", actorName, target.name(), null, null, null));
            case "unbanip" -> {
                BanRecord existing = bans.get(id);
                String ip = existing != null && existing.ipAddress() != null ? existing.ipAddress()
                        : (visible != null && visible.getAddress() != null ? visible.getAddress().getAddress().getHostAddress() : null);
                if (ip == null) { send(actor.sender(), Messages.DISCIPLINE_NOT_ACTIVE, "{player}", target.name()); return; }
                String finalIp = ip;
                write(actor, id, online, () -> {
                    int removed = repository.removeBansByIp(finalIp, actorName);
                    if (removed > 0) {
                        publish(() -> {
                            Map<UUID, BanRecord> next = new HashMap<>(bans);
                            next.values().removeIf(b -> finalIp.equals(b.ipAddress()));
                            bans = Map.copyOf(next);
                        });
                    }
                    return removed > 0;
                }, removed -> removed, "unbanip", target.name(), notice("unban", actorName, target.name(), null, null, null));
            }
            case "freeze" -> {
                boolean freeze = !isFrozen(id);
                int offset = 1;
                if (args.length > 1 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                    freeze = args[1].equalsIgnoreCase("on"); offset = 2;
                }
                FreezeRecord record = new FreezeRecord(id, target.name(), actorName, reason(args, offset), now);
                boolean next = freeze;
                write(actor, id, online, () -> {
                    if (next) repository.setFrozen(record); else repository.removeFrozen(id, actorName);
                    publish(() -> { if (next) frozen.put(id, record); else frozen.remove(id); });
                    return next;
                }, result -> {
                    if (result && sameSession(online)) UtilitySupport.onOwner(plugin, online, () -> { if (sameSession(online)) online.leaveVehicle(); });
                    return true;
                },
                        next ? "freeze" : "unfreeze", target.name(),
                        notice(next ? "freeze" : "unfreeze", actorName, target.name(), record.reason(), null, null));
            }
            case "jail" -> jail(actor, target, online, args[1], reason, null);
            case "unjail" -> unjail(actor, target, online);
            case "togglejail" -> toggleJail(actor, target, online, args);
            case "warn", "notes" -> write(actor, id, online,
                    () -> action.equals("warn") ? repository.addWarning(id, target.name(), actorName, reason)
                            : repository.addNote(id, target.name(), actorName, reason), entry -> {
                        if (action.equals("warn") && sameSession(online)) literal(online, Messages.DISCIPLINE_WARNING_NOTICE, entry.reason());
                        return true;
                    }, action, target.name(), notice(action.equals("warn") ? "warn" : "note", actorName, target.name(), reason, null, null));
            default -> throw new IllegalArgumentException(action);
        }
    }

    private void tempBanLiteralIp(Actor actor, String ip, long duration, String reason) {
        if (!rate(actor.sender())) return;
        for (Player online : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (online != null && online.isOnline() && online.getAddress() != null
                    && ip.equalsIgnoreCase(online.getAddress().getAddress().getHostAddress())
                    && !canModerate(actor.sender(), online.getUniqueId())) {
                send(actor.sender(), Messages.DISCIPLINE_EXEMPT);
                return;
            }
        }
        UUID id = ModerationRepository.ipBanUuid(ip);
        long now = clock.getAsLong();
        BanRecord ban = new BanRecord(id, ip, actor.name(), reason, now, now + duration, ip);
        write(actor, id, null, () -> {
            repository.saveBan(ban);
            publish(() -> { Map<UUID, BanRecord> next = new HashMap<>(bans); next.put(id, ban); bans = Map.copyOf(next); });
            return ban;
        }, result -> {
            kickMatchingIp(actor.sender(), ip, result.reason(), null);
            return true;
        }, "tempbanip", ip, () -> {}, null, false);
    }

    private void unbanLiteralIp(Actor actor, String ip) {
        if (!rate(actor.sender())) return;
        UUID id = ModerationRepository.ipBanUuid(ip);
        write(actor, id, null, () -> {
            int removed = repository.removeBansByIp(ip, actor.name());
            if (removed > 0) {
                publish(() -> {
                    Map<UUID, BanRecord> next = new HashMap<>(bans);
                    next.values().removeIf(b -> ip.equals(b.ipAddress()));
                    bans = Map.copyOf(next);
                });
            }
            return removed > 0;
        }, removed -> removed, "unbanip", ip, () -> {}, null, false);
    }

    private void kickMatchingIp(CommandSender actor, String ip, String reason, UUID alreadyKicked) {
        String message = text(Messages.DISCIPLINE_BAN_DENIED, reason);
        for (Player online : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (online == null || !online.isOnline() || online.getAddress() == null) continue;
            if (alreadyKicked != null && alreadyKicked.equals(online.getUniqueId())) continue;
            if (!ip.equalsIgnoreCase(online.getAddress().getAddress().getHostAddress())) continue;
            if (canModerate(actor, online.getUniqueId())) {
                kickOnOwner(online, message);
            }
        }
    }

    private void toggleJail(Actor actor, NameCache.Entry target, Player online, String[] args) {
        JailAssignment existing = prisoners.get(target.uuid());
        if (existing != null && !existing.isExpired(clock.getAsLong())) {
            if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase(existing.jailName()))) {
                unjail(actor, target, online);
                return;
            }
            if (args.length == 2) {
                var parsed = DurationParser.parseMillis(args[1]);
                if (parsed.isPresent() && parsed.getAsLong() <= Long.MAX_VALUE - clock.getAsLong()) {
                    jail(actor, target, online, existing.jailName(), existing.reason(), parsed.getAsLong());
                    return;
                }
                send(actor.sender(), Messages.DISCIPLINE_ALREADY_JAILED, "{player}", target.name(), "{jail}", existing.jailName());
                return;
            }
            if (args[1].equalsIgnoreCase(existing.jailName())) {
                var parsed = DurationParser.parseMillis(args[2]);
                if (parsed.isEmpty() || parsed.getAsLong() > Long.MAX_VALUE - clock.getAsLong()) {
                    usage(actor.sender(), usage("togglejail"));
                    return;
                }
                String reason = args.length >= 4 ? reason(args, 3) : existing.reason();
                jail(actor, target, online, existing.jailName(), reason, parsed.getAsLong());
                return;
            }
            send(actor.sender(), Messages.DISCIPLINE_ALREADY_JAILED, "{player}", target.name(), "{jail}", existing.jailName());
            return;
        }

        String jailName;
        if (args.length >= 2) {
            jailName = args[1];
        } else if (jails.size() == 1) {
            jailName = jails.keySet().iterator().next();
        } else if (jails.isEmpty()) {
            send(actor.sender(), Messages.DISCIPLINE_JAILS_EMPTY);
            return;
        } else {
            usage(actor.sender(), usage("togglejail"));
            return;
        }

        Long durationMillis = null;
        if (args.length >= 3) {
            var parsed = DurationParser.parseMillis(args[2]);
            if (parsed.isEmpty() || parsed.getAsLong() > Long.MAX_VALUE - clock.getAsLong()) {
                usage(actor.sender(), usage("togglejail"));
                return;
            }
            durationMillis = parsed.getAsLong();
        }
        String reason = args.length >= 4 ? reason(args, 3) : "No reason supplied.";
        jail(actor, target, online, jailName, reason, durationMillis);
    }

    private void setJail(Actor actor, String name) {
        Player player = actor.player();
        JailRecord record;
        try { record = new JailRecord(name, saved(player.getLocation())); }
        catch (IllegalArgumentException e) { usage(player, "/setjail <name>"); return; }
        if (BukkitServerThread.isFolia()) {
            safeAsync(record.location()).whenComplete((found, err) -> serverFor(player, () -> {
                if (!authorized(actor)) return;
                if (err != null || found == null || found.isEmpty()) { send(player, Messages.DISCIPLINE_UNSAFE_LOCATION); return; }
                commitSetJail(actor, player, record);
            }));
            return;
        }
        if (safe(record.location()) == null) { send(player, Messages.DISCIPLINE_UNSAFE_LOCATION); return; }
        commitSetJail(actor, player, record);
    }

    private void commitSetJail(Actor actor, Player player, JailRecord record) {
        if (!rate(player)) return;
        write(actor, new UUID(0, 0), null, () -> {
            repository.saveJail(record, actor.name());
            publish(() -> { Map<String, JailRecord> next = new HashMap<>(jails); next.put(record.name(), record); jails = Map.copyOf(next); });
            return record;
        }, result -> true, "setjail", record.name(), () -> {}, null, false);
    }

    private void deleteJail(Actor actor, String rawName) {
        String key;
        try { key = ModerationRepository.jailName(rawName); }
        catch (IllegalArgumentException e) { send(actor.sender(), Messages.DISCIPLINE_JAIL_NOT_FOUND, "{jail}", plain(rawName)); return; }
        if (!jails.containsKey(key)) {
            send(actor.sender(), Messages.DISCIPLINE_JAIL_NOT_FOUND, "{jail}", key);
            return;
        }
        for (JailAssignment prisoner : prisoners.values()) {
            if (key.equals(prisoner.jailName())) {
                send(actor.sender(), Messages.DISCIPLINE_JAIL_IN_USE, "{jail}", key);
                return;
            }
        }
        if (!rate(actor.sender())) return;
        write(actor, new UUID(0, 0), null, () -> {
            boolean deleted = repository.deleteJail(key, actor.name());
            if (deleted) {
                publish(() -> { Map<String, JailRecord> next = new HashMap<>(jails); next.remove(key); jails = Map.copyOf(next); });
            }
            return deleted;
        }, deleted -> deleted, "deljail", key, () -> {}, null, false);
    }

    private void kickAll(Actor actor, String[] args) {
        if (!rate(actor.sender())) return;
        String reason = reason(args, 0);
        String kickText = text(Messages.DISCIPLINE_KICK_NOTICE, reason);
        CommandSender sender = actor.sender();
        String exemptNode = settings.exemptPermission();
        boolean trustedActor = sender instanceof ConsoleCommandSender || sender.hasPermission(BYPASS_PERMISSION);
        int kicked = 0;
        for (Player online : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (online == null || !online.isOnline()) continue;
            if (sender instanceof Player p && p.getUniqueId().equals(online.getUniqueId())) continue;
            if (online.hasPermission(KICKALL_EXEMPT_PERMISSION)) continue;
            if (!trustedActor && exemptNode != null && !exemptNode.isBlank() && online.hasPermission(exemptNode)) continue;
            kickOnOwner(online, kickText);
            kicked++;
        }
        if (kicked == 0) {
            send(sender, Messages.DISCIPLINE_KICKALL_NONE);
            return;
        }
        int finalKicked = kicked;
        send(sender, Messages.DISCIPLINE_KICKALL_SUCCESS, "{count}", Integer.toString(finalKicked));
        submit(() -> {
            try {
                repository.recordAudit("KICKALL", actor.name(), null, "all(" + finalKicked + ")", reason);
            } catch (SQLException | RuntimeException failure) {
                plugin.logger().warning("Discipline kickall audit failed: " + failure.getMessage());
            }
        }, () -> {});
    }

    private void jail(Actor actor, NameCache.Entry target, Player online, String jailName, String reason, Long durationMillis) {
        JailRecord jail = jails.get(jailName.toLowerCase(Locale.ROOT));
        if (jail == null) { send(actor.sender(), Messages.DISCIPLINE_JAIL_NOT_FOUND, "{jail}", plain(jailName)); return; }
        if (BukkitServerThread.isFolia()) {
            JailAssignment existing = prisoners.get(target.uuid());
            if (existing == null && (online == null || !online.isOnline() || !ready(target.uuid()))) {
                send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return;
            }
            CompletableFuture<Optional<Location>> safeDestFuture = safeAsync(jail.location());
            CompletableFuture<SavedLocation> originFuture = new CompletableFuture<>();
            if (existing != null) {
                originFuture.complete(existing.returnLocation());
            } else {
                UtilitySupport.onOwner(plugin, online, () -> {
                    if (closed || !sameSession(online)) {
                        originFuture.complete(null);
                    } else {
                        originFuture.complete(saved(online.getLocation()));
                    }
                });
            }
            safeDestFuture.thenCombine(originFuture, (safeDest, origin) -> new Object[]{safeDest, origin})
                    .whenComplete((pair, err) -> serverFor(actor.sender(), () -> {
                        if (!authorized(actor)) return;
                        if (err != null || pair == null) { send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return; }
                        @SuppressWarnings("unchecked")
                        Optional<Location> safeDest = (Optional<Location>) pair[0];
                        SavedLocation origin = (SavedLocation) pair[1];
                        if (safeDest == null || safeDest.isEmpty()) { send(actor.sender(), Messages.DISCIPLINE_UNSAFE_LOCATION); return; }
                        if (origin == null) { send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return; }
                        commitJail(actor, target, online, jail, origin, safeDest.get(), reason, durationMillis);
                    }));
            return;
        }
        if (safe(jail.location()) == null) { send(actor.sender(), Messages.DISCIPLINE_UNSAFE_LOCATION); return; }
        JailAssignment existing = prisoners.get(target.uuid());
        if (existing == null && (online == null || !online.isOnline() || !ready(target.uuid()))) {
            send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return;
        }
        SavedLocation origin = existing == null ? saved(online.getLocation()) : existing.returnLocation();
        commitJail(actor, target, online, jail, origin, null, reason, durationMillis);
    }

    private void commitJail(Actor actor, NameCache.Entry target, Player online, JailRecord jail,
                            SavedLocation origin, Location preResolvedDest, String reason, Long durationMillis) {
        long now = clock.getAsLong();
        Long expiresAt = durationMillis == null ? null : now + durationMillis;
        JailAssignment assignment = new JailAssignment(target.uuid(), target.name(), actor.name(), reason,
                now, jail.name(), origin, expiresAt);
        write(actor, target.uuid(), online, () -> {
            JailAssignment previous = repository.loadJailed(target.uuid()).orElse(null);
            JailAssignment persisted = previous == null ? assignment : new JailAssignment(assignment.playerUuid(),
                    assignment.playerName(), assignment.actorName(), assignment.reason(), assignment.createdAt(),
                    assignment.jailName(), previous.returnLocation(), assignment.expiresAt());
            if (!repository.setJailed(persisted)) throw new SQLException("Jail no longer exists");
            publish(() -> prisoners.put(target.uuid(), persisted));
            return persisted;
        }, result -> {
            if (sameSession(online)) {
                if (BukkitServerThread.isFolia()) {
                    if (preResolvedDest == null) return false;
                    moveRegional(online, preResolvedDest);
                    return true;
                }
                Location destination = safe(jail.location());
                return destination != null && move(online, destination);
            }
            return true;
        }, "jail", target.name(), notice("jail", actor.name(), target.name(), reason, expiresAt, jail.name()));
    }

    private void unjail(Actor actor, NameCache.Entry target, Player online) {
        JailAssignment assignment = prisoners.get(target.uuid());
        if (assignment == null) { send(actor.sender(), Messages.DISCIPLINE_NOT_ACTIVE, "{player}", target.name()); return; }
        // Without a durable pending-return row, offline release would discard the exact return.
        if (!sameSession(online) || !ready(target.uuid())) { send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return; }
        if (BukkitServerThread.isFolia()) {
            safeAsync(assignment.returnLocation()).whenComplete((found, err) -> serverFor(actor.sender(), () -> {
                if (!authorized(actor)) return;
                if (err != null) { send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return; }
                if (found == null || found.isEmpty()) { send(actor.sender(), Messages.DISCIPLINE_UNSAFE_LOCATION); return; }
                if (!sameSession(online) || !ready(target.uuid())) { send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return; }
                moveRegional(online, found.get()).whenComplete((moved, moveErr) -> serverFor(actor.sender(), () -> {
                    if (closed) return;
                    if (moveErr != null || !Boolean.TRUE.equals(moved)) {
                        sendIfCurrent(actor, Messages.DISCIPLINE_UNAVAILABLE);
                        return;
                    }
                    commitUnjail(actor, target, online, assignment);
                }));
            }));
            return;
        }
        Location origin = safe(assignment.returnLocation());
        if (origin == null) { send(actor.sender(), Messages.DISCIPLINE_UNSAFE_LOCATION); return; }
        // Prove the exact return succeeded before removing its only durable copy. During storage
        // the assignment remains active and movement remains blocked. Failure re-applies the jail.
        if (!move(online, origin)) { send(actor.sender(), Messages.DISCIPLINE_UNAVAILABLE); return; }
        commitUnjail(actor, target, online, assignment);
    }

    private void commitUnjail(Actor actor, NameCache.Entry target, Player online, JailAssignment assignment) {
        PendingRelease release = new PendingRelease(online, assignment);
        releases.put(target.uuid(), release);
        write(actor, target.uuid(), online, () -> {
            if (release.unsafeRespawn) throw new SQLException("Return location became unsafe during release");
            boolean removed = repository.removeJailed(target.uuid(), actor.name());
            if (removed) publish(() -> prisoners.remove(target.uuid(), assignment));
            return removed;
        }, removed -> {
            if (release.unsafeRespawn && removed) {
                // The SQL delete raced a respawn whose original destination became unsafe.
                // Restore the durable assignment, retain its exact return, and fail closed.
                publish(() -> prisoners.put(target.uuid(), assignment));
                restoreRelease(target.uuid(), release, 0);
                return false;
            }
            releases.remove(target.uuid(), release);
            return removed;
        }, "unjail", target.name(), () -> {
            releases.remove(target.uuid(), release);
            if (sameSession(online)) enforceLoaded(online);
        }, notice("unjail", actor.name(), target.name(), null, null, null));
    }

    private void restoreRelease(UUID id, PendingRelease release, int attempt) {
        writes.incrementAndGet();
        submit(() -> {
            try {
                if (!repository.setJailed(release.assignment)) throw new SQLException("Jail missing during return restoration");
                server(() -> {
                    releases.remove(id, release);
                    if (sameSession(release.player)) enforceLoaded(release.player);
                });
            } catch (SQLException | RuntimeException failure) {
                plugin.logger().severe("Exact jail return retained but restoration unresolved for " + id + ": " + failure.getMessage());
                retry(() -> restoreRelease(id, release, attempt + 1), attempt);
            } finally { writes.decrementAndGet(); }
        }, () -> {
            writes.decrementAndGet();
            plugin.logger().severe("Jail return restoration rejected; retained original for " + id);
            retry(() -> restoreRelease(id, release, attempt + 1), attempt);
        });
    }

    private <T> void write(Actor actor, UUID id, Player expectedTarget, SqlWork<T> sql, Commit<T> commit, String action, String name) {
        write(actor, id, expectedTarget, sql, commit, action, name, () -> {}, null, true);
    }

    private <T> void write(Actor actor, UUID id, Player expectedTarget, SqlWork<T> sql, Commit<T> commit, String action, String name,
                           codes.castled.allium.discord.StaffPosts.Notice notice) {
        write(actor, id, expectedTarget, sql, commit, action, name, () -> {}, notice, true);
    }

    private <T> void write(Actor actor, UUID id, Player expectedTarget, SqlWork<T> sql, Commit<T> commit, String action, String name,
                           Runnable failed, codes.castled.allium.discord.StaffPosts.Notice notice) {
        write(actor, id, expectedTarget, sql, commit, action, name, failed, notice, true);
    }

    /** {@code notice}, when not null, goes to the Discord feeds once the action is saved and applied. */
    private <T> void write(Actor actor, UUID id, Player expectedTarget, SqlWork<T> sql, Commit<T> commit, String action, String name,
                           Runnable failed, codes.castled.allium.discord.StaffPosts.Notice notice, boolean checkTargetModerate) {
        Object token = new Object();
        if (writes.get() >= 256) { failed.run(); send(actor.sender(), Messages.DISCIPLINE_BUSY); return; }
        if (pending.putIfAbsent(id, token) != null) { failed.run(); send(actor.sender(), Messages.DISCIPLINE_BUSY); return; }
        writes.incrementAndGet();
        revisions.merge(id, 1L, Long::sum);
        // Queue a server-thread authorization checkpoint immediately before queuing the JDBC write.
        // This catches disconnects/permission changes while waiting in a saturated storage pool.
        submit(() -> serverFor(actor.sender(), () -> {
            if (!authorized(actor) || checkTargetModerate && !canModerate(actor.sender(), id)
                    || expectedTarget != null && !sameSession(expectedTarget)) {
                finish(id, token); failed.run(); sendIfCurrent(actor, Messages.DISCIPLINE_EXEMPT); return;
            }
            submit(() -> {
                if (closed) { finish(id, token); return; }
                try {
                    if (checkTargetModerate && expectedTarget == null && !(actor.sender() instanceof ConsoleCommandSender))
                        repository.recordAudit("DISCIPLINE_BYPASS", actor.name(), id, name, "offline permission override action=" + action);
                    T result = sql.run();
                    serverFor(actor.sender(), () -> {
                        try {
                            boolean permitted = authorized(actor) && (!checkTargetModerate || canModerate(actor.sender(), id));
                            // Durable actions must reach the captured target session even when the
                            // actor leaves during SQL. Authorization here gates feedback only.
                            boolean changed = commit.apply(result);
                            if (changed && notice != null) notices.accept(notice);
                            if (permitted) {
                                if (changed) {
                                    if (action.equals("setjail")) {
                                        send(actor.sender(), Messages.DISCIPLINE_JAIL_SET, "{jail}", name);
                                    } else if (action.equals("deljail")) {
                                        send(actor.sender(), Messages.DISCIPLINE_JAIL_DELETED, "{jail}", name);
                                    } else {
                                        send(actor.sender(), Messages.DISCIPLINE_SUCCESS, "{action}", action, "{player}", name);
                                    }
                                } else {
                                    send(actor.sender(), Messages.DISCIPLINE_NOT_ACTIVE, "{player}", name);
                                }
                            }
                        } finally { finish(id, token); }
                    });
                    if (closed) finish(id, token);
                } catch (SQLException | RuntimeException failure) {
                    plugin.logger().warning("Discipline " + action + " storage failed for " + id + ": " + failure.getMessage());
                    finish(id, token);
                    serverFor(actor.sender(), () -> { failed.run(); sendIfCurrent(actor, Messages.DISCIPLINE_UNAVAILABLE); });
                }
            }, () -> { finish(id, token); failed.run(); sendIfCurrent(actor, Messages.DISCIPLINE_UNAVAILABLE); });
        }), () -> { finish(id, token); failed.run(); sendIfCurrent(actor, Messages.DISCIPLINE_UNAVAILABLE); });
    }

    private void finish(UUID id, Object token) { if (pending.remove(id, token)) writes.decrementAndGet(); }
    private void publish(Runnable state) {
        synchronized (stateLock) { state.run(); stateRevision.incrementAndGet(); }
    }
    private boolean authorized(Actor actor) {
        return !closed && (actor.player() == null || sameSession(actor.player())) && actor.sender().hasPermission(actor.permission());
    }
    private boolean sameSession(Player player) {
        return player != null && player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }
    private void sendIfCurrent(Actor actor, Messages message) { if (authorized(actor)) send(actor.sender(), message); }

    private void listBans(CommandSender sender) {
        List<BanRecord> records = bans.values().stream().filter(b -> !b.isExpired(clock.getAsLong()))
                .sorted(Comparator.comparing(BanRecord::playerName)).toList();
        if (records.isEmpty()) { send(sender, Messages.DISCIPLINE_LIST_EMPTY); return; }
        send(sender, Messages.DISCIPLINE_LIST_HEADER, "{count}", Integer.toString(records.size()));
        for (BanRecord b : records) literal(sender, Messages.DISCIPLINE_LIST_ENTRY, b.reason(), "{player}", b.playerName(), "{actor}", b.actorName());
    }

    private void listJails(CommandSender sender) {
        List<String> names = jails.keySet().stream().sorted().toList();
        if (names.isEmpty()) { send(sender, Messages.DISCIPLINE_JAILS_EMPTY); return; }
        send(sender, Messages.DISCIPLINE_JAILS_LIST, "{count}", Integer.toString(names.size()), "{jails}", String.join(", ", names));
    }

    private void listJailedPlayers(CommandSender sender) {
        long now = clock.getAsLong();
        List<JailAssignment> active = prisoners.values().stream()
                .filter(j -> !j.isExpired(now))
                .filter(j -> {
                    Player online = plugin.getServer().getPlayer(j.playerUuid());
                    return online == null || PlayerNameVisibility.canSeeTarget(sender, online);
                })
                .sorted(Comparator.comparing(j -> j.playerName().toLowerCase(Locale.ROOT)))
                .toList();
        if (active.isEmpty()) { send(sender, Messages.DISCIPLINE_JAILED_PLAYERS_EMPTY); return; }
        send(sender, Messages.DISCIPLINE_JAILED_PLAYERS_HEADER, "{count}", Integer.toString(active.size()));
        for (JailAssignment assignment : active) {
            send(sender, Messages.DISCIPLINE_JAILED_PLAYERS_ENTRY,
                    "{player}", assignment.playerName(),
                    "{jail}", assignment.jailName(),
                    "{remaining}", assignment.formattedRemaining(now));
        }
    }

    private void listHistory(Actor actor, NameCache.Entry target, boolean warnings) {
        submit(() -> {
            try {
                List<HistoryEntry> entries = warnings ? repository.listWarnings(target.uuid(), 50) : repository.listNotes(target.uuid(), 50);
                serverFor(actor.sender(), () -> {
                    if (!authorized(actor)) return;
                    if (entries.isEmpty()) { send(actor.sender(), Messages.DISCIPLINE_LIST_EMPTY); return; }
                    send(actor.sender(), Messages.DISCIPLINE_LIST_HEADER, "{count}", Integer.toString(entries.size()));
                    for (HistoryEntry entry : entries) literal(actor.sender(), Messages.DISCIPLINE_LIST_ENTRY, entry.reason(),
                            "{player}", entry.targetName(), "{actor}", entry.actorName());
                });
            } catch (SQLException failure) { serverFor(actor.sender(), () -> sendIfCurrent(actor, Messages.DISCIPLINE_UNAVAILABLE)); }
        }, () -> sendIfCurrent(actor, Messages.DISCIPLINE_UNAVAILABLE));
    }

    private void retry(Runnable task, int attempt) {
        if (!closed && attempt < settings.retries() && plugin.getScheduler() != null)
            plugin.getScheduler().runAsyncLater(() -> { if (!closed) task.run(); }, Math.min(300_000, settings.retryMillis() * (1L << attempt)));
    }
    private void submit(Runnable task, Runnable rejected) {
        if (closed) { rejected.run(); return; }
        try { io.execute(task); } catch (RejectedExecutionException e) { rejected.run(); }
    }
    private void server(Runnable task) {
        if (closed) return;
        if (plugin.getScheduler() == null) task.run();
        else plugin.getScheduler().runOnServer(() -> { if (!closed) task.run(); });
    }
    private void serverFor(CommandSender sender, Runnable task) {
        if (closed) return;
        if (plugin.getScheduler() == null) {
            task.run();
            return;
        }
        Runnable guarded = () -> { if (!closed) task.run(); };
        if (BukkitServerThread.isFolia()) {
            BukkitServerThread.runFor(plugin, plugin.getScheduler(), sender, guarded, guarded, 0L);
        } else {
            plugin.getScheduler().runOnServer(guarded);
        }
    }
    private static String plain(String raw) { return UntrustedText.clean(raw == null ? "" : raw); }
    private static String reason(String[] args, int start) {
        String reason = start >= args.length ? "No reason supplied." : plain(String.join(" ", Arrays.copyOfRange(args, start, args.length)));
        return reason.length() > 256 ? reason.substring(0, 256) : reason;
    }
    /** The command's usage card, narrowed to the form that was wrong. */
    private void usage(CommandSender sender, String usage) {
        if (closed) return;
        String command = usage.startsWith("/") ? usage.substring(1).split(" ", 2)[0] : usage;
        UtilitySupport.onOwner(plugin, sender, () -> {
            if (!closed) codes.castled.allium.command.CommandUsageCard.sendInvalid(plugin, sender, command, command, usage);
        });
    }
    private void send(CommandSender sender, Messages message, String... replacements) { UtilitySupport.send(plugin, sender, message, replacements); }
    private FormattedText formatted(Messages message, String body, String... replacements) {
        return UntrustedText.insert(plugin.getMessageManager().resolve(message, replacements), UntrustedText.parse(body, UntrustedText.Allow.NONE));
    }
    private String text(Messages message, String body) { return formatted(message, body).toLegacy(); }
    private void literal(CommandSender sender, Messages message, String body, String... replacements) {
        FormattedText content = formatted(message, body, replacements);
        UtilitySupport.onOwner(plugin, sender, () -> TextSender.sendLiteral(sender, content));
    }
}
