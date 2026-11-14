package codes.castled.allium.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.DurationParser;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Runtime service for S18 player info utilities ({@code /list}, {@code /seen}, {@code /whois},
 * {@code /near}, {@code /afk}) built against real vanish and backed by bounded SQL storage.
 */
public final class PlayerInfoService implements Listener {

    public static final String PERMISSION_LIST = "allium.list";
    public static final String PERMISSION_SEEN = "allium.seen";
    public static final String PERMISSION_WHOIS = "allium.whois";
    public static final String PERMISSION_WHOIS_LOCATION = "allium.whois.location";
    public static final String PERMISSION_NEAR = "allium.near";
    public static final String PERMISSION_NEAR_BYPASS_MAX = "allium.near.bypass-max";
    public static final String PERMISSION_AFK = "allium.afk";
    public static final String PERMISSION_AFK_KICK_EXEMPT = "allium.afk.kick.exempt";
    public static final String LIST_GROUP_PERMISSION_PREFIX = "allium.list.group.";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final List<String> DEFAULT_GROUPS = List.of("owner", "admin", "mod", "staff", "vip");

    public record InfoSettings(
            boolean groupByPermission,
            String defaultGroup,
            List<String> groups,
            int nearDefaultRadius,
            int nearMaxRadius,
            long autoAfkSeconds,
            boolean kickOnAfk,
            long kickAfterSeconds,
            boolean afkBroadcast,
            boolean afkIgnoreSleep
    ) {
        public static InfoSettings fromConfig(@Nullable ConfigurationSection section) {
            ConfigurationSection listSec = section == null ? null : section.getConfigurationSection("list");
            boolean groupByPerm = listSec == null || listSec.getBoolean("group-by-permission", true);
            String defGroup = listSec == null ? "Players" : listSec.getString("default-group", "Players");
            if (defGroup == null || defGroup.isBlank()) {
                defGroup = "Players";
            } else {
                defGroup = defGroup.trim();
            }
            List<String> configuredGroups = (listSec != null && listSec.isList("groups"))
                    ? listSec.getStringList("groups")
                    : DEFAULT_GROUPS;
            List<String> cleanedGroups = new ArrayList<>();
            for (String g : configuredGroups) {
                if (g != null && !g.isBlank()) {
                    cleanedGroups.add(g.trim().toLowerCase(Locale.ROOT));
                }
            }

            ConfigurationSection nearSec = section == null ? null : section.getConfigurationSection("near");
            int nearDef = Math.max(1, nearSec == null ? 200 : nearSec.getInt("default-radius", 200));
            int nearMax = Math.max(nearDef, nearSec == null ? 500 : nearSec.getInt("max-radius", 500));

            ConfigurationSection afkSec = section == null ? null : section.getConfigurationSection("afk");
            long autoAfk = Math.max(0L, afkSec == null ? 300L : afkSec.getLong("auto-afk-seconds", 300L));
            boolean kickOnAfk = afkSec != null && afkSec.getBoolean("kick-on-afk", false);
            long kickAfter = Math.max(1L, afkSec == null ? 600L : afkSec.getLong("kick-after-seconds", 600L));
            boolean broadcast = afkSec == null || afkSec.getBoolean("broadcast", true);
            boolean ignoreSleep = afkSec == null || afkSec.getBoolean("ignore-sleep", true);

            return new InfoSettings(
                    groupByPerm,
                    defGroup,
                    List.copyOf(cleanedGroups),
                    nearDef,
                    nearMax,
                    autoAfk,
                    kickOnAfk,
                    kickAfter,
                    broadcast,
                    ignoreSleep
            );
        }
    }

    public record ListedEntry(String name, String group, boolean afk, boolean vanished) {
    }

    public record NearbyEntry(String name, double distance, boolean afk, boolean vanished) {
    }

    public record ProfileLookupResult(
            PlayerInfoRecord record,
            List<PlayerNameRepository.NameHistoryEntry> nameHistory
    ) {
    }

    private static final class PlayerSession {
        final UUID uuid;
        volatile String name;
        volatile long firstJoin;
        volatile long lastJoin;
        volatile long lastSeen;
        volatile long lastActivity;
        volatile boolean afk;
        volatile long afkSince;
        volatile @Nullable String afkMessage;

        PlayerSession(UUID uuid, String name, long now) {
            this.uuid = uuid;
            this.name = name;
            this.firstJoin = now;
            this.lastJoin = now;
            this.lastSeen = now;
            this.lastActivity = now;
        }
    }

    private final PluginStart plugin;
    private final PlayerInfoRepository repository;
    private final @Nullable PlayerNameRepository nameRepository;
    private final Executor storageExecutor;
    private final LongSupplier clock;
    private final VaultGroupResolver vaultGroups;
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private volatile InfoSettings settings;
    private TaskHandle afkTickHandle;
    private volatile boolean closed;

    public PlayerInfoService(
            PluginStart plugin,
            PlayerInfoRepository repository,
            @Nullable PlayerNameRepository nameRepository,
            Executor storageExecutor,
            LongSupplier clock
    ) {
        this(plugin, repository, nameRepository, storageExecutor, clock, VaultGroupResolver.detect());
    }

    PlayerInfoService(
            PluginStart plugin,
            PlayerInfoRepository repository,
            @Nullable PlayerNameRepository nameRepository,
            Executor storageExecutor,
            LongSupplier clock,
            VaultGroupResolver vaultGroups
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.nameRepository = nameRepository;
        this.storageExecutor = Objects.requireNonNull(storageExecutor, "storageExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.vaultGroups = Objects.requireNonNull(vaultGroups, "vaultGroups");
        this.settings = InfoSettings.fromConfig(plugin.getConfig() == null ? null : plugin.getConfig().getConfigurationSection("info"));
    }

    public void enable() throws SQLException {
        closed = false;
        reloadConfigSnapshot();
        repository.migrate();
        if (nameRepository != null) {
            nameRepository.migrate();
        }
        sessions.clear();

        ModerationService moderation = activeModerationService();
        if (moderation != null) {
            moderation.setAdditionalSleepIgnoreCheck(this::isSleepingIgnoredByAfk);
            moderation.setVanishTransitionListener(this::onVanishTransition);
        }

        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(this, plugin);

        long now = clock.getAsLong();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online == null) {
                continue;
            }
            PlayerSession session = new PlayerSession(online.getUniqueId(), online.getName(), now);
            sessions.put(online.getUniqueId(), session);
            boolean vanished = PlayerNameVisibility.isVanished(online);
            syncSleepIgnored(online);
            dispatchJoinPersistence(session, vanished);
        }

        if (plugin.getScheduler() != null) {
            afkTickHandle = plugin.getScheduler().repeatOnServer(() -> {
                if (closed) {
                    return false;
                }
                tickAfk();
                return true;
            }, 1_000L, 1_000L);
        }
    }

    public void disable() {
        closed = true;
        if (afkTickHandle != null) {
            afkTickHandle.cancel();
            afkTickHandle = null;
        }
        HandlerList.unregisterAll(this);

        ModerationService moderation = activeModerationService();
        if (moderation != null) {
            moderation.setAdditionalSleepIgnoreCheck(null);
            moderation.setVanishTransitionListener(null);
        }

        long now = clock.getAsLong();
        List<CompletableFuture<Void>> flushes = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online == null) {
                continue;
            }
            boolean vanished = PlayerNameVisibility.isVanished(online);
            online.setSleepingIgnored(moderation != null && moderation.isVanished(online));
            if (!vanished) {
                UUID id = online.getUniqueId();
                String name = online.getName();
                CompletableFuture<Void> f = new CompletableFuture<>();
                try {
                    storageExecutor.execute(() -> {
                        try {
                            repository.recordLastSeen(id, name, now);
                        } catch (SQLException ex) {
                            plugin.logger().warning("Failed to flush last-seen for " + name + ": " + ex.getMessage());
                        } finally {
                            f.complete(null);
                        }
                    });
                    flushes.add(f);
                } catch (RuntimeException ignored) {
                    // Pool closing; nothing more to await.
                }
            }
        }
        if (!flushes.isEmpty()) {
            try {
                CompletableFuture.allOf(flushes.toArray(CompletableFuture[]::new)).get(2_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (TimeoutException | java.util.concurrent.ExecutionException | RuntimeException ignored) {
                // Bounded shutdown wait elapsed.
            }
        }
        sessions.clear();
    }

    public void reloadSettings() {
        reloadConfigSnapshot();
        runOnServer(() -> {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                syncSleepIgnored(online);
            }
        });
    }

    private void reloadConfigSnapshot() {
        ConfigurationSection section = plugin.getConfig() == null ? null : plugin.getConfig().getConfigurationSection("info");
        settings = InfoSettings.fromConfig(section);
    }

    public InfoSettings settings() {
        return settings;
    }

    public boolean isAfk(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        PlayerSession session = sessions.get(player.getUniqueId());
        return session != null && session.afk;
    }

    public boolean isSleepingIgnoredByAfk(@Nullable Player player) {
        return settings.afkIgnoreSleep() && isAfk(player);
    }

    // -- /list ------------------------------------------------------------------------------------

    public Map<String, List<ListedEntry>> collectVisibleGroupedPlayers(CommandSender viewer) {
        InfoSettings current = settings;
        Map<String, List<ListedEntry>> grouped = new LinkedHashMap<>();
        if (current.groupByPermission()) {
            for (String g : current.groups()) {
                grouped.put(displayGroupName(g), new ArrayList<>());
            }
        }

        List<ListedEntry> allEntries = new ArrayList<>();
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (target == null || !PlayerNameVisibility.canSee(viewer, target)) {
                continue;
            }
            boolean vanished = PlayerNameVisibility.isVanished(target);
            boolean afk = isAfk(target);
            String group = current.groupByPermission() ? resolveGroup(target, current) : current.defaultGroup();
            allEntries.add(new ListedEntry(target.getName(), group, afk, vanished));
        }

        allEntries.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        for (ListedEntry entry : allEntries) {
            grouped.computeIfAbsent(entry.group(), ignored -> new ArrayList<>()).add(entry);
        }
        grouped.entrySet().removeIf(e -> e.getValue().isEmpty());
        return grouped;
    }

    private String resolveGroup(Player player, InfoSettings current) {
        for (String configured : current.groups()) {
            String perm = LIST_GROUP_PERMISSION_PREFIX + configured;
            if (player.isPermissionSet(perm) && player.hasPermission(perm)) {
                return displayGroupName(configured);
            }
        }
        Optional<String> vaultGroup = vaultGroups.primaryGroup(player);
        if (vaultGroup.isPresent()) {
            return displayGroupName(vaultGroup.get());
        }
        return current.defaultGroup();
    }

    private static String displayGroupName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Players";
        }
        String trimmed = raw.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    // -- /near ------------------------------------------------------------------------------------

    public List<NearbyEntry> findNearbyPlayers(Player origin, double radius) {
        World world = origin.getWorld();
        Location originLoc = origin.getLocation();
        double maxDistSq = radius * radius;
        List<NearbyEntry> found = new ArrayList<>();
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (target == null || target.equals(origin) || !target.getWorld().equals(world)) {
                continue;
            }
            if (!PlayerNameVisibility.canSeeTarget(origin, target)) {
                continue;
            }
            double distSq = originLoc.distanceSquared(target.getLocation());
            if (distSq <= maxDistSq) {
                found.add(new NearbyEntry(
                        target.getName(),
                        Math.sqrt(distSq),
                        isAfk(target),
                        PlayerNameVisibility.isVanished(target)
                ));
            }
        }
        found.sort(Comparator.comparingDouble(NearbyEntry::distance)
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(found);
    }

    // -- /seen & /whois async profile lookup ------------------------------------------------------

    /**
     * Resolves a player asynchronously on the bounded {@code Allium-PlayerInfo} executor without
     * running any blocking SQL on the main thread. Visibility for online targets MUST be re-checked
     * on the server thread before rendering the response so vanished targets match offline output
     * identically.
     */
    public CompletableFuture<Optional<ProfileLookupResult>> lookupProfile(
            CommandSender sender,
            String rawName,
            boolean includeHistory
    ) {
        if (rawName == null || rawName.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String trimmed = rawName.trim();

        // Check online match on server thread without prefix-leaking hidden vanished players.
        Player exactOnline = plugin.getServer().getPlayerExact(trimmed);
        UUID matchedOnlineUuid = exactOnline != null ? exactOnline.getUniqueId() : null;
        String matchedOnlineName = exactOnline != null ? exactOnline.getName() : null;
        if (matchedOnlineUuid == null) {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            Player prefixMatch = null;
            for (Player candidate : plugin.getServer().getOnlinePlayers()) {
                if (candidate == null || !PlayerNameVisibility.canSeeTarget(sender, candidate)) {
                    continue;
                }
                if (candidate.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    prefixMatch = candidate;
                    break;
                }
            }
            if (prefixMatch != null) {
                matchedOnlineUuid = prefixMatch.getUniqueId();
                matchedOnlineName = prefixMatch.getName();
            }
        }

        UUID onlineUuid = matchedOnlineUuid;
        String onlineName = matchedOnlineName;
        PlayerSession liveSession = onlineUuid == null ? null : sessions.get(onlineUuid);

        CompletableFuture<Optional<ProfileLookupResult>> result = new CompletableFuture<>();
        try {
            storageExecutor.execute(() -> {
                try {
                    PlayerInfoRecord record = null;
                    if (onlineUuid != null) {
                        record = repository.findByUuid(onlineUuid).orElse(null);
                    }
                    if (record == null) {
                        record = repository.findByName(trimmed).orElse(null);
                    }
                    if (record == null && nameRepository != null) {
                        Optional<NameCache.Entry> nameEntry = onlineUuid != null
                                ? nameRepository.byUuid(onlineUuid)
                                : nameRepository.byName(trimmed);
                        if (nameEntry.isPresent()) {
                            NameCache.Entry entry = nameEntry.get();
                            record = new PlayerInfoRecord(
                                    entry.uuid(), entry.name(), entry.lastSeen(), entry.lastSeen(), entry.lastSeen());
                        }
                    }
                    if (record == null && onlineUuid != null && liveSession != null) {
                        record = new PlayerInfoRecord(
                                onlineUuid,
                                onlineName != null ? onlineName : trimmed,
                                liveSession.firstJoin,
                                liveSession.lastJoin,
                                liveSession.lastSeen
                        );
                    }
                    if (record == null) {
                        result.complete(Optional.empty());
                        return;
                    }
                    List<PlayerNameRepository.NameHistoryEntry> history = (includeHistory && nameRepository != null)
                            ? nameRepository.nameHistory(record.uuid())
                            : List.of();
                    if (history.isEmpty()) {
                        history = List.of(new PlayerNameRepository.NameHistoryEntry(
                                record.lastName(), record.firstJoin(), record.lastSeen()));
                    }
                    result.complete(Optional.of(new ProfileLookupResult(record, history)));
                } catch (SQLException | RuntimeException ex) {
                    result.completeExceptionally(ex);
                }
            });
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    public long onlineDurationMillis(Player online, PlayerInfoRecord dbRecord) {
        PlayerSession session = sessions.get(online.getUniqueId());
        long joinedAt = session != null ? session.lastJoin : dbRecord.lastJoin();
        return Math.max(0L, clock.getAsLong() - joinedAt);
    }

    public String formatTimestamp(long epochMillis) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(Math.max(0L, epochMillis)));
    }

    public String formatDurationSince(long timestampMillis) {
        long elapsed = Math.max(0L, clock.getAsLong() - timestampMillis);
        return formatElapsed(elapsed);
    }

    public static String formatElapsed(long millis) {
        if (millis < 1_000L) {
            return "0s";
        }
        return DurationParser.formatRemaining(millis);
    }

    // -- /afk & auto-AFK --------------------------------------------------------------------------

    public void toggleAfk(Player player, @Nullable String rawMessage) {
        PlayerSession session = sessionFor(player);
        if (session.afk) {
            setAfk(player, false, null);
        } else {
            String message = (rawMessage == null || rawMessage.isBlank()) ? null : rawMessage.trim();
            setAfk(player, true, message);
        }
    }

    public void setAfk(Player player, boolean nextAfk, @Nullable String message) {
        PlayerSession session = sessionFor(player);
        long now = clock.getAsLong();
        if (session.afk == nextAfk) {
            if (!nextAfk) {
                session.lastActivity = now;
            }
            return;
        }

        session.afk = nextAfk;
        session.afkSince = nextAfk ? now : 0L;
        session.afkMessage = nextAfk ? message : null;
        if (!nextAfk) {
            session.lastActivity = now;
        }
        syncSleepIgnored(player);

        String cleanMessage = message == null ? null : codes.castled.allium.messaging.UntrustedText.clean(message).trim();
        if (cleanMessage != null && cleanMessage.isEmpty()) {
            cleanMessage = null;
        }
        if (nextAfk) {
            if (cleanMessage != null) {
                sendWithUntrustedReason(player, Messages.INFO_AFK_SELF_ENTER_REASON, player.getName(), cleanMessage);
            } else {
                plugin.getMessageManager().send(player, Messages.INFO_AFK_SELF_ENTER);
            }
        } else {
            plugin.getMessageManager().send(player, Messages.INFO_AFK_SELF_LEAVE);
        }

        if (settings.afkBroadcast()) {
            broadcastAfkChange(player, nextAfk, cleanMessage);
        }
    }

    private void sendWithUntrustedReason(CommandSender recipient, Messages key, String playerName, String cleanReason) {
        String template = plugin.getMessageManager().resolve(key, "{player}", playerName);
        var body = codes.castled.allium.messaging.UntrustedText.parse(
                cleanReason, codes.castled.allium.messaging.UntrustedText.Allow.NONE);
        var rendered = codes.castled.allium.messaging.UntrustedText.insert(template, body);
        codes.castled.allium.text.TextSender.send(recipient, rendered);
    }

    private void broadcastAfkChange(Player subject, boolean entered, @Nullable String cleanMessage) {
        Messages key = entered
                ? (cleanMessage != null ? Messages.INFO_AFK_ENTER_REASON : Messages.INFO_AFK_ENTER)
                : Messages.INFO_AFK_LEAVE;

        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer == null || viewer.equals(subject)) {
                continue;
            }
            if (PlayerNameVisibility.canSee(viewer, subject)) {
                if (entered && cleanMessage != null) {
                    sendWithUntrustedReason(viewer, key, subject.getName(), cleanMessage);
                } else {
                    plugin.getMessageManager().send(viewer, key, "{player}", subject.getName());
                }
            }
        }
        if (plugin.getServer().getConsoleSender() != null) {
            if (entered && cleanMessage != null) {
                sendWithUntrustedReason(plugin.getServer().getConsoleSender(), key, subject.getName(), cleanMessage);
            } else {
                plugin.getMessageManager().send(plugin.getServer().getConsoleSender(), key, "{player}", subject.getName());
            }
        }
    }

    public void tickAfk() {
        InfoSettings current = settings;
        long now = clock.getAsLong();
        long autoMillis = current.autoAfkSeconds() * 1_000L;
        long kickMillis = current.kickAfterSeconds() * 1_000L;

        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            PlayerSession session = sessionFor(player);
            if (!session.afk) {
                if (current.autoAfkSeconds() > 0 && now - session.lastActivity >= autoMillis) {
                    setAfk(player, true, null);
                }
            } else if (current.kickOnAfk()
                    && now - session.afkSince >= kickMillis
                    && !player.hasPermission(PERMISSION_AFK_KICK_EXEMPT)) {
                String kickMsg = codes.castled.allium.message.MessageManager.colorize(plugin.getMessageManager().resolve(Messages.INFO_AFK_KICK));
                player.kickPlayer(kickMsg);
            }
        }
    }

    private void markActivity(Player player) {
        if (player == null) {
            return;
        }
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.lastActivity = clock.getAsLong();
        if (session.afk) {
            setAfk(player, false, null);
        }
    }

    private void onVanishTransition(Player target, boolean vanished) {
        if (closed || target == null) {
            return;
        }
        long now = clock.getAsLong();
        UUID uuid = target.getUniqueId();
        String name = target.getName();
        PlayerSession session = sessions.get(uuid);
        if (session != null) {
            session.lastSeen = now;
        }
        syncSleepIgnored(target);
        storageExecutor.execute(() -> {
            try {
                repository.recordLastSeen(uuid, name, now);
            } catch (SQLException ex) {
                plugin.logger().warning("Failed to update last-seen on vanish change for " + name + ": " + ex.getMessage());
            }
        });
    }

    private void syncSleepIgnored(Player player) {
        if (player == null) {
            return;
        }
        ModerationService moderation = activeModerationService();
        if (moderation != null) {
            moderation.refreshSleepingIgnored(player);
        } else {
            player.setSleepingIgnored(PlayerNameVisibility.isVanished(player) || isSleepingIgnoredByAfk(player));
        }
    }

    private @Nullable ModerationService activeModerationService() {
        if (plugin.getModuleManager() == null) {
            return null;
        }
        if (plugin.getModuleManager().getModule("moderation") instanceof ModerationModule mod
                && mod.isEnabled()) {
            return mod.moderation();
        }
        return null;
    }

    private PlayerSession sessionFor(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(),
                id -> new PlayerSession(id, player.getName(), clock.getAsLong()));
    }

    private void dispatchJoinPersistence(PlayerSession session, boolean vanished) {
        storageExecutor.execute(() -> {
            try {
                repository.recordJoin(session.uuid, session.name, session.lastJoin, vanished);
                repository.findByUuid(session.uuid).ifPresent(stored -> {
                    session.firstJoin = stored.firstJoin();
                    session.lastJoin = stored.lastJoin();
                    session.lastSeen = stored.lastSeen();
                });
            } catch (SQLException ex) {
                plugin.logger().warning("Failed to persist player info on join for " + session.name + ": " + ex.getMessage());
            }
        });
    }

    // -- Listeners --------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = clock.getAsLong();
        PlayerSession session = new PlayerSession(player.getUniqueId(), player.getName(), now);
        boolean vanished = PlayerNameVisibility.isVanished(player);
        if (vanished) {
            session.lastSeen = 0L;
        }
        sessions.put(player.getUniqueId(), session);
        syncSleepIgnored(player);
        dispatchJoinPersistence(session, vanished);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean vanished = PlayerNameVisibility.isVanished(player);
        sessions.remove(uuid);
        player.setSleepingIgnored(false);
        if (!vanished) {
            long now = clock.getAsLong();
            String name = player.getName();
            storageExecutor.execute(() -> {
                try {
                    repository.recordLastSeen(uuid, name, now);
                } catch (SQLException ex) {
                    plugin.logger().warning("Failed to persist last-seen on quit for " + name + ": " + ex.getMessage());
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getWorld() != to.getWorld()
                || from.distanceSquared(to) > 0.04
                || Math.abs(from.getYaw() - to.getYaw()) > 1.5f
                || Math.abs(from.getPitch() - to.getPitch()) > 1.5f) {
            markActivity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        PlayerSession session = sessions.get(sender.getUniqueId());
        if (session == null) {
            return;
        }
        session.lastActivity = clock.getAsLong();
        if (session.afk) {
            runOnServer(() -> markActivity(sender));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw != null) {
            String trimmed = raw.trim().toLowerCase(Locale.ROOT);
            if (trimmed.equals("/afk") || trimmed.startsWith("/afk ")
                    || trimmed.equals("/away") || trimmed.startsWith("/away ")
                    || trimmed.equals("/allium:afk") || trimmed.startsWith("/allium:afk ")
                    || trimmed.equals("/allium:away") || trimmed.startsWith("/allium:away ")) {
                return;
            }
        }
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markActivity(player);
        }
    }

    public void runOnServer(Runnable task) {
        if (plugin.getScheduler() != null) {
            plugin.getScheduler().runOnServer(task);
        } else {
            task.run();
        }
    }
}
