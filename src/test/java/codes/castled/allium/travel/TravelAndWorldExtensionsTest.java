package codes.castled.allium.travel;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.IgnoreService;
import codes.castled.allium.messaging.MessagePolicy;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.teleport.TeleportService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelAndWorldExtensionsTest {

    @TempDir
    Path tempDir;

    @Test
    void repositoryRenameHomeAndLimitOverridesAreAtomic() throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("travel.db"));
        TravelRepository repo = new TravelRepository(source, SqlDialect.SQLITE, System::currentTimeMillis);
        assertEquals(2, repo.migrate());

        UUID owner = UUID.randomUUID();
        SavedLocation a = new SavedLocation(UUID.randomUUID(), "world", 10, 64, 20, 0, 0);
        SavedLocation b = new SavedLocation(UUID.randomUUID(), "world", 30, 64, 40, 0, 0);

        assertEquals(TravelRepository.MutationResult.CREATED, repo.createHome(owner, "old", a, 5));
        assertEquals(TravelRepository.MutationResult.CREATED, repo.createHome(owner, "taken", b, 5));

        StoredDestination old = repo.home(owner, "old").orElseThrow();
        assertEquals(TravelRepository.MutationResult.EXISTS, repo.renameHome(owner, "old", "taken", old.revision()));
        assertEquals(TravelRepository.MutationResult.STALE, repo.renameHome(owner, "old", "fresh", old.revision() + 99));
        assertEquals(TravelRepository.MutationResult.UPDATED, repo.renameHome(owner, "old", "fresh", old.revision()));

        assertTrue(repo.home(owner, "old").isEmpty());
        assertEquals(a, repo.home(owner, "fresh").orElseThrow().location());
        assertEquals(2, repo.homeCount(owner));

        assertEquals(OptionalInt.empty(), repo.homeLimitOverride(owner));
        repo.setHomeLimitOverride(owner, 15);
        assertEquals(OptionalInt.of(15), repo.homeLimitOverride(owner));
        repo.setHomeLimitOverride(owner, -1);
        assertEquals(OptionalInt.empty(), repo.homeLimitOverride(owner));
    }

    @Test
    void warpInfoWarpOthersRenameHomeSethomesBedAndCompassWork() throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("svc.db"));
        TravelRepository repo = new TravelRepository(source, SqlDialect.SQLITE, System::currentTimeMillis);
        repo.migrate();

        UUID worldId = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getTime()).thenReturn(4000L);
        when(world.isThundering()).thenReturn(false);

        SavedLocation warpLoc = new SavedLocation(worldId, "world", 12.5, 70.0, -34.5, 90.0f, 0.0f);
        SavedLocation homeLoc = new SavedLocation(worldId, "world", 100.0, 65.0, 200.0, 0.0f, 0.0f);
        repo.createWarp("market", warpLoc);

        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("homes.compass-towards-home-perm", true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TravelTest"));
        var travelLog = new codes.castled.allium.util.AlliumLogger(plugin);
        when(plugin.logger()).thenReturn(travelLog);
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);

        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        Map<String, Permission> registeredPerms = new HashMap<>();
        when(pm.getPermission(anyString())).thenAnswer(inv -> registeredPerms.get(inv.getArgument(0)));
        doAnswer(inv -> {
            Permission perm = inv.getArgument(0);
            registeredPerms.put(perm.getName(), perm);
            return null;
        }).when(pm).addPermission(any(Permission.class));
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getWorld(worldId)).thenReturn(world);
        when(plugin.getServer()).thenReturn(server);

        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return TaskHandle.NONE; }).when(scheduler).runOnServer(any(Runnable.class));
        when(plugin.getScheduler()).thenReturn(scheduler);

        UUID aliceId = UUID.randomUUID();
        Player alice = mock(Player.class);
        Set<String> alicePerms = new HashSet<>(Set.of(
                "allium.warp", "allium.warps.market", "allium.warpinfo", "allium.warp.others",
                "allium.renamehome", "allium.sethomes", "allium.sethome.bed", "allium.home.compass"));
        when(alice.getUniqueId()).thenReturn(aliceId);
        when(alice.getName()).thenReturn("Alice");
        when(alice.isOnline()).thenReturn(true);
        when(alice.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(alice.hasPermission(anyString())).thenAnswer(inv -> alicePerms.contains(inv.getArgument(0)));
        when(alice.getEffectivePermissions()).thenReturn(Set.of());

        UUID bobId = UUID.randomUUID();
        Player bob = mock(Player.class);
        when(bob.getUniqueId()).thenReturn(bobId);
        when(bob.getName()).thenReturn("Bob");
        when(bob.isOnline()).thenReturn(true);
        when(bob.getLocation()).thenReturn(new Location(world, 5, 64, 5));

        when(server.getPlayer(aliceId)).thenReturn(alice);
        when(server.getPlayer(bobId)).thenReturn(bob);
        when(server.getPlayerExact("Alice")).thenReturn(alice);
        when(server.getPlayerExact("Bob")).thenReturn(bob);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(alice, bob));

        TeleportService teleports = mock(TeleportService.class);
        when(teleports.find(alice, "Bob")).thenReturn(bob);

        repo.createHome(aliceId, "alpha", homeLoc, 5);

        ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        TravelService service = new TravelService(plugin, repo, teleports, io);
        service.enable();
        waitIdle(io);

        // 1. Compass points at Alice's first home because homes.compass-towards-home-perm=true + allium.home.compass.
        ArgumentCaptor<Location> compass = ArgumentCaptor.forClass(Location.class);
        verify(alice).setCompassTarget(compass.capture());
        assertEquals(100.0, compass.getValue().getX());
        assertEquals(200.0, compass.getValue().getZ());

        // 2. /warpinfo market shows coordinates and permission.
        service.warpInfo(alice, "market");
        verify(messages).send(eq(alice), eq(Messages.WARP_INFO),
                eq("{name}"), eq("market"), eq("{world}"), eq("world"),
                eq("{x}"), eq("12.5"), eq("{y}"), eq("70.0"), eq("{z}"), eq("-34.5"),
                eq("{yaw}"), eq("90.0"), eq("{pitch}"), eq("0.0"),
                eq("{permission}"), eq("allium.warps.market"));

        // 3. /warp market Bob warps Bob via forceLocation.
        service.warpOther(alice, "market", "Bob");
        verify(teleports).forceLocation(eq(alice), eq(bob), any(Location.class), eq("warp"));
        verify(messages).send(eq(alice), eq(Messages.WARP_OTHER), eq("{player}"), eq("Bob"), eq("{name}"), eq("market"));

        // 4. /renamehome alpha beta renames Alice's home.
        service.rename(alice, "alpha", "beta");
        waitIdle(io);
        verify(messages).send(eq(alice), eq(Messages.HOME_RENAMED), eq("{old}"), eq("alpha"), eq("{name}"), eq("beta"));
        assertEquals(List.of("beta"), service.completeHomes(alice));

        // 5. /core sethomes Alice +4 sets DB override to 5 (base 1 + 4), updating live limit(alice) immediately.
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        service.sethomesAdmin(console, new String[]{"Alice", "+4"});
        waitIdle(io);
        assertEquals(5, service.limit(alice));
        verify(messages).send(eq(console), eq(Messages.HOME_LIMIT_SET), eq("{player}"), eq("Alice"), eq("{override}"), eq("5"), eq("{effective}"), eq("5"));

        service.sethomesAdmin(console, new String[]{"Alice", "-1"});
        waitIdle(io);
        assertEquals(1, service.limit(alice));
        verify(messages).send(eq(console), eq(Messages.HOME_LIMIT_CLEARED), eq("{player}"), eq("Alice"), eq("{effective}"), eq("1"));

        // 6. allium.sethome.bed: daytime bed right-click cancels event and notifies HOME_BED_SET.
        Block bedBlock = mock(Block.class);
        when(bedBlock.getType()).thenReturn(Material.RED_BED);
        when(bedBlock.getWorld()).thenReturn(world);
        when(bedBlock.getLocation()).thenReturn(new Location(world, 2, 64, 3));
        PlayerInteractEvent dayClick = new PlayerInteractEvent(alice, Action.RIGHT_CLICK_BLOCK, null, bedBlock, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
        service.onDaytimeBedClick(dayClick);
        assertTrue(dayClick.isCancelled());
        verify(messages).send(eq(alice), eq(Messages.HOME_BED_SET));

        service.disable();
    }

    @Test
    void tpoAndTpohereBypassTpToggleAndWorldsPermissionGatesCrossWorldTeleports() throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("tp.db"));
        TeleportRepository repo = new TeleportRepository(source, SqlDialect.SQLITE, System::currentTimeMillis);
        repo.migrate();

        World overworld = mock(World.class);
        when(overworld.getUID()).thenReturn(UUID.randomUUID());
        when(overworld.getName()).thenReturn("world");
        when(overworld.getMinHeight()).thenReturn(-64);
        when(overworld.getMaxHeight()).thenReturn(320);
        when(overworld.isChunkGenerated(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        when(overworld.isChunkLoaded(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        org.bukkit.WorldBorder border = mock(org.bukkit.WorldBorder.class);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(border.getCenter()).thenReturn(new Location(overworld, 0, 64, 0));
        when(border.getSize()).thenReturn(1000.0);
        when(overworld.getWorldBorder()).thenReturn(border);
        Block airBlock = mock(Block.class);
        when(airBlock.getType()).thenReturn(Material.AIR);
        when(airBlock.isPassable()).thenReturn(true);
        when(airBlock.isEmpty()).thenReturn(true);
        Block stoneBlock = mock(Block.class);
        when(stoneBlock.getType()).thenReturn(Material.STONE);
        when(stoneBlock.isPassable()).thenReturn(false);
        when(stoneBlock.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(0, 63, 0, 1, 64, 1));
        org.bukkit.block.data.BlockData stoneData = mock(org.bukkit.block.data.BlockData.class);
        when(stoneData.isFaceSturdy(org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockSupport.CENTER)).thenReturn(true);
        when(stoneBlock.getBlockData()).thenReturn(stoneData);
        when(overworld.getBlockAt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> ((int) inv.getArgument(1)) < 64 ? stoneBlock : airBlock);
        World nether = mock(World.class);
        when(nether.getUID()).thenReturn(UUID.randomUUID());
        when(nether.getName()).thenReturn("world_nether");

        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TpTest"));
        var tpLog = new codes.castled.allium.util.AlliumLogger(plugin);
        when(plugin.logger()).thenReturn(tpLog);
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);

        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        Map<String, Permission> registered = new HashMap<>();
        when(pm.getPermission(anyString())).thenAnswer(inv -> registered.get(inv.getArgument(0)));
        doAnswer(inv -> { Permission p = inv.getArgument(0); registered.put(p.getName(), p); return null; })
                .when(pm).addPermission(any(Permission.class));
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getWorlds()).thenReturn(List.of(overworld));
        when(plugin.getServer()).thenReturn(server);

        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return TaskHandle.NONE; }).when(scheduler).runOnServer(any(Runnable.class));
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
        when(plugin.getScheduler()).thenReturn(scheduler);

        UUID staffId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Player staff = mock(Player.class);
        Player target = mock(Player.class);
        when(staff.getUniqueId()).thenReturn(staffId);
        when(staff.getName()).thenReturn("Staff");
        when(staff.isOnline()).thenReturn(true);
        when(staff.getLocation()).thenReturn(new Location(overworld, 0, 64, 0));
        when(staff.getWorld()).thenReturn(overworld);
        when(staff.canSee(target)).thenReturn(true);
        when(staff.hasPermission("allium.tp")).thenReturn(true);
        when(staff.hasPermission("allium.tpo")).thenReturn(true);
        when(staff.hasPermission("allium.tpohere")).thenReturn(true);
        when(staff.hasPermission("allium.teleport.bypass.warmup")).thenReturn(true);
        when(staff.hasPermission("allium.teleport.unsafe")).thenReturn(true);
        when(staff.teleport(any(Location.class), any())).thenReturn(true);

        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Target");
        when(target.isOnline()).thenReturn(true);
        when(target.getLocation()).thenReturn(new Location(overworld, 20, 64, 20));
        when(target.getWorld()).thenReturn(overworld);
        when(target.canSee(staff)).thenReturn(true);
        when(target.teleport(any(Location.class), any())).thenReturn(true);

        when(server.getPlayer(staffId)).thenReturn(staff);
        when(server.getPlayer(targetId)).thenReturn(target);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(staff, target));

        PreferencesModule prefs = mock(PreferencesModule.class);
        when(prefs.isEnabled()).thenReturn(true);
        when(prefs.effective(staffId)).thenReturn(Optional.of(new EffectivePreferences(true, true, true, 1.0, false)));
        when(prefs.effective(targetId)).thenReturn(Optional.of(new EffectivePreferences(true, true, false, 1.0, false)));
        when(prefs.acceptance(staffId, PreferenceKey.ACCEPT_TELEPORT_REQUESTS)).thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
        when(prefs.acceptance(targetId, PreferenceKey.ACCEPT_TELEPORT_REQUESTS)).thenReturn(PreferenceSessions.Acceptance.REFUSES);

        MessagingModule messaging = mock(MessagingModule.class);
        codes.castled.allium.messaging.MessagingService msgSvc = mock(codes.castled.allium.messaging.MessagingService.class);
        when(msgSvc.visibleTo(any(), any())).thenReturn(true);
        IgnoreService ignores = mock(IgnoreService.class);
        when(messaging.isEnabled()).thenReturn(true);
        when(messaging.messaging()).thenReturn(msgSvc);
        when(messaging.ignores()).thenReturn(ignores);
        when(ignores.ignores(any(), any())).thenReturn(MessagePolicy.Ignore.NO);

        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("preferences")).thenReturn(prefs);
        when(modules.getModule("messaging")).thenReturn(messaging);
        when(plugin.getModuleManager()).thenReturn(modules);

        ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        TeleportService service = new TeleportService(plugin, repo, io);
        service.enable();
        waitIdle(io);

        // Dynamic world permission registered with default TRUE on enable and on WorldLoadEvent.
        assertEquals(PermissionDefault.TRUE, registered.get("allium.worlds.world").getDefault());
        service.worldLoaded(new WorldLoadEvent(nether));
        assertEquals(PermissionDefault.TRUE, registered.get("allium.worlds.world_nether").getDefault());

        // Plain /tp respects target's tptoggle=off without bypass.toggle.
        service.force(staff, staff, target, "tp");
        verify(staff, never()).teleport(any(Location.class), any());
        verify(messages).send(eq(staff), eq(Messages.TELEPORT_REFUSED), eq("{player}"), eq("Target"));

        // /tpo bypasses target's tptoggle=off!
        service.force(staff, staff, target, "tpo");
        verify(staff).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));

        // /tpohere bypasses target's tptoggle=off!
        service.force(staff, target, staff, "tpohere");
        verify(target).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));

        // Denying allium.worlds.world_nether cancels cross-world PlayerTeleportEvent with WORLD_ACCESS_DENIED.
        when(target.isPermissionSet("allium.worlds.world_nether")).thenReturn(true);
        when(target.hasPermission("allium.worlds.world_nether")).thenReturn(false);
        assertFalse(service.canEnterWorld(target, nether));
        PlayerTeleportEvent crossWorld = new PlayerTeleportEvent(
                target, new Location(overworld, 0, 64, 0), new Location(nether, 0, 64, 0), PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
        service.guardWorldAccess(crossWorld);
        assertTrue(crossWorld.isCancelled());
        verify(messages).send(eq(target), eq(Messages.WORLD_ACCESS_DENIED), eq("{world}"), eq("world_nether"));

        service.disable();
    }

    private static void waitIdle(ThreadPoolExecutor io) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        do {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("worker did not drain");
            io.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } while (io.getActiveCount() > 0 || !io.getQueue().isEmpty());
    }
}
