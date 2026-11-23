package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.CommandManager;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.library.SkullProfiles;
import codes.castled.allium.util.AlliumLogger;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoliaUtilityCommandsTest {

    private record Harness(
            PluginStart plugin,
            Server server,
            TaskScheduler scheduler,
            MessageManager messages,
            Player sender,
            Player target,
            ServerThread senderOwner,
            ServerThread targetOwner
    ) {}

    private static Harness harness() {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        MessageManager messages = mock(MessageManager.class);
        ModuleManager modules = mock(ModuleManager.class);
        CommandManager commands = mock(CommandManager.class);
        CommandFamilyPolicy policy = mock(CommandFamilyPolicy.class);

        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getCommandManager()).thenReturn(commands);
        when(commands.getFamilyPolicy()).thenReturn(policy);
        when(scheduler.runOn(any(), any(), any(), anyLong())).thenReturn(mock(TaskHandle.class));

        Player sender = player("Sender", UUID.randomUUID());
        Player target = player("Target", UUID.randomUUID());
        when(server.getPlayer("Target")).thenReturn(target);
        when(server.getPlayerExact("Target")).thenReturn(target);
        when(server.getPlayer(target.getUniqueId())).thenReturn(target);
        when(server.getPlayer(sender.getUniqueId())).thenReturn(sender);

        ServerThread senderOwner = mock(ServerThread.class);
        ServerThread targetOwner = mock(ServerThread.class);
        when(senderOwner.isServerThread()).thenReturn(true);
        when(targetOwner.isServerThread()).thenReturn(false);

        return new Harness(plugin, server, scheduler, messages, sender, target, senderOwner, targetOwner);
    }

    private static Player player(String name, UUID id) {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        when(p.getUniqueId()).thenReturn(id);
        when(p.isOnline()).thenReturn(true);
        when(p.hasPermission(anyString())).thenReturn(true);
        return p;
    }

    @Test
    void feedRunsOnTargetRegionAndReturnsSenderFeedbackToSenderRegion() {
        Harness h = harness();
        FeedCommand cmd = new FeedCommand(h.plugin());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.target())).thenReturn(h.targetOwner());

            var targetTask = ArgumentCaptor.forClass(Runnable.class);
            var senderTask = ArgumentCaptor.forClass(Runnable.class);

            cmd.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.FEED, h.sender(), "feed", new String[]{"Target"}));

            verify(h.scheduler()).runOn(eq(h.targetOwner()), targetTask.capture(), any(), eq(0L));
            verify(h.target(), never()).setFoodLevel(anyInt());

            // Simulate execution on target's region thread (no longer on sender's region).
            when(h.senderOwner().isServerThread()).thenReturn(false);
            when(h.targetOwner().isServerThread()).thenReturn(true);
            targetTask.getValue().run();

            verify(h.target()).setFoodLevel(20);
            verify(h.target()).setSaturation(20f);
            verify(h.messages()).sendByKey(eq(h.target()), anyString(), any(String[].class));
            verify(h.messages(), never()).sendByKey(eq(h.sender()), anyString(), any(String[].class));
            verify(h.scheduler()).runOn(eq(h.senderOwner()), senderTask.capture(), any(), eq(0L));

            when(h.senderOwner().isServerThread()).thenReturn(true);
            senderTask.getValue().run();
            verify(h.messages()).sendByKey(eq(h.sender()), anyString(), eq("{player}"), eq("Target"));
            verify(h.scheduler(), never()).runOnServer(any());
        }
    }

    @Test
    void queuedMutationDoesNotTouchReplacementConnectionAndRetiredNotifiesSender() {
        Harness h = harness();
        BurnCommand burn = new BurnCommand(h.plugin());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.target())).thenReturn(h.targetOwner());

            var targetTask = ArgumentCaptor.forClass(Runnable.class);
            burn.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.BURN, h.sender(), "burn", new String[]{"Target", "5"}));
            verify(h.scheduler()).runOn(eq(h.targetOwner()), targetTask.capture(), any(), eq(0L));

            Player replacement = player("Target", h.target().getUniqueId());
            when(h.server().getPlayer(h.target().getUniqueId())).thenReturn(replacement);

            targetTask.getValue().run();
            verify(h.target(), never()).setFireTicks(anyInt());
            verify(replacement, never()).setFireTicks(anyInt());
        }
    }

    @Test
    void skullAsyncResolutionDeliversOnTargetRegionAndReportsRetiredTarget() {
        Harness h = harness();
        SkullProfiles skulls = mock(SkullProfiles.class);
        CompletableFuture<SkullProfiles.Resolved> pending = new CompletableFuture<>();
        when(skulls.resolve("Notch")).thenReturn(pending);
        ItemStack head = MockItemStacks.create(Material.PLAYER_HEAD);
        SkullMeta meta = mock(SkullMeta.class);
        when(head.getItemMeta()).thenReturn(meta);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(h.target().getInventory()).thenReturn(inv);
        when(inv.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        SkullCommand cmd = new SkullCommand(h.plugin(), () -> skulls, () -> head);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.target())).thenReturn(h.targetOwner());

            cmd.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.SKULL, h.sender(), "skull", new String[]{"Notch", "Target"}));
            verify(h.messages()).send(eq(h.sender()), eq(Messages.UTILITY_SKULL_PENDING), eq("{owner}"), eq("Notch"));

            // Simulate async lookup completing off-thread; must hop to targetOwner, never runOnServer.
            when(h.senderOwner().isServerThread()).thenReturn(false);
            when(h.targetOwner().isServerThread()).thenReturn(false);
            var targetTask = ArgumentCaptor.forClass(Runnable.class);
            var retiredTask = ArgumentCaptor.forClass(Runnable.class);
            PlayerProfile profile = mock(PlayerProfile.class);
            pending.complete(new SkullProfiles.Resolved(profile));

            verify(h.scheduler()).runOn(eq(h.targetOwner()), targetTask.capture(), retiredTask.capture(), eq(0L));
            verify(h.scheduler(), never()).runOnServer(any());

            // Verify retired callback notifies sender on senderOwner with PLAYER_NOT_FOUND.
            var senderOffline = ArgumentCaptor.forClass(Runnable.class);
            retiredTask.getValue().run();
            verify(h.scheduler()).runOn(eq(h.senderOwner()), senderOffline.capture(), any(), eq(0L));
            when(h.senderOwner().isServerThread()).thenReturn(true);
            senderOffline.getValue().run();
            verify(h.messages()).send(eq(h.sender()), eq(Messages.PLAYER_NOT_FOUND), eq("{player}"), eq("Target"));

            // Verify normal execution on targetOwner applies profile and inserts item.
            when(h.targetOwner().isServerThread()).thenReturn(true);
            targetTask.getValue().run();
            verify(meta).setOwnerProfile(same(profile));
            verify(inv).addItem(head);
        }
    }

    @Test
    void unlimitedReplenishesOnPlayerEntitySchedulerNotGlobal() {
        Harness h = harness();
        UnlimitedCommand cmd = new UnlimitedCommand(h.plugin());
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(h.sender().getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenReturn("stone");
        when(h.sender().getGameMode()).thenReturn(GameMode.SURVIVAL);

        PlayerInventory inv = mock(PlayerInventory.class);
        when(h.sender().getInventory()).thenReturn(inv);
        when(inv.getHeldItemSlot()).thenReturn(0);
        ItemStack held = MockItemStacks.create(Material.STONE);
        held.setAmount(1);
        when(held.hasItemMeta()).thenReturn(false);

        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(h.sender());
        when(event.getItemInHand()).thenReturn(held);
        when(event.canBuild()).thenReturn(true);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());

            cmd.placed(event);

            verify(h.scheduler()).runOn(eq(h.senderOwner()), any(), any(), eq(50L));
            verify(h.scheduler(), never()).runOnServerLater(any(), anyLong());
        }
    }

    @Test
    void smiteRayTraceStrikesOnHitChunkRegion() {
        Harness h = harness();
        World world = mock(World.class);
        when(h.sender().getWorld()).thenReturn(world);
        Block hitBlock = mock(Block.class);
        RayTraceResult hit = new RayTraceResult(new Vector(4096.5, 64.0, 4096.5), hitBlock, null);
        when(h.sender().rayTraceBlocks(128.0D, FluidCollisionMode.NEVER)).thenReturn(hit);

        ServerThread strikeRegion = mock(ServerThread.class);
        when(strikeRegion.isServerThread()).thenReturn(false);
        SmiteCommand cmd = new SmiteCommand(h.plugin());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());
            platform.when(() -> BukkitServerThread.region(h.plugin(), world, 256, 256)).thenReturn(strikeRegion);

            var regionTask = ArgumentCaptor.forClass(Runnable.class);
            cmd.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.SMITE, h.sender(), "smite", new String[0]));

            verify(h.scheduler()).runOn(eq(strikeRegion), regionTask.capture(), any(), eq(0L));
            verify(world, never()).strikeLightningEffect(any());

            AtomicBoolean struck = new AtomicBoolean(false);
            doAnswer(inv -> { struck.set(true); return null; }).when(world).strikeLightningEffect(any(Location.class));
            regionTask.getValue().run();
            assertTrue(struck.get());
        }
    }

    @Test
    void timeAndWeatherCommandsMutateWorldOnGlobalSchedulerAndReplyToSenderRegion() {
        Harness h = harness();
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getTime()).thenReturn(1000L);
        when(world.hasStorm()).thenReturn(true);
        when(world.isThundering()).thenReturn(true);
        when(h.sender().getWorld()).thenReturn(world);
        when(h.scheduler().isServerThread()).thenReturn(false);

        TimePauseCoordinator pause = new TimePauseCoordinator(h.plugin());
        TimeCommand time = new TimeCommand(h.plugin(), CoreCommandSpecs.TIME, pause);
        WeatherCommand weather = new WeatherCommand(h.plugin());
        ThunderCommand thunder = new ThunderCommand(h.plugin());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());

            var globalTasks = ArgumentCaptor.forClass(Runnable.class);
            assertTrue(time.executeWithResult(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.TIME, h.sender(), "time", new String[]{"set", "day"})));
            assertTrue(time.executeWithResult(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.TIME, h.sender(), "time", new String[]{"pause", "on"})));
            assertTrue(weather.executeWithResult(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.WEATHER, h.sender(), "weather", new String[]{"thunder"})));
            thunder.execute(new BukkitCommandContext(h.plugin(), ThunderCommand.SPEC, h.sender(), "thunder", new String[]{"true", "30"}));

            verify(world, never()).setTime(anyLong());
            verify(world, never()).setStorm(anyBoolean());
            verify(world, never()).setThundering(anyBoolean());
            verify(world, never()).setGameRule(eq(GameRule.DO_DAYLIGHT_CYCLE), any());
            verify(h.scheduler(), times(4)).runOnServer(globalTasks.capture());

            // Run queued mutations on the global tick thread (not sender's entity region).
            when(h.senderOwner().isServerThread()).thenReturn(false);
            when(h.scheduler().isServerThread()).thenReturn(true);
            for (Runnable task : globalTasks.getAllValues()) {
                task.run();
            }

            verify(world).setTime(1000L);
            verify(world).setGameRule(GameRule.DO_DAYLIGHT_CYCLE, Boolean.FALSE);
            verify(world).setStorm(true);
            verify(world, times(2)).setThundering(true);
            verify(world).setThunderDuration(600);
            // All four confirmations must hop back to senderOwner rather than sending on the global thread.
            verify(h.scheduler(), times(4)).runOn(eq(h.senderOwner()), any(), any(), eq(0L));
        }
    }

    @Test
    void removeFansOutAcrossLoadedChunkRegionsAndAggregatesResult() {
        Harness h = harness();
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(h.server().getWorld("world")).thenReturn(world);

        Chunk chunkA = mock(Chunk.class);
        Chunk chunkB = mock(Chunk.class);
        when(chunkA.getX()).thenReturn(0);
        when(chunkA.getZ()).thenReturn(0);
        when(chunkB.getX()).thenReturn(256);
        when(chunkB.getZ()).thenReturn(256);
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunkA, chunkB});
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.isChunkLoaded(256, 256)).thenReturn(true);
        when(world.getChunkAt(0, 0)).thenReturn(chunkA);
        when(world.getChunkAt(256, 256)).thenReturn(chunkB);

        Pig pigA = mock(Pig.class);
        Pig pigB = mock(Pig.class);
        when(pigA.getType()).thenReturn(EntityType.PIG);
        when(pigB.getType()).thenReturn(EntityType.PIG);
        when(chunkA.getEntities()).thenReturn(new Entity[]{pigA});
        when(chunkB.getEntities()).thenReturn(new Entity[]{pigB});

        ServerThread regionA = mock(ServerThread.class);
        ServerThread regionB = mock(ServerThread.class);
        when(regionA.isServerThread()).thenReturn(true);
        when(regionB.isServerThread()).thenReturn(false);

        RemoveCommand cmd = new RemoveCommand(h.plugin());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());
            platform.when(() -> BukkitServerThread.region(h.plugin(), world, 0, 0)).thenReturn(regionA);
            platform.when(() -> BukkitServerThread.region(h.plugin(), world, 256, 256)).thenReturn(regionB);

            var regionBTask = ArgumentCaptor.forClass(Runnable.class);
            cmd.execute(new BukkitCommandContext(h.plugin(), RemoveCommand.SPEC, h.sender(), "remove", new String[]{"mobs", "world"}));

            // Chunk A was owned inline; chunk B was deferred and must not have been mutated yet.
            verify(pigA).remove();
            verify(pigB, never()).remove();
            verify(h.messages(), never()).send(eq(h.sender()), eq(Messages.REMOVE_COUNT), any(String[].class));
            verify(h.scheduler()).runOn(eq(regionB), regionBTask.capture(), any(), eq(0L));

            // Execute chunk B on region B; aggregated count of 2 must hop back to senderOwner.
            when(h.senderOwner().isServerThread()).thenReturn(false);
            var senderReply = ArgumentCaptor.forClass(Runnable.class);
            regionBTask.getValue().run();
            verify(pigB).remove();
            verify(h.scheduler()).runOn(eq(h.senderOwner()), senderReply.capture(), any(), eq(0L));

            when(h.senderOwner().isServerThread()).thenReturn(true);
            senderReply.getValue().run();
            verify(h.messages()).send(eq(h.sender()), eq(Messages.REMOVE_COUNT), eq("{count}"), eq("2"));
        }
    }

    @Test
    void spawnMobForOtherPlayerRunsOnTargetRegion() {
        Harness h = harness();
        World world = mock(World.class);
        Location targetLoc = new Location(world, 4096.5, 64.0, 4096.5);
        when(h.target().getLocation()).thenReturn(targetLoc);
        Pig spawned = mock(Pig.class);
        when(world.spawn(any(Location.class), eq(Pig.class))).thenReturn(spawned);

        SpawnMobCommand cmd = new SpawnMobCommand(h.plugin());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.target())).thenReturn(h.targetOwner());

            var targetTask = ArgumentCaptor.forClass(Runnable.class);
            cmd.execute(new BukkitCommandContext(h.plugin(), CoreCommandSpecs.SPAWNMOB, h.sender(),
                    "spawnmob", new String[]{"pig", "1", "Target"}));

            verify(world, never()).spawn(any(Location.class), eq(Pig.class));
            verify(h.scheduler()).runOn(eq(h.targetOwner()), targetTask.capture(), any(), eq(0L));

            when(h.senderOwner().isServerThread()).thenReturn(false);
            when(h.targetOwner().isServerThread()).thenReturn(true);
            targetTask.getValue().run();

            verify(world).spawn(any(Location.class), eq(Pig.class));
            verify(h.scheduler()).runOn(eq(h.senderOwner()), any(), any(), eq(0L));
        }
    }

    @Test
    void treeGeneratesOnTargetChunkRegionAndRoutesCrossBorderCanopyBlocks() {
        Harness h = harness();
        World world = mock(World.class);
        when(h.sender().getWorld()).thenReturn(world);
        Block ground = mock(Block.class);
        Block above = mock(Block.class);
        Location origin = new Location(world, 32, 64, 0);
        when(ground.getRelative(BlockFace.UP)).thenReturn(above);
        when(above.getLocation()).thenReturn(origin);
        when(h.sender().getTargetBlockExact(6)).thenReturn(ground);

        BlockState canopy = mock(BlockState.class);
        when(canopy.getWorld()).thenReturn(world);
        when(canopy.getX()).thenReturn(48);
        when(canopy.getZ()).thenReturn(0);
        when(canopy.getLocation()).thenReturn(new Location(world, 48, 68, 0));
        when(world.generateTree(eq(origin), any(), eq(TreeType.TREE),
                org.mockito.ArgumentMatchers.<java.util.function.Predicate<BlockState>>any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Predicate<BlockState> capture = inv.getArgument(3);
            capture.test(canopy);
            return true;
        });

        ServerThread originRegion = mock(ServerThread.class);
        ServerThread canopyRegion = mock(ServerThread.class);
        when(originRegion.isServerThread()).thenReturn(false);
        when(canopyRegion.isServerThread()).thenReturn(false);

        TreeCommand cmd = new TreeCommand(h.plugin(), TreeCommand.TREE);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(h.plugin(), h.sender())).thenReturn(h.senderOwner());
            platform.when(() -> BukkitServerThread.region(h.plugin(), world, 2, 0)).thenReturn(originRegion);
            platform.when(() -> BukkitServerThread.region(h.plugin(), world, 3, 0)).thenReturn(canopyRegion);

            var originTask = ArgumentCaptor.forClass(Runnable.class);
            var canopyTask = ArgumentCaptor.forClass(Runnable.class);
            cmd.execute(new BukkitCommandContext(h.plugin(), TreeCommand.TREE, h.sender(), "tree", new String[0]));

            verify(h.scheduler()).runOn(eq(originRegion), originTask.capture(), any(), eq(0L));
            verify(canopy, never()).update(anyBoolean(), anyBoolean());

            originTask.getValue().run();
            verify(h.scheduler()).runOn(eq(canopyRegion), canopyTask.capture(), any(), eq(0L));
            verify(canopy, never()).update(anyBoolean(), anyBoolean());

            canopyTask.getValue().run();
            verify(canopy).update(true, false);
        }
    }
}
