package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic storage/server checkpoints; these tests never start a server or database. */
class DisciplineServiceTest {
    private PluginStart plugin;
    private Server server;
    private ModerationRepository repository;
    private PlayerNameService names;
    private MessageManager messages;
    private YamlConfiguration config;
    private DisciplineService service;
    private ConsoleCommandSender console;
    private World world;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final QueueExecutor io = new QueueExecutor();
    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<String, NameCache.Entry> directory = new HashMap<>();
    private final Map<UUID, BanRecord> bans = new HashMap<>();
    private final Map<UUID, FreezeRecord> frozen = new HashMap<>();
    private final Map<UUID, JailAssignment> prisoners = new HashMap<>();
    private final List<JailRecord> jails = new ArrayList<>();

    private static final class QueueExecutor implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();
        boolean immediate;
        boolean reject;
        public void execute(Runnable work) {
            if (reject) throw new RejectedExecutionException("test full");
            if (immediate) work.run(); else queue.add(work);
        }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }

    @BeforeEach void setup() throws Exception {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        repository = mock(ModerationRepository.class);
        names = mock(PlayerNameService.class);
        console = mock(ConsoleCommandSender.class);
        config = new YamlConfiguration();
        config.set("moderation.preload-wait-millis", 0);
        config.set("moderation.discipline.rate-limit-millis", 0);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getPlayerNames()).thenReturn(names);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getOnlinePlayers()).thenAnswer(i -> new ArrayList<>(online.values()));
        when(server.getPlayer(any(UUID.class))).thenAnswer(i -> online.get(i.getArgument(0)));
        when(console.hasPermission(anyString())).thenReturn(true);
        when(console.getName()).thenReturn("Console");
        LanguageManager language = mock(LanguageManager.class);
        for (Messages m : Messages.values()) when(language.get(m)).thenReturn(m.getDefault());
        messages = spy(new MessageManager(plugin, language));
        when(plugin.getMessageManager()).thenReturn(messages);
        when(names.resolve(anyString())).thenAnswer(i -> CompletableFuture.completedFuture(Optional.ofNullable(directory.get(i.<String>getArgument(0).toLowerCase()))));
        when(repository.loadActiveBans()).thenAnswer(i -> List.copyOf(bans.values()));
        when(repository.loadActiveBan(any())).thenAnswer(i -> Optional.ofNullable(bans.get(i.getArgument(0))));
        when(repository.loadBanByIp(anyString())).thenAnswer(i -> bans.values().stream().filter(b -> i.getArgument(0).equals(b.ipAddress())).findFirst());
        when(repository.loadFrozenPlayers()).thenAnswer(i -> List.copyOf(frozen.values()));
        when(repository.loadFrozen(any())).thenAnswer(i -> Optional.ofNullable(frozen.get(i.getArgument(0))));
        when(repository.loadJailedPlayers()).thenAnswer(i -> List.copyOf(prisoners.values()));
        when(repository.loadJailed(any())).thenAnswer(i -> Optional.ofNullable(prisoners.get(i.getArgument(0))));
        when(repository.loadJails()).thenAnswer(i -> List.copyOf(jails));
        doAnswer(i -> { BanRecord b = i.getArgument(0); bans.put(b.playerUuid(), b); return null; }).when(repository).saveBan(any());
        doAnswer(i -> { FreezeRecord f = i.getArgument(0); frozen.put(f.playerUuid(), f); return null; }).when(repository).setFrozen(any());
        when(repository.removeFrozen(any(), anyString())).thenAnswer(i -> frozen.remove(i.getArgument(0)) != null);
        when(repository.setJailed(any())).thenAnswer(i -> { JailAssignment j = i.getArgument(0); prisoners.put(j.playerUuid(), j); return true; });
        when(repository.removeJailed(any(), anyString())).thenAnswer(i -> prisoners.remove(i.getArgument(0)) != null);
        world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        WorldBorder border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.getCenter()).thenReturn(new Location(world, 0, 64, 0));
        when(border.getSize()).thenReturn(60_000_000.0);
        when(border.isInside(any())).thenReturn(true);
        Block floor = mock(Block.class), air = mock(Block.class);
        BlockData sturdy = mock(BlockData.class);
        when(sturdy.isFaceSturdy(any(), any())).thenReturn(true);
        when(floor.getBlockData()).thenReturn(sturdy);
        when(floor.getType()).thenReturn(Material.STONE);
        when(air.getType()).thenReturn(Material.AIR);
        when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(i -> (int) i.getArgument(1) == 63 ? floor : air);
        service = new DisciplineService(plugin, repository, io, clock::get);
    }

    private void enable() { service.enable(); io.drain(); }
    private Player player(String name, Set<String> permissions) {
        UUID id = UUID.randomUUID();
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenAnswer(i -> online.get(id) == p);
        when(p.hasPermission(anyString())).thenAnswer(i -> permissions.contains(i.getArgument(0)));
        AtomicReference<Location> location = new AtomicReference<>(new Location(world, 20.5, 64, 20.5, 30, 40));
        when(p.getLocation()).thenAnswer(i -> location.get().clone());
        when(p.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class))).thenAnswer(i -> {
            PlayerTeleportEvent e = new PlayerTeleportEvent(p, location.get().clone(), i.getArgument(0), i.getArgument(1));
            service.onTeleport(e);
            if (e.isCancelled()) return false;
            location.set(e.getTo().clone()); return true;
        });
        online.put(id, p);
        directory.put(name.toLowerCase(), new NameCache.Entry(id, name, clock.get()));
        return p;
    }
    private void joined(Player p) { service.onJoin(new PlayerJoinEvent(p, "")); io.drain(); }
    private AsyncPlayerPreLoginEvent login(UUID id) throws Exception {
        return new AsyncPlayerPreLoginEvent("Target", InetAddress.getLoopbackAddress(), id);
    }
    private BanRecord ban(UUID id, Long expiry) { return new BanRecord(id, "Target", "Console", "reason", clock.get(), expiry, null); }
    private SavedLocation saved(Location l) { return new SavedLocation(world.getUID(), "world", l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()); }

    @Test void banIsPublishedAndKickedOnlyAfterSuccessfulPersistence() throws Exception {
        enable(); Player p = player("Target", Set.of()); joined(p);
        service.dispatch(console, "ban", new String[]{"Target", "&c<rainbow>bad§\nreason"});
        assertEquals(1, service.pendingWrites()); assertFalse(service.isBanned(p.getUniqueId()));
        verify(p, never()).kickPlayer(anyString());
        io.drain();
        assertTrue(service.isBanned(p.getUniqueId())); assertEquals(0, service.pendingWrites());
        ArgumentCaptor<BanRecord> saved = ArgumentCaptor.forClass(BanRecord.class);
        verify(repository).saveBan(saved.capture());
        assertEquals("&c<rainbow>badreason", saved.getValue().reason());
        verify(p).kickPlayer("§cYou are banned: &c<rainbow>badreason");
        verify(messages).send(console, Messages.DISCIPLINE_SUCCESS, "{action}", "ban", "{player}", "Target");
    }

    @Test void preloginRefreshesExternallyAddedAndRemovedBans() throws Exception {
        service = new DisciplineService(plugin, repository, Runnable::run, clock::get);
        service.enable();
        UUID id = UUID.randomUUID();
        bans.put(id, ban(id, null)); // A second storage writer; startup snapshot is already loaded.
        AsyncPlayerPreLoginEvent denied = login(id); service.onAsyncPreLogin(denied);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, denied.getLoginResult());
        bans.remove(id);
        AsyncPlayerPreLoginEvent allowed = login(id); service.onAsyncPreLogin(allowed);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, allowed.getLoginResult());
        verify(repository, times(2)).loadActiveBan(id);
    }

    @Test void nativeBanAuditCountsOnlyUnknownEntriesWithoutImporting() {
        UUID known = UUID.randomUUID(), unknown = UUID.randomUUID();
        assertEquals(1, NativeBanAudit.unknownCount("[{\"uuid\":\"" + known + "\"},{\"uuid\":\"" + unknown + "\"}]", Set.of(known)));
        assertEquals(0, NativeBanAudit.unknownCount("[]", Set.of()));
    }

    @Test void failedBanDoesNotPublishKickOrSuccess() throws Exception {
        enable(); Player p = player("Target", Set.of()); joined(p);
        doThrow(new SQLException("disk full")).when(repository).saveBan(any());
        service.dispatch(console, "ban", new String[]{"Target"}); io.drain();
        assertFalse(service.isBanned(p.getUniqueId())); assertEquals(0, service.pendingWrites());
        verify(p, never()).kickPlayer(anyString());
        verify(messages).send(console, Messages.DISCIPLINE_UNAVAILABLE);
        verify(messages, never()).send(eq(console), eq(Messages.DISCIPLINE_SUCCESS), any(String[].class));
    }

    @Test void deniedPreloginDoesNoStorageAndHandlerRunsAtMonitor() throws Exception {
        enable(); AsyncPlayerPreLoginEvent event = login(UUID.randomUUID());
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "elsewhere");
        service.onAsyncPreLogin(event);
        assertTrue(io.queue.isEmpty());
        assertEquals(EventPriority.MONITOR, DisciplineService.class.getMethod("onAsyncPreLogin", AsyncPlayerPreLoginEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class).priority());
    }

    @Test void knownBanAlwaysDeniesUnderAllowAndExpiryIsCheckedNow() throws Exception {
        UUID id = UUID.randomUUID(); bans.put(id, ban(id, clock.get() + 100));
        config.set("moderation.discipline.ban-load-failure-policy", "ALLOW"); enable();
        io.reject = true;
        AsyncPlayerPreLoginEvent denied = login(id); service.onAsyncPreLogin(denied);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, denied.getLoginResult());
        clock.addAndGet(100);
        AsyncPlayerPreLoginEvent expired = login(id); service.onAsyncPreLogin(expired);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, expired.getLoginResult());
    }

    @Test void unresolvedDefaultDeniesButAllowRemainsMovementFailClosed() throws Exception {
        enable(); io.reject = true;
        AsyncPlayerPreLoginEvent denied = login(UUID.randomUUID()); service.onAsyncPreLogin(denied);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, denied.getLoginResult());
        config.set("moderation.discipline.ban-load-failure-policy", "ALLOW"); service.reloadSettings();
        Player p = player("Target", Set.of());
        AsyncPlayerPreLoginEvent allowed = login(p.getUniqueId()); service.onAsyncPreLogin(allowed);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, allowed.getLoginResult());
        service.onJoin(new PlayerJoinEvent(p, ""));
        assertFalse(service.ready(p.getUniqueId()));
        PlayerMoveEvent move = new PlayerMoveEvent(p, p.getLocation(), p.getLocation().add(2, 0, 0));
        service.onMove(move); assertTrue(move.isCancelled());
        PlayerTeleportEvent teleport = new PlayerTeleportEvent(p, p.getLocation(), p.getLocation().add(2, 0, 0));
        service.onTeleport(teleport); assertTrue(teleport.isCancelled());
    }

    @Test void staleActorSessionAndRevokedPermissionCannotReachStorage() throws Exception {
        enable(); Set<String> grants = new HashSet<>(Set.of("allium.ban"));
        Player actor = player("Staff", grants), target = player("Target", Set.of()); joined(actor); joined(target);
        service.dispatch(actor, "ban", new String[]{"Target"});
        grants.clear(); io.drain();
        verify(repository, never()).saveBan(any()); assertEquals(0, service.pendingWrites());
        grants.add("allium.ban");
        service.dispatch(actor, "ban", new String[]{"Target"});
        online.put(actor.getUniqueId(), mock(Player.class)); io.drain();
        verify(repository, never()).saveBan(any());
    }

    @Test void lateNameLookupCannotMutateForAnOldActorSession() throws Exception {
        enable(); Player actor = player("Staff", Set.of("allium.ban"));
        Player target = player("Target", Set.of()); joined(actor); joined(target);
        CompletableFuture<Optional<NameCache.Entry>> lookup = new CompletableFuture<>();
        when(names.resolve("Target")).thenReturn(lookup);
        service.dispatch(actor, "ban", new String[]{"Target"});
        online.remove(actor.getUniqueId());
        lookup.complete(Optional.of(directory.get("target"))); io.drain();
        verify(repository, never()).saveBan(any());
    }

    @Test void committedBanStillKicksCapturedTargetAfterActorQuitsDuringSql() throws Exception {
        enable(); Player actor = player("Staff", Set.of("allium.ban"));
        Player target = player("Target", Set.of()); joined(actor); joined(target);
        doAnswer(i -> {
            BanRecord record = i.getArgument(0); bans.put(record.playerUuid(), record);
            online.remove(actor.getUniqueId()); return null;
        }).when(repository).saveBan(any());
        service.dispatch(actor, "ban", new String[]{"Target"}); io.drain();
        assertTrue(service.isBanned(target.getUniqueId()));
        verify(target).kickPlayer(anyString());
        verify(messages, never()).send(eq(actor), eq(Messages.DISCIPLINE_SUCCESS), any(String[].class));
        assertEquals(0, service.pendingWrites());
    }

    @Test void auditedKickStillAppliesAfterActorPermissionRevokedDuringSql() throws Exception {
        enable(); Set<String> grants = new HashSet<>(Set.of("allium.kick"));
        Player actor = player("Staff", grants), target = player("Target", Set.of()); joined(actor); joined(target);
        doAnswer(i -> { grants.clear(); return null; }).when(repository).recordAudit(eq("KICK"), anyString(), any(), anyString(), anyString());
        service.dispatch(actor, "kick", new String[]{"Target"}); io.drain();
        verify(target).kickPlayer(anyString());
        verify(messages, never()).send(eq(actor), eq(Messages.DISCIPLINE_SUCCESS), any(String[].class));
    }

    @Test void committedJailMovesCapturedTargetAfterActorQuitsDuringSql() throws Exception {
        enable(); Player actor = player("Staff", Set.of("allium.jail")), target = player("Target", Set.of());
        jails.add(new JailRecord("cell", saved(new Location(world, .5, 64, .5)))); joined(actor); joined(target);
        doAnswer(i -> {
            JailAssignment assignment = i.getArgument(0); prisoners.put(assignment.playerUuid(), assignment);
            online.remove(actor.getUniqueId()); return true;
        }).when(repository).setJailed(any());
        service.dispatch(actor, "jail", new String[]{"Target", "cell"}); io.drain();
        assertTrue(service.jailed(target.getUniqueId())); assertEquals(.5, target.getLocation().getX());
        verify(messages, never()).send(eq(actor), eq(Messages.DISCIPLINE_SUCCESS), any(String[].class));
    }

    @Test void storageUsesCapturedActorNameWithoutCallingBukkitNameGetter() throws Exception {
        enable(); Player actor = player("Staff", Set.of("allium.freeze", DisciplineService.BYPASS_PERMISSION));
        UUID id = UUID.randomUUID(); directory.put("target", new NameCache.Entry(id, "Target", clock.get())); joined(actor);
        service.dispatch(actor, "freeze", new String[]{"Target", "on"});
        clearInvocations(actor);
        io.drain();
        verify(actor, never()).getName();
        verify(repository).recordAudit("DISCIPLINE_BYPASS", "Staff", id, "Target", "offline permission override action=freeze");
        ArgumentCaptor<FreezeRecord> record = ArgumentCaptor.forClass(FreezeRecord.class);
        verify(repository).setFrozen(record.capture()); assertEquals("Staff", record.getValue().actorName());
    }

    @Test void rankExemptionSelfAndOfflineChecksNeedExplicitBypass() {
        enable(); Player actor = player("Staff", Set.of("allium.ban"));
        Set<String> targetGrants = new HashSet<>(Set.of("allium.ban"));
        Player target = player("Target", targetGrants);
        assertFalse(service.canModerate(actor, target.getUniqueId()));
        assertFalse(service.canModerate(actor, actor.getUniqueId()));
        targetGrants.clear(); assertTrue(service.canModerate(actor, target.getUniqueId()));
        targetGrants.add("allium.moderation.exempt"); assertFalse(service.canModerate(actor, target.getUniqueId()));
        online.remove(target.getUniqueId()); assertFalse(service.canModerate(actor, target.getUniqueId()));
        assertTrue(service.canModerate(console, target.getUniqueId()));
        Player bypass = player("Bypass", Set.of("allium.ban", DisciplineService.BYPASS_PERMISSION));
        assertTrue(service.canModerate(bypass, target.getUniqueId()));
    }

    @Test void frozenStatePublishesOnlyAfterStorageAndRejectsEveryTeleportCause() throws Exception {
        enable(); Player p = player("Target", Set.of()); joined(p);
        service.dispatch(console, "freeze", new String[]{"Target", "on"});
        assertFalse(service.isFrozen(p.getUniqueId())); io.drain(); assertTrue(service.isFrozen(p.getUniqueId()));
        for (PlayerTeleportEvent.TeleportCause cause : PlayerTeleportEvent.TeleportCause.values()) {
            PlayerTeleportEvent e = new PlayerTeleportEvent(p, p.getLocation(), p.getLocation().add(5, 0, 0), cause);
            service.onTeleport(e); assertTrue(e.isCancelled(), cause.name());
        }
        PlayerMoveEvent look = new PlayerMoveEvent(p, p.getLocation(), p.getLocation().clone());
        look.getTo().setYaw(180); service.onMove(look); assertEquals(180, look.getTo().getYaw());
    }

    @Test void frozenTranslationCancelsMovementSoServerCorrectsClientPosition() {
        enable(); Player p = player("Target", Set.of()); joined(p);
        service.dispatch(console, "freeze", new String[]{"Target", "on"}); io.drain();
        PlayerMoveEvent movement = new PlayerMoveEvent(p, p.getLocation(), p.getLocation().add(1, 0, 0));
        service.onMove(movement);
        assertTrue(movement.isCancelled(), "cancel native movement instead of initiating a blocked plugin teleport");
        PlayerMoveEvent look = new PlayerMoveEvent(p, p.getLocation(), p.getLocation().clone());
        look.getTo().setYaw(73); service.onMove(look);
        assertFalse(look.isCancelled()); assertEquals(73, look.getTo().getYaw());
    }

    @Test void jailedLoginWaitsForLoadAndScopedReleasePreservesExactOriginal() {
        enable(); Player p = player("Target", Set.of()); joined(p);
        Location origin = p.getLocation();
        JailRecord jail = new JailRecord("cell", saved(new Location(world, .5, 64, .5)));
        jails.add(jail);
        // A fresh connection loads the jail catalogue before commands can address the new cell.
        service.onQuit(new PlayerQuitEvent(p, "")); joined(p);
        service.dispatch(console, "jail", new String[]{"Target", "cell", "reason"}); io.drain();
        assertTrue(service.jailed(p.getUniqueId()));
        assertEquals(jail.location().x(), p.getLocation().getX());
        assertEquals(saved(origin), prisoners.get(p.getUniqueId()).returnLocation());
        service.dispatch(console, "unjail", new String[]{"Target"});
        assertEquals(origin, p.getLocation()); // Exact return is proven while assignment is still durable.
        assertTrue(service.jailed(p.getUniqueId())); io.drain();
        assertFalse(service.jailed(p.getUniqueId())); assertFalse(prisoners.containsKey(p.getUniqueId()));
    }

    @Test void cancelledReleaseNeverDeletesTheOnlyReturnLocation() throws Exception {
        enable(); Player p = player("Target", Set.of());
        JailRecord jail = new JailRecord("cell", saved(new Location(world, .5, 64, .5)));
        jails.add(jail);
        JailAssignment assignment = new JailAssignment(p.getUniqueId(), "Target", "Console", "reason", clock.get(), "cell", saved(p.getLocation()));
        prisoners.put(p.getUniqueId(), assignment); joined(p);
        when(p.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(false);
        service.dispatch(console, "unjail", new String[]{"Target"}); io.drain();
        verify(repository, never()).removeJailed(any(), anyString());
        assertEquals(assignment, prisoners.get(p.getUniqueId())); assertTrue(service.jailed(p.getUniqueId()));
    }

    @Test void failedReleaseStorageReenforcesJailAndKeepsReturn() throws Exception {
        enable(); Player p = player("Target", Set.of());
        JailRecord jail = new JailRecord("cell", saved(new Location(world, .5, 64, .5)));
        jails.add(jail);
        JailAssignment assignment = new JailAssignment(p.getUniqueId(), "Target", "Console", "reason", clock.get(), "cell", saved(p.getLocation()));
        prisoners.put(p.getUniqueId(), assignment); joined(p);
        when(repository.removeJailed(any(), anyString())).thenThrow(new SQLException("disk full"));
        service.dispatch(console, "unjail", new String[]{"Target"}); io.drain();
        assertTrue(service.jailed(p.getUniqueId()));
        assertEquals(.5, p.getLocation().getX()); assertEquals(assignment, prisoners.get(p.getUniqueId()));
    }

    @Test void respawnDuringPendingReleaseUsesExactOriginBeforeDeleteCompletes() {
        enable(); Player p = player("Target", Set.of());
        Location origin = p.getLocation();
        jails.add(new JailRecord("cell", saved(new Location(world, .5, 64, .5))));
        prisoners.put(p.getUniqueId(), new JailAssignment(p.getUniqueId(), "Target", "Console", "reason", clock.get(), "cell", saved(origin)));
        joined(p);
        service.dispatch(console, "unjail", new String[]{"Target"});
        assertTrue(service.jailed(p.getUniqueId())); assertEquals(1, service.pendingWrites());
        PlayerRespawnEvent respawn = new PlayerRespawnEvent(p, new Location(world, 100.5, 64, 100.5), false);
        service.onRespawn(respawn);
        assertEquals(origin, respawn.getRespawnLocation());
        // Simulate Bukkit completing the respawn before the queued SQL delete.
        when(p.getLocation()).thenReturn(respawn.getRespawnLocation());
        io.drain();
        assertFalse(service.jailed(p.getUniqueId())); assertEquals(origin, p.getLocation());
        verify(messages).send(console, Messages.DISCIPLINE_SUCCESS, "{action}", "unjail", "{player}", "Target");
    }

    @Test void unsafeRespawnDuringDeleteRestoresAssignmentInsteadOfLosingReturn() throws Exception {
        enable(); Player p = player("Target", Set.of()); Location origin = p.getLocation();
        jails.add(new JailRecord("cell", saved(new Location(world, .5, 64, .5))));
        JailAssignment assignment = new JailAssignment(p.getUniqueId(), "Target", "Console", "reason", clock.get(), "cell", saved(origin));
        prisoners.put(p.getUniqueId(), assignment); joined(p);
        when(repository.removeJailed(any(), anyString())).thenAnswer(i -> {
            prisoners.remove(p.getUniqueId());
            when(world.isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)).thenReturn(false);
            service.onRespawn(new PlayerRespawnEvent(p, new Location(world, .5, 64, .5), false));
            return true;
        });
        service.dispatch(console, "unjail", new String[]{"Target"}); io.drain();
        assertEquals(assignment, prisoners.get(p.getUniqueId())); assertTrue(service.jailed(p.getUniqueId()));
        verify(repository).setJailed(assignment);
        verify(messages, never()).send(eq(console), eq(Messages.DISCIPLINE_SUCCESS), any(String[].class));
    }

    @Test void staleConnectionLoadCannotUnfreezeAReplacementConnection() throws Exception {
        enable(); Player p = player("Target", Set.of());
        service.onJoin(new PlayerJoinEvent(p, ""));
        service.onQuit(new PlayerQuitEvent(p, ""));
        frozen.put(p.getUniqueId(), new FreezeRecord(p.getUniqueId(), "Target", "Console", "reason", clock.get()));
        service.onJoin(new PlayerJoinEvent(p, "")); io.drain();
        assertTrue(service.ready(p.getUniqueId())); assertTrue(service.isFrozen(p.getUniqueId()));
    }

    @Test void rejectedWriteReleasesPendingAndIpBanStaysDisabled() throws Exception {
        enable(); Player p = player("Target", Set.of()); joined(p);
        io.reject = true; service.dispatch(console, "freeze", new String[]{"Target"});
        assertEquals(0, service.pendingWrites()); assertFalse(service.isFrozen(p.getUniqueId()));
        service.dispatch(console, "ipban", new String[]{"Target"});
        verify(messages).send(console, Messages.DISCIPLINE_IP_DISABLED);
        verify(repository, never()).saveBan(any());
    }

    @Test void warningHistoryIsReadOnlyAndUntrustedReasonsStayLiteral() throws Exception {
        enable(); UUID id = UUID.randomUUID();
        directory.put("target", new NameCache.Entry(id, "Target", clock.get()));
        when(repository.listWarnings(id, 50)).thenReturn(List.of(new HistoryEntry(1, id, "Target", "Staff", "&c<rainbow>literal", clock.get())));
        service.dispatch(console, "warn", new String[]{"Target", "history"}); io.drain();
        verify(repository).listWarnings(id, 50);
        verify(repository, never()).addWarning(any(), anyString(), anyString(), anyString());
        verify(console).sendMessage("- Target by Staff: &c<rainbow>literal");
    }

    @Test void jailCompletionFiltersBeforeCapAndInvalidReloadKeepsDeny() throws Exception {
        for (int i = 0; i < 70; i++) jails.add(new JailRecord("a" + i, saved(new Location(world, .5, 64, .5))));
        jails.add(new JailRecord("zcell", saved(new Location(world, .5, 64, .5)))); enable();
        assertEquals(List.of("zcell"), service.completeJails("Z"));
        assertEquals(50, service.completeJails("").size());
        config.set("moderation.discipline.rate-limit-millis", "wrong");
        config.set("moderation.discipline.ban-load-failure-policy", "ALLOW"); service.reloadSettings();
        io.reject = true;
        AsyncPlayerPreLoginEvent e = login(UUID.randomUUID()); service.onAsyncPreLogin(e);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, e.getLoginResult());
    }
}
