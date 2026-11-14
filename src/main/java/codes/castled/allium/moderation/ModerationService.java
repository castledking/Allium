package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.CommandMapBridge;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.messaging.MuteSource;
import codes.castled.allium.messaging.VanishSource;
import codes.castled.allium.module.TeleportModule;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Runtime vanish and mute service backed by {@link ModerationRepository} on a bounded storage
 * executor. Implements {@link VanishSource} and {@link MuteSource} so messaging, social spy, mail,
 * teleport, warps, and tab completion share one authoritative moderation state without main-thread
 * SQL.
 *
 * <p>Visibility pairs are recomputed on plugin enable, player join, {@code /vanish}, and
 * {@code /allium reload}, plus lazy per-pair verification when visibility is queried.
 */
public final class ModerationService implements Listener, VanishSource, MuteSource {

    public static final String PERMISSION_VANISH = "allium.vanish";
    public static final String PERMISSION_VANISH_OTHERS = "allium.vanish.others";
    public static final String PERMISSION_VANISH_SEE = "allium.vanish.see";
    public static final String PERMISSION_VANISH_CONTAINER_LIVE = "allium.vanish.container.live";
    public static final String PERMISSION_MUTE = "allium.mute";
    public static final String PERMISSION_UNMUTE = "allium.unmute";
    public static final String METADATA_VANISHED = "vanished";
    public static final long SLOW_LOAD_FAULT_MILLIS = 6_000L;

    static final List<String> DEFAULT_MUTED_COMMANDS = List.of(
            "/me",
            "/say",
            "/tell",
            "/minecraft:msg",
            "/teammsg",
            "/msg",
            "/allium:msg",
            "/reply",
            "/allium:reply",
            "/mail send",
            "/allium:mail send",
            "/mail gift",
            "/allium:mail gift"
    );

    public enum MuteLoadState {
        LOADING,
        READY,
        FAILED
    }

    private record MutedCommandRule(boolean namespaced, List<String> tokens) {
        static @Nullable MutedCommandRule parse(String raw) {
            if (raw == null) {
                return null;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1).trim();
            }
            if (normalized.isEmpty()) {
                return null;
            }
            String[] parts = normalized.split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) {
                return null;
            }
            boolean namespaced = parts[0].indexOf(':') >= 0;
            return new MutedCommandRule(namespaced, List.of(parts));
        }

        String rootLabel() {
            return tokens.get(0);
        }

        boolean matchesSubTokens(List<String> lowerInputTokens) {
            if (lowerInputTokens.size() < tokens.size()) {
                return false;
            }
            for (int i = 1; i < tokens.size(); i++) {
                if (!lowerInputTokens.get(i).equals(tokens.get(i))) {
                    return false;
                }
            }
            return true;
        }

        boolean matchesTokensFallback(List<String> lowerInputTokens, String unnamespacedCommand) {
            if (!matchesSubTokens(lowerInputTokens)) {
                return false;
            }
            String firstInput = namespaced ? lowerInputTokens.get(0) : unnamespacedCommand;
            return firstInput.equals(tokens.get(0));
        }
    }

    private record ModerationSettings(
            long preloadWaitMillis,
            int loadRetries,
            long retryFirstDelayMillis,
            long retryMaxDelayMillis,
            boolean allowVanishPublicChat,
            boolean muteFailClosed,
            List<MutedCommandRule> mutedCommands
    ) {
        static ModerationSettings fromConfig(@Nullable ConfigurationSection section) {
            long preload = section == null ? 2_000L : Math.max(0L, Math.min(10_000L, section.getLong("preload-wait-millis", 2_000L)));
            ConfigurationSection retrySection = section == null ? null : section.getConfigurationSection("load-retry");
            int retries = retrySection == null ? 4 : Math.max(0, Math.min(10, retrySection.getInt("retries", 4)));
            long firstDelay = retrySection == null ? 1_000L : Math.max(50L, Math.min(60_000L, retrySection.getLong("first-delay-millis", 1_000L)));
            long maxDelay = retrySection == null ? 15_000L : Math.max(firstDelay, Math.min(300_000L, retrySection.getLong("max-delay-millis", 15_000L)));

            ConfigurationSection vanishSection = section == null ? null : section.getConfigurationSection("vanish");
            boolean allowPublicChat = vanishSection != null && vanishSection.getBoolean("allow-public-chat", false);

            ConfigurationSection muteSection = section == null ? null : section.getConfigurationSection("mute");
            boolean muteFailClosed = muteSection == null || muteSection.getBoolean("fail-closed", true);
            List<String> rawCommands = (muteSection != null && muteSection.isList("muted-commands"))
                    ? muteSection.getStringList("muted-commands")
                    : DEFAULT_MUTED_COMMANDS;
            List<MutedCommandRule> rules = new ArrayList<>();
            for (String raw : rawCommands) {
                MutedCommandRule rule = MutedCommandRule.parse(raw);
                if (rule != null) {
                    rules.add(rule);
                }
            }
            return new ModerationSettings(preload, retries, firstDelay, maxDelay, allowPublicChat, muteFailClosed, List.copyOf(rules));
        }
    }

    private static final @Nullable Method ADVANCEMENT_MESSAGE_METHOD = resolveAdvancementMessageMethod();

    private static @Nullable Method resolveAdvancementMessageMethod() {
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            return PlayerAdvancementDoneEvent.class.getMethod("message", componentClass);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            return null;
        }
    }

    private final PluginStart plugin;
    private final ModerationRepository repository;
    private final Executor storageExecutor;
    private final LongSupplier clock;
    private final BiConsumer<Runnable, Long> asyncRetryScheduler;
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> vanishedLowerNames = new ConcurrentHashMap<>();
    private final Map<UUID, MuteRecord> activeMutes = new ConcurrentHashMap<>();
    private final Map<UUID, MuteLoadState> muteLoadStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> connectionGenerations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> abandonedLoginTimes = new ConcurrentHashMap<>();
    private final Set<UUID> localMuteMutations = ConcurrentHashMap.newKeySet();
    private final AtomicLong generationCounter = new AtomicLong();
    private final Set<UUID> expiryQueued = ConcurrentHashMap.newKeySet();
    private volatile ModerationSettings settings;
    private volatile boolean asyncTabListenerRegistered;
    private volatile boolean xpPickupListenerRegistered;
    private volatile @Nullable java.util.function.Predicate<Player> additionalSleepIgnoreCheck;
    private volatile @Nullable BiConsumer<Player, Boolean> vanishTransitionListener;
    private TaskHandle tickHandle;
    private volatile boolean closed;

    public ModerationService(
            PluginStart plugin,
            ModerationRepository repository,
            Executor storageExecutor,
            LongSupplier clock
    ) {
        this(plugin, repository, storageExecutor, clock, null);
    }

    ModerationService(
            PluginStart plugin,
            ModerationRepository repository,
            Executor storageExecutor,
            LongSupplier clock,
            @Nullable BiConsumer<Runnable, Long> asyncRetryScheduler
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storageExecutor = Objects.requireNonNull(storageExecutor, "storageExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.asyncRetryScheduler = asyncRetryScheduler;
        this.settings = ModerationSettings.fromConfig(plugin.getConfig() == null ? null : plugin.getConfig().getConfigurationSection("moderation"));
    }

    public void enable() throws SQLException {
        closed = false;
        reloadConfigSnapshot();
        vanishedPlayers.clear();
        vanishedLowerNames.clear();
        vanishedPlayers.addAll(repository.loadVanishedPlayers());
        activeMutes.clear();
        muteLoadStates.clear();
        connectionGenerations.clear();
        abandonedLoginTimes.clear();
        localMuteMutations.clear();
        for (MuteRecord record : repository.loadActiveMutes()) {
            activeMutes.put(record.playerUuid(), record);
        }

        PlayerNameVisibility.setVanishHooks(this::isVanished, this::syncPairVisibility);
        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(this, plugin);
        registerOptionalPaperListeners(pm);
        logSpigotVanishPlatformWarningIfApplicable();

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online == null) {
                continue;
            }
            connectionGenerations.put(online.getUniqueId(), generationCounter.incrementAndGet());
            muteLoadStates.put(online.getUniqueId(), MuteLoadState.READY);
            if (vanishedPlayers.contains(online.getUniqueId()) && online.getName() != null) {
                vanishedLowerNames.put(online.getUniqueId(), online.getName().toLowerCase(Locale.ROOT));
            }
            applyPlayerVanishState(online);
        }
        syncAllOnlineVisibility();

        if (plugin.getScheduler() != null) {
            tickHandle = plugin.getScheduler().repeatOnServer(() -> {
                if (closed) {
                    return false;
                }
                tickExpiredMutes();
                sweepAbandonedLogins();
                return true;
            }, 500L, 500L);
        }
    }

    private void logSpigotVanishPlatformWarningIfApplicable() {
        if (isVanishCommandDisabled()) {
            return;
        }
        boolean missingAdvancementSuppress = ADVANCEMENT_MESSAGE_METHOD == null;
        if (!asyncTabListenerRegistered || missingAdvancementSuppress || !xpPickupListenerRegistered) {
            String serverName = plugin.getServer() == null || plugin.getServer().getName() == null
                    ? "Spigot/Bukkit"
                    : plugin.getServer().getName();
            plugin.logger().warning(
                    "Vanish is enabled on " + serverName + ", which lacks Paper APIs to suppress known vanish leaks: "
                            + "vanilla tab completion (minecraft:* Brigadier suggestions), advancement announcements, "
                            + "and physical XP orb pickup on collision. Paper is recommended for full vanish suppression."
            );
        }
    }

    private boolean isVanishCommandDisabled() {
        if (plugin.getConfig() == null) {
            return false;
        }
        for (String disabled : plugin.getConfig().getStringList("disabled-commands")) {
            if (disabled == null) {
                continue;
            }
            String norm = disabled.trim().toLowerCase(Locale.ROOT);
            if (norm.startsWith("/")) {
                norm = norm.substring(1);
            }
            if (norm.equals("vanish") || norm.equals("v") || norm.equals("allium:vanish") || norm.equals("allium:v")) {
                return true;
            }
        }
        return false;
    }

    public void disable() {
        closed = true;
        if (tickHandle != null) {
            tickHandle.cancel();
            tickHandle = null;
        }
        HandlerList.unregisterAll(this);
        PlayerNameVisibility.setVanishHooks(null, null);
        vanishedLowerNames.clear();
        muteLoadStates.clear();
        connectionGenerations.clear();
        abandonedLoginTimes.clear();
        localMuteMutations.clear();
        java.util.function.Predicate<Player> extraSleep = additionalSleepIgnoreCheck;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.removeMetadata(METADATA_VANISHED, plugin);
            online.setSleepingIgnored(extraSleep != null && extraSleep.test(online));
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (!viewer.equals(online)) {
                    viewer.showPlayer(plugin, online);
                }
            }
        }
    }

    /**
     * Re-reads {@code moderation.*} settings on {@code /allium reload} and recomputes visibility for
     * every online viewer/target pair on the server thread.
     */
    public void setAdditionalSleepIgnoreCheck(@Nullable java.util.function.Predicate<Player> check) {
        this.additionalSleepIgnoreCheck = check;
    }

    public void setVanishTransitionListener(@Nullable BiConsumer<Player, Boolean> listener) {
        this.vanishTransitionListener = listener;
    }

    public void refreshSleepingIgnored(Player player) {
        if (player == null) {
            return;
        }
        java.util.function.Predicate<Player> extra = additionalSleepIgnoreCheck;
        boolean ignored = isVanished(player) || (extra != null && extra.test(player));
        player.setSleepingIgnored(ignored);
    }

    public void reloadSettings() {
        reloadConfigSnapshot();
        runOnServer(this::syncAllOnlineVisibility);
    }

    private void reloadConfigSnapshot() {
        ConfigurationSection section = plugin.getConfig() == null ? null : plugin.getConfig().getConfigurationSection("moderation");
        settings = ModerationSettings.fromConfig(section);
    }

    private void registerOptionalPaperListeners(PluginManager pm) {
        asyncTabListenerRegistered = false;
        xpPickupListenerRegistered = false;
        try {
            Class<? extends Event> asyncTabClass = Class.forName(
                    "com.destroystokyo.paper.event.server.AsyncTabCompleteEvent").asSubclass(Event.class);
            Method getSender = asyncTabClass.getMethod("getSender");
            Method getCompletions = asyncTabClass.getMethod("getCompletions");
            Method setCompletions = asyncTabClass.getMethod("setCompletions", List.class);
            Method completionsMethod = asyncTabClass.getMethod("completions");
            Class<?> completionClass = Class.forName("com.destroystokyo.paper.event.server.AsyncTabCompleteEvent$Completion");
            Method suggestionMethod = completionClass.getMethod("suggestion");
            pm.registerEvent(asyncTabClass, this, EventPriority.HIGHEST, (listener, event) -> {
                if (asyncTabClass.isInstance(event)) {
                    filterPaperAsyncTabComplete(event, getSender, getCompletions, setCompletions, completionsMethod, suggestionMethod);
                }
            }, plugin, true);
            asyncTabListenerRegistered = true;
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Spigot 1.20.1 does not expose Paper's AsyncTabCompleteEvent.
        }

        try {
            Class<? extends Event> xpPickupClass = Class.forName(
                    "com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent").asSubclass(Event.class);
            Method getPlayer = xpPickupClass.getMethod("getPlayer");
            pm.registerEvent(xpPickupClass, this, EventPriority.LOWEST, (listener, event) -> {
                if (!xpPickupClass.isInstance(event)) {
                    return;
                }
                try {
                    Object playerObj = getPlayer.invoke(event);
                    if (playerObj instanceof Player player && isVanished(player) && event instanceof Cancellable cancellable) {
                        cancellable.setCancelled(true);
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }, plugin, true);
            xpPickupListenerRegistered = true;
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Spigot 1.20.1 does not expose Paper's PlayerPickupExperienceEvent.
        }
    }

    @SuppressWarnings("unchecked")
    private void filterPaperAsyncTabComplete(
            Event event,
            Method getSender,
            Method getCompletions,
            Method setCompletions,
            Method completionsMethod,
            Method suggestionMethod
    ) {
        try {
            Object senderObj = getSender.invoke(event);
            if (!(senderObj instanceof Player viewer) || viewer.hasPermission(PERMISSION_VANISH_SEE)) {
                return;
            }
            Set<String> hiddenLower = hiddenLowerNamesFor(viewer);
            if (hiddenLower.isEmpty()) {
                return;
            }
            List<String> stringCompletions = (List<String>) getCompletions.invoke(event);
            if (stringCompletions != null && !stringCompletions.isEmpty()) {
                List<String> filtered = new ArrayList<>(stringCompletions.size());
                boolean changed = false;
                for (String candidate : stringCompletions) {
                    if (shouldFilterSuggestion(candidate, hiddenLower)) {
                        changed = true;
                    } else {
                        filtered.add(candidate);
                    }
                }
                if (changed) {
                    setCompletions.invoke(event, filtered);
                }
            }
            List<?> richCompletions = (List<?>) completionsMethod.invoke(event);
            if (richCompletions != null && !richCompletions.isEmpty()) {
                Iterator<?> it = richCompletions.iterator();
                while (it.hasNext()) {
                    Object entry = it.next();
                    String suggestion = entry == null ? null : (String) suggestionMethod.invoke(entry);
                    if (shouldFilterSuggestion(suggestion, hiddenLower)) {
                        it.remove();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Never disrupt async completions if a custom server fork changes list mutability.
        }
    }

    public ModerationRepository repository() {
        return repository;
    }

    // -- VanishSource & vanish state --------------------------------------------------------------

    @Override
    public boolean isVanished(Player player) {
        if (player == null) {
            return false;
        }
        if (vanishedPlayers.contains(player.getUniqueId())) {
            return true;
        }
        return VanishSource.METADATA.isVanished(player);
    }

    public boolean isVanished(UUID playerUuid) {
        return playerUuid != null && vanishedPlayers.contains(playerUuid);
    }

    @Override
    public boolean hiddenFrom(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return false;
        }
        if (viewer.equals(target)) {
            return false;
        }
        syncPairVisibility(viewer, target);
        return !viewer.canSee(target) || isVanished(target);
    }

    /**
     * Toggles or sets vanish for {@code target} on the server thread and persists asynchronously
     * on the bounded moderation storage pool.
     */
    public void setVanished(CommandSender actor, Player target, @Nullable Boolean desiredState) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");

        UUID targetId = target.getUniqueId();
        boolean previous = vanishedPlayers.contains(targetId);
        boolean next = desiredState != null ? desiredState : !previous;
        String actorName = actorName(actor);

        if (next) {
            vanishedPlayers.add(targetId);
            if (target.getName() != null) {
                vanishedLowerNames.put(targetId, target.getName().toLowerCase(Locale.ROOT));
            }
        } else {
            vanishedPlayers.remove(targetId);
            vanishedLowerNames.remove(targetId);
        }

        applyPlayerVanishState(target);
        syncAllOnlineVisibility();
        BiConsumer<Player, Boolean> transition = vanishTransitionListener;
        if (transition != null && previous != next) {
            transition.accept(target, next);
        }
        if (next && plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("teleport") instanceof TeleportModule teleportModule
                && teleportModule.isEnabled()
                && teleportModule.teleports() != null) {
            teleportModule.teleports().cancelInvisibleRequests(target);
        }

        if (target.equals(actor)) {
            plugin.getMessageManager().send(actor, next ? Messages.VANISH_ON : Messages.VANISH_OFF);
        } else {
            plugin.getMessageManager().send(
                    actor,
                    next ? Messages.VANISH_OTHER_ON : Messages.VANISH_OTHER_OFF,
                    "{player}", target.getName()
            );
            plugin.getMessageManager().send(target, next ? Messages.VANISH_ON : Messages.VANISH_OFF);
        }

        String action = next ? "VANISH_ON" : "VANISH_OFF";
        String detail = "vanished=" + next;
        logAudit(action, actorName, targetId, target.getName(), detail);

        storageExecutor.execute(() -> {
            try {
                repository.setVanished(targetId, next, actorName);
                repository.recordAudit(action, actorName, targetId, target.getName(), detail);
            } catch (SQLException ex) {
                plugin.logger().warning("Failed to persist vanish state for " + target.getName() + ": " + ex.getMessage());
                runOnServer(() -> {
                    if (previous) {
                        vanishedPlayers.add(targetId);
                        if (target.getName() != null) {
                            vanishedLowerNames.put(targetId, target.getName().toLowerCase(Locale.ROOT));
                        }
                    } else {
                        vanishedPlayers.remove(targetId);
                        vanishedLowerNames.remove(targetId);
                    }
                    if (target.isOnline()) {
                        applyPlayerVanishState(target);
                    }
                    syncAllOnlineVisibility();
                    plugin.getMessageManager().send(actor, Messages.MODERATION_STORAGE_FAILED);
                });
            }
        });
    }

    private void applyPlayerVanishState(Player player) {
        if (player == null) {
            return;
        }
        boolean vanished = vanishedPlayers.contains(player.getUniqueId());
        if (vanished) {
            player.setMetadata(METADATA_VANISHED, new FixedMetadataValue(plugin, true));
            clearMobTargetsOn(player);
        } else {
            player.removeMetadata(METADATA_VANISHED, plugin);
        }
        refreshSleepingIgnored(player);
    }

    private void clearMobTargetsOn(Player vanishedPlayer) {
        try {
            List<Entity> nearby = vanishedPlayer.getNearbyEntities(64.0, 64.0, 64.0);
            if (nearby == null) {
                return;
            }
            for (Entity entity : nearby) {
                if (entity instanceof Mob mob
                        && mob.getTarget() != null
                        && vanishedPlayer.getUniqueId().equals(mob.getTarget().getUniqueId())) {
                    mob.setTarget(null);
                }
            }
        } catch (RuntimeException ignored) {
            // Mocked or unloaded worlds in unit tests may not support getNearbyEntities.
        }
    }

    public void syncAllOnlineVisibility() {
        var onlinePlayers = plugin.getServer().getOnlinePlayers();
        if (onlinePlayers == null) {
            return;
        }
        for (Player target : onlinePlayers) {
            if (target == null) {
                continue;
            }
            boolean targetVanished = vanishedPlayers.contains(target.getUniqueId());
            if (targetVanished) {
                target.setMetadata(METADATA_VANISHED, new FixedMetadataValue(plugin, true));
                refreshSleepingIgnored(target);
                if (target.getName() != null) {
                    vanishedLowerNames.put(target.getUniqueId(), target.getName().toLowerCase(Locale.ROOT));
                }
            } else {
                vanishedLowerNames.remove(target.getUniqueId());
            }
            for (Player viewer : onlinePlayers) {
                if (viewer == null || viewer.equals(target)) {
                    continue;
                }
                if (targetVanished && !viewer.hasPermission(PERMISSION_VANISH_SEE)) {
                    viewer.hidePlayer(plugin, target);
                } else {
                    viewer.showPlayer(plugin, target);
                }
            }
        }
    }

    private void syncPairVisibility(Player viewer, Player target) {
        if (viewer == null || target == null || viewer.equals(target)) {
            return;
        }
        if (vanishedPlayers.contains(target.getUniqueId())) {
            if (viewer.hasPermission(PERMISSION_VANISH_SEE)) {
                viewer.showPlayer(plugin, target);
            } else {
                viewer.hidePlayer(plugin, target);
            }
        }
    }

    // -- MuteSource & mute management -------------------------------------------------------------

    @Override
    public long nowMillis() {
        return clock.getAsLong();
    }

    @Override
    public boolean isMuteStateReady(UUID playerUuid) {
        if (!settings.muteFailClosed()) {
            return true;
        }
        return playerUuid != null && muteLoadStates.get(playerUuid) == MuteLoadState.READY;
    }

    public Optional<MuteLoadState> muteLoadState(UUID playerUuid) {
        return playerUuid == null ? Optional.empty() : Optional.ofNullable(muteLoadStates.get(playerUuid));
    }

    @Override
    public boolean isMuted(UUID playerUuid) {
        return activeMute(playerUuid).isPresent();
    }

    @Override
    public Optional<MuteRecord> activeMute(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        MuteRecord record = activeMutes.get(playerUuid);
        if (record == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (record.isExpired(now)) {
            expireMute(record, now);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    public List<MuteRecord> activeMutes(CommandSender viewer) {
        long now = clock.getAsLong();
        List<MuteRecord> out = new ArrayList<>();
        for (MuteRecord record : activeMutes.values()) {
            if (record.isExpired(now)) {
                expireMute(record, now);
                continue;
            }
            Player online = plugin.getServer().getPlayer(record.playerUuid());
            if (online != null && !PlayerNameVisibility.canSeeTarget(viewer, online)) {
                continue;
            }
            out.add(record);
        }
        out.sort(Comparator.comparingLong(MuteRecord::createdAt)
                .thenComparing(r -> r.playerName().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    /**
     * Mutes a target player (online or known in the in-memory name index) and persists the mute
     * and audit entry asynchronously on the bounded storage pool.
     */
    public void mute(
            CommandSender actor,
            UUID targetUuid,
            String targetName,
            @Nullable Long durationMillis,
            @Nullable String rawReason
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(targetUuid, "targetUuid");
        if (actor instanceof Player actorPlayer && actorPlayer.getUniqueId().equals(targetUuid)) {
            plugin.getMessageManager().send(actor, Messages.MUTE_SELF);
            return;
        }

        long now = clock.getAsLong();
        Long expiresAt = durationMillis == null ? null : (now + durationMillis);
        String actorName = actorName(actor);
        String reason = MuteRecord.normalizeReason(rawReason);
        MuteRecord record = new MuteRecord(targetUuid, targetName, actorName, reason, now, expiresAt);
        MuteRecord previous = activeMutes.put(targetUuid, record);
        localMuteMutations.add(targetUuid);
        expiryQueued.remove(targetUuid);

        String remainingText = record.formattedRemaining(now);
        if (record.isPermanent()) {
            plugin.getMessageManager().send(
                    actor,
                    Messages.MUTE,
                    "{player}", record.playerName(),
                    "{reason}", record.reason()
            );
        } else {
            plugin.getMessageManager().send(
                    actor,
                    Messages.MUTE_TEMP,
                    "{player}", record.playerName(),
                    "{duration}", remainingText,
                    "{reason}", record.reason()
            );
        }

        Player onlineTarget = plugin.getServer().getPlayer(targetUuid);
        if (onlineTarget != null && onlineTarget.isOnline()) {
            plugin.getMessageManager().send(
                    onlineTarget,
                    Messages.MUTE_TARGET_NOTICE,
                    "{remaining}", remainingText,
                    "{reason}", record.reason()
            );
        }

        String detail = "duration=" + remainingText + " reason=" + record.reason();
        logAudit("MUTE", actorName, targetUuid, record.playerName(), detail);

        storageExecutor.execute(() -> {
            try {
                repository.saveMute(record);
                repository.recordAudit("MUTE", actorName, targetUuid, record.playerName(), detail);
            } catch (SQLException ex) {
                plugin.logger().warning("Failed to persist mute for " + record.playerName() + ": " + ex.getMessage());
                runOnServer(() -> {
                    if (previous == null) {
                        activeMutes.remove(targetUuid, record);
                    } else {
                        activeMutes.put(targetUuid, previous);
                    }
                    plugin.getMessageManager().send(actor, Messages.MODERATION_STORAGE_FAILED);
                });
            }
        });
    }

    /**
     * Unmutes a muted player and records an audit entry asynchronously on the bounded storage pool.
     */
    public void unmute(
            CommandSender actor,
            UUID targetUuid,
            String targetName,
            @Nullable String rawReason
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(targetUuid, "targetUuid");

        Optional<MuteRecord> currentOpt = activeMute(targetUuid);
        if (currentOpt.isEmpty()) {
            plugin.getMessageManager().send(actor, Messages.UNMUTE_NOT_MUTED, "{player}", targetName);
            return;
        }

        MuteRecord removed = currentOpt.get();
        activeMutes.remove(targetUuid, removed);
        localMuteMutations.add(targetUuid);
        expiryQueued.remove(targetUuid);

        String actorName = actorName(actor);
        String reason = (rawReason == null || rawReason.isBlank()) ? "Unmuted by staff." : MuteRecord.normalizeReason(rawReason);

        plugin.getMessageManager().send(actor, Messages.UNMUTE, "{player}", removed.playerName());
        Player onlineTarget = plugin.getServer().getPlayer(targetUuid);
        if (onlineTarget != null && onlineTarget.isOnline()) {
            plugin.getMessageManager().send(onlineTarget, Messages.UNMUTE_TARGET_NOTICE);
        }

        String detail = "reason=" + reason;
        logAudit("UNMUTE", actorName, targetUuid, removed.playerName(), detail);

        storageExecutor.execute(() -> {
            try {
                repository.removeMute(targetUuid);
                repository.recordAudit("UNMUTE", actorName, targetUuid, removed.playerName(), detail);
            } catch (SQLException ex) {
                plugin.logger().warning("Failed to persist unmute for " + removed.playerName() + ": " + ex.getMessage());
                runOnServer(() -> {
                    activeMutes.putIfAbsent(targetUuid, removed);
                    plugin.getMessageManager().send(actor, Messages.MODERATION_STORAGE_FAILED);
                });
            }
        });
    }

    /**
     * Resolves a target for {@code /mute} or {@code /unmute} from visible online players, active
     * mutes, or the in-memory player-name cache without touching blocking storage on the server thread.
     */
    public Optional<NameCache.Entry> resolveModerationTarget(CommandSender sender, String rawName, boolean includeActiveMutes) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawName.trim();
        Player exactOnline = plugin.getServer().getPlayerExact(trimmed);
        if (exactOnline != null) {
            if (!PlayerNameVisibility.canSeeTarget(sender, exactOnline)) {
                return Optional.empty();
            }
            return Optional.of(new NameCache.Entry(exactOnline.getUniqueId(), exactOnline.getName(), clock.getAsLong()));
        }

        if (includeActiveMutes) {
            long now = clock.getAsLong();
            for (MuteRecord record : activeMutes.values()) {
                if (!record.isExpired(now) && record.playerName().equalsIgnoreCase(trimmed)) {
                    Player online = plugin.getServer().getPlayer(record.playerUuid());
                    if (online != null && !PlayerNameVisibility.canSeeTarget(sender, online)) {
                        return Optional.empty();
                    }
                    return Optional.of(new NameCache.Entry(record.playerUuid(), record.playerName(), record.createdAt()));
                }
            }
        }

        PlayerNameService names = plugin.getPlayerNames();
        if (names != null) {
            Optional<NameCache.Entry> cached = names.cache().byName(trimmed);
            if (cached.isPresent()) {
                Player online = plugin.getServer().getPlayer(cached.get().uuid());
                if (online != null && !PlayerNameVisibility.canSeeTarget(sender, online)) {
                    return Optional.empty();
                }
                return cached;
            }
        }
        return Optional.empty();
    }

    public void tickExpiredMutes() {
        long now = clock.getAsLong();
        for (MuteRecord record : List.copyOf(activeMutes.values())) {
            if (record.isExpired(now)) {
                expireMute(record, now);
            }
        }
    }

    private void expireMute(MuteRecord record, long now) {
        UUID playerUuid = record.playerUuid();
        if (!activeMutes.remove(playerUuid, record)) {
            return;
        }
        if (!expiryQueued.add(playerUuid)) {
            return;
        }
        String detail = "expired_at=" + now + " original_reason=" + record.reason();
        logAudit("MUTE_EXPIRED", "System", playerUuid, record.playerName(), detail);
        storageExecutor.execute(() -> {
            try {
                repository.removeMute(playerUuid);
                repository.recordAudit("MUTE_EXPIRED", "System", playerUuid, record.playerName(), detail);
            } catch (SQLException ex) {
                plugin.logger().warning("Failed to purge expired mute for " + record.playerName() + ": " + ex.getMessage());
            } finally {
                expiryQueued.remove(playerUuid);
            }
        });
    }

    // -- Pre-login loading with configurable fail-closed mute state and bounded retry -------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID playerUuid = event.getUniqueId();
        long generation = generationCounter.incrementAndGet();
        connectionGenerations.put(playerUuid, generation);
        abandonedLoginTimes.put(playerUuid, System.nanoTime());
        localMuteMutations.remove(playerUuid);
        muteLoadStates.put(playerUuid, MuteLoadState.LOADING);

        ModerationSettings currentSettings = settings;
        CompletableFuture<Void> loadFuture = new CompletableFuture<>();
        storageExecutor.execute(() -> {
            try {
                loadForConnection(playerUuid, generation, 0, currentSettings);
            } finally {
                loadFuture.complete(null);
            }
        });

        long waitMillis = currentSettings.preloadWaitMillis();
        if (waitMillis <= 0L) {
            return;
        }
        try {
            loadFuture.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            // Player joins while LOADING; mute enforcement fails closed when moderation.mute.fail-closed=true.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // Handled inside loadForConnection.
        }
    }

    private void loadForConnection(UUID playerUuid, long generation, int attempt, ModerationSettings snapshot) {
        if (closed || !Objects.equals(connectionGenerations.get(playerUuid), generation)) {
            return;
        }
        try {
            if (FaultInjection.trip(FaultInjection.MODERATION_LOAD_FAIL)) {
                throw new SQLException("fault injection: moderation load failed");
            }
            if (FaultInjection.trip(FaultInjection.MODERATION_LOAD_SLOW)) {
                try {
                    Thread.sleep(SLOW_LOAD_FAULT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            boolean vanished = repository.loadVanished(playerUuid);
            Optional<MuteRecord> mute = repository.loadActiveMute(playerUuid);
            if (closed || !Objects.equals(connectionGenerations.get(playerUuid), generation)) {
                return;
            }
            if (vanished) {
                vanishedPlayers.add(playerUuid);
            } else {
                vanishedPlayers.remove(playerUuid);
            }
            if (!localMuteMutations.contains(playerUuid)) {
                if (mute.isPresent()) {
                    activeMutes.put(playerUuid, mute.get());
                } else {
                    activeMutes.remove(playerUuid);
                }
            }
            muteLoadStates.put(playerUuid, MuteLoadState.READY);
            if (attempt > 0) {
                plugin.logger().info("Recovered moderation state for " + playerUuid + " after " + attempt + " retry(s).");
            }
            runOnServer(() -> {
                Player online = plugin.getServer().getPlayer(playerUuid);
                if (online != null && online.isOnline()) {
                    if (vanishedPlayers.contains(playerUuid) && online.getName() != null) {
                        vanishedLowerNames.put(playerUuid, online.getName().toLowerCase(Locale.ROOT));
                    } else {
                        vanishedLowerNames.remove(playerUuid);
                    }
                    applyPlayerVanishState(online);
                    syncAllOnlineVisibility();
                }
            });
        } catch (SQLException ex) {
            if (!Objects.equals(connectionGenerations.get(playerUuid), generation)) {
                return;
            }
            muteLoadStates.put(playerUuid, MuteLoadState.FAILED);
            if (attempt == 0) {
                plugin.logger().warning("Failed to load moderation state for " + playerUuid
                        + (snapshot.muteFailClosed()
                        ? " (speech will fail closed until retry succeeds): "
                        : " (moderation.mute.fail-closed=false; speech stays open until retry succeeds): ")
                        + ex.getMessage());
            }
            if (attempt < snapshot.loadRetries()) {
                long nextDelay = nextRetryDelay(attempt, snapshot);
                scheduleRetry(() -> storageExecutor.execute(() -> loadForConnection(playerUuid, generation, attempt + 1, snapshot)),
                        nextDelay);
            } else {
                plugin.logger().warning("Exhausted moderation load retries (" + snapshot.loadRetries() + ") for " + playerUuid
                        + (snapshot.muteFailClosed()
                        ? "; speech stays fail-closed for this session."
                        : "; speech remains open under moderation.mute.fail-closed=false."));
            }
        }
    }

    private static long nextRetryDelay(int failedAttempt, ModerationSettings snapshot) {
        long delay = snapshot.retryFirstDelayMillis();
        for (int i = 0; i < failedAttempt; i++) {
            delay = Math.min(snapshot.retryMaxDelayMillis(), delay * 2L);
        }
        return delay;
    }

    private void scheduleRetry(Runnable task, long delayMillis) {
        if (closed) {
            return;
        }
        if (asyncRetryScheduler != null) {
            asyncRetryScheduler.accept(task, delayMillis);
        } else if (plugin.getScheduler() != null) {
            plugin.getScheduler().runAsyncLater(task, delayMillis);
        }
    }

    private void sweepAbandonedLogins() {
        long cutoff = System.nanoTime() - TimeUnit.SECONDS.toNanos(60);
        abandonedLoginTimes.forEach((uuid, startedAt) -> {
            if (startedAt < cutoff && plugin.getServer().getPlayer(uuid) == null && abandonedLoginTimes.remove(uuid, startedAt)) {
                connectionGenerations.remove(uuid);
                muteLoadStates.remove(uuid);
                localMuteMutations.remove(uuid);
            }
        });
    }

    // -- Event listeners --------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        UUID playerUuid = joining.getUniqueId();
        abandonedLoginTimes.remove(playerUuid);
        boolean vanished = vanishedPlayers.contains(playerUuid);
        if (vanished) {
            event.setJoinMessage(null);
            if (joining.getName() != null) {
                vanishedLowerNames.put(playerUuid, joining.getName().toLowerCase(Locale.ROOT));
            }
        } else {
            vanishedLowerNames.remove(playerUuid);
        }
        applyPlayerVanishState(joining);
        syncAllOnlineVisibility();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quitting = event.getPlayer();
        UUID playerUuid = quitting.getUniqueId();
        if (isVanished(quitting)) {
            event.setQuitMessage(null);
        }
        vanishedLowerNames.remove(playerUuid);
        connectionGenerations.remove(playerUuid);
        abandonedLoginTimes.remove(playerUuid);
        muteLoadStates.remove(playerUuid);
        localMuteMutations.remove(playerUuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (isVanished(event.getEntity())) {
            event.setDeathMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (ADVANCEMENT_MESSAGE_METHOD == null || !isVanished(event.getPlayer())) {
            return;
        }
        try {
            ADVANCEMENT_MESSAGE_METHOD.invoke(event, new Object[]{null});
        } catch (ReflectiveOperationException ignored) {
            // Spigot 1.20.1 does not expose PlayerAdvancementDoneEvent#message(Component).
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerListPing(ServerListPingEvent event) {
        try {
            Iterator<Player> it = event.iterator();
            while (it.hasNext()) {
                Player online = it.next();
                if (online != null && isVanished(online)) {
                    it.remove();
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // Some server implementations do not support removing players from ServerListPingEvent.iterator().
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player target && isVanished(target)) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPickupArrow(PlayerPickupArrowEvent event) {
        if (isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockReceiveGameEvent(BlockReceiveGameEvent event) {
        if (event.getEntity() instanceof Player player && isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPhysicalInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!isVanished(player) || player.isSneaking()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        if (clicked.getType() == Material.ENDER_CHEST) {
            event.setCancelled(true);
            player.openInventory(player.getEnderChest());
            return;
        }
        if (player.hasPermission(PERMISSION_VANISH_CONTAINER_LIVE)) {
            // Opt-in live container access (trades stealth/lid sound for full R/W access).
            return;
        }
        BlockState state = clicked.getState();
        if (state instanceof Container container && isAudibleLiddedContainer(clicked.getType(), state)) {
            event.setCancelled(true);
            openSilentContainerMirror(player, container);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSilentContainerClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SilentContainerHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSilentContainerDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SilentContainerHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        handleAsyncChat(event, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAsyncPlayerChatHighest(AsyncPlayerChatEvent event) {
        // Re-cancel at HIGHEST with ignoreCancelled = false so later plugins cannot un-cancel muted/vanished chat.
        if (!event.isCancelled()) {
            handleAsyncChat(event, false);
        }
    }

    private void handleAsyncChat(AsyncPlayerChatEvent event, boolean notifyPlayer) {
        Player sender = event.getPlayer();
        UUID senderId = sender.getUniqueId();
        if (!isMuteStateReady(senderId)) {
            event.setCancelled(true);
            if (notifyPlayer) {
                plugin.getMessageManager().send(sender, Messages.MUTE_UNAVAILABLE);
            }
            return;
        }
        Optional<MuteRecord> active = activeMute(senderId);
        if (active.isPresent()) {
            event.setCancelled(true);
            if (notifyPlayer) {
                MuteRecord record = active.get();
                String remaining = record.formattedRemaining(clock.getAsLong());
                plugin.getMessageManager().send(
                        sender,
                        Messages.MUTE_BLOCKED,
                        "{remaining}", remaining,
                        "{reason}", record.reason()
                );
            }
            return;
        }
        if (!settings.allowVanishPublicChat() && isVanished(sender)) {
            event.setCancelled(true);
            if (notifyPlayer) {
                plugin.getMessageManager().send(sender, Messages.VANISH_CHAT_BLOCKED);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String rawMessage = event.getMessage();
        List<String> tokens = parseCommandTokens(rawMessage);
        if (tokens.isEmpty()) {
            return;
        }
        String label = tokens.get(0);
        int colonIdx = label.indexOf(':');
        String unnamespaced = colonIdx >= 0 ? label.substring(colonIdx + 1) : label;

        // 1. Muted (or still-loading / failed when fail-closed) players cannot run configured muted-commands.
        if (matchesMutedCommand(tokens, unnamespaced)) {
            UUID playerId = player.getUniqueId();
            if (!isMuteStateReady(playerId)) {
                event.setCancelled(true);
                plugin.getMessageManager().send(player, Messages.MUTE_UNAVAILABLE);
                return;
            }
            Optional<MuteRecord> active = activeMute(playerId);
            if (active.isPresent()) {
                event.setCancelled(true);
                MuteRecord record = active.get();
                plugin.getMessageManager().send(
                        player,
                        Messages.MUTE_BLOCKED,
                        "{remaining}", record.formattedRemaining(clock.getAsLong()),
                        "{reason}", record.reason()
                );
                return;
            }
        }

        // 2. Intercept vanilla commands outside Allium that could leak or target vanished players.
        if (player.hasPermission(PERMISSION_VANISH_SEE)) {
            return;
        }
        if (label.equals("minecraft:list") || label.equals("bukkit:list")
                || (unnamespaced.equals("list") && plugin.getCommand("list") == null)) {
            event.setCancelled(true);
            sendFilteredVanillaList(player);
            return;
        }
        if (label.equals("minecraft:msg") || label.equals("minecraft:tell") || label.equals("minecraft:w")) {
            if (tokens.size() >= 2 && targetsHiddenVanishedPlayer(player, tokens.get(1))) {
                event.setCancelled(true);
                player.sendMessage("\u00a7cNo player was found");
            }
            return;
        }
        if (label.equals("minecraft:tp") || label.equals("minecraft:teleport")) {
            if (tokens.size() >= 2 && targetsHiddenVanishedPlayer(player, tokens.get(1))) {
                event.setCancelled(true);
                player.sendMessage("\u00a7cNo player was found");
                return;
            }
            if (tokens.size() >= 3 && !looksNumericOrRelative(tokens.get(2))
                    && targetsHiddenVanishedPlayer(player, tokens.get(2))) {
                event.setCancelled(true);
                player.sendMessage("\u00a7cNo player was found");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player viewer) || viewer.hasPermission(PERMISSION_VANISH_SEE)) {
            return;
        }
        Set<String> hiddenLower = hiddenLowerNamesFor(viewer);
        if (hiddenLower.isEmpty()) {
            return;
        }
        List<String> completions = event.getCompletions();
        if (completions == null || completions.isEmpty()) {
            return;
        }
        List<String> filtered = new ArrayList<>(completions.size());
        boolean changed = false;
        for (String candidate : completions) {
            if (shouldFilterSuggestion(candidate, hiddenLower)) {
                changed = true;
            } else {
                filtered.add(candidate);
            }
        }
        if (changed) {
            try {
                event.setCompletions(filtered);
            } catch (UnsupportedOperationException ignored) {
                completions.removeIf(candidate -> shouldFilterSuggestion(candidate, hiddenLower));
            }
        }
    }

    private Set<String> hiddenLowerNamesFor(Player viewer) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        String selfLower = viewer.getName() == null ? null : viewer.getName().toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> entry : vanishedLowerNames.entrySet()) {
            String lower = entry.getValue();
            if (lower != null && !lower.isEmpty() && !lower.equals(selfLower)) {
                out.add(lower);
            }
        }
        // Include any metadata-only vanished players visible on the current server thread.
        if (plugin.getServer() != null && plugin.getServer().isPrimaryThread() && plugin.getServer().getOnlinePlayers() != null) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online != null && !online.equals(viewer) && isVanished(online) && online.getName() != null) {
                    out.add(online.getName().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static boolean shouldFilterSuggestion(@Nullable String candidate, Set<String> hiddenLower) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        return hiddenLower.contains(candidate.toLowerCase(Locale.ROOT));
    }

    private static List<String> parseCommandTokens(@Nullable String rawMessage) {
        if (rawMessage == null) {
            return List.of();
        }
        String trimmed = rawMessage.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>(parts.length);
        tokens.add(parts[0].toLowerCase(Locale.ROOT));
        tokens.addAll(Arrays.asList(parts).subList(1, parts.length));
        return tokens;
    }

    private boolean matchesMutedCommand(List<String> rawTokens, String unnamespacedCommand) {
        if (rawTokens.isEmpty()) {
            return false;
        }
        List<String> lowerTokens = new ArrayList<>(rawTokens.size());
        for (String t : rawTokens) {
            lowerTokens.add(t.toLowerCase(Locale.ROOT));
        }
        CommandMap commandMap = CommandMapBridge.resolveCommandMapDirect(plugin.getServer());
        if (commandMap != null) {
            Command typedCommand = commandMap.getCommand(lowerTokens.get(0));
            Object typedIdentity = CommandMapBridge.canonicalCommandIdentity(typedCommand);
            if (typedIdentity == null) {
                return false;
            }
            for (MutedCommandRule rule : settings.mutedCommands()) {
                if (!rule.matchesSubTokens(lowerTokens)) {
                    continue;
                }
                Command ruleCommand = commandMap.getCommand(rule.rootLabel());
                Object ruleIdentity = CommandMapBridge.canonicalCommandIdentity(ruleCommand);
                if (ruleIdentity != null && typedIdentity == ruleIdentity) {
                    return true;
                }
            }
            return false;
        }
        // Fallback only when a unit-test mock Server exposes no CommandMap.
        for (MutedCommandRule rule : settings.mutedCommands()) {
            if (rule.matchesTokensFallback(lowerTokens, unnamespacedCommand)) {
                return true;
            }
        }
        return false;
    }

    private boolean targetsHiddenVanishedPlayer(Player viewer, String arg) {
        if (arg == null || arg.isEmpty()) {
            return false;
        }
        String lowerArg = arg.toLowerCase(Locale.ROOT);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online != null && online.getName() != null
                    && online.getName().toLowerCase(Locale.ROOT).equals(lowerArg)
                    && !PlayerNameVisibility.canSeeTarget(viewer, online)) {
                return true;
            }
        }
        if (arg.startsWith("@") && !lowerArg.equals("@s")) {
            try {
                List<Entity> selected = plugin.getServer().selectEntities(viewer, arg);
                if (selected != null) {
                    for (Entity entity : selected) {
                        if (entity instanceof Player target && !PlayerNameVisibility.canSeeTarget(viewer, target)) {
                            return true;
                        }
                    }
                }
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                // Invalid selector or no selector permission: let vanilla emit its own normal error.
            }
        }
        return false;
    }

    private static boolean looksNumericOrRelative(String token) {
        if (token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        return first == '~' || first == '^' || first == '-' || (first >= '0' && first <= '9');
    }

    private void sendFilteredVanillaList(Player viewer) {
        List<String> visible = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online != null && PlayerNameVisibility.canSee(viewer, online) && online.getName() != null) {
                visible.add(online.getName());
            }
        }
        visible.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
        int maxPlayers = plugin.getServer().getMaxPlayers();
        viewer.sendMessage("There are " + visible.size() + " of a max of " + maxPlayers + " players online: "
                + String.join(", ", visible));
    }

    private boolean isAudibleLiddedContainer(Material type, BlockState state) {
        if (state instanceof Lidded) {
            return true;
        }
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.equals("CHEST") || name.equals("TRAPPED_CHEST") || name.equals("BARREL") || name.endsWith("SHULKER_BOX");
    }

    private void openSilentContainerMirror(Player player, Container container) {
        Inventory source = container.getInventory();
        int rawSize = source.getSize();
        int size = Math.max(9, Math.min(54, ((rawSize + 8) / 9) * 9));
        Server server = plugin.getServer();
        Inventory mirror = server != null
                ? server.createInventory(SilentContainerHolder.INSTANCE, size, "Silent Container View")
                : Bukkit.createInventory(SilentContainerHolder.INSTANCE, size, "Silent Container View");
        ItemStack[] contents = source.getContents();
        if (contents != null) {
            for (int i = 0; i < Math.min(size, contents.length); i++) {
                ItemStack item = contents[i];
                if (item != null) {
                    mirror.setItem(i, item.clone());
                }
            }
        }
        player.openInventory(mirror);
        plugin.getMessageManager().send(player, Messages.VANISH_SILENT_CONTAINER);
    }

    private String actorName(CommandSender actor) {
        if (actor instanceof Player player) {
            return player.getName();
        }
        return plugin.getMessageManager() != null
                ? plugin.getMessageManager().resolve(Messages.MSG_CONSOLE_NAME)
                : "Console";
    }

    private void logAudit(String action, String actorName, @Nullable UUID targetUuid, @Nullable String targetName, String detail) {
        plugin.logger().info(
                "Moderation audit action=" + action
                        + " actor=" + MessagingService.safeName(actorName)
                        + " target=" + (targetName == null ? "none" : MessagingService.safeName(targetName))
                        + " uuid=" + (targetUuid == null ? "none" : targetUuid)
                        + " " + detail
        );
    }

    private void runOnServer(Runnable task) {
        if (closed) {
            return;
        }
        if (plugin.getScheduler() != null) {
            plugin.getScheduler().runOnServer(() -> {
                if (!closed) {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** Marker InventoryHolder for read-only silent container views opened while vanished. */
    public static final class SilentContainerHolder implements InventoryHolder {
        public static final SilentContainerHolder INSTANCE = new SilentContainerHolder();

        private SilentContainerHolder() {
        }

        @Override
        public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException("SilentContainerHolder is a virtual read-only view");
        }
    }
}
