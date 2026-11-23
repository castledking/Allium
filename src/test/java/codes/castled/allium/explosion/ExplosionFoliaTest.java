package codes.castled.allium.explosion;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.explosion.CannonCommand;
import codes.castled.allium.command.explosion.ExplodeCommand;
import codes.castled.allium.command.explosion.NukeCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExplosionFoliaTest {

    @TempDir Path tempDir;

    private final ArrayDeque<Runnable> centerTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> neighborTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> entityTasks = new ArrayDeque<>();

    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private World world;
    private UUID worldId;
    private ServerThread centerRegion;
    private ServerThread neighborRegion;
    private ServerThread entityOwner;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        scheduler = mock(TaskScheduler.class);
        messages = mock(MessageManager.class);
        world = mock(World.class);
        worldId = UUID.randomUUID();

        when(plugin.getName()).thenReturn("Allium");
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getWorlds()).thenReturn(List.of());
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(messages.resolve(any(Messages.class), any(String[].class))).thenReturn("BOOM\nWatch out");

        Files.writeString(tempDir.resolve("explosions.yml"), """
                regeneration:
                  enabled: true
                  delay-seconds: 0
                  blocks-per-tick: 10
                  effects: false
                  skip-claims: false
                  sources:
                    creeper: true
                explode:
                  default-power: 4
                  min-power: 1
                  max-power: 127
                """);

        centerRegion = mock(ServerThread.class);
        neighborRegion = mock(ServerThread.class);
        entityOwner = mock(ServerThread.class);
        when(centerRegion.isServerThread()).thenReturn(true);
        when(neighborRegion.isServerThread()).thenReturn(false);
        when(entityOwner.isServerThread()).thenReturn(true);

        when(scheduler.runOn(eq(centerRegion), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            centerTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
        when(scheduler.runOn(eq(neighborRegion), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            neighborTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
        when(scheduler.runOn(eq(entityOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            entityTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
    }

    @Test
    void regenerationOnFoliaRunsPerJobOnChunkRegionAndRestoresCrossRegionBlocksBeforeFinishing() throws Exception {
        RegenerationService regen = new RegenerationService(plugin);
        regen.enable();

        Block centerBlock = mockStoneBlock(8, 64, 8);      // chunk (0, 0) -> centerRegion
        Block crossBlock = mockStoneBlock(17, 64, 8);      // chunk (1, 0) -> neighborRegion
        when(world.getBlockAt(8, 64, 8)).thenReturn(centerBlock);
        when(world.getBlockAt(17, 64, 8)).thenReturn(crossBlock);

        Creeper creeper = mock(Creeper.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(creeper.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(null);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.region(plugin, world, 0, 0)).thenReturn(centerRegion);
            platform.when(() -> BukkitServerThread.region(plugin, world, 1, 0)).thenReturn(neighborRegion);

            List<Block> exploded = new ArrayList<>(List.of(centerBlock, crossBlock));
            regen.onEntityExplode(new EntityExplodeEvent(creeper, new Location(world, 8.5, 64.5, 8.5), exploded, 1.0f));

            assertTrue(exploded.isEmpty(), "captured blocks are removed from explosion");
            assertEquals(1, regen.waiting()[0]);
            verify(scheduler, never()).repeatOnServer(any(), anyLong(), anyLong());
            // Ticket for chunk (1,0) was dispatched onto neighborRegion
            assertEquals(1, neighborTasks.size());
            neighborTasks.remove().run();
            verify(world).addPluginChunkTicket(0, 0, plugin);
            verify(world).addPluginChunkTicket(1, 0, plugin);

            // Simulate air after capture
            when(centerBlock.getType()).thenReturn(Material.AIR);
            when(crossBlock.getType()).thenReturn(Material.AIR);

            // Center region tick restores centerBlock inline and dispatches crossBlock onto neighborRegion
            assertEquals(1, centerTasks.size());
            centerTasks.remove().run();
            verify(centerBlock.getState()).update(true, false);
            assertEquals(1, neighborTasks.size(), "cross-region block must be dispatched to neighborRegion");
            assertEquals(1, regen.waiting()[0], "job must wait for cross-region restore before finishing");

            // Neighbor region finishes restoring crossBlock
            when(centerRegion.isServerThread()).thenReturn(false);
            when(neighborRegion.isServerThread()).thenReturn(true);
            neighborTasks.remove().run();
            verify(crossBlock.getState()).update(true, false);

            // Next center tick sees inFlightCrossRegion == 0 and finishes job
            when(centerRegion.isServerThread()).thenReturn(true);
            when(neighborRegion.isServerThread()).thenReturn(false);
            centerTasks.remove().run();
            assertEquals(0, regen.waiting()[0]);
            verify(world).removePluginChunkTicket(0, 0, plugin);
            assertEquals(1, neighborTasks.size());
            neighborTasks.remove().run();
            verify(world).removePluginChunkTicket(1, 0, plugin);
        }
    }

    @Test
    void regenerationDisableOffRegionOnFoliaPreservesJournalWithoutGlobalBlockMutation() throws Exception {
        RegenerationService regen = new RegenerationService(plugin);
        regen.enable();

        Block centerBlock = mockStoneBlock(8, 64, 8);
        when(world.getBlockAt(8, 64, 8)).thenReturn(centerBlock);

        Creeper creeper = mock(Creeper.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(creeper.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(null);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.region(plugin, world, 0, 0)).thenReturn(centerRegion);

            regen.onEntityExplode(new EntityExplodeEvent(creeper, new Location(world, 8.5, 64.5, 8.5),
                    new ArrayList<>(List.of(centerBlock)), 1.0f));
            assertEquals(1, regen.waiting()[0]);

            // Shutdown happens on global thread (centerRegion.isServerThread() == false)
            when(centerRegion.isServerThread()).thenReturn(false);
            regen.disable();

            assertEquals(0, regen.waiting()[0]);
            verify(centerBlock.getState(), never()).update(true, false);
            long journalFiles = Files.list(tempDir.resolve("regeneration")).count();
            assertEquals(1L, journalFiles, "journal must be preserved on disk for next startup resume");
        }
    }

    @Test
    void cannonCommandOnFoliaSchedulesPopOnFlierEntityRegion() throws Exception {
        RegenerationService regen = new RegenerationService(plugin);
        regen.enable();
        CannonCommand cmd = CannonCommand.beezooka(plugin, regen);

        Player player = mock(Player.class);
        Location eye = new Location(world, 4, 65, 4);
        eye.setDirection(new Vector(1, 0, 0));
        when(player.getName()).thenReturn("Alice");
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(eye);

        Bee bee = mock(Bee.class);
        PersistentDataContainer beePdc = mock(PersistentDataContainer.class);
        when(bee.getPersistentDataContainer()).thenReturn(beePdc);
        when(bee.getLocation()).thenReturn(new Location(world, 20, 65, 4));
        when(world.spawn(any(Location.class), eq(Bee.class))).thenReturn(bee);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, bee)).thenReturn(entityOwner);

            cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.BEEZOOKA, player, "beezooka", new String[0]));

            assertEquals(1, entityTasks.size(), "pop task must run on the bee's entity scheduler");
            verify(scheduler, never()).runOnServerLater(any(), anyLong());

            entityTasks.remove().run();
            verify(bee).remove();
            verify(world).createExplosion(any(Location.class), eq(0.0F), eq(false), eq(false));
        }
    }

    @Test
    void explodeAndNukeCommandsRouteCrossRegionTargetsOntoTargetRegions() throws Exception {
        RegenerationService regen = new RegenerationService(plugin);
        regen.enable();
        ExplodeCommand explode = new ExplodeCommand(plugin, regen);
        NukeCommand nuke = new NukeCommand(plugin, regen);

        Player sender = mock(Player.class);
        Player target = mock(Player.class);
        UUID senderId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(sender.getUniqueId()).thenReturn(senderId);
        when(sender.getName()).thenReturn("Staff");
        when(sender.isOnline()).thenReturn(true);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(sender.canSee(target)).thenReturn(true);

        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Victim");
        when(target.isOnline()).thenReturn(true);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new Location(world, 8, 64, 8));
        when(world.getHighestBlockYAt(any(Location.class))).thenReturn(64);

        Fireball fireball = mock(Fireball.class);
        when(fireball.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(world.spawn(any(Location.class), eq(Fireball.class))).thenReturn(fireball);
        TNTPrimed tnt = mock(TNTPrimed.class);
        when(tnt.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(world.spawn(any(Location.class), eq(TNTPrimed.class))).thenReturn(tnt);

        when(server.getPlayer("Victim")).thenReturn(target);
        when(server.getPlayer(targetId)).thenReturn(target);
        when(server.getPlayer(senderId)).thenReturn(sender);
        when(server.getOnlinePlayers()).thenAnswer(c -> List.of(sender, target));

        ServerThread senderOwner = mock(ServerThread.class);
        when(senderOwner.isServerThread()).thenReturn(true);
        when(entityOwner.isServerThread()).thenReturn(false);
        when(scheduler.runOn(eq(senderOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            ((Runnable) c.getArgument(1)).run();
            return mock(TaskHandle.class);
        });

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, sender)).thenReturn(senderOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, target)).thenReturn(entityOwner);
            platform.when(() -> BukkitServerThread.region(eq(plugin), eq(world), anyInt(), anyInt())).thenReturn(centerRegion);

            explode.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.EXPLODE, sender, "explode", new String[]{"Victim", "105"}));
            assertEquals(1, entityTasks.size(), "/explode must hop to target's entity region");
            verify(world, never()).spawn(any(Location.class), eq(Fireball.class));

            when(entityOwner.isServerThread()).thenReturn(true);
            entityTasks.remove().run();
            verify(world).spawn(any(Location.class), eq(Fireball.class));
            assertFalse(centerTasks.isEmpty(), "cataclysm spiral/shockwave effects must schedule on chunk region");
            verify(scheduler, never()).runOnServerLater(any(), anyLong());

            // Nuke cross-region
            when(entityOwner.isServerThread()).thenReturn(false);
            nuke.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.NUKE, sender, "nuke", new String[]{"Victim"}));
            assertEquals(1, entityTasks.size(), "/nuke must hop to target's entity region");
            when(entityOwner.isServerThread()).thenReturn(true);
            entityTasks.remove().run();
            verify(world, times(25)).spawn(any(Location.class), eq(TNTPrimed.class));
        }
    }

    private Block mockStoneBlock(int x, int y, int z) {
        Block block = mock(Block.class);
        BlockState state = mock(BlockState.class);
        BlockData data = mock(BlockData.class);
        Block air = mock(Block.class);
        when(air.getWorld()).thenReturn(world);
        when(air.getType()).thenReturn(Material.AIR);

        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getLocation()).thenReturn(new Location(world, x, y, z));
        when(block.getType()).thenReturn(Material.STONE);
        when(block.getBlockData()).thenReturn(data);
        when(block.getState()).thenReturn(state);
        when(block.getRelative(any(BlockFace.class))).thenReturn(air);
        when(state.getType()).thenReturn(Material.STONE);
        when(state.getBlockData()).thenReturn(data);
        when(data.getMaterial()).thenReturn(Material.STONE);
        when(data.getAsString()).thenReturn("minecraft:stone");
        return block;
    }
}
