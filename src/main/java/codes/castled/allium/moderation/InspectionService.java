package codes.castled.allium.moderation;

import codes.castled.allium.gui.GuiIcons;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.module.MailModule;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runtime service for {@code /invsee} and {@code /enderchest} ({@code /echest}, {@code /ec}).
 *
 * <p>Enforces permission separation ({@code .edit} and {@code .offline} are separate op-default
 * nodes outside {@code allium.*}), vanish blindness parity for hidden targets, rank/exempt policy
 * via {@link DisciplineService} for online targets, mail/kit busy-lock exclusion, single mutable session locking per
 * {@code (uuid, type)}, 1-tick post-click flush with snapshot verification and target event
 * guards, atomic CAS offline {@code .dat} updates with login fencing, and bounded SQL audit logging.
 */
public final class InspectionService implements Listener {

    public static final String PERMISSION_INVSEE = "allium.invsee";
    public static final String PERMISSION_INVSEE_EDIT = "allium.invsee.edit";
    public static final String PERMISSION_INVSEE_OFFLINE = "allium.invsee.offline";
    public static final String PERMISSION_ENDERCHEST = "allium.enderchest";
    public static final String PERMISSION_ENDERCHEST_OTHERS = "allium.enderchest.others";
    public static final String PERMISSION_ENDERCHEST_EDIT = "allium.enderchest.edit";
    public static final String PERMISSION_ENDERCHEST_OFFLINE = "allium.enderchest.offline";
    public static final String PERMISSION_BYPASS = DisciplineService.BYPASS_PERMISSION;

    private static final long LOGIN_FENCE_TIMEOUT_MILLIS = 5_000L;
    private static final long ONLINE_REFRESH_MILLIS = 150L;

    public record SessionKey(UUID targetUuid, InspectionLayout.InspectionType type) {
    }

    public static final class InspectionHolder implements InventoryHolder {
        private final UUID sessionId;
        private final InspectionLayout.InspectionType type;
        private final InspectionLayout.InspectionMode mode;
        private final boolean editable;
        private Inventory inventory;

        InspectionHolder(
                UUID sessionId,
                InspectionLayout.InspectionType type,
                InspectionLayout.InspectionMode mode,
                boolean editable
        ) {
            this.sessionId = sessionId;
            this.type = type;
            this.mode = mode;
            this.editable = editable;
        }

        public UUID sessionId() {
            return sessionId;
        }

        public InspectionLayout.InspectionType type() {
            return type;
        }

        public InspectionLayout.InspectionMode mode() {
            return mode;
        }

        public boolean editable() {
            return editable;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class InspectionSession {
        final UUID sessionId = UUID.randomUUID();
        final Player viewer;
        final UUID viewerUuid;
        final String viewerName;
        final UUID targetUuid;
        final String targetName;
        final InspectionLayout.InspectionType type;
        final InspectionLayout.InspectionMode mode;
        final boolean editable;
        final InspectionHolder holder;
        Inventory gui;

        volatile boolean active;
        volatile boolean flushScheduled;
        volatile boolean guardActive;
        volatile boolean writeInFlight;
        volatile boolean aborted;
        final java.util.concurrent.atomic.AtomicBoolean refreshInFlight = new java.util.concurrent.atomic.AtomicBoolean();
        /** Offline sessions: slot changes saved so far, and whether the close summary still waits on a write. */
        volatile int savedChanges;
        volatile boolean summaryPending;
        /**
         * The held hotbar slot the GUI is laid out for (its item sits in the main-hand cell), or -1
         * when unknown. Captures always use the layout that was displayed.
         */
        volatile int held = -1;
        /** Shown in {@link #BACK_BUTTON_SLOT} when the menu was opened from another menu. */
        @Nullable BackButton back;

        volatile ItemStack[] lastSyncedSlots;
        volatile byte[] currentRawBytes;
        volatile byte[] currentHash;
        volatile Map<String, String> bukkitValues;
        volatile CompletableFuture<Void> loginFlushFuture;

        InspectionSession(
                Player viewer,
                UUID targetUuid,
                String targetName,
                InspectionLayout.InspectionType type,
                InspectionLayout.InspectionMode mode,
                boolean editable
        ) {
            this.viewer = viewer;
            this.viewerUuid = viewer.getUniqueId();
            this.viewerName = MessagingService.safeName(viewer.getName());
            this.targetUuid = targetUuid;
            this.targetName = MessagingService.safeName(targetName);
            this.type = type;
            this.mode = mode;
            this.editable = editable;
            this.holder = new InspectionHolder(sessionId, type, mode, editable);
        }

        SessionKey key() {
            return new SessionKey(targetUuid, type);
        }
    }

    private final PluginStart plugin;
    private final ModerationRepository repository;
    private final OfflinePlayerDataStore offlineStore;
    private final Executor io;
    private final BiPredicate<CommandSender, UUID> canModeratePolicy;
    private final Predicate<Player> busyPolicy;
    private final Predicate<UUID> offlineHoldPolicy;
    private final Supplier<PlayerNameService> nameService;

    private final Map<SessionKey, InspectionSession> activeMutableSessions = new ConcurrentHashMap<>();
    private final Map<UUID, InspectionSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<InspectionSession>> sessionsByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<InspectionSession>> sessionsByViewer = new ConcurrentHashMap<>();
    private final Set<UUID> activeTargetGuards = ConcurrentHashMap.newKeySet();
    private final Set<UUID> resyncQueued = ConcurrentHashMap.newKeySet();

    private TaskHandle refreshHandle;
    private volatile boolean closed = true;
    private final java.util.concurrent.atomic.AtomicBoolean offlineClearRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean offlineEditingEnabled = false;

    public InspectionService(
            PluginStart plugin,
            ModerationRepository repository,
            DisciplineService discipline,
            Executor io
    ) {
        this(
                plugin,
                repository,
                createOfflineStore(plugin),
                io,
                discipline != null ? discipline::canModerate : ((sender, id) -> sender.hasPermission(PERMISSION_BYPASS)),
                defaultBusyPolicy(plugin),
                defaultOfflineHoldPolicy(plugin, repository),
                plugin::getPlayerNames
        );
    }

    InspectionService(
            PluginStart plugin,
            ModerationRepository repository,
            OfflinePlayerDataStore offlineStore,
            Executor io,
            BiPredicate<CommandSender, UUID> canModeratePolicy,
            Predicate<Player> busyPolicy,
            Supplier<PlayerNameService> nameService
    ) {
        this(
                plugin,
                repository,
                offlineStore,
                io,
                canModeratePolicy,
                busyPolicy,
                defaultOfflineHoldPolicy(plugin, repository),
                nameService
        );
    }

    InspectionService(
            PluginStart plugin,
            ModerationRepository repository,
            @Nullable OfflinePlayerDataStore offlineStore,
            Executor io,
            BiPredicate<CommandSender, UUID> canModeratePolicy,
            Predicate<Player> busyPolicy,
            Predicate<UUID> offlineHoldPolicy,
            Supplier<PlayerNameService> nameService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.offlineStore = offlineStore;
        this.io = Objects.requireNonNull(io, "io");
        this.canModeratePolicy = Objects.requireNonNull(canModeratePolicy, "canModeratePolicy");
        this.busyPolicy = Objects.requireNonNull(busyPolicy, "busyPolicy");
        this.offlineHoldPolicy = Objects.requireNonNull(offlineHoldPolicy, "offlineHoldPolicy");
        this.nameService = Objects.requireNonNull(nameService, "nameService");
    }

    private static @Nullable OfflinePlayerDataStore createOfflineStore(PluginStart plugin) {
        try {
            return new OfflinePlayerDataStore(
                    PlayerDataFiles.directory(plugin.getServer()),
                    new CraftBukkitPlayerDataCodec(plugin.getServer())
            );
        } catch (RuntimeException | LinkageError err) {
            plugin.logger().severe("Offline playerdata codec initialization FAILED; offline /invsee and /enderchest editing is DISABLED: " + err.getMessage());
            return null;
        }
    }

    private static Predicate<UUID> defaultOfflineHoldPolicy(PluginStart plugin, ModerationRepository repository) {
        return targetUuid -> {
            try {
                return repository.hasPendingWorkflowHold(targetUuid);
            } catch (SQLException err) {
                plugin.logger().warning("Refusing offline edit for " + targetUuid + ": could not check pending holds: " + err.getMessage());
                return true;
            }
        };
    }

    private static Predicate<Player> defaultBusyPolicy(PluginStart plugin) {
        return player -> {
            if (plugin.getModuleManager() == null) {
                return false;
            }
            if (plugin.getModuleManager().getModule("mail") instanceof MailModule mailModule
                    && mailModule.isEnabled() && mailModule.mail() != null && mailModule.mail().busy(player)) {
                return true;
            }
            return plugin.getModuleManager().getModule("kits") instanceof KitsModule kitsModule
                    && kitsModule.isEnabled() && kitsModule.kits() != null && kitsModule.kits().busy(player);
        };
    }

    public boolean isOfflineEditingEnabled() {
        return offlineEditingEnabled;
    }

    public void enable() {
        closed = false;
        if (offlineStore == null) {
            offlineEditingEnabled = false;
        } else {
            try {
                offlineStore.runCodecSelfTest();
                offlineEditingEnabled = true;
            } catch (IOException | RuntimeException | LinkageError err) {
                offlineEditingEnabled = false;
                plugin.logger().severe("Offline playerdata codec startup self-test FAILED; disabling offline /invsee and /enderchest editing: " + err.getMessage());
            }
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online != null && online.isOnline() && offlineStore != null) {
                offlineStore.markOnline(online.getUniqueId());
            }
        }
        if (plugin.getScheduler() != null) {
            refreshHandle = plugin.getScheduler().repeatOnServer(() -> {
                if (closed) {
                    return false;
                }
                refreshOnlineSessions();
                return true;
            }, ONLINE_REFRESH_MILLIS, ONLINE_REFRESH_MILLIS);
        }
    }

    public void disable() {
        closed = true;
        if (refreshHandle != null) {
            refreshHandle.cancel();
            refreshHandle = null;
        }
        for (InspectionSession session : List.copyOf(sessionsById.values())) {
            if (session.editable && session.flushScheduled) {
                if (session.mode == InspectionLayout.InspectionMode.ONLINE) {
                    if (!BukkitServerThread.isFolia()) {
                        flushOnlineSession(session);
                    }
                } else if (session.mode == InspectionLayout.InspectionMode.OFFLINE) {
                    if (!BukkitServerThread.isFolia()) {
                        flushOfflineSession(session);
                    }
                }
            }
            closeAndUnregisterSession(session, !BukkitServerThread.isFolia() || plugin.isEnabled(), "plugin-disable");
        }
        HandlerList.unregisterAll(this);
        activeMutableSessions.clear();
        sessionsById.clear();
        sessionsByTarget.clear();
        sessionsByViewer.clear();
        activeTargetGuards.clear();
        if (io instanceof ExecutorService pool) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    plugin.logger().warning("Inspection executor did not drain within 5s; forcing shutdown.");
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }

    public void reloadSettings() {
        // Hierarchy and exempt settings are live-delegated through DisciplineService#canModerate.
    }

    // ---------------------------------------------------------------------------------------------
    // Command dispatch
    // ---------------------------------------------------------------------------------------------

    public void dispatchInvsee(CommandSender sender, String[] args) {
        dispatchInvsee(sender, args, null);
    }

    /**
     * A button in a menu's last locked cell that leaves the inspection for where it was opened from
     * ({@code /creativemanager}). {@code lore} says where it goes.
     */
    public record BackButton(String lore, Runnable action) {
    }

    /** The cell the {@link BackButton} occupies: the last locked pane of the invsee layout. */
    static final int BACK_BUTTON_SLOT = InspectionLayout.INVSEE_LOCKED_END;

    /** {@code /invsee <player>}, with {@code back} (or none) in the menu's last locked cell. */
    public void dispatchInvsee(CommandSender sender, String[] args, @Nullable BackButton back) {
        if (!(sender instanceof Player viewer)) {
            send(sender, Messages.PLAYER_ONLY);
            return;
        }
        if (closed) {
            send(viewer, Messages.INSPECT_UNAVAILABLE);
            return;
        }
        if (args.length != 1) {
            codes.castled.allium.command.CommandUsageCard.sendInvalid(plugin, viewer, "invsee", "invsee", "/invsee <player>");
            return;
        }
        if (!viewer.hasPermission(PERMISSION_INVSEE)) {
            send(viewer, Messages.NO_PERMISSION);
            return;
        }
        String rawTarget = MessagingService.safeName(args[0]);
        if (rawTarget.equalsIgnoreCase(viewer.getName())) {
            send(viewer, Messages.INSPECT_SELF);
            return;
        }
        resolveAndOpen(viewer, rawTarget, InspectionLayout.InspectionType.INVSEE, back);
    }

    public void dispatchEnderchest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            send(sender, Messages.PLAYER_ONLY);
            return;
        }
        if (closed) {
            send(viewer, Messages.INSPECT_UNAVAILABLE);
            return;
        }
        if (args.length > 1) {
            codes.castled.allium.command.CommandUsageCard.sendInvalid(plugin, viewer, "enderchest", "enderchest", "/enderchest [player]");
            return;
        }
        if (!viewer.hasPermission(PERMISSION_ENDERCHEST)) {
            send(viewer, Messages.NO_PERMISSION);
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase(viewer.getName())) {
            openSelfEnderchest(viewer);
            return;
        }
        if (!viewer.hasPermission(PERMISSION_ENDERCHEST_OTHERS)) {
            send(viewer, Messages.NO_PERMISSION);
            return;
        }
        String rawTarget = MessagingService.safeName(args[0]);
        resolveAndOpen(viewer, rawTarget, InspectionLayout.InspectionType.ENDERCHEST, null);
    }

    private void openSelfEnderchest(Player viewer) {
        if (closed || !viewer.isOnline()) {
            return;
        }
        if (busyPolicy.test(viewer) || activeMutableSessions.containsKey(
                new SessionKey(viewer.getUniqueId(), InspectionLayout.InspectionType.ENDERCHEST))) {
            send(viewer, Messages.INSPECT_BUSY);
            return;
        }
        viewer.openInventory(viewer.getEnderChest());
        String viewerName = MessagingService.safeName(viewer.getName());
        String detail = InspectionLayout.formatAuditDetail(
                InspectionLayout.InspectionType.ENDERCHEST,
                InspectionLayout.InspectionMode.SELF,
                true,
                "self",
                List.of()
        );
        logInspection("ENDERCHEST_OPEN", viewerName, viewer.getUniqueId(), viewerName, detail);
    }

    private void resolveAndOpen(Player viewer, String rawTarget, InspectionLayout.InspectionType type, @Nullable BackButton back) {
        PlayerNameService names = nameService.get();
        if (names == null) {
            Player exact = findVisibleExactOnline(viewer, rawTarget);
            if (exact == null) {
                handleTargetResolution(viewer, rawTarget, Optional.empty(), type, back);
            } else {
                handleTargetResolution(viewer, rawTarget, Optional.of(
                        new NameCache.Entry(exact.getUniqueId(), exact.getName(), System.currentTimeMillis())), type, back);
            }
            return;
        }
        try {
            names.resolve(rawTarget).whenComplete((found, error) -> runFor(viewer, () -> {
                if (closed || !viewer.isOnline()) {
                    return;
                }
                if (error != null) {
                    send(viewer, Messages.INSPECT_UNAVAILABLE);
                    return;
                }
                handleTargetResolution(viewer, rawTarget, found == null ? Optional.empty() : found, type, back);
            }));
        } catch (RejectedExecutionException rejected) {
            send(viewer, Messages.INSPECT_UNAVAILABLE);
        }
    }

    private @Nullable Player findVisibleExactOnline(Player viewer, String name) {
        Player exact = plugin.getServer().getPlayerExact(name);
        if (exact != null && exact.isOnline() && PlayerNameVisibility.canSeeTarget(viewer, exact)) {
            return exact;
        }
        return null;
    }

    private void handleTargetResolution(
            Player viewer,
            String rawTarget,
            Optional<NameCache.Entry> resolved,
            InspectionLayout.InspectionType type,
            @Nullable BackButton back
    ) {
        if (closed || !viewer.isOnline()) {
            return;
        }
        if (!hasOpenCommandPermission(viewer, type, true)) {
            send(viewer, Messages.NO_PERMISSION);
            return;
        }

        if (resolved.isEmpty()) {
            if (handleOfflineOrHiddenDenial(viewer, rawTarget, type)) {
                send(viewer, Messages.INSPECT_OFFLINE_UNAVAILABLE, "{player}", rawTarget);
            }
            return;
        }

        NameCache.Entry entry = resolved.get();
        UUID targetId = entry.uuid();
        String targetName = MessagingService.safeName(entry.name());

        if (targetId.equals(viewer.getUniqueId())) {
            if (type == InspectionLayout.InspectionType.INVSEE) {
                send(viewer, Messages.INSPECT_SELF);
            } else {
                openSelfEnderchest(viewer);
            }
            return;
        }

        Player online = plugin.getServer().getPlayer(targetId);
        boolean actuallyOnline = online != null && online.isOnline();
        boolean visibleOnline = actuallyOnline && PlayerNameVisibility.canSeeTarget(viewer, online);

        if (!visibleOnline) {
            // Hidden vanished targets MUST behave identically to offline targets.
            if (!handleOfflineOrHiddenDenial(viewer, targetName, type)) {
                return;
            }
            if (offlineStore == null) {
                send(viewer, Messages.INSPECT_UNAVAILABLE);
                return;
            }
            if (actuallyOnline || offlineStore.isOnlineOrFenced(targetId)) {
                send(viewer, Messages.INSPECT_OFFLINE_UNAVAILABLE, "{player}", targetName);
                return;
            }
            boolean editable = viewer.hasPermission(editPermission(type));
            if (editable && !offlineEditingEnabled) {
                send(viewer, Messages.INSPECT_OFFLINE_EDIT_DISABLED);
                return;
            }
            openOfflineSession(viewer, targetId, targetName, type, editable, back);
            return;
        }

        if (!canModeratePolicy.test(viewer, targetId)) {
            send(viewer, Messages.DISCIPLINE_EXEMPT);
            return;
        }
        boolean editable = viewer.hasPermission(editPermission(type));
        if (BukkitServerThread.isFolia() && !BukkitServerThread.entity(plugin, online).isServerThread()) {
            openOnlineSessionRegional(viewer, online, type, editable, back);
            return;
        }
        if (type == InspectionLayout.InspectionType.INVSEE && online.isDead()) {
            send(viewer, Messages.INSPECT_TARGET_DEAD_OPEN, "{player}", MessagingService.safeName(online.getName()));
            return;
        }
        if (editable && busyPolicy.test(online)) {
            send(viewer, Messages.INSPECT_BUSY);
            return;
        }
        openOnlineSession(viewer, online, type, editable, back);
    }

    /**
     * Offline (and hidden) targets need only the {@code .offline} node. Their exempt status cannot be
     * read while they are offline, so offline inspection does not apply the discipline policy.
     */
    private boolean handleOfflineOrHiddenDenial(Player viewer, String targetName, InspectionLayout.InspectionType type) {
        String offlinePerm = offlinePermission(type);
        if (!viewer.hasPermission(offlinePerm)) {
            send(viewer, Messages.INSPECT_OFFLINE_REQUIRED, "{permission}", offlinePerm);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Session creation (Online & Offline)
    // ---------------------------------------------------------------------------------------------

    private void openOnlineSessionRegional(
            Player viewer,
            Player target,
            InspectionLayout.InspectionType type,
            boolean editable,
            @Nullable BackButton back
    ) {
        InspectionSession session = new InspectionSession(
                viewer,
                target.getUniqueId(),
                target.getName(),
                type,
                InspectionLayout.InspectionMode.ONLINE,
                editable
        );
        session.back = back;
        if (editable && activeMutableSessions.putIfAbsent(session.key(), session) != null) {
            send(viewer, Messages.INSPECT_BUSY);
            return;
        }
        Runnable releaseAndAbortOffline = () -> {
            activeMutableSessions.remove(session.key(), session);
            send(viewer, Messages.INSPECT_TARGET_OFFLINE, "{player}", session.targetName);
        };
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), target, () -> {
            if (closed || !target.isOnline()) {
                releaseAndAbortOffline.run();
                return;
            }
            if (type == InspectionLayout.InspectionType.INVSEE && target.isDead()) {
                activeMutableSessions.remove(session.key(), session);
                send(viewer, Messages.INSPECT_TARGET_DEAD_OPEN, "{player}", session.targetName);
                return;
            }
            if (editable && busyPolicy.test(target)) {
                activeMutableSessions.remove(session.key(), session);
                send(viewer, Messages.INSPECT_BUSY);
                return;
            }
            ItemStack[] snapshot = readLiveTargetSlots(target, type);
            int held = type == InspectionLayout.InspectionType.INVSEE ? target.getInventory().getHeldItemSlot() : -1;
            BukkitServerThread.runFor(plugin, plugin.getScheduler(), viewer, () -> {
                if (closed || !viewer.isOnline() || !hasOpenCommandPermission(viewer, type, true)
                        || (editable && !viewer.hasPermission(editPermission(type)))) {
                    activeMutableSessions.remove(session.key(), session);
                    return;
                }
                try {
                    session.held = held;
                    Inventory gui = buildGui(session, snapshot);
                    session.gui = gui;
                    session.lastSyncedSlots = cloneSlots(snapshot);
                    session.active = true;
                    registerSession(session);
                    viewer.openInventory(gui);

                    String detail = InspectionLayout.formatAuditDetail(type, InspectionLayout.InspectionMode.ONLINE, editable, "open", List.of());
                    logInspection(openAction(type), session.viewerName, session.targetUuid, session.targetName, detail);
                } catch (RuntimeException error) {
                    unregisterSession(session);
                    throw error;
                }
            }, () -> activeMutableSessions.remove(session.key(), session), 0L);
        }, releaseAndAbortOffline, 0L);
    }

    private void openOnlineSession(
            Player viewer,
            Player target,
            InspectionLayout.InspectionType type,
            boolean editable,
            @Nullable BackButton back
    ) {
        InspectionSession session = new InspectionSession(
                viewer,
                target.getUniqueId(),
                target.getName(),
                type,
                InspectionLayout.InspectionMode.ONLINE,
                editable
        );
        session.back = back;
        if (editable && activeMutableSessions.putIfAbsent(session.key(), session) != null) {
            send(viewer, Messages.INSPECT_BUSY);
            return;
        }
        try {
            ItemStack[] snapshot = readLiveTargetSlots(target, type);
            session.held = type == InspectionLayout.InspectionType.INVSEE ? target.getInventory().getHeldItemSlot() : -1;
            Inventory gui = buildGui(session, snapshot);
            session.gui = gui;
            session.lastSyncedSlots = cloneSlots(snapshot);
            session.active = true;
            registerSession(session);
            viewer.openInventory(gui);

            String detail = InspectionLayout.formatAuditDetail(type, InspectionLayout.InspectionMode.ONLINE, editable, "open", List.of());
            logInspection(openAction(type), session.viewerName, session.targetUuid, session.targetName, detail);
        } catch (RuntimeException error) {
            unregisterSession(session);
            throw error;
        }
    }

    private void openOfflineSession(
            Player viewer,
            UUID targetUuid,
            String targetName,
            InspectionLayout.InspectionType type,
            boolean editable,
            @Nullable BackButton back
    ) {
        if (offlineStore == null) {
            send(viewer, Messages.INSPECT_UNAVAILABLE);
            return;
        }
        if (editable && !offlineEditingEnabled) {
            send(viewer, Messages.INSPECT_OFFLINE_EDIT_DISABLED);
            return;
        }
        InspectionSession session = new InspectionSession(
                viewer,
                targetUuid,
                targetName,
                type,
                InspectionLayout.InspectionMode.OFFLINE,
                editable
        );
        session.back = back;
        if (editable && activeMutableSessions.putIfAbsent(session.key(), session) != null) {
            send(viewer, Messages.INSPECT_BUSY);
            return;
        }

        try {
            Runnable releaseSession = () -> activeMutableSessions.remove(session.key(), session);
            io.execute(() -> {
                if (closed || offlineStore.isOnlineOrFenced(targetUuid)) {
                    runFor(viewer, () -> {
                        activeMutableSessions.remove(session.key(), session);
                        if (viewer.isOnline()) {
                            send(viewer, Messages.INSPECT_OFFLINE_UNAVAILABLE, "{player}", targetName);
                        }
                    }, releaseSession);
                    return;
                }
                if (editable && offlineHoldPolicy.test(targetUuid)) {
                    runFor(viewer, () -> {
                        activeMutableSessions.remove(session.key(), session);
                        if (viewer.isOnline()) {
                            send(viewer, Messages.INSPECT_OFFLINE_HOLD, "{player}", targetName);
                        }
                    }, releaseSession);
                    return;
                }
                OfflinePlayerDataStore.LoadedSnapshot loaded;
                try {
                    loaded = offlineStore.load(targetUuid, type);
                } catch (FileNotFoundException | OfflinePlayerDataStore.LoginFencedException e) {
                    runFor(viewer, () -> {
                        activeMutableSessions.remove(session.key(), session);
                        if (viewer.isOnline()) {
                            send(viewer, Messages.INSPECT_OFFLINE_UNAVAILABLE, "{player}", targetName);
                        }
                    }, releaseSession);
                    return;
                } catch (PlayerDataCodec.DataVersionTooNewException newer) {
                    plugin.logger().warning("Refused offline " + type + " for " + targetName + " (" + targetUuid + "): " + newer.getMessage());
                    runFor(viewer, () -> {
                        activeMutableSessions.remove(session.key(), session);
                        if (viewer.isOnline()) {
                            send(viewer, Messages.INSPECT_OFFLINE_NEWER_VERSION, "{player}", targetName);
                        }
                    }, releaseSession);
                    return;
                } catch (IOException | RuntimeException corrupt) {
                    plugin.logger().warning("Corrupt offline player data for " + targetName + " (" + targetUuid + "): " + corrupt.getMessage());
                    runFor(viewer, () -> {
                        activeMutableSessions.remove(session.key(), session);
                        if (viewer.isOnline()) {
                            send(viewer, Messages.INSPECT_OFFLINE_CORRUPT, "{player}", targetName);
                        }
                    }, releaseSession);
                    return;
                }
                int serverDataVersion = offlineStore.currentDataVersion();
                if (editable && loaded.dataVersion() != serverDataVersion) {
                    plugin.logger().warning("Refused offline " + type + " edit for " + targetName + " (" + targetUuid
                            + "): DataVersion " + loaded.dataVersion() + " != server DataVersion " + serverDataVersion);
                    runFor(viewer, () -> {
                        activeMutableSessions.remove(session.key(), session);
                        if (viewer.isOnline()) {
                            send(viewer, Messages.INSPECT_OFFLINE_VERSION_REWRITE, "{player}", targetName);
                        }
                    }, releaseSession);
                    return;
                }

                runFor(viewer, () -> {
                    Player onlineNow = plugin.getServer().getPlayer(targetUuid);
                    if (closed || !viewer.isOnline() || !hasOfflineOpenPermission(viewer, type, editable)
                            || (editable && !offlineEditingEnabled)
                            || (onlineNow != null && onlineNow.isOnline()) || offlineStore.isOnlineOrFenced(targetUuid)) {
                        activeMutableSessions.remove(session.key(), session);
                        if (viewer.isOnline() && ((onlineNow != null && onlineNow.isOnline()) || offlineStore.isOnlineOrFenced(targetUuid))) {
                            send(viewer, Messages.INSPECT_OFFLINE_UNAVAILABLE, "{player}", targetName);
                        } else if (viewer.isOnline() && editable && !offlineEditingEnabled) {
                            send(viewer, Messages.INSPECT_OFFLINE_EDIT_DISABLED);
                        }
                        return;
                    }
                    session.currentRawBytes = loaded.rawBytes();
                    session.currentHash = loaded.sha256();
                    session.bukkitValues = loaded.bukkitValues();
                    session.lastSyncedSlots = cloneSlots(loaded.slots());
                    // The main hand of an offline player: the hotbar slot their file selects.
                    session.held = type == InspectionLayout.InspectionType.INVSEE ? loaded.selectedItemSlot() : -1;
                    Inventory gui = buildGui(session, loaded.slots());
                    session.gui = gui;
                    session.active = true;
                    registerSession(session);
                    viewer.openInventory(gui);
                    send(viewer, Messages.INSPECT_OFFLINE_OPENED, "{player}", targetName, "{inventory}", inventoryLabel(type),
                            "{access}", editable ? "&aeditable" : "&7read-only");

                    String detail = InspectionLayout.formatAuditDetail(
                            type, InspectionLayout.InspectionMode.OFFLINE, editable,
                            "dataVersion=" + loaded.dataVersion(), List.of());
                    logInspection(openAction(type), session.viewerName, session.targetUuid, session.targetName, detail);
                }, releaseSession);
            });
        } catch (RejectedExecutionException rejected) {
            activeMutableSessions.remove(session.key(), session);
            send(viewer, Messages.INSPECT_UNAVAILABLE);
        }
    }

    private Inventory buildGui(InspectionSession session, ItemStack[] playerSlots) {
        String title = InspectionLayout.formatGuiTitle(
                session.type,
                session.mode,
                session.editable,
                session.targetName,
                ChatColor.DARK_GRAY.toString()
        );
        Inventory gui = plugin.getServer().createInventory(session.holder, InspectionLayout.guiSize(session.type), title);
        session.holder.bind(gui);
        if (session.type == InspectionLayout.InspectionType.INVSEE) {
            ItemStack filler = createLockedPane();
            for (int slot = InspectionLayout.INVSEE_LOCKED_START; slot <= InspectionLayout.INVSEE_LOCKED_END; slot++) {
                gui.setItem(slot, filler.clone());
            }
            if (session.back != null) {
                gui.setItem(BACK_BUTTON_SLOT, backButton(session.back));
            }
        }
        applySlotsToGui(session.type, gui, playerSlots, session.held);
        return gui;
    }

    private static ItemStack backButton(BackButton back) {
        ItemStack arrow = new ItemStack(org.bukkit.Material.ARROW);
        try {
            org.bukkit.inventory.meta.ItemMeta meta = arrow.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("\u00a7cBack");
                meta.setLore(List.of("\u00a77" + back.lore()));
                arrow.setItemMeta(meta);
            }
        } catch (RuntimeException noItemFactory) {
            // Plain unit tests have no item factory: a bare arrow.
        }
        return arrow;
    }

    private static ItemStack createLockedPane() {
        return GuiIcons.icon(GuiIcons.Icon.FILLER, " ", List.of());
    }

    /** The main-hand cell when it is empty (or the held slot is unknown): the sword icon. */
    private static ItemStack mainHandIcon(int held) {
        List<String> lore = InspectionLayout.validHeld(held)
                ? List.of("\u00a77Empty: hotbar slot \u00a7f" + (held + 1) + "\u00a77 is selected", "\u00a78Place an item here to put it in their hand")
                : List.of("\u00a78The selected hotbar slot is not known");
        return GuiIcons.icon(GuiIcons.Icon.SWORD, "\u00a77Main hand", lore);
    }

    // ---------------------------------------------------------------------------------------------
    // Inventory GUI click / drag / close handlers
    // ---------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof InspectionHolder holder)) {
            guardTargetInventoryClick(event);
            return;
        }
        InspectionSession session = sessionsById.get(holder.sessionId());
        if (session == null || !session.active || closed || session.aborted) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getWhoClicked() instanceof Player clicker) || !clicker.getUniqueId().equals(session.viewerUuid)) {
            event.setCancelled(true);
            return;
        }
        scheduleResync(clicker);

        int topSize = InspectionLayout.guiSize(session.type);
        int rawSlot = event.getRawSlot();
        InventoryAction action = event.getAction();
        ClickType click = event.getClick();
        boolean inTop = rawSlot >= 0 && rawSlot < topSize;
        boolean lockedSlot = InspectionLayout.isLockedUiSlot(session.type, rawSlot, session.held);
        boolean shiftFromBottom = rawSlot >= topSize && action == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        boolean collectToCursor = action == InventoryAction.COLLECT_TO_CURSOR || click == ClickType.DOUBLE_CLICK;

        if (lockedSlot) {
            event.setCancelled(true);
            if (rawSlot == BACK_BUTTON_SLOT && session.back != null && session.type == InspectionLayout.InspectionType.INVSEE) {
                // Opening the other menu closes this one, which flushes and ends the session as usual.
                Runnable back = session.back.action();
                runFor(clicker, () -> {
                    if (clicker.isOnline()) {
                        back.run();
                    }
                });
            }
            return;
        }
        if (collectToCursor && session.type == InspectionLayout.InspectionType.INVSEE && matchesLockedPane(event, session.gui)) {
            event.setCancelled(true);
            return;
        }
        if (inTop && isIconSlot(session.gui, rawSlot)) {
            // An empty equipment slot's icon: only a cursor item may go there, and the icon is
            // replaced, never picked up.
            event.setCancelled(true);
            ItemStack cursor = event.getCursor();
            boolean placing = cursor != null && !cursor.getType().isAir()
                    && (action == InventoryAction.SWAP_WITH_CURSOR || action == InventoryAction.PLACE_ALL || action == InventoryAction.PLACE_ONE
                    || action == InventoryAction.PLACE_SOME || click == ClickType.LEFT || click == ClickType.RIGHT);
            if (placing && session.editable && prepareEditableTopInteraction(session)) {
                int amount = click == ClickType.RIGHT ? 1 : cursor.getAmount();
                ItemStack placed = cursor.clone();
                placed.setAmount(amount);
                session.gui.setItem(rawSlot, placed);
                ItemStack rest = cursor.clone();
                rest.setAmount(cursor.getAmount() - amount);
                clicker.setItemOnCursor(rest.getAmount() <= 0 ? null : rest);
            }
            return;
        }

        boolean touchesTop = inTop || shiftFromBottom || collectToCursor;
        if (!touchesTop) {
            return;
        }

        if (!session.editable) {
            event.setCancelled(true);
            return;
        }

        if (!prepareEditableTopInteraction(session)) {
            event.setCancelled(true);
            return;
        }
        int marker = InspectionLayout.heldMarkerGuiSlot(session.type, session.held);
        if (shiftFromBottom && marker >= 0) {
            // Vanilla would fill the empty, locked held-slot cell; move into the item cells instead.
            event.setCancelled(true);
            ItemStack stack = event.getCurrentItem();
            if (stack == null || stack.getType().isAir() || event.getClickedInventory() == null) {
                return;
            }
            int remaining = codes.castled.allium.gui.PanelMoves.moveInto(session.gui, InspectionLayout.STORAGE_GUI_CELLS, marker, stack.clone());
            if (remaining <= 0) {
                event.getClickedInventory().setItem(event.getSlot(), null);
            } else if (remaining != stack.getAmount()) {
                ItemStack rest = stack.clone();
                rest.setAmount(remaining);
                event.getClickedInventory().setItem(event.getSlot(), rest);
            }
            runFor(clicker, clicker::updateInventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof InspectionHolder holder)) {
            guardTargetInventoryDrag(event);
            return;
        }
        InspectionSession session = sessionsById.get(holder.sessionId());
        if (session == null || !session.active || closed || session.aborted) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getWhoClicked() instanceof Player clicker) || !clicker.getUniqueId().equals(session.viewerUuid)) {
            event.setCancelled(true);
            return;
        }
        scheduleResync(clicker);

        int topSize = InspectionLayout.guiSize(session.type);
        boolean touchesLocked = false;
        boolean touchesTop = false;
        for (int rawSlot : event.getRawSlots()) {
            if (InspectionLayout.isLockedUiSlot(session.type, rawSlot, session.held) || isIconSlot(session.gui, rawSlot)) {
                touchesLocked = true;
            }
            if (rawSlot >= 0 && rawSlot < topSize) {
                touchesTop = true;
            }
        }
        if (touchesLocked) {
            event.setCancelled(true);
            return;
        }
        if (!touchesTop) {
            return;
        }
        if (!session.editable) {
            event.setCancelled(true);
            return;
        }
        if (!prepareEditableTopInteraction(session)) {
            event.setCancelled(true);
        }
    }

    private boolean prepareEditableTopInteraction(InspectionSession session) {
        if (!hasSessionEditPermission(session)) {
            abortSession(session, "permission-revoked", Messages.NO_PERMISSION, null);
            return false;
        }
        if (session.mode == InspectionLayout.InspectionMode.ONLINE) {
            Player target = plugin.getServer().getPlayer(session.targetUuid);
            if (target == null || !target.isOnline()) {
                abortSession(session, "target-unavailable", Messages.INSPECT_TARGET_OFFLINE, session.targetName);
                return false;
            }
            boolean sameRegion = !BukkitServerThread.isFolia() || BukkitServerThread.entity(plugin, target).isServerThread();
            if (sameRegion) {
                Messages unavailable = liveTargetUnavailable(session, target);
                if (unavailable != null) {
                    abortSession(session, "target-unavailable", unavailable, session.targetName);
                    return false;
                }
                if (busyPolicy.test(target)) {
                    abortSession(session, "target-busy", Messages.INSPECT_BUSY, null);
                    return false;
                }
                // Snapshot verification before allowing the click/drag
                ItemStack[] live = readLiveTargetSlots(target, session.type);
                if (!slotsEqual(live, session.lastSyncedSlots)) {
                    session.lastSyncedSlots = cloneSlots(live);
                    applySlotsToGui(session.type, session.gui, live, session.held);
                    return false;
                }
            }
            session.guardActive = true;
            activeTargetGuards.add(session.targetUuid);
            if (!session.flushScheduled) {
                session.flushScheduled = true;
                runFor(session.viewer, () -> flushOnlineSession(session), () -> {
                    session.flushScheduled = false;
                    session.guardActive = false;
                    recomputeTargetGuard(session.targetUuid);
                });
            }
            return true;
        }

        // OFFLINE editable session
        if (!offlineEditingEnabled || offlineStore == null) {
            abortSession(session, "offline-edit-disabled", Messages.INSPECT_OFFLINE_EDIT_DISABLED, null);
            return false;
        }
        if (offlineStore.isOnlineOrFenced(session.targetUuid) || plugin.getServer().getPlayer(session.targetUuid) != null) {
            abortSession(session, "target-login", Messages.INSPECT_TARGET_ONLINE, session.targetName);
            return false;
        }
        if (session.writeInFlight) {
            send(session.viewer, Messages.INSPECT_BUSY);
            return false;
        }
        if (!session.flushScheduled) {
            session.flushScheduled = true;
            runFor(session.viewer, () -> flushOfflineSession(session), () -> {
                session.flushScheduled = false;
                completeLoginFlush(session);
            });
        }
        return true;
    }

    private static boolean matchesLockedPane(InventoryClickEvent event, Inventory gui) {
        return GuiIcons.isIcon(event.getCursor()) || GuiIcons.isIcon(event.getCurrentItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof InspectionHolder holder)) {
            return;
        }
        if (event.getPlayer() instanceof Player viewer) {
            // Clears any icon a mispredicting client still shows in the viewer's own inventory.
            scheduleResync(viewer);
        }
        InspectionSession session = sessionsById.get(holder.sessionId());
        if (session == null || !session.active) {
            return;
        }
        if (session.editable) {
            if (session.mode == InspectionLayout.InspectionMode.ONLINE) {
                flushOnlineSession(session);
            } else if (session.mode == InspectionLayout.InspectionMode.OFFLINE && session.flushScheduled && !session.writeInFlight) {
                flushOfflineSession(session);
            }
            if (session.mode == InspectionLayout.InspectionMode.OFFLINE) {
                if (session.writeInFlight) {
                    session.summaryPending = true;
                } else {
                    sendOfflineSummary(session);
                }
            }
        }
        closeAndUnregisterSession(session, false, "viewer-close");
    }

    // ---------------------------------------------------------------------------------------------
    // Online & offline flush
    // ---------------------------------------------------------------------------------------------

    private void flushOnlineSession(InspectionSession session) {
        session.flushScheduled = false;
        if (!session.active || session.aborted || closed) {
            session.guardActive = false;
            recomputeTargetGuard(session.targetUuid);
            return;
        }
        Player target = plugin.getServer().getPlayer(session.targetUuid);
        if (target == null || !target.isOnline()) {
            session.guardActive = false;
            recomputeTargetGuard(session.targetUuid);
            abortSession(session, "target-unavailable", Messages.INSPECT_TARGET_OFFLINE, session.targetName);
            return;
        }
        if (BukkitServerThread.isFolia() && !BukkitServerThread.entity(plugin, target).isServerThread()) {
            ItemStack[] baseline = cloneSlots(session.lastSyncedSlots);
            ItemStack[] newSlots = captureGuiSlots(session.type, session.gui, session.held);
            List<InspectionLayout.SlotDelta> deltas = computeDeltas(baseline, newSlots);
            if (deltas.isEmpty()) {
                session.guardActive = false;
                recomputeTargetGuard(session.targetUuid);
                return;
            }
            Runnable releaseGuard = () -> {
                session.guardActive = false;
                recomputeTargetGuard(session.targetUuid);
            };
            BukkitServerThread.runFor(plugin, plugin.getScheduler(), target, () -> {
                try {
                    if (session.aborted || closed) {
                        return;
                    }
                    Messages unavailable = liveTargetUnavailable(session, target);
                    if (unavailable != null) {
                        abortSession(session, "target-unavailable", unavailable, session.targetName);
                        return;
                    }
                    if (busyPolicy.test(target)) {
                        abortSession(session, "target-busy", Messages.INSPECT_BUSY, null);
                        return;
                    }
                    ItemStack[] live = readLiveTargetSlots(target, session.type);
                    boolean conflict = false;
                    for (InspectionLayout.SlotDelta delta : deltas) {
                        int slot = delta.playerSlot();
                        if (!stackEquals(live[slot], baseline[slot])) {
                            conflict = true;
                            break;
                        }
                    }
                    if (conflict) {
                        ItemStack[] fresh = cloneSlots(live);
                        runFor(session.viewer, () -> {
                            if (session.active && !session.aborted && !closed) {
                                session.lastSyncedSlots = cloneSlots(fresh);
                                applySlotsToGui(session.type, session.gui, fresh, session.held);
                            }
                            send(session.viewer, Messages.INSPECT_CONFLICT, "{player}", session.targetName);
                        });
                        return;
                    }
                    applySlotsToLiveTarget(target, session.type, baseline, newSlots, deltas);
                    ItemStack[] afterLive = readLiveTargetSlots(target, session.type);
                    String detail = InspectionLayout.formatAuditDetail(
                            session.type, InspectionLayout.InspectionMode.ONLINE, true, null, deltas);
                    logInspection(editAction(session.type), session.viewerName, session.targetUuid, session.targetName, detail);
                    runFor(session.viewer, () -> {
                        if (session.active && !session.aborted && !closed) {
                            session.lastSyncedSlots = cloneSlots(afterLive);
                            applySlotsToGui(session.type, session.gui, afterLive, session.held);
                        }
                    });
                } finally {
                    releaseGuard.run();
                }
            }, () -> {
                releaseGuard.run();
                abortSession(session, "target-unavailable", Messages.INSPECT_TARGET_OFFLINE, session.targetName);
            }, 0L);
            return;
        }
        session.guardActive = false;
        recomputeTargetGuard(session.targetUuid);
        Messages unavailable = liveTargetUnavailable(session, target);
        if (unavailable != null) {
            abortSession(session, "target-unavailable", unavailable, session.targetName);
            return;
        }
        ItemStack[] newSlots = captureGuiSlots(session.type, session.gui, session.held);
        List<InspectionLayout.SlotDelta> deltas = computeDeltas(session.lastSyncedSlots, newSlots);
        if (!deltas.isEmpty()) {
            applySlotsToLiveTarget(target, session.type, session.lastSyncedSlots, newSlots, deltas);
            session.lastSyncedSlots = cloneSlots(newSlots);
            paintEquipmentIcons(session.type, session.gui, session.held);
            String detail = InspectionLayout.formatAuditDetail(
                    session.type, InspectionLayout.InspectionMode.ONLINE, true, null, deltas);
            logInspection(editAction(session.type), session.viewerName, session.targetUuid, session.targetName, detail);
        }
    }

    private void flushOfflineSession(InspectionSession session) {
        session.flushScheduled = false;
        if (session.aborted) {
            completeLoginFlush(session);
            return;
        }
        if (!offlineEditingEnabled || offlineStore == null) {
            completeLoginFlush(session);
            abortSession(session, "offline-edit-disabled", Messages.INSPECT_OFFLINE_EDIT_DISABLED, null);
            return;
        }
        if (session.writeInFlight) {
            return;
        }
        ItemStack[] newSlots = captureGuiSlots(session.type, session.gui, session.held);
        List<InspectionLayout.SlotDelta> deltas = computeDeltas(session.lastSyncedSlots, newSlots);
        if (deltas.isEmpty()) {
            completeLoginFlush(session);
            return;
        }
        byte[] expectedHash = session.currentHash;
        byte[] baseBytes = session.currentRawBytes;
        Map<String, String> bukkitValues = session.bukkitValues;
        if (expectedHash == null || baseBytes == null || bukkitValues == null) {
            completeLoginFlush(session);
            return;
        }
        session.writeInFlight = true;
        ItemStack[] baselineOnFailure = cloneSlots(session.lastSyncedSlots);
        session.lastSyncedSlots = cloneSlots(newSlots);
        paintEquipmentIcons(session.type, session.gui, session.held);

        try {
            Runnable settleFailedWrite = () -> {
                session.writeInFlight = false;
                session.lastSyncedSlots = baselineOnFailure;
                completeLoginFlush(session);
            };
            io.execute(() -> {
                try {
                    if (offlineHoldPolicy.test(session.targetUuid)) {
                        runFor(session.viewer, () -> {
                            session.writeInFlight = false;
                            session.lastSyncedSlots = baselineOnFailure;
                            completeLoginFlush(session);
                            abortSession(session, "pending-hold", Messages.INSPECT_OFFLINE_HOLD, session.targetName);
                        }, settleFailedWrite);
                        return;
                    }
                    OfflinePlayerDataStore.SavedSnapshot saved = offlineStore.save(
                            session.targetUuid,
                            session.type,
                            expectedHash,
                            baseBytes,
                            bukkitValues,
                            newSlots
                    );
                    String detail = InspectionLayout.formatAuditDetail(
                            session.type, InspectionLayout.InspectionMode.OFFLINE, true, null, deltas);
                    logInspection(editAction(session.type), session.viewerName, session.targetUuid, session.targetName, detail);
                    Runnable commitSavedState = () -> {
                        session.writeInFlight = false;
                        session.currentRawBytes = saved.rawBytes();
                        session.currentHash = saved.sha256();
                        session.savedChanges += deltas.size();
                        completeLoginFlush(session);
                    };
                    runFor(session.viewer, () -> {
                        commitSavedState.run();
                        // A click made while this write was in flight is still flushed after the viewer
                        // closed the window: its items live only in the GUI until they are saved.
                        if (!session.aborted && !closed && session.flushScheduled) {
                            flushOfflineSession(session);
                        }
                        if (session.summaryPending && !session.writeInFlight) {
                            sendOfflineSummary(session);
                        }
                    }, commitSavedState);
                } catch (OfflinePlayerDataStore.DataVersionRewriteException rewriteErr) {
                    plugin.logger().warning("Refused offline " + session.type + " edit for " + session.targetName + ": " + rewriteErr.getMessage());
                    runFor(session.viewer, () -> {
                        session.writeInFlight = false;
                        session.lastSyncedSlots = baselineOnFailure;
                        completeLoginFlush(session);
                        abortSession(session, "dataversion-rewrite", Messages.INSPECT_OFFLINE_VERSION_REWRITE, session.targetName);
                    }, settleFailedWrite);
                } catch (OfflinePlayerDataStore.CasConflictException conflict) {
                    plugin.logger().warning("Offline " + session.type + " CAS conflict for " + session.targetName + ": " + conflict.getMessage());
                    runFor(session.viewer, () -> {
                        session.writeInFlight = false;
                        session.lastSyncedSlots = baselineOnFailure;
                        completeLoginFlush(session);
                        abortSession(session, "cas-conflict", Messages.INSPECT_CONFLICT, session.targetName);
                    }, settleFailedWrite);
                } catch (OfflinePlayerDataStore.LoginFencedException fenced) {
                    runFor(session.viewer, () -> {
                        session.writeInFlight = false;
                        session.lastSyncedSlots = baselineOnFailure;
                        completeLoginFlush(session);
                        abortSession(session, "login-fenced", Messages.INSPECT_TARGET_ONLINE, session.targetName);
                    }, settleFailedWrite);
                } catch (IOException | RuntimeException ioErr) {
                    plugin.logger().warning("Offline " + session.type + " save failed for " + session.targetName + ": " + ioErr.getMessage());
                    runFor(session.viewer, () -> {
                        session.writeInFlight = false;
                        session.lastSyncedSlots = baselineOnFailure;
                        completeLoginFlush(session);
                        abortSession(session, "io-error", Messages.INSPECT_OFFLINE_CORRUPT, session.targetName);
                    }, settleFailedWrite);
                }
            });
        } catch (RejectedExecutionException rejected) {
            session.writeInFlight = false;
            session.lastSyncedSlots = baselineOnFailure;
            completeLoginFlush(session);
            abortSession(session, "pool-full", Messages.INSPECT_UNAVAILABLE, null);
        }
    }

    private void completeLoginFlush(InspectionSession session) {
        CompletableFuture<Void> future = session.loginFlushFuture;
        if (future != null) {
            future.complete(null);
        }
    }

    private void refreshOnlineSessions() {
        for (InspectionSession session : List.copyOf(sessionsById.values())) {
            if (!session.active || session.aborted || session.mode != InspectionLayout.InspectionMode.ONLINE) {
                continue;
            }
            if (session.flushScheduled || session.guardActive) {
                continue;
            }
            if (BukkitServerThread.isFolia()) {
                refreshOnlineSessionRegional(session);
                continue;
            }
            if (!session.viewer.isOnline() || !Objects.equals(session.viewer.getOpenInventory().getTopInventory(), session.gui)) {
                closeAndUnregisterSession(session, false, "viewer-closed");
                continue;
            }
            if (session.editable && !hasSessionEditPermission(session)) {
                abortSession(session, "permission-revoked", Messages.NO_PERMISSION, null);
                continue;
            }
            Player target = plugin.getServer().getPlayer(session.targetUuid);
            Messages unavailable = liveTargetUnavailable(session, target);
            if (unavailable != null) {
                abortSession(session, unavailable == Messages.INSPECT_TARGET_DEAD ? "target-dead" : "target-offline",
                        unavailable, session.targetName);
                continue;
            }
            if (session.type == InspectionLayout.InspectionType.INVSEE
                    && session.held != target.getInventory().getHeldItemSlot()) {
                // The target picked another hotbar slot: lay the GUI out for it (nothing is pending).
                session.held = target.getInventory().getHeldItemSlot();
                applySlotsToGui(session.type, session.gui, session.lastSyncedSlots, session.held);
            }
            if (!session.viewer.hasPermission(PERMISSION_BYPASS) && !canModeratePolicy.test(session.viewer, session.targetUuid)) {
                abortSession(session, "policy-revoked", Messages.DISCIPLINE_EXEMPT, null);
                continue;
            }
            if (session.editable && busyPolicy.test(target)) {
                abortSession(session, "target-busy", Messages.INSPECT_BUSY, null);
                continue;
            }
            ItemStack[] live = readLiveTargetSlots(target, session.type);
            if (!slotsEqual(live, session.lastSyncedSlots)) {
                session.lastSyncedSlots = cloneSlots(live);
                applySlotsToGui(session.type, session.gui, live, session.held);
            }
        }
    }

    private void refreshOnlineSessionRegional(InspectionSession session) {
        if (!session.refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        Player target = plugin.getServer().getPlayer(session.targetUuid);
        if (target == null || !target.isOnline()) {
            session.refreshInFlight.set(false);
            abortSession(session, "target-offline", Messages.INSPECT_TARGET_OFFLINE, session.targetName);
            return;
        }
        Runnable releaseTargetRetired = () -> {
            session.refreshInFlight.set(false);
            abortSession(session, "target-offline", Messages.INSPECT_TARGET_OFFLINE, session.targetName);
        };
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), target, () -> {
            if (closed || !session.active || session.aborted || session.flushScheduled || session.guardActive) {
                session.refreshInFlight.set(false);
                return;
            }
            Messages unavailable = liveTargetUnavailable(session, target);
            if (unavailable != null) {
                session.refreshInFlight.set(false);
                abortSession(session, unavailable == Messages.INSPECT_TARGET_DEAD ? "target-dead" : "target-offline",
                        unavailable, session.targetName);
                return;
            }
            if (session.editable && busyPolicy.test(target)) {
                session.refreshInFlight.set(false);
                abortSession(session, "target-busy", Messages.INSPECT_BUSY, null);
                return;
            }
            int liveHeld = session.type == InspectionLayout.InspectionType.INVSEE
                    ? target.getInventory().getHeldItemSlot() : -1;
            ItemStack[] live = readLiveTargetSlots(target, session.type);
            BukkitServerThread.runFor(plugin, plugin.getScheduler(), session.viewer, () -> {
                try {
                    if (closed || !session.active || session.aborted || session.flushScheduled || session.guardActive) {
                        return;
                    }
                    if (!session.viewer.isOnline() || !Objects.equals(session.viewer.getOpenInventory().getTopInventory(), session.gui)) {
                        closeAndUnregisterSession(session, false, "viewer-closed");
                        return;
                    }
                    if (session.editable && !hasSessionEditPermission(session)) {
                        abortSession(session, "permission-revoked", Messages.NO_PERMISSION, null);
                        return;
                    }
                    if (!session.viewer.hasPermission(PERMISSION_BYPASS) && !canModeratePolicy.test(session.viewer, session.targetUuid)) {
                        abortSession(session, "policy-revoked", Messages.DISCIPLINE_EXEMPT, null);
                        return;
                    }
                    boolean heldChanged = session.type == InspectionLayout.InspectionType.INVSEE && session.held != liveHeld;
                    boolean slotsChanged = !slotsEqual(live, session.lastSyncedSlots);
                    if (heldChanged || slotsChanged) {
                        if (heldChanged) {
                            session.held = liveHeld;
                        }
                        session.lastSyncedSlots = cloneSlots(live);
                        applySlotsToGui(session.type, session.gui, live, session.held);
                    }
                } finally {
                    session.refreshInFlight.set(false);
                }
            }, () -> {
                session.refreshInFlight.set(false);
                closeAndUnregisterSession(session, false, "viewer-retired");
            }, 0L);
        }, releaseTargetRetired, 0L);
    }

    // ---------------------------------------------------------------------------------------------
    // Target & viewer lifecycle events + login fence + 1-tick target guards
    // ---------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (closed || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || offlineStore == null) {
            return;
        }
        UUID uuid = event.getUniqueId();
        CompletableFuture<Void> sessionSettled = scheduleOfflineLoginAbortIfNeeded(uuid);
        try {
            if (sessionSettled != null) {
                sessionSettled.get(LOGIN_FENCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }
            offlineStore.beginLoginFence(uuid, LOGIN_FENCE_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            offlineStore.clearLoginFence(uuid);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Please try again shortly.");
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            plugin.logger().warning("Login fence timed out for " + uuid + ": " + e.getMessage());
            offlineStore.clearLoginFence(uuid);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Player data is finishing a save; please try again shortly.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginMonitor(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED && offlineStore != null) {
            offlineStore.clearLoginFence(event.getUniqueId());
        }
    }

    private @Nullable CompletableFuture<Void> scheduleOfflineLoginAbortIfNeeded(UUID targetUuid) {
        CopyOnWriteArrayList<InspectionSession> targetSessions = sessionsByTarget.get(targetUuid);
        if (targetSessions == null || targetSessions.isEmpty()) {
            return null;
        }
        List<InspectionSession> offlineSessions = new ArrayList<>();
        for (InspectionSession s : targetSessions) {
            if (s.active && s.mode == InspectionLayout.InspectionMode.OFFLINE) {
                offlineSessions.add(s);
            }
        }
        if (offlineSessions.isEmpty()) {
            return null;
        }
        CompletableFuture<Void> settled = new CompletableFuture<>();
        if (BukkitServerThread.isFolia()) {
            List<CompletableFuture<Void>> perSession = new ArrayList<>();
            for (InspectionSession s : offlineSessions) {
                CompletableFuture<Void> done = new CompletableFuture<>();
                perSession.add(done);
                runFor(s.viewer, () -> {
                    if (!s.active || s.aborted) {
                        done.complete(null);
                        return;
                    }
                    if (s.editable && (s.flushScheduled || s.writeInFlight)) {
                        CompletableFuture<Void> f = new CompletableFuture<>();
                        s.loginFlushFuture = f;
                        f.whenComplete((ignored, err) -> done.complete(null));
                        if (s.flushScheduled && !s.writeInFlight) {
                            flushOfflineSession(s);
                        }
                        abortSession(s, "target-login", Messages.INSPECT_TARGET_ONLINE, s.targetName);
                    } else {
                        abortSession(s, "target-login", Messages.INSPECT_TARGET_ONLINE, s.targetName);
                        done.complete(null);
                    }
                }, () -> {
                    closeAndUnregisterSession(s, false, "viewer-retired");
                    done.complete(null);
                });
            }
            CompletableFuture.allOf(perSession.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> settled.complete(null));
            return settled;
        }
        scheduleOnServer(() -> {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (InspectionSession s : offlineSessions) {
                if (!s.active || s.aborted) {
                    continue;
                }
                if (s.editable && (s.flushScheduled || s.writeInFlight)) {
                    CompletableFuture<Void> f = new CompletableFuture<>();
                    s.loginFlushFuture = f;
                    pending.add(f);
                    if (s.flushScheduled && !s.writeInFlight) {
                        flushOfflineSession(s);
                    }
                    // Mark aborted after queuing/attaching flush so click finishes cleanly
                    abortSession(s, "target-login", Messages.INSPECT_TARGET_ONLINE, s.targetName);
                } else {
                    abortSession(s, "target-login", Messages.INSPECT_TARGET_ONLINE, s.targetName);
                }
            }
            if (pending.isEmpty()) {
                settled.complete(null);
            } else {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                        .whenComplete((ignored, error) -> settled.complete(null));
            }
        });
        return settled;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID targetUuid = event.getPlayer().getUniqueId();
        if (offlineStore != null) {
            offlineStore.markOnline(targetUuid);
        }
        CopyOnWriteArrayList<InspectionSession> list = sessionsByTarget.get(targetUuid);
        if (list != null) {
            for (InspectionSession session : List.copyOf(list)) {
                if (session.active && session.mode == InspectionLayout.InspectionMode.OFFLINE) {
                    abortSession(session, "target-join", Messages.INSPECT_TARGET_ONLINE, session.targetName);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 1. Flush any open sessions targeting this player before CraftBukkit saves their .dat
        CopyOnWriteArrayList<InspectionSession> targeted = sessionsByTarget.get(uuid);
        if (targeted != null) {
            for (InspectionSession session : List.copyOf(targeted)) {
                if (session.mode == InspectionLayout.InspectionMode.ONLINE) {
                    if (session.editable && session.flushScheduled
                            && (!BukkitServerThread.isFolia() || BukkitServerThread.entity(plugin, session.viewer).isServerThread())) {
                        flushOnlineSession(session);
                    }
                    abortSession(session, "target-quit", Messages.INSPECT_TARGET_OFFLINE, session.targetName);
                }
            }
        }

        // 2. Close/flush any sessions being viewed by this quitting player
        CopyOnWriteArrayList<InspectionSession> viewing = sessionsByViewer.get(uuid);
        if (viewing != null) {
            for (InspectionSession session : List.copyOf(viewing)) {
                if (session.editable && session.flushScheduled) {
                    if (session.mode == InspectionLayout.InspectionMode.ONLINE) {
                        flushOnlineSession(session);
                    } else if (session.mode == InspectionLayout.InspectionMode.OFFLINE && !session.writeInFlight) {
                        flushOfflineSession(session);
                    }
                }
                closeAndUnregisterSession(session, false, "viewer-quit");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuitComplete(PlayerQuitEvent event) {
        if (offlineStore != null) {
            offlineStore.markOffline(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID targetUuid = event.getEntity().getUniqueId();
        CopyOnWriteArrayList<InspectionSession> targeted = sessionsByTarget.get(targetUuid);
        if (targeted == null || targeted.isEmpty()) {
            return;
        }
        for (InspectionSession session : List.copyOf(targeted)) {
            if (!session.active || session.mode != InspectionLayout.InspectionMode.ONLINE) {
                continue;
            }
            if (session.type == InspectionLayout.InspectionType.INVSEE) {
                // Prevent death drop duplication if a click happened on the same tick before 1-tick flush.
                if (session.editable && session.flushScheduled) {
                    if (!BukkitServerThread.isFolia() || BukkitServerThread.entity(plugin, session.viewer).isServerThread()) {
                        reconcileUnflushedInvseeDrops(session, event.getDrops());
                    }
                    session.flushScheduled = false;
                }
                abortSession(session, "target-died", Messages.INSPECT_TARGET_DIED, session.targetName);
            }
        }
    }

    private void reconcileUnflushedInvseeDrops(InspectionSession session, List<ItemStack> drops) {
        if (session.gui == null || session.lastSyncedSlots == null || drops == null || drops.isEmpty()) {
            return;
        }
        ItemStack[] newSlots = captureGuiSlots(InspectionLayout.InspectionType.INVSEE, session.gui, session.held);
        List<InspectionLayout.SlotDelta> deltas = computeDeltas(session.lastSyncedSlots, newSlots);
        for (InspectionLayout.SlotDelta delta : deltas) {
            int pSlot = delta.playerSlot();
            ItemStack oldItem = session.lastSyncedSlots[pSlot];
            ItemStack newItem = newSlots[pSlot];
            if (oldItem == null || oldItem.getType().isAir()) {
                continue;
            }
            int removedCount;
            if (newItem == null || newItem.getType().isAir()) {
                removedCount = oldItem.getAmount();
            } else if (oldItem.isSimilar(newItem) && oldItem.getAmount() > newItem.getAmount()) {
                removedCount = oldItem.getAmount() - newItem.getAmount();
            } else if (!oldItem.isSimilar(newItem)) {
                removedCount = oldItem.getAmount();
            } else {
                removedCount = 0;
            }
            if (removedCount > 0) {
                removeFromDropList(drops, oldItem, removedCount);
            }
        }
    }

    private static void removeFromDropList(List<ItemStack> drops, ItemStack template, int count) {
        int remaining = count;
        for (int i = 0; i < drops.size() && remaining > 0; i++) {
            ItemStack drop = drops.get(i);
            if (drop != null && !drop.getType().isAir() && template.isSimilar(drop)) {
                int take = Math.min(remaining, drop.getAmount());
                remaining -= take;
                int left = drop.getAmount() - take;
                if (left <= 0) {
                    drops.remove(i);
                    i--;
                } else {
                    drop.setAmount(left);
                }
            }
        }
    }

    private void guardTargetInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && activeTargetGuards.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void guardTargetInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p && activeTargetGuards.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTargetDrop(PlayerDropItemEvent event) {
        if (activeTargetGuards.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTargetSwap(PlayerSwapHandItemsEvent event) {
        if (activeTargetGuards.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTargetPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && activeTargetGuards.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTargetConsume(PlayerItemConsumeEvent event) {
        if (activeTargetGuards.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTargetHeld(PlayerItemHeldEvent event) {
        if (activeTargetGuards.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Internal session registry, slot helpers, permissions & audit logging
    // ---------------------------------------------------------------------------------------------

    private void registerSession(InspectionSession session) {
        sessionsById.put(session.sessionId, session);
        sessionsByTarget.computeIfAbsent(session.targetUuid, k -> new CopyOnWriteArrayList<>()).add(session);
        sessionsByViewer.computeIfAbsent(session.viewerUuid, k -> new CopyOnWriteArrayList<>()).add(session);
    }

    private void unregisterSession(InspectionSession session) {
        session.active = false;
        session.guardActive = false;
        sessionsById.remove(session.sessionId, session);
        activeMutableSessions.remove(session.key(), session);
        CopyOnWriteArrayList<InspectionSession> targetList = sessionsByTarget.get(session.targetUuid);
        if (targetList != null) {
            targetList.remove(session);
            if (targetList.isEmpty()) {
                sessionsByTarget.remove(session.targetUuid, targetList);
            }
        }
        CopyOnWriteArrayList<InspectionSession> viewerList = sessionsByViewer.get(session.viewerUuid);
        if (viewerList != null) {
            viewerList.remove(session);
            if (viewerList.isEmpty()) {
                sessionsByViewer.remove(session.viewerUuid, viewerList);
            }
        }
        recomputeTargetGuard(session.targetUuid);
    }

    private void recomputeTargetGuard(UUID targetUuid) {
        CopyOnWriteArrayList<InspectionSession> list = sessionsByTarget.get(targetUuid);
        if (list == null || list.isEmpty()) {
            activeTargetGuards.remove(targetUuid);
            return;
        }
        for (InspectionSession s : list) {
            if (s.active && s.guardActive) {
                activeTargetGuards.add(targetUuid);
                return;
            }
        }
        activeTargetGuards.remove(targetUuid);
    }

    private void abortSession(InspectionSession session, String reason, Messages notify, @Nullable String playerName) {
        if (session.aborted) {
            return;
        }
        session.aborted = true;
        closeAndUnregisterSession(session, true, reason);
        if (session.viewer.isOnline() && notify != null) {
            if (playerName != null) {
                send(session.viewer, notify, "{player}", playerName);
            } else {
                send(session.viewer, notify);
            }
        }
        String detail = InspectionLayout.formatAuditDetail(session.type, session.mode, session.editable, reason, List.of());
        logInspection(abortAction(session.type), session.viewerName, session.targetUuid, session.targetName, detail);
    }

    /**
     * Why a live session can no longer show its target, or null when it can. A player on the respawn
     * screen is online but dead: their inventory is already dropped (or kept), so a live inventory
     * view closes with its own message rather than claiming they went offline. Ender chests survive
     * death, so they stay open.
     */
    private static @Nullable Messages liveTargetUnavailable(InspectionSession session, @Nullable Player target) {
        if (target == null || !target.isOnline()) {
            return Messages.INSPECT_TARGET_OFFLINE;
        }
        if (target.isDead() && session.type == InspectionLayout.InspectionType.INVSEE) {
            return Messages.INSPECT_TARGET_DEAD;
        }
        return null;
    }

    private static String inventoryLabel(InspectionLayout.InspectionType type) {
        return type == InspectionLayout.InspectionType.ENDERCHEST ? "ender chest" : "inventory";
    }

    private void sendOfflineSummary(InspectionSession session) {
        session.summaryPending = false;
        if (session.savedChanges > 0 && session.viewer.isOnline()) {
            send(session.viewer, Messages.INSPECT_OFFLINE_SAVED, "{changes}", Integer.toString(session.savedChanges),
                    "{player}", session.targetName, "{inventory}", inventoryLabel(session.type));
        }
    }

    /** True while a staff member has {@code target}'s inventory or ender chest open for editing. */
    public boolean editing(UUID target) {
        return activeMutableSessions.keySet().stream().anyMatch(key -> key.targetUuid().equals(target));
    }

    private void closeAndUnregisterSession(InspectionSession session, boolean closeWindow, String reason) {
        unregisterSession(session);
        if (closeWindow && session.gui != null) {
            UtilitySupport.onOwner(plugin, session.viewer, () -> {
                if (session.viewer.isOnline()
                        && Objects.equals(session.viewer.getOpenInventory().getTopInventory(), session.gui)) {
                    session.viewer.closeInventory();
                }
            });
        }
    }

    private static ItemStack[] readLiveTargetSlots(Player target, InspectionLayout.InspectionType type) {
        if (type == InspectionLayout.InspectionType.ENDERCHEST) {
            Inventory ec = target.getEnderChest();
            ItemStack[] out = new ItemStack[InspectionLayout.ENDERCHEST_GUI_SIZE];
            for (int i = 0; i < out.length; i++) {
                out[i] = cloneOrNull(ec.getItem(i));
            }
            return out;
        }
        PlayerInventory inv = target.getInventory();
        ItemStack[] out = new ItemStack[InspectionLayout.INVSEE_PLAYER_SLOT_COUNT];
        for (int i = 0; i <= 35; i++) {
            out[i] = cloneOrNull(inv.getItem(i));
        }
        out[36] = cloneOrNull(inv.getBoots());
        out[37] = cloneOrNull(inv.getLeggings());
        out[38] = cloneOrNull(inv.getChestplate());
        out[39] = cloneOrNull(inv.getHelmet());
        out[40] = cloneOrNull(inv.getItemInOffHand());
        return out;
    }

    private static void applySlotsToLiveTarget(
            Player target,
            InspectionLayout.InspectionType type,
            ItemStack[] expectedBefore,
            ItemStack[] newSlots,
            List<InspectionLayout.SlotDelta> deltas
    ) {
        if (type == InspectionLayout.InspectionType.ENDERCHEST) {
            Inventory ec = target.getEnderChest();
            for (InspectionLayout.SlotDelta delta : deltas) {
                int slot = delta.playerSlot();
                ec.setItem(slot, cloneOrNull(newSlots[slot]));
            }
            return;
        }
        PlayerInventory inv = target.getInventory();
        for (InspectionLayout.SlotDelta delta : deltas) {
            int slot = delta.playerSlot();
            ItemStack val = cloneOrNull(newSlots[slot]);
            if (slot <= 35) {
                inv.setItem(slot, val);
            } else if (slot == 36) {
                inv.setBoots(val);
            } else if (slot == 37) {
                inv.setLeggings(val);
            } else if (slot == 38) {
                inv.setChestplate(val);
            } else if (slot == 39) {
                inv.setHelmet(val);
            } else if (slot == 40) {
                inv.setItemInOffHand(val);
            }
        }
    }

    private static void applySlotsToGui(InspectionLayout.InspectionType type, Inventory gui, ItemStack[] playerSlots, int held) {
        int count = InspectionLayout.slotCount(type);
        if (type == InspectionLayout.InspectionType.INVSEE) {
            gui.setItem(InspectionLayout.MAIN_HAND_GUI_SLOT, null);
        }
        for (int pSlot = 0; pSlot < count; pSlot++) {
            int guiSlot = InspectionLayout.toGuiSlot(type, pSlot, held);
            if (guiSlot >= 0) {
                ItemStack stack = (playerSlots != null && pSlot < playerSlots.length) ? cloneOrNull(playerSlots[pSlot]) : null;
                gui.setItem(guiSlot, stack);
            }
        }
        // The held hotbar cell stays empty (its item is in the main-hand cell) and locked.
        int marker = InspectionLayout.heldMarkerGuiSlot(type, held);
        if (marker >= 0) {
            gui.setItem(marker, null);
        }
        paintEquipmentIcons(type, gui, held);
    }

    /** An empty armor, offhand or main-hand slot shows its empty-slot icon; icons are never saved as items. */
    private static void paintEquipmentIcons(InspectionLayout.InspectionType type, Inventory gui, int held) {
        if (type != InspectionLayout.InspectionType.INVSEE) {
            return;
        }
        ItemStack hand = gui.getItem(InspectionLayout.MAIN_HAND_GUI_SLOT);
        if (hand == null || hand.getType().isAir() || !InspectionLayout.validHeld(held)) {
            gui.setItem(InspectionLayout.MAIN_HAND_GUI_SLOT, mainHandIcon(held));
        }
        for (int pSlot = 36; pSlot <= 40; pSlot++) {
            int guiSlot = InspectionLayout.toGuiSlot(type, pSlot);
            ItemStack current = gui.getItem(guiSlot);
            if (current == null || current.getType().isAir()) {
                GuiIcons.Icon icon = GuiIcons.equipmentIcon(pSlot);
                gui.setItem(guiSlot, GuiIcons.icon(icon, "\u00a77Empty " + InspectionLayout.slotLabel(type, pSlot)
                        .replace("armor[", "").replace("]", ""), List.of("\u00a78Place an item here to equip it")));
            }
        }
    }

    private static boolean isIconSlot(Inventory gui, int rawSlot) {
        return rawSlot >= 0 && rawSlot < gui.getSize() && GuiIcons.isIcon(gui.getItem(rawSlot));
    }

    private static ItemStack[] captureGuiSlots(InspectionLayout.InspectionType type, Inventory gui, int held) {
        int count = InspectionLayout.slotCount(type);
        ItemStack[] out = new ItemStack[count];
        for (int pSlot = 0; pSlot < count; pSlot++) {
            int guiSlot = InspectionLayout.toGuiSlot(type, pSlot, held);
            ItemStack stack = guiSlot >= 0 ? gui.getItem(guiSlot) : null;
            out[pSlot] = GuiIcons.isIcon(stack) ? null : cloneOrNull(stack);
        }
        return out;
    }

    private static List<InspectionLayout.SlotDelta> computeDeltas(ItemStack[] before, ItemStack[] after) {
        List<InspectionLayout.SlotDelta> deltas = new ArrayList<>();
        int len = Math.min(before.length, after.length);
        for (int i = 0; i < len; i++) {
            if (!stackEquals(before[i], after[i])) {
                deltas.add(new InspectionLayout.SlotDelta(i, summarize(before[i]), summarize(after[i])));
            }
        }
        return deltas;
    }

    private static InspectionLayout.ItemSummary summarize(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return InspectionLayout.ItemSummary.EMPTY;
        }
        boolean hasMeta = false;
        try {
            hasMeta = stack.hasItemMeta();
        } catch (RuntimeException ignored) {
            // Mocked ItemStack in unit tests
        }
        return new InspectionLayout.ItemSummary(stack.getType().name(), stack.getAmount(), hasMeta);
    }

    private static boolean slotsEqual(ItemStack[] a, ItemStack[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!stackEquals(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean stackEquals(@Nullable ItemStack a, @Nullable ItemStack b) {
        boolean emptyA = a == null || a.getType().isAir() || a.getAmount() <= 0;
        boolean emptyB = b == null || b.getType().isAir() || b.getAmount() <= 0;
        if (emptyA && emptyB) return true;
        if (emptyA || emptyB) return false;
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    private static ItemStack[] cloneSlots(ItemStack[] src) {
        ItemStack[] copy = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            copy[i] = cloneOrNull(src[i]);
        }
        return copy;
    }

    private static @Nullable ItemStack cloneOrNull(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return null;
        }
        return stack.clone();
    }

    private boolean hasOpenCommandPermission(Player viewer, InspectionLayout.InspectionType type, boolean other) {
        if (type == InspectionLayout.InspectionType.INVSEE) {
            return viewer.hasPermission(PERMISSION_INVSEE);
        }
        return viewer.hasPermission(PERMISSION_ENDERCHEST) && (!other || viewer.hasPermission(PERMISSION_ENDERCHEST_OTHERS));
    }

    private boolean hasOfflineOpenPermission(Player viewer, InspectionLayout.InspectionType type, boolean editable) {
        if (!hasOpenCommandPermission(viewer, type, true)) return false;
        if (!viewer.hasPermission(offlinePermission(type))) return false;
        return !editable || viewer.hasPermission(editPermission(type));
    }

    private boolean hasSessionEditPermission(InspectionSession session) {
        if (session.mode == InspectionLayout.InspectionMode.OFFLINE) {
            return hasOfflineOpenPermission(session.viewer, session.type, true);
        }
        return hasOpenCommandPermission(session.viewer, session.type, true)
                && session.viewer.hasPermission(editPermission(session.type));
    }

    private static String editPermission(InspectionLayout.InspectionType type) {
        return type == InspectionLayout.InspectionType.INVSEE ? PERMISSION_INVSEE_EDIT : PERMISSION_ENDERCHEST_EDIT;
    }

    private static String offlinePermission(InspectionLayout.InspectionType type) {
        return type == InspectionLayout.InspectionType.INVSEE ? PERMISSION_INVSEE_OFFLINE : PERMISSION_ENDERCHEST_OFFLINE;
    }

    private static String openAction(InspectionLayout.InspectionType type) {
        return type == InspectionLayout.InspectionType.INVSEE ? "INVSEE_OPEN" : "ENDERCHEST_OPEN";
    }

    private static String editAction(InspectionLayout.InspectionType type) {
        return type == InspectionLayout.InspectionType.INVSEE ? "INVSEE_EDIT" : "ENDERCHEST_EDIT";
    }

    private static String abortAction(InspectionLayout.InspectionType type) {
        return type == InspectionLayout.InspectionType.INVSEE ? "INVSEE_ABORT" : "ENDERCHEST_ABORT";
    }

    private void logInspection(String action, String actorName, UUID targetUuid, String targetName, String detail) {
        plugin.logger().info("[Inspect/" + action + "] actor=" + actorName + " target=" + targetName + " (" + targetUuid + ") " + detail);
        try {
            io.execute(() -> {
                try {
                    repository.recordAudit(action, actorName, targetUuid, targetName, detail);
                } catch (SQLException e) {
                    plugin.logger().warning("Failed to record inspection audit (" + action + "): " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException rejected) {
            plugin.logger().warning("Inspection audit pool busy; skipped SQL audit for " + action + " (" + targetUuid + ")");
        }
    }

    /**
     * Resends the viewer's open window and cursor on the next tick, once per tick. The server's own
     * corrections go out in the same tick as the click; a client that predicts a click wrongly, such as
     * one behind a protocol translator or an inventory mod, can keep showing an icon it never picked up
     * (it cannot be clicked, and placing an item on it clears it). A full resend after the client's
     * whole click sequence removes it.
     */
    private void scheduleResync(Player viewer) {
        UUID id = viewer.getUniqueId();
        if (!resyncQueued.add(id)) {
            return;
        }
        runFor(viewer, () -> {
            resyncQueued.remove(id);
            if (viewer.isOnline()) {
                viewer.updateInventory();
            }
        }, () -> resyncQueued.remove(id));
    }

    private void runFor(CommandSender target, Runnable task) {
        runFor(target, task, () -> {});
    }

    private void runFor(CommandSender target, Runnable task, Runnable retired) {
        if (closed) {
            return;
        }
        if (plugin.getScheduler() != null) {
            Runnable guarded = () -> {
                if (!closed) {
                    task.run();
                }
            };
            if (BukkitServerThread.isFolia()) {
                BukkitServerThread.runFor(plugin, plugin.getScheduler(), target, guarded, retired, 0L);
            } else {
                plugin.getScheduler().runOnServer(guarded);
            }
        }
    }

    private void scheduleOnServer(Runnable task) {
        if (closed) {
            return;
        }
        if (plugin.getScheduler() != null) {
            plugin.getScheduler().runOnServer(() -> {
                if (!closed) {
                    task.run();
                }
            });
        }
    }

    private void runOnServer(Runnable task) {
        scheduleOnServer(task);
    }

    private void send(CommandSender sender, Messages message, String... replacements) {
        if (sender == null || (sender instanceof Player p && !p.isOnline())) {
            return;
        }
        UtilitySupport.send(plugin, sender, message, replacements);
    }

    public boolean hasActiveMutableSession(UUID targetUuid, InspectionLayout.InspectionType type) {
        return activeMutableSessions.containsKey(new SessionKey(targetUuid, type));
    }

    /** Clears saved inventories using the same login fence, version and CAS checks as offline inspection. */
    public void clearOfflineInventories(CommandSender sender, Set<UUID> onlineAtStart,
                                        java.util.function.ToIntFunction<ItemStack[]> clear) {
        if (!offlineClearRunning.compareAndSet(false, true)) {
            send(sender, Messages.INSPECT_UNAVAILABLE);
            return;
        }
        String actor = sender.getName();
        try {
            io.execute(() -> {
                try {
                    clearOfflineBatch(sender, actor, onlineAtStart, clear);
                } finally {
                    offlineClearRunning.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            offlineClearRunning.set(false);
            send(sender, Messages.INSPECT_UNAVAILABLE);
        }
    }

    private void clearOfflineBatch(CommandSender sender, String actor, Set<UUID> onlineAtStart,
                                   java.util.function.ToIntFunction<ItemStack[]> clear) {
        long removed = 0;
        int changed = 0;
        int skipped = 0;
        var type = InspectionLayout.InspectionType.INVSEE;
        try (var files = java.nio.file.Files.newDirectoryStream(offlineStore.playerDataDir(), "*.dat")) {
            for (var path : files) {
                if (closed || !offlineEditingEnabled) break;
                String file = path.getFileName().toString();
                UUID id;
                try {
                    id = UUID.fromString(file.substring(0, file.length() - 4));
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                if (onlineAtStart.contains(id) || offlineStore.isOnlineOrFenced(id)) continue;
                if (hasActiveMutableSession(id, type) || offlineHoldPolicy.test(id)) {
                    skipped++;
                    continue;
                }
                try {
                    var snapshot = offlineStore.load(id, type);
                    if (snapshot.dataVersion() != offlineStore.currentDataVersion()) {
                        skipped++;
                        continue;
                    }
                    var edit = new CompletableFuture<Integer>();
                    runFor(sender, () -> {
                        try {
                            if (closed || !sender.hasPermission("allium.clearinventory")
                                    || !sender.hasPermission("allium.clearinventory.all")
                                    || sender instanceof Player player && !player.isOnline()
                                    || hasActiveMutableSession(id, type) || offlineStore.isOnlineOrFenced(id)) {
                                edit.complete(-1);
                            } else {
                                edit.complete(clear.applyAsInt(snapshot.slots()));
                            }
                        } catch (RuntimeException failure) {
                            edit.completeExceptionally(failure);
                        }
                    }, () -> edit.complete(-1));
                    int count = edit.get(3, TimeUnit.SECONDS);
                    if (count < 0) {
                        skipped++;
                    } else if (count > 0) {
                        if (closed || !offlineEditingEnabled || hasActiveMutableSession(id, type)
                                || offlineHoldPolicy.test(id)) {
                            skipped++;
                            continue;
                        }
                        offlineStore.save(id, type, snapshot.sha256(), snapshot.rawBytes(),
                                snapshot.bukkitValues(), snapshot.slots());
                        removed += count;
                        changed++;
                        String detail = "offline=true removed=" + count;
                        plugin.logger().info("[Inspect/clearinventory] actor=" + actor + " target=" + id + " " + detail);
                        try {
                            repository.recordAudit("clearinventory", actor, id, id.toString(), detail);
                        } catch (SQLException failed) {
                            plugin.logger().warning("Could not audit offline clear for " + id + ": " + failed.getMessage());
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException | TimeoutException | java.util.concurrent.ExecutionException | RuntimeException failed) {
                    skipped++;
                    plugin.logger().warning("Offline clear skipped " + id + ": " + failed.getMessage());
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException failed) {
            plugin.logger().warning("Offline clear could not enumerate player data: " + failed.getMessage());
            runFor(sender, () -> send(sender, Messages.INSPECT_UNAVAILABLE));
        }
        String items = Long.toString(removed);
        String players = Integer.toString(changed);
        String failures = Integer.toString(skipped);
        runFor(sender, () -> send(sender, Messages.UTILITY_CLEAR_OFFLINE,
                "{count}", items, "{players}", players, "{skipped}", failures));
    }

    public OfflinePlayerDataStore offlineStore() {
        return offlineStore;
    }
}
