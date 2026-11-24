package codes.castled.allium.spawn;

import codes.castled.allium.PluginStart;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.messaging.VanishSource;
import codes.castled.allium.moderation.DisciplineService;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.module.RtpModule;
import codes.castled.allium.rtp.RtpService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.teleport.TeleportService;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Coordinates opt-in player join placement and exactly-once first-join onboarding:
 * first-join announcement, initial spawn placement, and starter kit delivery.
 *
 * <p>Uses {@link SpawnRespawnResolver} for semantic precedence, {@link OnboardingRepository} for
 * durable per-step idempotency across reconnects and partial outages, and delegates execution to
 * {@link TeleportService} and {@link KitService}.
 */
public final class OnboardingService implements Listener {

    private static final int MAX_READY_RETRIES = 20;
    private static final long RETRY_DELAY_MILLIS = 250L;

    private final PluginStart plugin;
    private final OnboardingRepository repository;
    private final TeleportService teleports;
    private final ExecutorService io;
    private final boolean ownsPool;
    @Nullable private final KitService kitsOverride;
    @Nullable private final RtpService rtpOverride;
    @Nullable private final DisciplineService disciplineOverride;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Set<UUID> kitInFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public OnboardingService(PluginStart plugin, OnboardingRepository repository, TeleportService teleports) {
        this(plugin, repository, teleports,
                plugin.getScheduler().boundedPool("Allium-Onboarding", 1, 256), true, null, null, null);
    }

    OnboardingService(
            PluginStart plugin,
            OnboardingRepository repository,
            TeleportService teleports,
            ExecutorService io,
            @Nullable KitService kitsOverride,
            @Nullable RtpService rtpOverride,
            @Nullable DisciplineService disciplineOverride
    ) {
        this(plugin, repository, teleports, io, false, kitsOverride, rtpOverride, disciplineOverride);
    }

    private OnboardingService(
            PluginStart plugin,
            OnboardingRepository repository,
            TeleportService teleports,
            ExecutorService io,
            boolean ownsPool,
            @Nullable KitService kitsOverride,
            @Nullable RtpService rtpOverride,
            @Nullable DisciplineService disciplineOverride
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.teleports = teleports;
        this.io = io;
        this.ownsPool = ownsPool;
        this.kitsOverride = kitsOverride;
        this.rtpOverride = rtpOverride;
        this.disciplineOverride = disciplineOverride;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        active.clear();
        kitInFlight.clear();
        if (ownsPool) {
            io.shutdown();
            try {
                if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                    io.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                io.shutdownNow();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (closed) return;
        FileConfiguration config = plugin.getConfig();
        if (config == null) return;

        boolean announceEnabled = config.getBoolean("join-and-quit-messages.first-join.enabled", false);
        String announceFormat = config.getString(
                "join-and-quit-messages.first-join.format",
                "&dWelcome &f%name% &dto the server for the first time!"
        );
        boolean firstJoinSpawn = config.getBoolean("teleport.first-join-spawn", false);
        boolean spawnOnJoin = config.getBoolean("teleport.spawn-on-join", false);
        String firstJoinKit = config.getString("kits.first-join-kit", "");
        boolean hasKit = firstJoinKit != null && !firstJoinKit.isBlank();

        if (!announceEnabled && !firstJoinSpawn && !spawnOnJoin && !hasKit) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean bukkitFirstJoin = !player.hasPlayedBefore();
        boolean vanished = isVanished(player);

        // Quick join-policy evaluation on the join thread (captures world, jail, and group/default spawn).
        SpawnRespawnResolver.JoinResolution initialResolution = SpawnRespawnResolver.evaluateJoin(
                plugin, player, bukkitFirstJoin, teleports, rtp(), discipline());
        if (initialResolution.decision().primary() == SpawnRespawnPolicy.JoinTarget.JAIL) {
            return;
        }

        // Returning player when no first-join onboarding steps are enabled: only handle spawn-on-join.
        if (!bukkitFirstJoin && !announceEnabled && !firstJoinSpawn && !hasKit) {
            if (spawnOnJoin) {
                runReturningSpawn(player, initialResolution);
            }
            return;
        }

        if (!active.add(playerId)) {
            return;
        }

        // Pre-compute first-join resolution on the join thread in case a returning player needs outage recovery.
        SpawnRespawnResolver.JoinResolution firstJoinResolution = bukkitFirstJoin
                ? initialResolution
                : SpawnRespawnResolver.evaluateJoin(plugin, player, true, teleports, rtp(), discipline());

        try {
            io.execute(() -> {
                try {
                    if (closed) {
                        active.remove(playerId);
                        return;
                    }
                    OnboardingRepository.State state;
                    if (bukkitFirstJoin) {
                        state = repository.begin(playerId);
                    } else {
                        Optional<OnboardingRepository.State> existing = repository.load(playerId);
                        if (existing.isEmpty() || existing.get().completed()) {
                            active.remove(playerId);
                            if (spawnOnJoin) {
                                runOnPlayer(player, () -> runReturningSpawn(player, initialResolution), () -> {});
                            }
                            return;
                        }
                        state = existing.get();
                    }
                    if (state.completed()) {
                        active.remove(playerId);
                        if (spawnOnJoin) {
                            runOnPlayer(player, () -> runReturningSpawn(player, initialResolution), () -> {});
                        }
                        return;
                    }
                    runFirstJoinSteps(
                            player,
                            state,
                            firstJoinResolution,
                            announceEnabled,
                            announceFormat,
                            vanished
                    );
                } catch (SQLException | RuntimeException failure) {
                    active.remove(playerId);
                    plugin.logger().warning("First-join onboarding unavailable for " + player.getName()
                            + ": " + failure.getMessage());
                }
            });
        } catch (RejectedExecutionException busy) {
            active.remove(playerId);
            plugin.logger().warning("First-join onboarding queue busy for " + player.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        active.remove(id);
        kitInFlight.remove(id);
    }

    private void runFirstJoinSteps(
            Player player,
            OnboardingRepository.State state,
            SpawnRespawnResolver.JoinResolution resolution,
            boolean announceEnabled,
            @Nullable String announceFormat,
            boolean vanished
    ) throws SQLException {
        UUID playerId = player.getUniqueId();
        SpawnRespawnPolicy.JoinDecision decision = resolution.decision();

        boolean needAnnounce = announceEnabled && !vanished && announceFormat != null && !announceFormat.isBlank();
        boolean needSpawnMove = decision.primary() == SpawnRespawnPolicy.JoinTarget.GROUP_SPAWN
                || decision.primary() == SpawnRespawnPolicy.JoinTarget.DEFAULT_SPAWN;
        boolean needKit = decision.grantsFirstJoinKit();

        // 1. First-join announcement (at-most-once via atomic DB claim).
        if (needAnnounce && !state.announced()) {
            if (repository.claimAnnounce(playerId)) {
                String template = announceFormat;
                onServer(() -> {
                    if (!closed && samePlayer(player)) {
                        broadcastFirstJoin(player, template);
                    }
                });
            }
        }

        // 2. Placement step.
        boolean asyncPlacementPending = false;
        if (!state.placed()) {
            if (needSpawnMove) {
                Location target = resolution.targetLocation(decision.primary());
                if (target != null) {
                    asyncPlacementPending = true;
                    runOnPlayer(player, () -> placeAtSpawn(player, target, 0, arrived -> {
                        if (arrived) {
                            submitIo(() -> {
                                repository.markPlaced(playerId);
                                tryComplete(playerId, needAnnounce, true, needKit);
                            });
                        }
                    }), () -> {});
                }
            } else {
                // FIRST_JOIN_RTP is handled by RtpService; KEEP_POSITION and DEFER_TO_WORLD_PLUGIN need no move.
                repository.markPlaced(playerId);
            }
        }

        // 3. Starter kit step (only persisted once KitService confirms delivery; failed/outage attempts retry on next join).
        boolean asyncKitPending = false;
        if (needKit && !state.kitGranted()) {
            String kitName = decision.grantKit();
            KitService kitService = kits();
            if (kitName != null && kitService != null && kitInFlight.add(playerId)) {
                asyncKitPending = true;
                runOnPlayer(player, () -> deliverStarterKit(player, kitService, kitName, 0, delivered -> {
                    kitInFlight.remove(playerId);
                    if (delivered) {
                        submitIo(() -> {
                            repository.claimKit(playerId, kitName);
                            tryComplete(playerId, needAnnounce, needSpawnMove, true);
                        });
                    }
                }), () -> kitInFlight.remove(playerId));
            }
        }

        // If no async step is still running, check completion now.
        if (!asyncPlacementPending && !asyncKitPending) {
            tryComplete(playerId, needAnnounce, needSpawnMove, needKit);
        }
        active.remove(playerId);
    }

    private void tryComplete(UUID playerId, boolean needAnnounce, boolean needPlacement, boolean needKit) throws SQLException {
        Optional<OnboardingRepository.State> current = repository.load(playerId);
        if (current.isEmpty() || current.get().completed()) return;
        OnboardingRepository.State s = current.get();
        if (needAnnounce && !s.announced()) return;
        if (needPlacement && !s.placed()) return;
        if (needKit && !s.kitGranted()) return;
        repository.markCompleted(playerId);
    }

    private void runReturningSpawn(Player player, SpawnRespawnResolver.JoinResolution resolution) {
        if (closed || !samePlayer(player)) return;
        SpawnRespawnPolicy.JoinTarget primary = resolution.decision().primary();
        if (primary != SpawnRespawnPolicy.JoinTarget.GROUP_SPAWN
                && primary != SpawnRespawnPolicy.JoinTarget.DEFAULT_SPAWN) {
            return;
        }
        Location target = resolution.targetLocation(primary);
        if (target != null) {
            placeAtSpawn(player, target, 0, ignored -> {});
        }
    }

    private void placeAtSpawn(Player player, Location target, int attempt, Consumer<Boolean> outcome) {
        if (closed || !samePlayer(player)) {
            outcome.accept(false);
            return;
        }
        if (!teleports.ready(player.getUniqueId())) {
            if (attempt < MAX_READY_RETRIES) {
                BukkitServerThread.runFor(plugin, plugin.getScheduler(), player,
                        () -> placeAtSpawn(player, target, attempt + 1, outcome),
                        () -> outcome.accept(false),
                        RETRY_DELAY_MILLIS);
            } else {
                outcome.accept(false);
            }
            return;
        }
        teleports.forceLocation(plugin.getServer().getConsoleSender(), player, target.clone(), "spawn", outcome);
    }

    private void deliverStarterKit(Player player, KitService kitService, String kitName, int attempt, Consumer<Boolean> outcome) {
        if (closed || !samePlayer(player)) {
            outcome.accept(false);
            return;
        }
        if (!kitService.readyForOnboarding(player)) {
            if (attempt < MAX_READY_RETRIES) {
                BukkitServerThread.runFor(plugin, plugin.getScheduler(), player,
                        () -> deliverStarterKit(player, kitService, kitName, attempt + 1, outcome),
                        () -> outcome.accept(false),
                        RETRY_DELAY_MILLIS);
            } else {
                outcome.accept(false);
            }
            return;
        }
        kitService.grantOnboardingKit(player, kitName, outcome);
    }

    private void broadcastFirstJoin(Player player, String template) {
        String display = player.getDisplayName();
        if (display == null || display.isEmpty()) display = player.getName();
        String rendered = template
                .replace("%name%", player.getName())
                .replace("%player%", player.getName())
                .replace("{player}", player.getName())
                .replace("%displayname%", display);
        FormattedText formatted = TextFormatter.parse(rendered);
        var server = plugin.getServer();
        if (server == null) return;
        for (Player viewer : server.getOnlinePlayers()) {
            TextSender.send(viewer, formatted);
        }
        if (server.getConsoleSender() != null) {
            TextSender.send(server.getConsoleSender(), formatted);
        }
    }

    private boolean isVanished(Player player) {
        if (VanishSource.METADATA.isVanished(player)) return true;
        var modules = plugin.getModuleManager();
        return modules != null
                && modules.getModule("moderation") instanceof ModerationModule mod
                && mod.isEnabled() && mod.moderation() != null && mod.moderation().isVanished(player);
    }

    @Nullable
    private KitService kits() {
        if (kitsOverride != null) return kitsOverride;
        var modules = plugin.getModuleManager();
        if (modules != null && modules.getModule("kits") instanceof KitsModule mod && mod.isEnabled()) {
            return mod.kits();
        }
        return null;
    }

    @Nullable
    private RtpService rtp() {
        if (rtpOverride != null) return rtpOverride;
        var modules = plugin.getModuleManager();
        if (modules != null && modules.getModule("rtp") instanceof RtpModule mod && mod.isEnabled()) {
            return mod.rtp();
        }
        return null;
    }

    @Nullable
    private DisciplineService discipline() {
        if (disciplineOverride != null) return disciplineOverride;
        var modules = plugin.getModuleManager();
        if (modules != null && modules.getModule("moderation") instanceof ModerationModule mod && mod.isEnabled()) {
            return mod.discipline();
        }
        return null;
    }

    private boolean samePlayer(Player player) {
        var server = plugin.getServer();
        if (server == null) return false;
        return player.isOnline() && server.getPlayer(player.getUniqueId()) == player;
    }

    private void onServer(Runnable task) {
        if (plugin.getScheduler().isServerThread()) {
            task.run();
        } else {
            plugin.getScheduler().runOnServer(task);
        }
    }

    private void runOnPlayer(Player player, Runnable task, Runnable retired) {
        if (BukkitServerThread.isFolia()) {
            var owner = BukkitServerThread.entity(plugin, player);
            if (owner.isServerThread()) {
                task.run();
            } else {
                plugin.getScheduler().runOn(owner, task, retired, 0L);
            }
            return;
        }
        onServer(task);
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    private void submitIo(SqlWork work) {
        if (closed) return;
        try {
            io.execute(() -> {
                try {
                    work.run();
                } catch (SQLException | RuntimeException failure) {
                    plugin.logger().warning("Onboarding persistence update failed: " + failure.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }
}
