package net.survivalfun.core.listeners.world;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.world.OreGenerationManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listener for automatic ore generation in the Resource world.
 * Throttles generation during pregen to reduce lag.
 */
public class OreGenerationListener implements Listener {
    
    private final PluginStart plugin;
    private final OreGenerationManager oreManager;
    private static final String RESOURCE_WORLD_NAME = "Resource";
    private boolean autoGenerateEnabled;
    private boolean debugLogging;
    private int chunksPerTick;  // Throttling: max chunks to process per tick
    private int generationDelayTicks;  // Delay between processing batches
    
    // Queue for throttled chunk processing
    private final ConcurrentLinkedQueue<Chunk> chunkQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingChunks = new AtomicInteger(0);
    private boolean processorRunning = false;
    
    public OreGenerationListener(PluginStart plugin, OreGenerationManager oreManager) {
        this.plugin = plugin;
        this.oreManager = oreManager;
        this.autoGenerateEnabled = plugin.getConfig().getBoolean("ore-generation.auto-generate", true);
        this.debugLogging = plugin.getConfig().getBoolean("ore-generation.debug", false);
        this.chunksPerTick = plugin.getConfig().getInt("ore-generation.pregen.chunks-per-tick", 2);
        this.generationDelayTicks = plugin.getConfig().getInt("ore-generation.pregen.delay-ticks", 1);
        
        // HIGHLY VISIBLE INIT LOG (only if debug enabled)
        if (debugLogging) {
            plugin.getLogger().info("[OreGen-INIT] OreGenerationListener initialized!");
            plugin.getLogger().info("[OreGen-INIT] Resource world: " + RESOURCE_WORLD_NAME);
            plugin.getLogger().info("[OreGen-INIT] Auto-generate enabled: " + autoGenerateEnabled);
            plugin.getLogger().info("[OreGen-INIT] Throttling: " + chunksPerTick + " chunks/tick, delay: " + generationDelayTicks + " ticks");
        }
        
        // Start the chunk processor
        startChunkProcessor();
        
        // Check if Resource world is ALREADY loaded
        World existingWorld = Bukkit.getWorld(RESOURCE_WORLD_NAME);
        if (existingWorld != null && autoGenerateEnabled) {
            if (debugLogging) {
                plugin.getLogger().info("[OreGen-INIT] Resource world '" + RESOURCE_WORLD_NAME + "' is ALREADY LOADED! Starting generation now...");
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (debugLogging) {
                    plugin.getLogger().info("[OreGen-INIT] Calling startGeneration for already-loaded world...");
                }
                oreManager.startGeneration(existingWorld, 0, 0, 5);
                
                // Schedule test replacement
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (debugLogging) {
                        plugin.getLogger().info("[OreGen-INIT] RUNNING TEST REPLACEMENT at (-1100, 74, -334)");
                    }
                    oreManager.runTestReplacement(existingWorld);
                }, 200L);
            }, 100L);
        } else if (debugLogging) {
            plugin.getLogger().info("[OreGen-INIT] Resource world '" + RESOURCE_WORLD_NAME + "' not loaded yet, waiting for WorldLoadEvent...");
        }
    }
    
    /**
     * When a world loads, check if it should have ore generation
     */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (debugLogging) {
            plugin.getLogger().info("[OreGen-EVENT] WorldLoadEvent fired for: " + event.getWorld().getName());
        }
        
        if (!autoGenerateEnabled) {
            if (debugLogging) {
                plugin.getLogger().info("[OreGen-EVENT] Auto-generate is DISABLED - skipping");
            }
            return;
        }
        
        // Check if this is the Resource world
        if (debugLogging) {
            plugin.getLogger().info("[OreGen-EVENT] Checking world: '" + event.getWorld().getName() + "' against Resource world");
        }
        if (event.getWorld().getName().equals(RESOURCE_WORLD_NAME)) {
            if (debugLogging) {
                plugin.getLogger().info("[OreGen-EVENT] MATCH! Starting ore generation for Resource world: " + event.getWorld().getName());
            }
            
            // Start generation in this world
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (debugLogging) {
                    plugin.getLogger().info("[OreGen-EVENT] Calling oreManager.startGeneration()...");
                }
                oreManager.startGeneration(event.getWorld(), 0, 0, 5);
                
                if (debugLogging) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        plugin.getLogger().info("[OreGen-EVENT] RUNNING TEST REPLACEMENT at (-1100, 74, -334)");
                        oreManager.runTestReplacement(event.getWorld());
                    }, 200L);
                }
            }, 100L);
        } else if (debugLogging) {
            plugin.getLogger().info("[OreGen-EVENT] World '" + event.getWorld().getName() + "' is not Resource world");
        }
    }
    
    /**
     * When a chunk loads, check if it needs ore generation
     * Only processes new chunks or chunks that haven't been generated yet
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        
        // Log chunk load (only if debug enabled)
        if (debugLogging && chunk.getWorld().getName().equals(RESOURCE_WORLD_NAME)) {
            plugin.getLogger().info("[OreGen-CHUNK] ChunkLoadEvent for (" + chunk.getX() + "," + chunk.getZ() + ") isNewChunk=" + event.isNewChunk() + " autoGenerate=" + autoGenerateEnabled + " isGenerating=" + oreManager.isGenerating());
        }
        
        if (!autoGenerateEnabled) return;
        if (!oreManager.isGenerating()) return;
        
        // Only generate in the Resource world
        if (!chunk.getWorld().getName().equals(RESOURCE_WORLD_NAME)) return;
        
        // Only process newly generated chunks (not just loaded)
        if (!event.isNewChunk()) return;
        
        if (debugLogging) {
            plugin.getLogger().info("[OreGen-CHUNK] Processing NEW chunk (" + chunk.getX() + "," + chunk.getZ() + ") - queued for throttled generation");
        }
        
        // Add to queue instead of immediate processing
        chunkQueue.offer(chunk);
        pendingChunks.incrementAndGet();
    }
    
    /**
     * Start the throttled chunk processor
     */
    private void startChunkProcessor() {
        if (processorRunning) return;
        processorRunning = true;
        
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!autoGenerateEnabled || !oreManager.isGenerating()) {
                return;
            }
            
            int processed = 0;
            Chunk chunk;
            
            // Process up to chunksPerTick chunks from the queue
            while (processed < chunksPerTick && (chunk = chunkQueue.poll()) != null) {
                // Verify chunk is still loaded and in Resource world
                if (chunk.getWorld().getName().equals(RESOURCE_WORLD_NAME) && chunk.isLoaded()) {
                    oreManager.generateOresInChunk(chunk);
                    pendingChunks.decrementAndGet();
                    processed++;
                } else {
                    pendingChunks.decrementAndGet();
                }
            }
            
            // Log queue status periodically (every 100 chunks processed)
            int remaining = pendingChunks.get();
            if (processed > 0 && remaining % 100 == 0 && remaining > 0) {
                plugin.getLogger().info("[OreGen] Queue status: " + remaining + " chunks pending, processed " + processed + " this tick");
            }
        }, generationDelayTicks, generationDelayTicks);
    }
    
    /**
     * Get the number of chunks pending in the queue
     */
    public int getPendingChunks() {
        return pendingChunks.get();
    }
}
