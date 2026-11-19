package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.essentials.MobSpawner;
import codes.castled.allium.command.essentials.SpawnMobCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.command.teleport.TeleportCommand;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.IgnoreService;
import codes.castled.allium.messaging.MessagePolicy;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.module.TeleportModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpawnMobAndTeleportLimitsTest {

    @Test
    void spawnMobPermissionsDataStackingAndLimitWork() {
        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("spawnmob.limit", 4);
        when(plugin.getConfig()).thenReturn(config);
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);

        World world = mock(World.class);
        Player staff = mock(Player.class);
        Set<String> perms = new HashSet<>(Set.of("allium.spawnmob", "allium.spawnmob.zombie", "allium.spawnmob.spider"));
        when(staff.getUniqueId()).thenReturn(UUID.randomUUID());
        when(staff.getName()).thenReturn("Staff");
        when(staff.isOnline()).thenReturn(true);
        when(staff.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        when(staff.hasPermission(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(staff));

        Zombie zombie = mock(Zombie.class);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        Spider spider = mock(Spider.class);
        when(spider.getType()).thenReturn(EntityType.SPIDER);
        when(world.spawn(any(Location.class), eq(Zombie.class))).thenReturn(zombie);
        when(world.spawn(any(Location.class), eq(Spider.class))).thenReturn(spider);

        SpawnMobCommand cmd = new SpawnMobCommand(plugin);

        // 1. Denied mob without per-mob permission.
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SPAWNMOB, staff, "spawnmob", new String[]{"creeper"}));
        verify(messages).send(staff, Messages.SPAWNMOB_NO_PERM_MOB, "{mob}", "creeper");

        // 2. Denied stacking without allium.spawnmob.stack.
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SPAWNMOB, staff, "spawnmob", new String[]{"zombie,spider"}));
        verify(messages).send(staff, Messages.SPAWNMOB_NO_STACK);

        // 3. Allowed stacked spawn with :data and limit clamping (limit=4 -> 2 stacks of 2 = 4 entities max).
        perms.add("allium.spawnmob.stack");
        cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SPAWNMOB, staff, "spawnmob", new String[]{"zombie:baby,spider", "5"}));
        verify(messages).send(staff, Messages.SPAWNMOB_LIMIT, "{limit}", "4");
        verify(zombie, times(2)).setBaby(true);
        verify(zombie, times(2)).addPassenger(spider);
        verify(messages).send(staff, Messages.SPAWNMOB_SPAWNED, "{count}", "4", "{mob}", "zombie,spider");

        // 4. Direct data modifier coverage for sheep, creeper, slime, wolf.
        Sheep sheep = mock(Sheep.class);
        when(sheep.getType()).thenReturn(EntityType.SHEEP);
        MobSpawner.applyData(sheep, "red:baby", staff);
        verify(sheep).setColor(DyeColor.RED);
        verify(sheep).setBaby();

        Creeper creeper = mock(Creeper.class);
        when(creeper.getType()).thenReturn(EntityType.CREEPER);
        MobSpawner.applyData(creeper, "powered", staff);
        verify(creeper).setPowered(true);

        Slime slime = mock(Slime.class);
        when(slime.getType()).thenReturn(EntityType.SLIME);
        MobSpawner.applyData(slime, "7", staff);
        verify(slime).setSize(7);

        Wolf wolf = mock(Wolf.class);
        when(wolf.getType()).thenReturn(EntityType.WOLF);
        MobSpawner.applyData(wolf, "tamed:angry", staff);
        verify(wolf).setTamed(true);
        verify(wolf).setOwner(staff);
        verify(wolf).setAngry(true);

        // 5. Tab completion filters by permission and completes :data hints.
        List<String> mobs = cmd.complete(staff, new String[]{"z"});
        assertTrue(mobs.contains("zombie"));
        assertFalse(mobs.contains("zoglin"));
        List<String> hints = cmd.complete(staff, new String[]{"zombie:ba"});
        assertTrue(hints.contains("zombie:baby"));
    }

    @Test
    void tppetAndTpeUseSeparateConfiguredMaxLimits() {
        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.entity-limits.tppet-max", 2);
        config.set("teleport.entity-limits.tpe-max", 4);
        when(plugin.getConfig()).thenReturn(config);

        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.getScheduler()).thenReturn(scheduler);
        Deque<Runnable> tasks = new ArrayDeque<>();
        when(scheduler.runOnServerLater(any(Runnable.class), anyLong())).thenAnswer(inv -> {
            tasks.add(inv.getArgument(0));
            return TaskHandle.NONE;
        });

        World world = mock(World.class);
        Player owner = mock(Player.class);
        UUID ownerId = UUID.randomUUID();
        when(owner.getUniqueId()).thenReturn(ownerId);
        when(owner.isOnline()).thenReturn(true);
        when(owner.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(owner.getWorld()).thenReturn(world);
        when(owner.getEffectivePermissions()).thenReturn(Set.of());
        Set<String> perms = new HashSet<>(Set.of("allium.tppet", "allium.tpmob"));
        when(owner.hasPermission(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
        when(server.getPlayer(ownerId)).thenReturn(owner);

        CompanionTeleports companions = new CompanionTeleports(plugin, mock(DestinationSafety.class));
        assertEquals(2, companions.maxSelection(owner, true));
        assertEquals(4, companions.maxSelection(owner, false));

        // Explicit numbered permission caps below config max, never above.
        perms.add("allium.tppet.1");
        assertEquals(1, companions.maxSelection(owner, true));
        perms.remove("allium.tppet.1");

        // /tppet selects 2 pets, refuses 3rd at tppet-max=2.
        companions.toggle(owner, owner, true);
        for (int i = 0; i < 3; i++) {
            Wolf wolf = mock(Wolf.class);
            UUID id = UUID.randomUUID();
            when(wolf.getUniqueId()).thenReturn(id);
            when(wolf.getOwner()).thenReturn(owner);
            when(wolf.isTamed()).thenReturn(true);
            when(wolf.isValid()).thenReturn(true);
            when(wolf.getType()).thenReturn(EntityType.WOLF);
            when(server.getEntity(id)).thenReturn(wolf);
            companions.click(new PlayerInteractEntityEvent(owner, wolf, EquipmentSlot.HAND));
            while (!tasks.isEmpty()) tasks.remove().run();
        }
        verify(messages).send(owner, Messages.TELEPORT_SELECTION, "{message}", "Selection limit: 2");

        // /tpe selects 4 entities, refuses 5th at tpe-max=4.
        companions.toggle(owner, owner, false);
        for (int i = 0; i < 5; i++) {
            Pig pig = mock(Pig.class);
            UUID id = UUID.randomUUID();
            when(pig.getUniqueId()).thenReturn(id);
            when(pig.isValid()).thenReturn(true);
            when(pig.getType()).thenReturn(EntityType.PIG);
            when(pig.getWorld()).thenReturn(world);
            when(server.getEntity(id)).thenReturn(pig);
            companions.click(new PlayerInteractEntityEvent(owner, pig, EquipmentSlot.HAND));
            while (!tasks.isEmpty()) tasks.remove().run();
        }
        verify(messages).send(owner, Messages.TELEPORT_SELECTION, "{message}", "Selection limit: 4");
        assertEquals(4, companions.selectedMobs(owner).size());
    }

    @Test
    void tphereEnforcesMaxLimitAndRequiresConfirmationForMassEntitySelector() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.warmup-seconds", 0);
        config.set("teleport.entity-limits.tphere-max", 3);
        config.set("teleport.entity-limits.confirm-seconds", 15);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test-tphere"));
        AlliumLogger alliumLogger = new AlliumLogger(plugin);
        when(plugin.logger()).thenReturn(alliumLogger);

        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getServer()).thenReturn(server);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return TaskHandle.NONE; }).when(scheduler).runOnServer(any(Runnable.class));
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
        when(plugin.getScheduler()).thenReturn(scheduler);

        World world = safeWorld("world");
        Player staff = mock(Player.class);
        UUID staffId = UUID.randomUUID();
        when(staff.getUniqueId()).thenReturn(staffId);
        when(staff.getName()).thenReturn("Staff");
        when(staff.isOnline()).thenReturn(true);
        when(staff.getWorld()).thenReturn(world);
        when(staff.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(server.getPlayer(staffId)).thenReturn(staff);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(staff));
        when(server.getWorlds()).thenReturn(List.of(world));

        List<Entity> worldEntities = new ArrayList<>();
        worldEntities.add(staff);
        List<Pig> pigs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Pig p = mock(Pig.class);
            when(p.getUniqueId()).thenReturn(UUID.randomUUID());
            when(p.isValid()).thenReturn(true);
            when(p.getWorld()).thenReturn(world);
            when(p.getLocation()).thenReturn(new Location(world, 5 + i, 64, 5));
            when(p.getType()).thenReturn(EntityType.PIG);
            when(p.teleport(any(Location.class))).thenReturn(true);
            pigs.add(p);
            worldEntities.add(p);
        }
        when(world.getEntities()).thenAnswer(inv -> worldEntities);

        TeleportRepository repo = mock(TeleportRepository.class);
        when(repo.load(any(), anyString())).thenReturn(Optional.empty());
        when(repo.loadCooldowns(any())).thenReturn(java.util.Map.of());
        ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        TeleportService service = new TeleportService(plugin, repo, io);
        service.enable();

        TeleportModule tpModule = mock(TeleportModule.class);
        when(tpModule.isEnabled()).thenReturn(true);
        when(tpModule.companions()).thenReturn(null);
        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("teleport")).thenReturn(tpModule);
        when(plugin.getModuleManager()).thenReturn(modules);

        var tphereSpec = CoreCommandSpecs.TELEPORT_SPECS.stream().filter(s -> s.canonicalName().equals("tphere")).findFirst().orElseThrow();
        TeleportCommand cmd = new TeleportCommand(plugin, service, tphereSpec);

        // 1. 4 pigs exceed tphere-max=3 -> blocked with TELEPORT_ENTITY_LIMIT_EXCEEDED, none teleported.
        cmd.execute(new BukkitCommandContext(plugin, tphereSpec, staff, "s", new String[]{"@e"}));
        verify(messages).send(staff, Messages.TELEPORT_ENTITY_LIMIT_EXCEEDED, "{count}", "4", "{limit}", "3");
        for (Pig p : pigs) verify(p, never()).teleport(any(Location.class));

        // 2. Reduce to 2 pigs (within limit=3): first /s @e prompts confirmation without teleporting.
        worldEntities.remove(pigs.get(3));
        worldEntities.remove(pigs.get(2));
        clearInvocations(messages);

        cmd.execute(new BukkitCommandContext(plugin, tphereSpec, staff, "s", new String[]{"@e"}));
        verify(messages).send(eq(staff), eq(Messages.TELEPORT_SELECTOR_CONFIRM),
                eq("{count}"), eq("2"),
                eq("{label}"), eq("entities"),
                eq("{command}"), eq("/s @e"),
                eq("{seconds}"), eq("15"));
        verify(pigs.get(0), never()).teleport(any(Location.class));
        verify(pigs.get(1), never()).teleport(any(Location.class));

        // 3. Running /s @e a second time confirms and teleports both entities!
        cmd.execute(new BukkitCommandContext(plugin, tphereSpec, staff, "s", new String[]{"@e"}));
        verify(pigs.get(0)).teleport(any(Location.class));
        verify(pigs.get(1)).teleport(any(Location.class));
        verify(messages).send(staff, Messages.TELEPORT_ENTITIES_MOVED, "{count}", "2", "{label}", "entities");

        service.disable();
    }

    @Test
    void tpallTeleportsAllOnlinePlayersBypassingTpToggle() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.warmup-seconds", 3); // Verify /tpall executes immediately without warmup
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test-tpall"));
        AlliumLogger alliumLogger = new AlliumLogger(plugin);
        when(plugin.logger()).thenReturn(alliumLogger);

        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getServer()).thenReturn(server);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return TaskHandle.NONE; }).when(scheduler).runOnServer(any(Runnable.class));
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
        when(plugin.getScheduler()).thenReturn(scheduler);

        World world = safeWorld("world");
        UUID staffId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        Player staff = onlinePlayer(staffId, "Staff", world, 0, 64, 0, Set.of("allium.tpall"));
        Player p1 = onlinePlayer(p1Id, "Alice", world, 20, 64, 20, Set.of());
        Player p2 = onlinePlayer(p2Id, "Bob", world, 40, 64, 40, Set.of());
        when(staff.canSee(any(Player.class))).thenReturn(true);
        when(p1.canSee(any(Player.class))).thenReturn(true);
        when(p2.canSee(any(Player.class))).thenReturn(true);
        when(server.getPlayer(staffId)).thenReturn(staff);
        when(server.getPlayer(p1Id)).thenReturn(p1);
        when(server.getPlayer(p2Id)).thenReturn(p2);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(staff, p1, p2));
        when(server.getWorlds()).thenReturn(List.of(world));

        PreferencesModule prefs = mock(PreferencesModule.class);
        when(prefs.isEnabled()).thenReturn(true);
        when(prefs.effective(any())).thenReturn(Optional.of(mock(EffectivePreferences.class)));
        when(prefs.acceptance(staffId, PreferenceKey.ACCEPT_TELEPORT_REQUESTS)).thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
        when(prefs.acceptance(p1Id, PreferenceKey.ACCEPT_TELEPORT_REQUESTS)).thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
        // Bob has /tptoggle off -> REFUSES!
        when(prefs.acceptance(p2Id, PreferenceKey.ACCEPT_TELEPORT_REQUESTS)).thenReturn(PreferenceSessions.Acceptance.REFUSES);

        MessagingModule messaging = mock(MessagingModule.class);
        when(messaging.isEnabled()).thenReturn(true);
        var msgSvc = mock(codes.castled.allium.messaging.MessagingService.class);
        when(msgSvc.visibleTo(any(), any())).thenReturn(true);
        when(messaging.messaging()).thenReturn(msgSvc);
        IgnoreService ignores = mock(IgnoreService.class);
        when(ignores.ignores(any(), any())).thenReturn(MessagePolicy.Ignore.NO);
        when(messaging.ignores()).thenReturn(ignores);

        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("preferences")).thenReturn(prefs);
        when(modules.getModule("messaging")).thenReturn(messaging);
        when(plugin.getModuleManager()).thenReturn(modules);

        TeleportRepository repo = mock(TeleportRepository.class);
        when(repo.load(any(), anyString())).thenReturn(Optional.empty());
        when(repo.loadCooldowns(any())).thenReturn(java.util.Map.of());
        ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        TeleportService service = new TeleportService(plugin, repo, io);
        service.enable();
        io.submit(() -> {}).get(5, TimeUnit.SECONDS);

        var tpallSpec = CoreCommandSpecs.TELEPORT_SPECS.stream().filter(s -> s.canonicalName().equals("tpall")).findFirst().orElseThrow();
        TeleportCommand cmd = new TeleportCommand(plugin, service, tpallSpec);
        cmd.execute(new BukkitCommandContext(plugin, tpallSpec, staff, "tpall", new String[0]));

        verify(p1).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(p2).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(staff, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(messages).send(staff, Messages.TELEPORT_ALL, "{count}", "2", "{player}", "Staff");

        service.disable();
    }

    private static World safeWorld(String name) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn(name);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.isChunkGenerated(anyInt(), anyInt())).thenReturn(true);
        WorldBorder border = mock(WorldBorder.class);
        when(border.getCenter()).thenReturn(new Location(world, 0, 64, 0));
        when(border.getSize()).thenReturn(10000.0);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(world.getWorldBorder()).thenReturn(border);
        Block solid = mock(Block.class);
        when(solid.getType()).thenReturn(Material.STONE);
        when(solid.isPassable()).thenReturn(false);
        when(solid.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(0, 63, 0, 1, 64, 1));
        BlockData solidData = mock(BlockData.class);
        when(solidData.isFaceSturdy(any(), any())).thenReturn(true);
        when(solid.getBlockData()).thenReturn(solidData);
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(air.isPassable()).thenReturn(true);
        when(air.isEmpty()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int y = inv.getArgument(1);
            return y < 64 ? solid : air;
        });
        return world;
    }

    private static Player onlinePlayer(UUID id, String name, World world, double x, double y, double z, Set<String> perms) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(new Location(world, x, y, z));
        when(p.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(true);
        when(p.hasPermission(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
        return p;
    }
}
