package codes.castled.allium.rtp;

import codes.castled.allium.PluginStart;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.Money;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.teleport.DestinationSafety;
import codes.castled.allium.teleport.TeleportService;
import codes.castled.allium.teleport.TeleportSettings;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Random teleports ({@code /rtp}, {@code /tpr}) and their locations ({@code /settpr}, {@code rtp.yml}).
 * A spot is found first (see {@link RtpSearch}), then handed to {@link TeleportService}, so the
 * warmup, cooldown ({@code teleport.cooldowns.rtp}), world access, safety and {@code /back} are the
 * same as every other teleport. On Folia, candidate checks use the destination region and
 * player state and the handoff use the player's owner.
 */
public final class RtpService implements Listener {

    public static final String PERMISSION = "allium.rtp";
    public static final String PERMISSION_OTHERS = "allium.rtp.others";
    public static final String PERMISSION_SET = "allium.settpr";
    public static final String PERMISSION_LOCATION = "allium.rtp.location.";
    public static final String PERMISSION_BIOME = "allium.rtp.biome";
    public static final String PERMISSION_FREE = "allium.rtp.bypass.cost";
    static final String COMMAND = "rtp";
    /** How long a found spot's chunk is kept loaded for the warmup that follows. */
    static final long HOLD_MILLIS = 60_000L;
    static final List<String> SET_KEYS = List.of("center", "minrange", "maxrange", "shape", "miny", "maxy", "world", "remove");

    private final PluginStart plugin;
    private final Supplier<TeleportService> teleports;
    private final Path file;
    private final DestinationSafety safety = new DestinationSafety();
    private final RtpSearch search;
    private volatile Configuration configuration;
    private final Map<UUID, Run> searching = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor io;
    private volatile boolean closed;

    private record Configuration(RtpSettings settings, Map<String, CachedSpots> cache) {
        Configuration(RtpSettings settings) {
            this(settings, new ConcurrentHashMap<>());
        }
    }

    private record CachedSpots(Deque<Location> spots, AtomicBoolean refilling) {
        CachedSpots() {
            this(new ConcurrentLinkedDeque<>(), new AtomicBoolean());
        }
    }

    public RtpService(PluginStart plugin, Supplier<TeleportService> teleports) {
        this.plugin = plugin;
        this.teleports = teleports;
        this.file = plugin.getDataFolder().toPath().resolve("rtp.yml");
        this.search = new RtpSearch(plugin, safety, plugin::getProtectionService);
        this.io = plugin.getScheduler().boundedPool("Allium-RTP-Payments", 1, 64);
    }

    public void enable() throws IOException, InvalidConfigurationException {
        if (!Files.exists(file)) plugin.saveResource("rtp.yml", false);
        configuration = new Configuration(RtpSettings.read(read()));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        closed = true;
        TeleportService tp = teleports.get();
        if (tp != null) tp.cooldownProvider(COMMAND, null);
        HandlerList.unregisterAll(this);
        configuration.cache().clear();
        searching.clear();
    }

    /** Re-reads {@code rtp.yml}; on an error the previous settings stay and false is returned. */
    public synchronized boolean reload() {
        try {
            configuration = new Configuration(RtpSettings.read(read()));
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException invalid) {
            plugin.logger().warning("Invalid rtp.yml; the previous random teleport settings are kept: " + invalid.getMessage());
            return false;
        }
    }

    public RtpSettings settings() {
        return configuration.settings();
    }

    private YamlConfiguration read() throws IOException, InvalidConfigurationException {
        if (Files.size(file) > 1_048_576) throw new IOException("rtp.yml exceeds 1 MiB");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    // -- /rtp ------------------------------------------------------------------------------------

    /**
     * Sends {@code target} to a random spot of {@code requested} (a location or world name; null is
     * {@code default-location}). {@code actor} is who asked: the target themselves, staff, or the
     * console (first join).
     */
    public void random(CommandSender actor, Player target, @Nullable String requested) {
        random(actor, target, requested, null);
    }

    /**
     * As {@link #random(CommandSender, Player, String)}; {@code biomes} (biome keys) limits the spot to
     * those biomes ({@code /rtp biome}).
     */
    public void random(CommandSender actor, Player target, @Nullable String requested, @Nullable Set<String> biomes) {
        random(actor, target, requested, biomes, null, null);
    }

    /** A priced sign settles its fee and any location cost together when the teleport arrives. */
    public void useSign(Player player, @Nullable String requested, Money price, BooleanSupplier valid) {
        random(player, player, requested, null, price, valid);
    }

    private void random(CommandSender actor, Player target, @Nullable String requested, @Nullable Set<String> biomes,
                        @Nullable Money signPrice, @Nullable BooleanSupplier valid) {
        if (closed || teleports.get() == null) {
            send(actor, Messages.MODULE_DISABLED);
            return;
        }
        var request = new Request(actor, target, requested, biomes == null ? null : Set.copyOf(biomes), signPrice, valid, configuration);
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), actor, () -> {
            if (!actor.hasPermission(request.self() ? PERMISSION : PERMISSION_OTHERS)) {
                send(actor, Messages.NO_PERMISSION);
                return;
            }
            BukkitServerThread.onOwner(plugin, plugin.getScheduler(), target, () -> resolveForTarget(request));
        });
    }

    private record Request(CommandSender actor, Player target, @Nullable String location, @Nullable Set<String> biomes,
                           @Nullable Money signPrice, @Nullable BooleanSupplier valid, Configuration configuration) {
        boolean self() { return actor == target; }
    }

    private void resolveForTarget(Request request) {
        if (!current(request.configuration()) || !samePlayer(request.target())) return;
        var actor = request.actor();
        var current = request.configuration().settings();
        String requested = request.location();
        String targetWorld = request.target().getWorld().getName();
        Optional<RtpSettings.Resolved> resolved = resolve(current, requested, targetWorld);
        if (resolved.isEmpty()) {
            send(actor, Messages.RTP_UNKNOWN_LOCATION, "{location}", requested == null ? current.defaultLocation() : requested);
            return;
        }
        RtpSettings.Resolved where = resolved.get();
        World world = plugin.getServer().getWorld(where.area().world());
        if (world != null && current.disabled(world.getName())) {
            Optional<RtpSettings.Resolved> redirect = requested == null && current.disabledWorldRedirect() != null
                    ? resolve(current, current.disabledWorldRedirect(), targetWorld) : Optional.empty();
            World redirected = redirect.map(r -> plugin.getServer().getWorld(r.area().world())).orElse(null);
            if (redirected == null || current.disabled(redirected.getName())) {
                send(actor, Messages.RTP_DISABLED_WORLD, "{world}", world.getName());
                return;
            }
            where = redirect.get();
            world = redirected;
        }
        if (world == null) {
            send(actor, Messages.RTP_UNKNOWN_LOCATION, "{location}", where.name());
            return;
        }
        // Named-location permissions and cost exemptions belong to the issuer.
        RtpSettings.Resolved destination = where;
        World destinationWorld = world;
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), actor, () -> authorize(request, destination, destinationWorld));
    }

    private void authorize(Request request, RtpSettings.Resolved where, World world) {
        TeleportService tp = teleports.get();
        if (!current(request.configuration()) || tp == null) return;
        CommandSender actor = request.actor();
        Player target = request.target();
        RtpSettings current = request.configuration().settings();
        String locationPermission = PERMISSION_LOCATION + where.name();
        if (!actor.hasPermission(request.self() ? PERMISSION : PERMISSION_OTHERS)
                || current.perLocationPermission() && !actor.hasPermission(locationPermission)) {
            send(actor, Messages.NO_PERMISSION);
            return;
        }
        long cooldown = tp.cooldownSeconds(actor, target.getUniqueId(), COMMAND);
        if (cooldown > 0) {
            send(actor, Messages.COOLDOWN_ACTIVE, "{time}", cooldown + "s");
            return;
        }
        // Staff, first-join and death teleports do not pay the location cost.
        Money price = request.self() && !actor.hasPermission(PERMISSION_FREE) ? current.costOf(where.area()) : new Money(0);
        if (request.signPrice() != null) {
            if (request.signPrice().minor() > Money.HARD - price.minor()) {
                send(actor, Messages.RTP_COST_UNAVAILABLE);
                return;
            }
            price = price.plus(request.signPrice());
        }
        BooleanSupplier canUseSign = request.valid() == null ? null : () -> validSign(request, locationPermission);
        var run = new Run(actor, target, request.self(), where, world, request.configuration(), request.biomes(), price, canUseSign,
                actor.hasPermission("allium.teleport.unsafe"));
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), target, () -> begin(run));
    }

    private boolean validSign(Request request, String locationPermission) {
        if (!current(request.configuration())) return false;
        try {
            return request.valid().getAsBoolean() && request.actor().hasPermission(PERMISSION)
                    && (!request.configuration().settings().perLocationPermission() || request.actor().hasPermission(locationPermission));
        } catch (RuntimeException failure) {
            plugin.logger().warning("Could not recheck the random teleport sign: " + failure);
            return false;
        }
    }

    private void begin(Run run) {
        TeleportService tp = teleports.get();
        Player target = run.target();
        if (!current(run.configuration()) || tp == null || !samePlayer(target)) return;
        if (!tp.canEnterWorld(target, run.world())) {
            send(run.actor(), Messages.WORLD_ACCESS_DENIED, "{world}", run.world().getName());
            return;
        }
        // Register on every use so a reloaded teleport module also gets the tier provider.
        tp.cooldownProvider(COMMAND, this::cooldownMillis);
        if (searching.putIfAbsent(target.getUniqueId(), run) != null) {
            send(run.actor(), Messages.RTP_BUSY);
            return;
        }
        if (run.price().minor() <= 0) {
            start(run, null);
            return;
        }
        if (!PaidWorkflowSupport.ready(plugin)) {
            abandon(run, Messages.RTP_COST_UNAVAILABLE);
            return;
        }
        reserve(run);
    }

    /** One random teleport in progress. */
    private record Run(CommandSender actor, Player target, boolean self, RtpSettings.Resolved where, World world,
                       Configuration configuration, @Nullable Set<String> biomes, Money price, @Nullable BooleanSupplier valid, boolean unsafe) {
    }

    /** Holds the price before the search; the hold is captured only when the player arrives. */
    private void reserve(Run run) {
        UUID workflow = UUID.randomUUID();
        UUID account = run.target().getUniqueId();
        try {
            io.execute(() -> {
                EconomyResult.Mutation result;
                try {
                    result = PaidWorkflowSupport.reservations(plugin).reserve(EconomyReservations.Kind.RTP, workflow, account,
                            run.price(), c -> true);
                } catch (java.sql.SQLException | RuntimeException failure) {
                    plugin.logger().warning("Random teleport charge for " + run.target().getName() + " failed: " + failure.getMessage());
                    onRunOwner(run, () -> abandon(run, Messages.ECO_STORAGE), null);
                    return;
                }
                boolean mayBeHeld = result instanceof EconomyResult.Committed || result instanceof EconomyResult.Unknown;
                onRunOwner(run, () -> {
                    if (!active(run)) {
                        endSearch(run);
                        if (mayBeHeld) settle(workflow, false, run);
                        return;
                    }
                    if (result instanceof EconomyResult.Committed) {
                        if (run.valid() != null && !run.valid().getAsBoolean()) {
                            settle(workflow, false, run);
                            endSearch(run);
                            return;
                        }
                        start(run, workflow);
                    } else if (result instanceof EconomyResult.Rejected refused
                            && ("INSUFFICIENT_FUNDS".equals(refused.reason()) || "MISSING_ACCOUNT".equals(refused.reason()))) {
                        endSearch(run);
                        send(run.actor(), Messages.RTP_INSUFFICIENT_FUNDS, "{price}", format(run.price()));
                    } else if (result instanceof EconomyResult.Unknown unknown) {
                        endSearch(run);
                        send(run.actor(), Messages.ECO_UNKNOWN, "{operation}", unknown.operationId().toString());
                        settle(workflow, false, run);
                    } else {
                        abandon(run, Messages.ECO_STORAGE);
                    }
                }, mayBeHeld ? workflow : null);
            });
        } catch (RejectedExecutionException full) {
            abandon(run, Messages.ECO_BUSY);
        }
    }

    private void abandon(Run run, Messages reason) {
        if (endSearch(run) && current(run.configuration())) send(run.actor(), reason);
    }

    /** Captures ({@code arrived}) or releases a held price; a failure leaves the hold, which a restart refunds. */
    private void settle(UUID workflow, boolean arrived, Run run) {
        try {
            io.execute(() -> {
                try {
                    var reservations = PaidWorkflowSupport.reservations(plugin);
                    EconomyResult.Mutation result = arrived
                            ? reservations.capture(EconomyReservations.Kind.RTP, workflow, c -> true)
                            : reservations.release(EconomyReservations.Kind.RTP, workflow, c -> true);
                    if (!(result instanceof EconomyResult.Committed) && (arrived
                            || !(result instanceof EconomyResult.Rejected refused) || !"MISSING_RESERVATION".equals(refused.reason()))) {
                        plugin.logger().warning("Random teleport hold " + workflow + " for " + run.target().getName()
                                + " was not " + (arrived ? "captured" : "released") + " (" + result.getClass().getSimpleName()
                                + "); it stays held until /eco resolve or the next restart refunds it.");
                    } else if (arrived) {
                        onServer(() -> send(run.target(), Messages.RTP_PAID, "{price}", format(run.price())));
                    }
                } catch (java.sql.SQLException | RuntimeException failure) {
                    plugin.logger().warning("Random teleport hold " + workflow + " could not be settled: " + failure.getMessage()
                            + "; it stays held until /eco resolve or the next restart refunds it.");
                }
            });
        } catch (RejectedExecutionException full) {
            plugin.logger().warning("Random teleport hold " + workflow + " could not be queued; the next restart refunds it.");
        }
    }

    private String format(Money price) {
        return EconomyDisplay.read(plugin.getConfig()).format(price);
    }

    /** Searches (or takes a cached spot) and hands the spot to the teleport pipeline. */
    private void start(Run run, @Nullable UUID workflow) {
        TeleportService tp = teleports.get();
        if (!active(run) || tp == null) {
            endSearch(run);
            if (workflow != null) settle(workflow, false, run);
            return;
        }
        Player target = run.target();
        CommandSender actor = run.actor();
        RtpSettings current = run.configuration().settings();
        send(target, Messages.RTP_SEARCHING, "{world}", run.world().getName());
        if (!run.self()) send(actor, Messages.RTP_SENT_OTHER, "{player}", target.getName(), "{world}", run.world().getName());
        TeleportSettings rules = tp.safetyRulesFor(target, run.unsafe());
        // A biome search never takes a cached spot: those were found for any biome.
        CompletableFuture<Location> fromCache = run.biomes() == null
                ? cached(run, rules) : CompletableFuture.completedFuture(null);
        fromCache.thenCompose(spot -> spot != null ? CompletableFuture.completedFuture(spot)
                        : search.find(run.world(), run.where().area(), current, target, rules, current.findAttempts(), run.biomes(), () -> active(run)))
                .whenComplete((spot, error) -> onRunOwner(run, () -> {
                    boolean validSearch = active(run);
                    endSearch(run);
                    TeleportService now = teleports.get();
                    if (!validSearch || now == null || error != null || spot == null
                            || run.valid() != null && !run.valid().getAsBoolean()) {
                        if (workflow != null) settle(workflow, false, run);
                        if (validSearch && (error != null || spot == null)) {
                            send(actor, Messages.RTP_NOT_FOUND, "{attempts}", Integer.toString(current.findAttempts()));
                            if (!run.self()) send(target, Messages.RTP_NOT_FOUND, "{attempts}", Integer.toString(current.findAttempts()));
                        }
                        return;
                    }
                    hold(spot);
                    Consumer<Boolean> outcome = arrived -> {
                        if (workflow != null) settle(workflow, arrived, run);
                    };
                    if (run.self() && run.valid() != null) {
                        now.teleport(target, spot::clone, COMMAND, run.valid(), outcome);
                    } else if (run.self()) {
                        now.teleport(target, spot, COMMAND, outcome);
                    } else {
                        now.forceLocation(actor, target, spot, COMMAND, outcome);
                    }
                    if (run.biomes() == null) refill(run.where(), run.world(), run.configuration());
                }, workflow));
    }

    /** {@code teleport.cooldowns.rtp}, or the shortest cooldown tier the player holds, in milliseconds (-1: configured). */
    private long cooldownMillis(Player player) {
        long seconds = settings().cooldownFor(player::hasPermission);
        return seconds < 0 ? -1 : seconds * 1000L;
    }

    private Optional<RtpSettings.Resolved> resolve(RtpSettings current, @Nullable String requested, String currentWorld) {
        return current.resolve(requested, currentWorld, name -> plugin.getServer().getWorld(name) != null);
    }

    /** A spot found earlier for this location, checked again for this player; null when none is usable. */
    private CompletableFuture<Location> cached(Run run, TeleportSettings rules) {
        var cached = run.configuration().cache().get(run.where().name());
        Location spot = cached == null ? null : cached.spots().poll();
        if (spot == null || spot.getWorld() != run.world()) return CompletableFuture.completedFuture(null);
        return search.inLoadedColumn(run.world(), spot.getBlockX(), spot.getBlockZ(), () -> active(run),
                () -> safety.safe(spot, rules) && search.claimFree(run.target(), spot, run.configuration().settings()) ? spot : null);
    }

    /** Keeps up to {@code cache-size} spots ready for a location, found one at a time in the background. */
    private void refill(RtpSettings.Resolved where, World world, Configuration config) {
        TeleportService tp = teleports.get();
        RtpSettings current = config.settings();
        if (tp == null || current.cacheSize() == 0 || !current(config)) return;
        CachedSpots cached = config.cache().computeIfAbsent(where.name(), key -> new CachedSpots());
        if (!cached.refilling().compareAndSet(false, true)) return;
        if (cached.spots().size() >= current.cacheSize()) {
            cached.refilling().set(false);
            return;
        }
        search.find(world, where.area(), current, null, tp.settings().forMover(false, false), current.findAttempts(), null,
                        () -> current(config))
                .whenComplete((spot, error) -> {
                    boolean save = spot != null && current(config);
                    if (save) cached.spots().add(spot);
                    cached.refilling().set(false);
                    if (save) refill(where, world, config);
                });
    }

    /** Keeps the spot's chunk loaded through the warmup, so the teleport does not load it again. */
    private void hold(Location spot) {
        // Folia's teleport pipeline loads the destination again during its regional safety checks.
        if (BukkitServerThread.isFolia()) return;
        try {
            Chunk chunk = spot.getChunk();
            chunk.addPluginChunkTicket(plugin);
            plugin.getScheduler().runOnServerLater(() -> chunk.removePluginChunkTicket(plugin), HOLD_MILLIS);
        } catch (RuntimeException unsupported) {
            // Tickets are only an optimisation.
        }
    }

    private void onServer(Runnable task) {
        if (plugin.getScheduler().isServerThread()) {
            task.run();
        } else {
            plugin.getScheduler().runOnServer(task);
        }
    }

    private boolean current(Configuration expected) {
        return !closed && configuration == expected;
    }

    private boolean samePlayer(Player target) {
        return BukkitServerThread.isFolia() ? plugin.getServer().getPlayer(target.getUniqueId()) == target : target.isOnline();
    }

    private boolean active(Run run) {
        return current(run.configuration()) && searching.get(run.target().getUniqueId()) == run && samePlayer(run.target());
    }

    private boolean endSearch(Run run) {
        return searching.remove(run.target().getUniqueId(), run);
    }

    private void onRunOwner(Run run, Runnable task, @Nullable UUID workflow) {
        if (!BukkitServerThread.isFolia()) {
            onServer(task);
            return;
        }
        var owner = BukkitServerThread.entity(plugin, run.target());
        if (owner.isServerThread()) {
            task.run();
            return;
        }
        Runnable retired = () -> {
            endSearch(run);
            if (workflow != null) settle(workflow, false, run);
        };
        try {
            if (plugin.getScheduler().runOn(owner, task, retired, 0) == TaskHandle.NONE) retired.run();
        } catch (RuntimeException failed) {
            retired.run();
            plugin.logger().warning("Could not resume a random teleport: " + failed);
        }
    }

    // -- First join ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (settings().firstJoin() && !event.getPlayer().hasPlayedBefore()) {
            firstJoin(event.getPlayer(), 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Run run = searching.get(player.getUniqueId());
        if (run != null && run.target() == player) endSearch(run);
    }

    /** Waits for the player's teleport data to load (up to 20 seconds), then sends them off as the console. */
    private void firstJoin(Player player, int tries) {
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
            TeleportService tp = teleports.get();
            if (closed || tp == null || !samePlayer(player) || !settings().firstJoin()) return;
            if (!tp.ready(player.getUniqueId())) {
                if (tries < 20) firstJoin(player, tries + 1);
                return;
            }
            random(plugin.getServer().getConsoleSender(), player, settings().firstJoinLocation());
        }, () -> {}, 1000L);
    }

    // -- Death -----------------------------------------------------------------------------------

    /**
     * A player respawning after death without a bed or respawn anchor is sent to a random spot of
     * {@code on-death.location}, as the console. Jailed players are left alone.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        RtpSettings current = settings();
        if (!current.onDeath() || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH
                || event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        Player player = event.getPlayer();
        if (jailed(player)) return;
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
            if (!closed && samePlayer(player) && !player.isDead() && !jailed(player) && settings().onDeath()) {
                random(plugin.getServer().getConsoleSender(), player, settings().onDeathLocation());
            }
        }, () -> {}, 250L);
    }

    private boolean jailed(Player player) {
        return plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("moderation") instanceof codes.castled.allium.module.ModerationModule moderation
                && moderation.isEnabled() && moderation.discipline() != null && moderation.discipline().jailed(player.getUniqueId());
    }

    // -- /rtp info, /rtp list --------------------------------------------------------------------

    public void info(CommandSender actor, @Nullable String requested) {
        RtpSettings current = settings();
        String currentWorld = actor instanceof Player player ? player.getWorld().getName()
                : plugin.getServer().getWorlds().get(0).getName();
        Optional<RtpSettings.Resolved> resolved = resolve(current, requested, currentWorld);
        if (resolved.isEmpty()) {
            send(actor, Messages.RTP_UNKNOWN_LOCATION, "{location}", requested == null ? current.defaultLocation() : requested);
            return;
        }
        RtpSettings.Area area = resolved.get().area();
        World world = plugin.getServer().getWorld(area.world());
        Location borderCenter = world == null ? null : world.getWorldBorder().getCenter();
        String center = area.centerX() != null && area.centerZ() != null
                ? (long) Math.floor(area.centerX()) + ", " + (long) Math.floor(area.centerZ())
                : borderCenter == null ? "world border" : (long) Math.floor(borderCenter.getX()) + ", " + (long) Math.floor(borderCenter.getZ()) + " (world border)";
        String max = area.maxRange() != null ? number(area.maxRange()) : number(current.defaultMaxRange());
        String height = (area.minY() == null ? "auto" : area.minY().toString()) + " to " + (area.maxY() == null ? "auto" : area.maxY().toString());
        send(actor, Messages.RTP_INFO, "{location}", resolved.get().name(), "{world}", area.world(), "{center}", center,
                "{min}", number(area.minRange()), "{max}", max, "{shape}", area.shape().name().toLowerCase(Locale.ROOT),
                "{height}", height);
    }

    public void list(CommandSender actor) {
        RtpSettings current = settings();
        Set<String> names = new TreeSet<>(current.locations().keySet());
        for (World world : plugin.getServer().getWorlds()) {
            if (!current.disabled(world.getName())) names.add(world.getName());
        }
        send(actor, Messages.RTP_LOCATIONS, "{locations}", String.join(", ", names));
    }

    private volatile List<String> biomeKeys;

    /** Every biome the server knows ({@code plains}, {@code cherry_grove}), for {@code /rtp biome}. */
    public List<String> biomeKeys() {
        List<String> known = biomeKeys;
        if (known != null) return known;
        Set<String> keys = new TreeSet<>();
        try {
            // Through Keyed: Biome is an enum on 1.20 and a registry interface on 26.x.
            for (Object biome : (Iterable<?>) org.bukkit.Registry.BIOME) {
                try {
                    if (biome instanceof org.bukkit.Keyed keyed) {
                        String key = RtpSettings.biomeKey(keyed.getKey().getKey());
                        if (!key.equals("custom")) keys.add(key);
                    }
                } catch (RuntimeException noKey) {
                    // A biome without a key cannot be named.
                }
            }
        } catch (LinkageError | RuntimeException noRegistry) {
            // Without a biome registry any well-formed name is accepted; the landing's biome is checked anyway.
            keys.clear();
        }
        known = List.copyOf(keys);
        biomeKeys = known;
        return known;
    }

    /** Location names and enabled worlds, for completion. */
    public List<String> names() {
        RtpSettings current = settings();
        List<String> out = new ArrayList<>(new TreeSet<>(current.locations().keySet()));
        for (World world : plugin.getServer().getWorlds()) {
            if (!current.disabled(world.getName()) && !out.contains(world.getName())) out.add(world.getName());
        }
        return out;
    }

    // -- /settpr ---------------------------------------------------------------------------------

    /** {@code /settpr <location> <center|minrange|maxrange|shape|miny|maxy|world|remove> [value]}. */
    public synchronized void set(CommandSender actor, String[] args, Consumer<String> usage) {
        if (!actor.hasPermission(PERMISSION_SET)) {
            send(actor, Messages.NO_PERMISSION);
            return;
        }
        if (args.length < 2 || args.length > 3) {
            usage.accept("/settpr");
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        String key = args[1].toLowerCase(Locale.ROOT);
        String value = args.length == 3 ? args[2] : null;
        if (!RtpSettings.validName(name) || !SET_KEYS.contains(key)) {
            usage.accept("/settpr");
            return;
        }
        boolean needsValue = !key.equals("center") && !key.equals("remove");
        if (needsValue != (value != null)) {
            usage.accept("/settpr <location> " + key + (needsValue ? " <value>" : ""));
            return;
        }
        if (key.equals("center") && !(actor instanceof Player)) {
            send(actor, Messages.PLAYER_ONLY);
            return;
        }
        String path = "locations." + name;
        try {
            YamlConfiguration yaml = read();
            if (key.equals("remove")) {
                if (!yaml.contains(path)) {
                    send(actor, Messages.RTP_UNKNOWN_LOCATION, "{location}", name);
                    return;
                }
                yaml.set(path, null);
            } else {
                if (!yaml.contains(path + ".world") && !key.equals("world")) {
                    yaml.set(path + ".world", actor instanceof Player player ? player.getWorld().getName() : name);
                }
                apply(yaml, path, key, value, actor);
            }
            RtpSettings next = RtpSettings.read(yaml);
            save(yaml);
            configuration = new Configuration(next);
            plugin.logger().info("Random teleport location " + name + " " + key + (value == null ? "" : " " + value)
                    + " set by " + actor.getName() + ".");
            if (key.equals("remove")) {
                send(actor, Messages.RTP_REMOVED, "{location}", name);
            } else {
                send(actor, Messages.RTP_SET, "{location}", name, "{key}", key,
                        "{value}", key.equals("center") ? describeCenter(yaml, path) : value);
            }
        } catch (IllegalArgumentException invalid) {
            send(actor, Messages.RTP_INVALID_VALUE, "{reason}", String.valueOf(invalid.getMessage()));
        } catch (IOException | InvalidConfigurationException failed) {
            send(actor, Messages.RTP_SAVE_FAILED, "{reason}", String.valueOf(failed.getMessage()));
        }
    }

    private void apply(YamlConfiguration yaml, String path, String key, String value, CommandSender actor) {
        switch (key) {
            case "center" -> {
                Location here = ((Player) actor).getLocation();
                yaml.set(path + ".world", here.getWorld().getName());
                yaml.set(path + ".center.x", Math.floor(here.getX()) + 0.5);
                yaml.set(path + ".center.z", Math.floor(here.getZ()) + 0.5);
            }
            case "minrange" -> yaml.set(path + ".min-range", parseNumber(value, "min-range"));
            case "maxrange" -> yaml.set(path + ".max-range", parseNumber(value, "max-range"));
            case "shape" -> {
                String shape = value.toLowerCase(Locale.ROOT);
                if (!shape.equals("square") && !shape.equals("circle")) throw new IllegalArgumentException("shape must be square or circle");
                yaml.set(path + ".shape", shape);
            }
            case "miny", "maxy" -> yaml.set(path + (key.equals("miny") ? ".min-y" : ".max-y"),
                    value.equalsIgnoreCase("none") ? null : (int) Math.floor(parseNumber(value, key)));
            case "world" -> {
                World world = plugin.getServer().getWorld(value);
                if (world == null) throw new IllegalArgumentException("no world named " + value);
                yaml.set(path + ".world", world.getName());
            }
            default -> throw new IllegalArgumentException("unknown setting " + key);
        }
    }

    static double parseNumber(String value, String what) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number) || number < -30_000_000 || number > 30_000_000) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(what + " must be a number");
        }
    }

    private static String describeCenter(YamlConfiguration yaml, String path) {
        return (long) Math.floor(yaml.getDouble(path + ".center.x")) + ", " + (long) Math.floor(yaml.getDouble(path + ".center.z"))
                + " in " + yaml.getString(path + ".world");
    }

    private void save(YamlConfiguration yaml) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".allium-rtp-", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.copy(file, file.resolveSibling("rtp.yml.before-edit"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String number(double value) {
        return value == Math.floor(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private void send(CommandSender to, Messages message, String... replacements) {
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), to, () -> {
            if (!(to instanceof Player player) || samePlayer(player)) plugin.getMessageManager().send(to, message, replacements);
        });
    }
}
