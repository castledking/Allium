package codes.castled.ores;

import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class FreshChunksDurabilityTest {

    private Plugin plugin;
    private Settings settings;
    private UUID worldId;
    private World world;
    private Map<NamespacedKey, Object> chunkPdcMap;
    private Chunk chunk;
    private AtomicBoolean chunkLoaded;
    private AtomicBoolean providerReady;
    private AtomicInteger placedCount;
    private OrePlacement placement;
    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(Plugin.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enabled: true
                auto-generate: true
                worlds: [world]
                pregen:
                  chunks-per-tick: 4
                  delay-ticks: 1
                  generate-ores: true
                ores:
                  salt:
                    enabled: true
                    block: sf:salt_ore
                    deepslate-block: sf:deepslate_salt_ore
                    vein-size: 8
                    min-height: 0
                    max-height: 64
                    chance-per-chunk: 1.0
                    veins-per-chunk: 2
                """);
        settings = Settings.read(yaml, msg -> fail(msg));

        worldId = UUID.randomUUID();
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(worldId);
        when(world.getSeed()).thenReturn(42L);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        chunkLoaded = new AtomicBoolean(true);
        chunkPdcMap = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(inv -> chunkPdcMap.containsKey(inv.getArgument(0)));
        when(pdc.get(any(NamespacedKey.class), any())).thenAnswer(inv -> chunkPdcMap.get(inv.getArgument(0)));
        doAnswer(inv -> {
            chunkPdcMap.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), any(), any());
        doAnswer(inv -> {
            chunkPdcMap.remove(inv.getArgument(0));
            return null;
        }).when(pdc).remove(any(NamespacedKey.class));

        Block stoneBlock = mock(Block.class);
        when(stoneBlock.getType()).thenReturn(Material.STONE);
        when(stoneBlock.getBiome()).thenReturn(Biome.PLAINS);

        chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(3);
        when(chunk.getZ()).thenReturn(-7);
        when(chunk.isLoaded()).thenAnswer(inv -> chunkLoaded.get());
        when(chunk.getPersistentDataContainer()).thenReturn(pdc);
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenReturn(stoneBlock);

        when(world.isChunkLoaded(3, -7)).thenAnswer(inv -> chunkLoaded.get());
        when(world.getChunkAt(3, -7)).thenReturn(chunk);
        when(world.getLoadedChunks()).thenAnswer(inv -> chunkLoaded.get() ? new Chunk[]{chunk} : new Chunk[0]);

        providerReady = new AtomicBoolean(false);
        placedCount = new AtomicInteger();
        placement = new OrePlacement() {
            @Override
            public boolean exists(BlockRef block) {
                return providerReady.get();
            }

            @Override
            public boolean place(Block block, BlockRef customBlock) {
                if (!providerReady.get()) return false;
                placedCount.incrementAndGet();
                return true;
            }

            @Override
            public String idOf(Block block) { return null; }

            @Override
            public org.bukkit.inventory.ItemStack itemOf(String id) { return null; }
        };

        scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return true; }
            @Override public TaskHandle runOnServer(Runnable task) { task.run(); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void unreadyProviderDefersChunkAndRetriesOnceProviderBecomesReady() {
        OreGeneration gen = new OreGeneration(plugin, scheduler, placement, settings);
        FreshChunks fresh = new FreshChunks(gen, scheduler, id -> id.equals(worldId) ? world : null);

        fresh.onPopulate(new ChunkPopulateEvent(chunk));
        assertTrue(gen.isPending(chunk));

        // Drain while provider is unready: nothing placed, pending marker preserved, deferred for retry.
        fresh.drain();
        assertEquals(0, placedCount.get());
        assertTrue(gen.isPending(chunk));
        assertEquals(1, fresh.deferredCount());

        // Provider becomes ready -> next drain automatically retries and completes generation.
        providerReady.set(true);
        fresh.drain();
        assertTrue(placedCount.get() > 0);
        assertFalse(gen.isPending(chunk));
        assertEquals(0, fresh.deferredCount());
        assertTrue(chunkPdcMap.containsKey(NamespacedKey.fromString("allium:ore_generated_salt")));
    }

    @Test
    void transientChunkUnloadPreservesPendingMarkerAndCompletesOnReload() {
        providerReady.set(true);
        OreGeneration gen = new OreGeneration(plugin, scheduler, placement, settings);
        FreshChunks fresh = new FreshChunks(gen, scheduler, id -> id.equals(worldId) ? world : null);

        fresh.onPopulate(new ChunkPopulateEvent(chunk));
        assertTrue(gen.isPending(chunk));

        // Chunk unloads before drain executes
        chunkLoaded.set(false);
        fresh.drain();
        assertEquals(0, placedCount.get());
        assertTrue(gen.isPending(chunk), "unloaded chunk must retain persisted pending marker");

        // Chunk reloads -> onChunkLoad re-enqueues it and drain generates ores
        chunkLoaded.set(true);
        fresh.onChunkLoad(new ChunkLoadEvent(chunk, false));
        assertEquals(1, fresh.queuedCount());
        fresh.drain();
        assertTrue(placedCount.get() > 0);
        assertFalse(gen.isPending(chunk));

        // Subsequent reload does NOT re-generate completed chunk
        int beforeSecondLoad = placedCount.get();
        fresh.onChunkLoad(new ChunkLoadEvent(chunk, false));
        assertEquals(0, fresh.queuedCount());
        fresh.drain();
        assertEquals(beforeSecondLoad, placedCount.get());
    }

    @Test
    void serverRestartReconstructsOutstandingWorkFromPersistedChunkPdc() {
        OreGeneration firstGen = new OreGeneration(plugin, scheduler, placement, settings);
        FreshChunks beforeRestart = new FreshChunks(firstGen, scheduler, id -> id.equals(worldId) ? world : null);

        beforeRestart.onPopulate(new ChunkPopulateEvent(chunk));
        beforeRestart.drain(); // provider unready -> deferred & pending persisted
        assertEquals(0, placedCount.get());
        assertTrue(firstGen.isPending(chunk));

        // Simulate restart: close old instance, boot new instance with provider now ready
        beforeRestart.close();
        providerReady.set(true);
        OreGeneration secondGen = new OreGeneration(plugin, scheduler, placement, settings);
        FreshChunks afterRestart = new FreshChunks(secondGen, scheduler, id -> id.equals(worldId) ? world : null);

        afterRestart.scanLoadedWorlds(List.of(world));
        assertEquals(1, afterRestart.queuedCount());
        afterRestart.drain();
        assertTrue(placedCount.get() > 0);
        assertFalse(secondGen.isPending(chunk));
    }
}
