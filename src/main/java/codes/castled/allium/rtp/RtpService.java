package codes.castled.allium.rtp;

import codes.castled.allium.PluginStart;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.Money;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.ProtectionService;
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
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Random teleports ({@code /rtp}, {@code /tpr}) and their locations ({@code /settpr}, {@code rtp.yml}).
 * A spot is found first (see {@link RtpSearch}), then handed to {@link TeleportService}, so the
 * warmup, cooldown ({@code teleport.cooldowns.rtp}), world access, safety and {@code /back} are the
 * same as every other teleport. Server thread throughout.
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
    private volatile RtpSettings settings;
    private final Set<UUID> searching = new HashSet<>();
    private final Map<String, Deque<Location>> cache = new HashMap<>();
    private final Set<String> refilling = new HashSet<>();
    private final ThreadPoolExecutor io;
    private boolean closed;

    public RtpService(PluginStart plugin, Supplier<TeleportService> teleports) {
        this.plugin = plugin;
        this.teleports = teleports;
        this.file = plugin.getDataFolder().toPath().resolve("rtp.yml");
        Supplier<ProtectionService> protection = plugin::getProtectionService;
        this.search = new RtpSearch(plugin.getScheduler(), safety, protection);
        this.io = plugin.getScheduler().boundedPool("Allium-RTP-Payments", 1, 64);
    }

    public void enable() throws IOException, InvalidConfigurationException {
        if (!Files.exists(file)) plugin.saveResource("rtp.yml", false);
        settings = RtpSettings.read(read());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        closed = true;
        TeleportService tp = teleports.get();
        if (tp != null) tp.cooldownProvider(COMMAND, null);
        HandlerList.unregisterAll(this);
        cache.clear();
        searching.clear();
    }

    /** Re-reads {@code rtp.yml}; on an error the previous settings stay and false is returned. */
    public boolean reload() {
        try {
            settings = RtpSettings.read(read());
            cache.clear();
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException invalid) {
            plugin.logger().warning("Invalid rtp.yml; the previous random teleport settings are kept: " + invalid.getMessage());
            return false;
        }
    }

    public RtpSettings settings() {
        return settings;
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
        TeleportService tp = teleports.get();
        if (closed || tp == null) {
            send(actor, Messages.MODULE_DISABLED);
            return;
        }
        boolean self = actor == target;
        if (!actor.hasPermission(self ? PERMISSION : PERMISSION_OTHERS)) {
            send(actor, Messages.NO_PERMISSION);
            return;
        }
        RtpSettings current = settings;
        Optional<RtpSettings.Resolved> resolved = resolve(current, requested, target.getWorld().getName());
        if (resolved.isEmpty()) {
            send(actor, Messages.RTP_UNKNOWN_LOCATION, "{location}", requested == null ? current.defaultLocation() : requested);
            return;
        }
        RtpSettings.Resolved where = resolved.get();
        World world = plugin.getServer().getWorld(where.area().world());
        if (world != null && current.disabled(world.getName())) {
            Optional<RtpSettings.Resolved> redirect = requested == null && current.disabledWorldRedirect() != null
                    ? resolve(current, current.disabledWorldRedirect(), target.getWorld().getName()) : Optional.empty();
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
        if (current.perLocationPermission() && !actor.hasPermission(PERMISSION_LOCATION + where.name())) {
            send(actor, Messages.NO_PERMISSION);
            return;
        }
        if (!tp.canEnterWorld(target, world)) {
            send(actor, Messages.WORLD_ACCESS_DENIED, "{world}", world.getName());
            return;
        }
        // Registered on every use, so a reloaded teleport module gets it too.
        tp.cooldownProvider(COMMAND, this::cooldownMillis);
        long cooldown = tp.cooldownSeconds(actor, target.getUniqueId(), COMMAND);
        if (cooldown > 0) {
            send(actor, Messages.COOLDOWN_ACTIVE, "{time}", cooldown + "s");
            return;
        }
        UUID id = target.getUniqueId();
        if (!searching.add(id)) {
            send(actor, Messages.RTP_BUSY);
            return;
        }
        // Only a player's own /rtp is paid: staff, first-join and death teleports never spend their money.
        Money price = self && !actor.hasPermission(PERMISSION_FREE) ? current.costOf(where.area()) : new Money(0);
        Run run = new Run(actor, target, self, where, world, current, biomes, price);
        if (price.minor() <= 0) {
            start(run, null);
            return;
        }
        if (!PaidWorkflowSupport.ready(plugin)) {
            searching.remove(id);
            send(actor, Messages.RTP_COST_UNAVAILABLE);
            return;
        }
        reserve(run);
    }

    /** One random teleport in progress. */
    private record Run(CommandSender actor, Player target, boolean self, RtpSettings.Resolved where, World world,
                       RtpSettings settings, @Nullable Set<String> biomes, Money price) {
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
                    onServer(() -> abandon(run, Messages.ECO_STORAGE));
                    return;
                }
                onServer(() -> {
                    if (result instanceof EconomyResult.Committed) {
                        if (closed || !run.target().isOnline()) {
                            settle(workflow, false, run);
                            searching.remove(account);
                            return;
                        }
                        start(run, workflow);
                    } else if (result instanceof EconomyResult.Rejected refused
                            && ("INSUFFICIENT_FUNDS".equals(refused.reason()) || "MISSING_ACCOUNT".equals(refused.reason()))) {
                        searching.remove(account);
                        send(run.actor(), Messages.RTP_INSUFFICIENT_FUNDS, "{price}", format(run.price()));
                    } else if (result instanceof EconomyResult.Unknown unknown) {
                        searching.remove(account);
                        send(run.actor(), Messages.ECO_UNKNOWN, "{operation}", unknown.operationId().toString());
                    } else {
                        abandon(run, Messages.ECO_STORAGE);
                    }
                });
            });
        } catch (RejectedExecutionException full) {
            abandon(run, Messages.ECO_BUSY);
        }
    }

    private void abandon(Run run, Messages reason) {
        searching.remove(run.target().getUniqueId());
        send(run.actor(), reason);
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
                    if (!(result instanceof EconomyResult.Committed)) {
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
        UUID id = run.target().getUniqueId();
        if (closed || tp == null) {
            searching.remove(id);
            if (workflow != null) settle(workflow, false, run);
            return;
        }
        Player target = run.target();
        CommandSender actor = run.actor();
        RtpSettings current = run.settings();
        send(target, Messages.RTP_SEARCHING, "{world}", run.world().getName());
        if (!run.self()) send(actor, Messages.RTP_SENT_OTHER, "{player}", target.getName(), "{world}", run.world().getName());
        TeleportSettings rules = tp.safetyRulesFor(target, actor);
        // A biome search never takes a cached spot: those were found for any biome.
        CompletableFuture<Location> fromCache = run.biomes() == null
                ? cached(run.where(), run.world(), target, rules, current) : CompletableFuture.completedFuture(null);
        fromCache.thenCompose(spot -> spot != null ? CompletableFuture.completedFuture(spot)
                        : search.find(run.world(), run.where().area(), current, target, rules, current.findAttempts(), run.biomes()))
                .whenComplete((spot, error) -> onServer(() -> {
                    searching.remove(id);
                    TeleportService now = teleports.get();
                    if (closed || now == null || !target.isOnline() || error != null || spot == null) {
                        if (workflow != null) settle(workflow, false, run);
                        if (!closed && target.isOnline() && (error != null || spot == null)) {
                            send(actor, Messages.RTP_NOT_FOUND, "{attempts}", Integer.toString(current.findAttempts()));
                            if (!run.self()) send(target, Messages.RTP_NOT_FOUND, "{attempts}", Integer.toString(current.findAttempts()));
                        }
                        return;
                    }
                    hold(spot);
                    java.util.function.Consumer<Boolean> outcome = arrived -> {
                        if (workflow != null) settle(workflow, arrived, run);
                    };
                    if (run.self()) {
                        now.teleport(target, spot, COMMAND, outcome);
                    } else {
                        now.forceLocation(actor, target, spot, COMMAND, outcome);
                    }
                    if (run.biomes() == null) refill(run.where(), run.world(), current);
                }));
    }

    /** {@code teleport.cooldowns.rtp}, or the shortest cooldown tier the player holds, in milliseconds (-1: configured). */
    private long cooldownMillis(Player player) {
        long seconds = settings.cooldownFor(player::hasPermission);
        return seconds < 0 ? -1 : seconds * 1000L;
    }

    private Optional<RtpSettings.Resolved> resolve(RtpSettings current, @Nullable String requested, String currentWorld) {
        return current.resolve(requested, currentWorld, name -> plugin.getServer().getWorld(name) != null);
    }

    /** A spot found earlier for this location, checked again for this player; null when none is usable. */
    private CompletableFuture<Location> cached(RtpSettings.Resolved where, World world, Player target, TeleportSettings rules,
                                               RtpSettings current) {
        Deque<Location> spots = cache.get(where.name());
        Location spot = spots == null ? null : spots.poll();
        if (spot == null || spot.getWorld() != world) return CompletableFuture.completedFuture(null);
        CompletableFuture<Location> checked = new CompletableFuture<>();
        AsyncChunks.load(world, spot.getBlockX() >> 4, spot.getBlockZ() >> 4, plugin.getScheduler())
                .whenComplete((chunk, error) -> onServer(() -> checked.complete(
                        error == null && target.isOnline() && safety.safe(spot, rules) && search.claimFree(target, spot, current)
                                ? spot : null)));
        return checked;
    }

    /** Keeps up to {@code cache-size} spots ready for a location, found one at a time in the background. */
    private void refill(RtpSettings.Resolved where, World world, RtpSettings current) {
        TeleportService tp = teleports.get();
        if (closed || tp == null || current.cacheSize() == 0 || settings != current || !refilling.add(where.name())) return;
        Deque<Location> spots = cache.computeIfAbsent(where.name(), key -> new ArrayDeque<>());
        if (spots.size() >= current.cacheSize()) {
            refilling.remove(where.name());
            return;
        }
        search.find(world, where.area(), current, null, tp.settings().forMover(false, false), current.findAttempts(), null)
                .whenComplete((spot, error) -> onServer(() -> {
                    refilling.remove(where.name());
                    if (spot != null && !closed && settings == current) {
                        spots.add(spot);
                        refill(where, world, current);
                    }
                }));
    }

    /** Keeps the spot's chunk loaded through the warmup, so the teleport does not load it again. */
    private void hold(Location spot) {
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

    // -- First join ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (settings.firstJoin() && !event.getPlayer().hasPlayedBefore()) {
            firstJoin(event.getPlayer(), 0);
        }
    }

    /** Waits for the player's teleport data to load (up to 20 seconds), then sends them off as the console. */
    private void firstJoin(Player player, int tries) {
        plugin.getScheduler().runOnServerLater(() -> {
            TeleportService tp = teleports.get();
            if (closed || tp == null || !player.isOnline()) return;
            if (!tp.ready(player.getUniqueId())) {
                if (tries < 20) firstJoin(player, tries + 1);
                return;
            }
            random(plugin.getServer().getConsoleSender(), player, settings.firstJoinLocation());
        }, 1000L);
    }

    // -- Death -----------------------------------------------------------------------------------

    /**
     * A player respawning after death without a bed or respawn anchor is sent to a random spot of
     * {@code on-death.location}, as the console. Jailed players are left alone.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        RtpSettings current = settings;
        if (!current.onDeath() || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH
                || event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        Player player = event.getPlayer();
        if (jailed(player)) return;
        plugin.getScheduler().runOnServerLater(() -> {
            if (!closed && player.isOnline() && !player.isDead() && !jailed(player)) {
                random(plugin.getServer().getConsoleSender(), player, settings.onDeathLocation());
            }
        }, 250L);
    }

    private boolean jailed(Player player) {
        return plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("moderation") instanceof codes.castled.allium.module.ModerationModule moderation
                && moderation.isEnabled() && moderation.discipline() != null && moderation.discipline().jailed(player.getUniqueId());
    }

    // -- /rtp info, /rtp list --------------------------------------------------------------------

    public void info(CommandSender actor, @Nullable String requested) {
        RtpSettings current = settings;
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
        Set<String> names = new TreeSet<>(settings.locations().keySet());
        for (World world : plugin.getServer().getWorlds()) {
            if (!settings.disabled(world.getName())) names.add(world.getName());
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
        List<String> out = new ArrayList<>(new TreeSet<>(settings.locations().keySet()));
        for (World world : plugin.getServer().getWorlds()) {
            if (!settings.disabled(world.getName()) && !out.contains(world.getName())) out.add(world.getName());
        }
        return out;
    }

    // -- /settpr ---------------------------------------------------------------------------------

    /** {@code /settpr <location> <center|minrange|maxrange|shape|miny|maxy|world|remove> [value]}. */
    public void set(CommandSender actor, String[] args, Consumer<String> usage) {
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
            settings = next;
            cache.remove(name);
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
        plugin.getMessageManager().send(to, message, replacements);
    }
}
