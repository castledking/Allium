package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Runtime vanish and mute service backed by {@link ModerationRepository} on a bounded storage
 * executor. Implements {@link VanishSource} and {@link MuteSource} so messaging, social spy, mail,
 * teleport, warps, and tab completion share one authoritative moderation state without main-thread
 * SQL.
 */
public final class ModerationService implements Listener, VanishSource, MuteSource {

    public static final String PERMISSION_VANISH = "allium.vanish";
    public static final String PERMISSION_VANISH_OTHERS = "allium.vanish.others";
    public static final String PERMISSION_VANISH_SEE = "allium.vanish.see";
    public static final String PERMISSION_MUTE = "allium.mute";
    public static final String PERMISSION_UNMUTE = "allium.unmute";
    public static final String METADATA_VANISHED = "vanished";

    private final PluginStart plugin;
    private final ModerationRepository repository;
    private final Executor storageExecutor;
    private final LongSupplier clock;
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, MuteRecord> activeMutes = new ConcurrentHashMap<>();
    private final Set<UUID> expiryQueued = ConcurrentHashMap.newKeySet();
    private TaskHandle tickHandle;
    private volatile boolean closed;

    public ModerationService(
            PluginStart plugin,
            ModerationRepository repository,
            Executor storageExecutor,
            LongSupplier clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storageExecutor = Objects.requireNonNull(storageExecutor, "storageExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void enable() throws SQLException {
        closed = false;
        vanishedPlayers.clear();
        vanishedPlayers.addAll(repository.loadVanishedPlayers());
        activeMutes.clear();
        for (MuteRecord record : repository.loadActiveMutes()) {
            activeMutes.put(record.playerUuid(), record);
        }

        PlayerNameVisibility.setVanishHooks(this::isVanished, this::syncPairVisibility);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            applyPlayerVanishState(online);
        }
        syncAllOnlineVisibility();

        if (plugin.getScheduler() != null) {
            tickHandle = plugin.getScheduler().repeatOnServer(() -> {
                if (closed) {
                    return false;
                }
                tickExpiredMutes();
                syncAllOnlineVisibility();
                return true;
            }, 500L, 500L);
        }
    }

    public void disable() {
        closed = true;
        if (tickHandle != null) {
            tickHandle.cancel();
            tickHandle = null;
        }
        HandlerList.unregisterAll(this);
        PlayerNameVisibility.setVanishHooks(null, null);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.removeMetadata(METADATA_VANISHED, plugin);
            online.setSleepingIgnored(false);
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (!viewer.equals(online)) {
                    viewer.showPlayer(plugin, online);
                }
            }
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
        } else {
            vanishedPlayers.remove(targetId);
        }

        applyPlayerVanishState(target);
        syncAllOnlineVisibility();
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
                    } else {
                        vanishedPlayers.remove(targetId);
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
            player.setSleepingIgnored(true);
            clearMobTargetsOn(player);
        } else {
            player.removeMetadata(METADATA_VANISHED, plugin);
            player.setSleepingIgnored(false);
        }
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
                target.setSleepingIgnored(true);
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

    // -- Event listeners --------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID playerUuid = event.getUniqueId();
        try {
            boolean vanished = repository.loadVanished(playerUuid);
            if (vanished) {
                vanishedPlayers.add(playerUuid);
            } else {
                vanishedPlayers.remove(playerUuid);
            }
            Optional<MuteRecord> mute = repository.loadActiveMute(playerUuid);
            if (mute.isPresent()) {
                activeMutes.put(playerUuid, mute.get());
            } else {
                activeMutes.remove(playerUuid);
            }
        } catch (SQLException ex) {
            plugin.logger().warning("Failed to refresh moderation state on pre-login for " + playerUuid + ": " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        boolean vanished = vanishedPlayers.contains(joining.getUniqueId());
        if (vanished) {
            event.setJoinMessage(null);
        }
        applyPlayerVanishState(joining);
        syncAllOnlineVisibility();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quitting = event.getPlayer();
        if (isVanished(quitting)) {
            event.setQuitMessage(null);
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
        Player sender = event.getPlayer();
        Optional<MuteRecord> active = activeMute(sender.getUniqueId());
        if (active.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        MuteRecord record = active.get();
        String remaining = record.formattedRemaining(clock.getAsLong());
        plugin.getMessageManager().send(
                sender,
                Messages.MUTE_BLOCKED,
                "{remaining}", remaining,
                "{reason}", record.reason()
        );
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
