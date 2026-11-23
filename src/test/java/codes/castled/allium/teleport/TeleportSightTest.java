package codes.castled.allium.teleport;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeleportSightTest {
    @Test
    void rayContinuesInOrderOnTheNextChunkOwner() {
        var plugin = mock(PluginStart.class);
        var scheduler = mock(TaskScheduler.class);
        var player = mock(Player.class);
        var world = mock(World.class);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(player.getEyeLocation()).thenReturn(new Location(world, 15.5, 65, 0.5, -90, 0));
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.isChunkLoaded(1, 0)).thenReturn(true);
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(17);
        when(block.getY()).thenReturn(65);
        var hit = mock(RayTraceResult.class);
        when(hit.getHitBlock()).thenReturn(block);
        when(hit.getHitBlockFace()).thenReturn(BlockFace.WEST);
        when(world.rayTraceBlocks(any(Location.class), any(), anyDouble(), eq(FluidCollisionMode.NEVER))).thenAnswer(call ->
                call.<Location>getArgument(0).getBlockX() >= 16 ? hit : null);
        var left = mock(ServerThread.class);
        var right = mock(ServerThread.class);
        var leftTasks = new ArrayDeque<Runnable>();
        var rightTasks = new ArrayDeque<Runnable>();
        when(scheduler.runOn(any(ServerThread.class), any(), any(), eq(0L))).thenAnswer(call -> {
            (call.getArgument(0) == left ? leftTasks : rightTasks).add(call.getArgument(1));
            return mock(TaskHandle.class);
        });
        try (var platform = mockStatic(BukkitServerThread.class)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.region(plugin, world, 0, 0)).thenReturn(left);
            platform.when(() -> BukkitServerThread.region(plugin, world, 1, 0)).thenReturn(right);
            var destination = TeleportSight.destination(plugin, player);
            verify(world, never()).rayTraceBlocks(any(Location.class), any(), anyDouble(), any());
            leftTasks.remove().run();
            assertFalse(destination.isDone());
            rightTasks.remove().run();
            assertEquals(new Location(world, 16.5, 65, 0.5, -90, 0), destination.join());
        }
    }
}
