package codes.castled.allium.travel;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.teleport.TeleportService;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static codes.castled.allium.travel.TravelRepository.MutationResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Worker completion and server callbacks are independently controlled; no sleeps or real threads. */
class TravelServiceTest {
    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<Player, Set<String>> grants = new HashMap<>();
    private final Map<Player, Location> positions = new HashMap<>();
    private final Map<String, Permission> permissions = new HashMap<>();
    private final Deque<Runnable> worker = new ArrayDeque<>();
    private final Deque<Runnable> serverCallbacks = new ArrayDeque<>();
    private final Deque<Runnable> later = new ArrayDeque<>();
    private final YamlConfiguration config = new YamlConfiguration();
    private PluginStart plugin;
    private Server server;
    private PluginManager manager;
    private MessageManager messages;
    private TravelRepository repository;
    private TeleportService teleports;
    private ThreadPoolExecutor io;
    private World world;
    private Player player;
    private TravelService service;
    private SavedLocation destination;
    private boolean rejecting;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        manager = mock(PluginManager.class);
        messages = mock(MessageManager.class);
        repository = mock(TravelRepository.class);
        teleports = mock(TeleportService.class);
        io = mock(ThreadPoolExecutor.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        when(server.getWorld(world.getUID())).thenReturn(world);
        when(server.getPlayer(any(UUID.class))).thenAnswer(call -> online.get(call.getArgument(0)));
        when(server.getOnlinePlayers()).thenAnswer(call -> List.copyOf(online.values()));
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.getPermission(anyString())).thenAnswer(call -> permissions.get(call.getArgument(0)));
        doAnswer(call -> {
            Permission permission = call.getArgument(0);
            permissions.put(permission.getName(), permission);
            return null;
        }).when(manager).addPermission(any(Permission.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(call -> {
            serverCallbacks.addLast(call.getArgument(0));
            return TaskHandle.NONE;
        });
        when(scheduler.runOnServerLater(any(Runnable.class), anyLong())).thenAnswer(call -> {
            later.addLast(call.getArgument(0));
            return TaskHandle.NONE;
        });
        doAnswer(call -> {
            if (rejecting) throw new RejectedExecutionException("controlled queue full");
            worker.addLast(call.getArgument(0));
            return null;
        }).when(io).execute(any(Runnable.class));
        when(io.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(io.isTerminated()).thenReturn(true);
        when(repository.homes(any(UUID.class))).thenReturn(Map.of());
        when(repository.warpEntries()).thenReturn(Map.of());
        when(repository.home(any(UUID.class), anyString())).thenReturn(Optional.empty());
        when(repository.warp(anyString())).thenReturn(Optional.empty());
        permissions.put("allium.sethome.unlimited", new Permission("allium.sethome.unlimited", PermissionDefault.FALSE));
        player = player(UUID.randomUUID());
        destination = snapshot(new Location(world, 100, 75, -30, -50, 15));
    }

    @AfterEach
    void close() {
        if (service != null) service.disable();
        worker.clear();
        serverCallbacks.clear();
    }

    @Test
    void operatorStatusDoesNotGrantARegisteredWarpAndDeniedAndUnknownNamesHaveTheSameRefusal() throws Exception {
        when(player.isOp()).thenReturn(true);
        when(repository.warpEntries()).thenReturn(warpRows(Map.of("public", destination)));
        enable();

        assertEquals(PermissionDefault.FALSE, permissions.get("allium.warps.public").getDefault());
        assertTrue(service.completeWarps(player).isEmpty());
        service.use(player, "public", true);
        service.use(player, "unknown", true);
        ArgumentCaptor<Messages> refusals = ArgumentCaptor.forClass(Messages.class);
        verify(messages, times(2)).send(eq(player), refusals.capture(), any(String[].class));
        assertEquals(List.of(Messages.WARP_NOT_FOUND, Messages.WARP_NOT_FOUND), refusals.getAllValues());
        verify(repository, never()).warp(anyString());
        verifyNoInteractions(teleports);

        grants.get(player).add("allium.warps.public");
        assertEquals(List.of("public"), service.completeWarps(player));
    }

    @Test
    void aConflictingPermissiveWarpPermissionFailsClosedEvenWithAnExplicitGrant() throws Exception {
        permissions.put("allium.warps.secret", new Permission("allium.warps.secret", PermissionDefault.OP));
        grants.get(player).add("allium.warps.secret");
        when(repository.warpEntries()).thenReturn(warpRows(Map.of("secret", destination)));
        enable();

        assertTrue(service.completeWarps(player).isEmpty());
        service.use(player, "secret", true);
        verify(repository, never()).warp(anyString());
        verifyNoInteractions(teleports);
        verify(plugin.logger()).severe(contains("allium.warps.secret"));
        assertEquals(PermissionDefault.OP, permissions.get("allium.warps.secret").getDefault());
    }

    @Test
    void numericGrantsUseTheirMaximumStaffOverridesProvideAFloorAndUnlimitedStillHasAHardCap() {
        config.set("homes.default-limit", 2);
        config.set("homes.staff-overrides." + player.getUniqueId(), 20);
        Set<PermissionAttachmentInfo> effective = new LinkedHashSet<>();
        grant(effective, "allium.sethome.multiple.7", true, true);
        grant(effective, "allium.sethome.12", true, true);
        grant(effective, "allium.sethome.multiple.999", false, true);
        grant(effective, "allium.sethome.multiple.50", true, false);
        grant(effective, "allium.sethome.multiple.-3", true, true);
        grant(effective, "allium.sethome.multiple.3000000000", true, true);
        when(player.getEffectivePermissions()).thenReturn(effective);
        enable();

        assertEquals(20, service.limit(player));
        config.set("homes.staff-overrides." + player.getUniqueId(), 5);
        assertTrue(service.reloadSettings());
        assertEquals(12, service.limit(player), "two numeric grants do not add to each other or the staff floor");
        grants.get(player).add("allium.sethome.unlimited");
        assertEquals(1000, service.limit(player));
        grants.get(player).remove("allium.sethome.unlimited");
        grant(effective, "allium.sethome.multiple.999999999", true, true);
        assertEquals(1000, service.limit(player));
    }

    @Test
    void overwriteConfirmationUsesTheOriginalPointAndExactRevisionAndCannotTransferToANewConnection() throws Exception {
        when(repository.homes(player.getUniqueId())).thenReturn(Map.of("base", destination));
        when(repository.createHome(eq(player.getUniqueId()), eq("base"), any(SavedLocation.class), anyInt())).thenReturn(EXISTS);
        when(repository.home(player.getUniqueId(), "base")).thenReturn(Optional.of(new StoredDestination("base", destination, 41)));
        when(repository.overwriteHome(eq(player.getUniqueId()), eq("base"), eq(41L), any(SavedLocation.class))).thenReturn(STALE);
        enable();
        SavedLocation firstPoint = snapshot(player.getLocation());

        service.set(player, "Base", false, false);
        assertTrue(service.pending(player.getUniqueId()));
        drain();
        assertFalse(service.pending(player.getUniqueId()));
        positions.put(player, new Location(world, 900, 100, 700));
        service.set(player, "base", false, true);
        drain();

        verify(repository).overwriteHome(player.getUniqueId(), "base", 41L, firstPoint);
        verify(repository, times(1)).home(player.getUniqueId(), "base");
        verify(messages).send(eq(player), eq(Messages.TRAVEL_CHANGED), any(String[].class));
        assertEquals(List.of("base"), service.completeHomes(player));

        service.set(player, "base", false, false);
        drain();
        Player replacement = player(player.getUniqueId());
        join(replacement);
        drain();
        service.set(replacement, "base", false, true);
        drain();
        verify(repository, times(1)).overwriteHome(any(UUID.class), anyString(), anyLong(), any(SavedLocation.class));
        verify(messages).send(eq(replacement), eq(Messages.TRAVEL_CONFIRM_EXPIRED), any(String[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void theTeleportCommitPolicyCancelsWhenWarpPermissionIsRevokedOrTheConnectionChanges() throws Exception {
        when(repository.warpEntries()).thenReturn(warpRows(Map.of("spawn", destination)));
        when(repository.warp("spawn")).thenReturn(Optional.of(new StoredDestination("spawn", destination, 9)));
        grants.get(player).add("allium.warps.spawn");
        enable();

        service.use(player, "spawn", true);
        drain();
        ArgumentCaptor<Supplier<Location>> point = ArgumentCaptor.forClass((Class) Supplier.class);
        ArgumentCaptor<BooleanSupplier> policy = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(teleports).teleport(eq(player), point.capture(), eq("warp"), policy.capture());
        assertEquals(destination, snapshot(point.getValue().get()));
        assertTrue(policy.getValue().getAsBoolean());
        grants.get(player).remove("allium.warps.spawn");
        assertFalse(policy.getValue().getAsBoolean(), "the warmup must not retain a permission granted only at admission");
        grants.get(player).add("allium.warps.spawn");
        assertTrue(policy.getValue().getAsBoolean());
        player(player.getUniqueId());
        assertFalse(policy.getValue().getAsBoolean(), "a replacement connection cannot inherit an admitted teleport");
    }

    @Test
    void completionsFilterThePrefixAndWarpAccessBeforeApplyingTheFiftyNameCap() throws Exception {
        Map<String, SavedLocation> names = new HashMap<>();
        for (int i = 0; i < 80; i++) names.put(String.format(java.util.Locale.ROOT, "a%03d", i), destination);
        for (int i = 0; i < 60; i++) names.put(String.format(java.util.Locale.ROOT, "z%03d", i), destination);
        for (String name : names.keySet()) grants.get(player).add("allium.warps." + name);
        names.put("z_secret", destination);
        when(repository.homes(player.getUniqueId())).thenReturn(Map.copyOf(names));
        when(repository.warpEntries()).thenReturn(warpRows(names));
        enable();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) expected.add(String.format(java.util.Locale.ROOT, "z%03d", i));
        assertEquals(expected, service.completeHomes(player, "Z"));
        assertEquals(expected, service.completeWarps(player, "Z"));
        assertEquals(List.of("z059"), service.completeHomes(player, "z059"));
        assertEquals(List.of("z059"), service.completeWarps(player, "z059"));
        assertEquals(List.of("z_secret"), service.completeHomes(player, "z_"));
        assertTrue(service.completeWarps(player, "z_").isEmpty());
    }

    @Test
    void aFullWorkerQueueDoesNotMutateCachesOrLeaveThePlayerBusy() throws Exception {
        when(repository.homes(player.getUniqueId())).thenReturn(Map.of("base", destination));
        when(repository.warpEntries()).thenReturn(warpRows(Map.of("spawn", destination)));
        grants.get(player).add("allium.warps.spawn");
        enable();
        rejecting = true;

        service.set(player, "new", false, false);
        service.delete(player, "base", false);
        service.use(player, "spawn", true);

        assertFalse(service.pending(player.getUniqueId()));
        assertTrue(service.ready(player.getUniqueId()));
        assertTrue(service.warpsReady());
        assertEquals(List.of("base"), service.completeHomes(player));
        assertEquals(List.of("spawn"), service.completeWarps(player));
        assertTrue(worker.isEmpty());
        verify(repository, never()).createHome(any(UUID.class), anyString(), any(SavedLocation.class), anyInt());
        verify(repository, never()).home(any(UUID.class), anyString());
        verify(repository, never()).warp(anyString());
        verifyNoInteractions(teleports);
        verify(messages, times(3)).send(eq(player), eq(Messages.TRAVEL_STORAGE_FAILED), any(String[].class));
    }

    @Test
    void aDelayedOldJoinCallbackCannotMarkTheReplacementSessionReadyOrOverwriteItsHomes() throws Exception {
        when(repository.homes(player.getUniqueId())).thenReturn(Map.of("old", destination), Map.of("fresh", destination));
        start();
        worker.removeFirst().run(); // Global warp load.
        serverCallbacks.removeFirst().run();
        worker.removeFirst().run(); // The old connection's homes are now waiting for the server callback.
        assertFalse(service.ready(player.getUniqueId()));
        Player replacement = player(player.getUniqueId());
        join(replacement);

        serverCallbacks.removeFirst().run();
        assertFalse(service.ready(replacement.getUniqueId()));
        assertTrue(service.completeHomes(replacement).isEmpty());
        worker.removeFirst().run();
        serverCallbacks.removeFirst().run();

        assertTrue(service.ready(replacement.getUniqueId()));
        assertEquals(List.of("fresh"), service.completeHomes(replacement));
        assertTrue(worker.isEmpty());
        assertTrue(serverCallbacks.isEmpty());
    }

    @Test
    void admittingAWarpOverwriteInvalidatesAnEarlierWarmupBeforeSqlAndRejectsOlderReadCallbacks() throws Exception {
        Player staff = player(UUID.randomUUID());
        Player reader = player(UUID.randomUUID());
        SavedLocation replacement = snapshot(staff.getLocation());
        StoredDestination original = new StoredDestination("spawn", destination, 7);
        when(repository.warpEntries()).thenReturn(Map.of("spawn", original));
        when(repository.warp("spawn")).thenReturn(Optional.of(original), Optional.of(original), Optional.of(original),
                Optional.of(new StoredDestination("spawn", replacement, 8)));
        when(repository.createWarp(eq("spawn"), any(SavedLocation.class))).thenReturn(EXISTS);
        when(repository.overwriteWarp("spawn", 7L, replacement)).thenReturn(UPDATED);
        for (Player actor : List.of(player, staff, reader)) grants.get(actor).add("allium.warps.spawn");
        enable();
        service.use(player, "spawn", true);
        drain();
        BooleanSupplier admittedPolicy = warpPolicy(player);
        assertTrue(admittedPolicy.getAsBoolean());
        service.set(staff, "spawn", true, false);
        drain();

        service.use(reader, "spawn", true);
        worker.removeFirst().run(); // The old snapshot is waiting on the server when the mutation is admitted.
        service.set(staff, "spawn", true, true);

        assertFalse(admittedPolicy.getAsBoolean(), "invalidation happens at admission, before SQL or readback runs");
        verify(repository, never()).overwriteWarp(anyString(), anyLong(), any(SavedLocation.class));
        serverCallbacks.removeFirst().run();
        verify(teleports, never()).teleport(eq(reader), any(), anyString(), any(BooleanSupplier.class));
        assertFalse(admittedPolicy.getAsBoolean(), "the delayed old query cannot republish its cached revision");
        worker.removeFirst().run(); // Commit overwrite; its result is still waiting on the server.
        assertFalse(admittedPolicy.getAsBoolean());
        serverCallbacks.removeFirst().run(); // Queue the authoritative mutation readback.
        assertFalse(admittedPolicy.getAsBoolean(), "the readback has not run yet");
        drain();

        verify(repository).overwriteWarp("spawn", 7L, replacement);
        assertFalse(admittedPolicy.getAsBoolean(), "a different persisted revision cannot revive the old warmup");
        assertFalse(service.pending(staff.getUniqueId()));
    }

    @Test
    void aDelayedBulkRefreshCannotRestoreTheRevisionInvalidatedByAnAdmittedOverwrite() throws Exception {
        Player staff = player(UUID.randomUUID());
        StoredDestination original = new StoredDestination("spawn", destination, 7);
        when(repository.warpEntries()).thenReturn(Map.of("spawn", original));
        when(repository.warp("spawn")).thenReturn(Optional.of(original));
        when(repository.createWarp(eq("spawn"), any(SavedLocation.class))).thenReturn(EXISTS);
        grants.get(player).add("allium.warps.spawn");
        enable();
        service.use(player, "spawn", true);
        drain();
        BooleanSupplier admittedPolicy = warpPolicy(player);
        assertTrue(admittedPolicy.getAsBoolean());
        service.set(staff, "spawn", true, false);
        drain();

        // Request the same recovery refresh used by a disconnected warp writer, without
        // introducing an unrelated writer just to reach this snapshot publication boundary.
        var refresh = TravelService.class.getDeclaredMethod("loadWarps", int.class);
        refresh.setAccessible(true);
        refresh.invoke(service, 0);
        worker.removeFirst().run(); // Bulk snapshot has revision 7; publication waits on the server.
        assertEquals(1, serverCallbacks.size());
        service.set(staff, "spawn", true, true);
        assertFalse(admittedPolicy.getAsBoolean());

        serverCallbacks.removeFirst().run();

        assertTrue(service.warpsReady());
        assertFalse(admittedPolicy.getAsBoolean(), "the old bulk snapshot must preserve the newer revision invalidation");
        verify(repository, never()).overwriteWarp(anyString(), anyLong(), any(SavedLocation.class));
        assertEquals(1, worker.size(), "the admitted mutation has not executed SQL or queued its readback yet");
    }

    @Test
    void anUnrelatedStaleHomeQueryDoesNotReloadWarpsOrCancelAnotherPlayersWarmup() throws Exception {
        Player homeowner = player(UUID.randomUUID());
        StoredDestination spawn = new StoredDestination("spawn", destination, 9);
        when(repository.warpEntries()).thenReturn(Map.of("spawn", spawn));
        when(repository.warp("spawn")).thenReturn(Optional.of(spawn));
        when(repository.home(homeowner.getUniqueId(), "base"))
                .thenReturn(Optional.of(new StoredDestination("base", destination, 1)));
        grants.get(player).add("allium.warps.spawn");
        enable();
        service.use(player, "spawn", true);
        drain();
        BooleanSupplier admittedPolicy = warpPolicy(player);
        assertTrue(admittedPolicy.getAsBoolean());
        clearInvocations(repository);

        service.use(homeowner, "base", false);
        worker.removeFirst().run();
        Player replacement = player(homeowner.getUniqueId());
        join(replacement);
        serverCallbacks.removeFirst().run(); // Completion belongs to the old home session.
        drain();

        verify(repository, never()).warpEntries();
        verify(repository, never()).warps();
        assertTrue(service.warpsReady());
        assertTrue(admittedPolicy.getAsBoolean(), "one player's stale home read cannot invalidate another's warp");
        assertTrue(service.ready(replacement.getUniqueId()));
    }

    @Test
    void settingsReloadCannotPublishAConfirmationFromAnAlreadyCompletedSnapshotRead() throws Exception {
        when(repository.homes(player.getUniqueId())).thenReturn(Map.of("base", destination));
        when(repository.createHome(eq(player.getUniqueId()), eq("base"), any(SavedLocation.class), anyInt())).thenReturn(EXISTS);
        when(repository.home(player.getUniqueId(), "base")).thenReturn(Optional.of(new StoredDestination("base", destination, 7)));
        enable();

        service.set(player, "base", false, false);
        worker.removeFirst().run();
        serverCallbacks.removeFirst().run(); // EXISTS queues a snapshot read for confirmation.
        worker.removeFirst().run(); // Snapshot finished; delay publishing its confirmation until after reload.
        assertTrue(service.reloadSettings());
        serverCallbacks.removeFirst().run();
        assertFalse(service.pending(player.getUniqueId()));

        verify(messages, never()).send(eq(player), eq(Messages.TRAVEL_CONFIRM), any(String[].class));
        verify(messages).send(eq(player), eq(Messages.TRAVEL_CHANGED), any(String[].class));
        service.set(player, "base", false, true);
        drain();
        verify(repository, never()).overwriteHome(any(UUID.class), anyString(), anyLong(), any(SavedLocation.class));
        verify(messages).send(eq(player), eq(Messages.TRAVEL_CONFIRM_EXPIRED), any(String[].class));
    }

    @Test
    void malformedParentSectionsAreRejectedWithoutReplacingThePreviousZeroHomeLimit() {
        config.set("homes.default-limit", 0);
        config.createSection("warps");
        enable();
        assertEquals(0, service.limit(player));

        config.set("homes", "not a section");
        assertFalse(service.reloadSettings());
        assertEquals(0, service.limit(player));
        config.createSection("homes");
        config.set("homes.default-limit", 0);
        config.set("warps", List.of("not", "a", "section"));
        assertFalse(service.reloadSettings());
        assertEquals(0, service.limit(player), "failed reloads keep the last valid settings rather than falling back to one home");
    }

    private static Map<String, StoredDestination> warpRows(Map<String, SavedLocation> locations) {
        Map<String, StoredDestination> result = new HashMap<>();
        locations.forEach((name, location) -> result.put(name, new StoredDestination(name, location, 1)));
        return Map.copyOf(result);
    }

    private BooleanSupplier warpPolicy(Player mover) {
        ArgumentCaptor<BooleanSupplier> policy = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(teleports).teleport(eq(mover), any(), eq("warp"), policy.capture());
        return policy.getValue();
    }

    private Player player(UUID id) {
        Player result = mock(Player.class);
        when(result.getUniqueId()).thenReturn(id);
        when(result.getName()).thenReturn("Traveler");
        when(result.isOnline()).thenReturn(true);
        when(result.getWorld()).thenReturn(world);
        grants.put(result, new HashSet<>(List.of("allium.home", "allium.homes", "allium.sethome", "allium.delhome",
                "allium.warp", "allium.warps", "allium.setwarp", "allium.delwarp")));
        positions.put(result, new Location(world, 1, 64, 2, 35, -10));
        when(result.getLocation()).thenAnswer(call -> positions.get(result).clone());
        when(result.hasPermission(anyString())).thenAnswer(call -> {
            String node = call.getArgument(0);
            Permission permission = permissions.get(node);
            return grants.get(result).contains(node) || (permission == null ? result.isOp()
                    : permission.getDefault().getValue(result.isOp()));
        });
        when(result.getEffectivePermissions()).thenReturn(Set.of());
        online.put(id, result);
        return result;
    }

    private void grant(Set<PermissionAttachmentInfo> effective, String node, boolean attachmentValue, boolean actuallyGranted) {
        effective.add(new PermissionAttachmentInfo(player, node, null, attachmentValue));
        if (actuallyGranted) grants.get(player).add(node);
    }

    private void start() {
        service = new TravelService(plugin, repository, teleports, io);
        service.enable();
    }

    private void enable() {
        start();
        drain();
        assertTrue(service.ready(player.getUniqueId()));
        assertTrue(service.warpsReady());
    }

    private void drain() {
        for (int i = 0; i < 100 && (!worker.isEmpty() || !serverCallbacks.isEmpty()); i++) {
            if (!worker.isEmpty()) worker.removeFirst().run();
            if (!serverCallbacks.isEmpty()) serverCallbacks.removeFirst().run();
        }
        assertTrue(worker.isEmpty(), "storage tasks remain bounded");
        assertTrue(serverCallbacks.isEmpty(), "server callbacks remain bounded");
    }

    private void join(Player joining) {
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(joining);
        service.onJoin(event);
    }

    private SavedLocation snapshot(Location location) {
        return new SavedLocation(world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }
}
