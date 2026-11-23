package codes.castled.ores;

import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AddonItems;
import codes.castled.allium.api.AddonText;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class OresFoliaTest {

    private record RegionCall(World world, int cx, int cz, Runnable task) {}

    @Test
    void pregenRoutesLoadedChunkPlacementToChunkRegionOnFolia() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enabled: true
                auto-generate: true
                worlds: [world]
                pregen:
                  chunks-per-tick: 4
                  delay-ticks: 1
                  generate-ores: false
                ores: {}
                """);
        Settings settings = Settings.read(yaml, msg -> fail(msg));

        List<BooleanSupplier> repeating = new ArrayList<>();
        List<RegionCall> regionCalls = new ArrayList<>();
        TaskScheduler scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return true; }
            @Override public TaskHandle runOnServer(Runnable task) { task.run(); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { task.run(); return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) {
                repeating.add(step);
                return TaskHandle.NONE;
            }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
                throw new UnsupportedOperationException();
            }
        };

        Plugin plugin = mock(Plugin.class);
        AddonConnection foliaCore = new AddonConnection() {
            @Override public Plugin plugin() { return plugin; }
            @Override public AddonItems items() { return null; }
            @Override public AddonCommands commands() { return null; }
            @Override public TaskScheduler scheduler() { return scheduler; }
            @Override public AddonText text() { return null; }
            @Override public boolean isFolia() { return true; }
            @Override public boolean ownsRegion(World world, int chunkX, int chunkZ) { return false; }
            @Override public TaskHandle runForRegion(World world, int chunkX, int chunkZ, Runnable task, long delayMillis) {
                regionCalls.add(new RegionCall(world, chunkX, chunkZ, task));
                return TaskHandle.NONE;
            }
        };

        Chunk chunk = mock(Chunk.class);
        when(chunk.isLoaded()).thenReturn(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);

        OreGeneration gen = new OreGeneration(plugin, foliaCore, null, settings);
        var future = gen.startGeneration(world, 5, -3, 1);
        assertEquals(1, repeating.size());

        // First pump dispatches up to chunksPerTick (4) chunks; none execute inline because ownsRegion is false.
        repeating.get(0).getAsBoolean();
        assertEquals(4, regionCalls.size());
        verify(chunk, never()).addPluginChunkTicket(plugin);

        // Run all dispatched chunk-region callbacks and pump until all 9 chunks complete.
        int safety = 0;
        while (!future.isDone() && safety++ < 20) {
            List<RegionCall> batch = List.copyOf(regionCalls);
            regionCalls.clear();
            batch.forEach(call -> call.task().run());
            repeating.get(0).getAsBoolean();
        }
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertEquals(9, gen.generatedChunks());
        verify(chunk, times(9)).addPluginChunkTicket(plugin);
        verify(chunk, times(9)).removePluginChunkTicket(plugin);
    }
}
