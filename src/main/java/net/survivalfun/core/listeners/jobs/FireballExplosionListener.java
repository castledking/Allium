package net.survivalfun.core.listeners.jobs; // Adjusted package for now

import net.survivalfun.core.PluginStart;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Fireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class FireballExplosionListener implements Listener {

    private final PluginStart plugin;
    private final Map<Integer, List<RegenerationTask>> regenerationLayers = new ConcurrentHashMap<>();
    private final Set<Location> pendingRegenerationLocations = Collections.synchronizedSet(new HashSet<>());
    private final Queue<Integer> layerQueue = new LinkedList<>();
    private boolean isProcessingLayers = false;

    private final Map<UUID, Integer> pendingBlocksPerExplosion = new ConcurrentHashMap<>();
    private final Map<UUID, ExplosionDetails> explosionDataMap = new ConcurrentHashMap<>();

    // Configuration constants (can be moved to config file)
    private static final float MIN_YIELD_FOR_REGEN = 2.0f;
    private static final int TICKS_PER_Y_LEVEL_DIFFERENCE = 2; // Delay added per block of height from explosion minY
    private static final int RANDOM_DELAY_MAX_TICKS = 10;      // Max random delay added to each block regen
    private static final int DELAY_BETWEEN_LAYERS_TICKS = 15;  // Delay before processing the next Y-layer
    private static final int MIN_REGEN_DELAY_TICKS = 5;        // Minimum delay for any block regeneration

    private static class ExplosionDetails {
        final Location center;
        final float yield;
        final World world;

        ExplosionDetails(Location center, float yield, World world) {
            this.center = center;
            this.yield = yield;
            this.world = world;
        }
    }

    private static class RegenerationTask {
        final Location location;
        final BlockState state;
        final int baseDelay;
        final UUID explosionId;

        RegenerationTask(Location location, BlockState state, int baseDelay, UUID explosionId) {
            this.location = location;
            this.state = state;
            this.baseDelay = baseDelay;
            this.explosionId = explosionId;
        }
    }

    public FireballExplosionListener(PluginStart plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("FireballExplosionListener enabled for fireballs with power > " + MIN_YIELD_FOR_REGEN);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireballExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Fireball)) {
            return;
        }

        Fireball fireball = (Fireball) event.getEntity();
        if (fireball.getYield() <= MIN_YIELD_FOR_REGEN) {
            return;
        }

        List<Block> destroyedBlocks = new ArrayList<>(event.blockList());
        if (destroyedBlocks.isEmpty()) {
            return;
        }

        UUID explosionId = UUID.randomUUID();
        Location explosionCenter = fireball.getLocation();
        explosionDataMap.put(explosionId, new ExplosionDetails(explosionCenter, fireball.getYield(), fireball.getWorld()));

        // Sort blocks by Y level (lowest first) to regenerate from bottom up
        destroyedBlocks.sort(Comparator.comparingInt(Block::getY));

        int minY = destroyedBlocks.get(0).getY();
        int tasksCreatedForThisExplosion = 0;

        // Group blocks by Y-level and create tasks
        Map<Integer, List<Block>> blocksByLayer = new HashMap<>();
        for (Block block : destroyedBlocks) {
            if (block.getType() == Material.AIR && !block.isLiquid()) { // Already air and not a liquid, skip (shouldn't be in list often)
                continue;
            }
            // Avoid processing a block location if it's already pending from another recent event
            if (pendingRegenerationLocations.contains(block.getLocation())) {
                continue;
            }
            blocksByLayer.computeIfAbsent(block.getY(), k -> new ArrayList<>()).add(block);
        }

        for (Map.Entry<Integer, List<Block>> entry : blocksByLayer.entrySet()) {
            int yLevel = entry.getKey();
            List<Block> layerBlocks = entry.getValue();

            for (Block block : layerBlocks) {
                BlockState originalState = block.getState(); // Capture state before it's changed by explosion
                int baseDelay = (yLevel - minY) * TICKS_PER_Y_LEVEL_DIFFERENCE;

                regenerationLayers.computeIfAbsent(yLevel, k -> new ArrayList<>()).add(
                        new RegenerationTask(block.getLocation(), originalState, baseDelay, explosionId)
                );
                pendingRegenerationLocations.add(block.getLocation());
                tasksCreatedForThisExplosion++;
            }

            synchronized (layerQueue) {
                if (!layerQueue.contains(yLevel)) {
                    layerQueue.add(yLevel);
                    // Sort queue to ensure lowest Y levels are processed first
                    ((LinkedList<Integer>) layerQueue).sort(Comparator.naturalOrder());
                }
            }
        }

        if (tasksCreatedForThisExplosion > 0) {
            pendingBlocksPerExplosion.put(explosionId, tasksCreatedForThisExplosion);
            plugin.getLogger().info(String.format("Fireball explosion (ID: %s) at %s (Power: %.1f) destroyed %d blocks. Scheduling regeneration.",
                    explosionId.toString().substring(0, 8), formatLocation(explosionCenter), fireball.getYield(), tasksCreatedForThisExplosion));
        } else {
            explosionDataMap.remove(explosionId); // No tasks, clean up
            return;
        }

        // Start processing layers if not already running
        if (!isProcessingLayers) {
            processNextLayer();
        }
    }

    private void processNextLayer() {
        synchronized (layerQueue) {
            if (layerQueue.isEmpty()) {
                isProcessingLayers = false;
                return;
            }

            // Get the next layer to process (lowest Y-level first)
            int currentYLevel = layerQueue.poll();
            List<RegenerationTask> currentLayerTasks = regenerationLayers.remove(currentYLevel);

            if (currentLayerTasks == null || currentLayerTasks.isEmpty()) {
                processNextLayer(); // Move to next layer if this one was empty or already processed
                return;
            }

            isProcessingLayers = true;

            for (RegenerationTask task : currentLayerTasks) {
                int randomDelay = ThreadLocalRandom.current().nextInt(RANDOM_DELAY_MAX_TICKS + 1);
                int totalDelay = Math.max(MIN_REGEN_DELAY_TICKS, task.baseDelay + randomDelay);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Block currentBlock = task.location.getBlock();
                        // Regenerate if it's air or fire (fireballs cause fire)
                        if (currentBlock.getType() == Material.AIR || currentBlock.getType() == Material.FIRE) {
                            playRegenEffect(task.location);
                            task.state.update(true, false); // Restore block state
                        }
                        pendingRegenerationLocations.remove(task.location);

                        // Check if this was the last block for this specific explosion
                        Integer remaining = pendingBlocksPerExplosion.computeIfPresent(task.explosionId, (id, count) -> count - 1);
                        if (remaining != null && remaining == 0) {
                            pendingBlocksPerExplosion.remove(task.explosionId);
                            ExplosionDetails details = explosionDataMap.remove(task.explosionId);
                            if (details != null) {
                                plugin.getLogger().info("Regeneration complete for fireball explosion " + task.explosionId.toString().substring(0,8) + ". Removing fire.");
                                removeFireWithWorldEdit(details);
                            }
                        }
                    }
                }.runTaskLater(plugin, totalDelay);
            }

            // Schedule processing of the next layer
            new BukkitRunnable() {
                @Override
                public void run() {
                    processNextLayer();
                }
            }.runTaskLater(plugin, DELAY_BETWEEN_LAYERS_TICKS);
        }
    }

    private void playRegenEffect(Location loc) {
        World world = loc.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.DUST, loc.clone().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, new Particle.DustOptions(Color.LIME, 1.0f));
            world.playSound(loc, Sound.BLOCK_GRASS_PLACE, 0.5f, 1.5f);
        }
    }

    private void removeFireWithWorldEdit(ExplosionDetails details) {
        Plugin worldEditPlugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (worldEditPlugin == null || !worldEditPlugin.isEnabled()) {
            plugin.getLogger().warning("WorldEdit not found or not enabled. Cannot remove fire for explosion at " + formatLocation(details.center));
            return;
        }

        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        String worldName = details.world.getName();
        int radius = (int) Math.ceil(details.yield);
        radius = Math.max(1, radius); // Ensure radius is at least 1

        Location center = details.center;
        int x1 = center.getBlockX() - radius;
        int y1 = center.getBlockY() - radius;
        int z1 = center.getBlockZ() - radius;
        int x2 = center.getBlockX() + radius;
        int y2 = center.getBlockY() + radius;
        int z2 = center.getBlockZ() + radius;

        // Ensure Y coordinates are within world limits
        y1 = Math.max(details.world.getMinHeight(), y1);
        y2 = Math.min(details.world.getMaxHeight() - 1, y2);

        plugin.getLogger().info(String.format("Attempting to remove fire in region (%d,%d,%d) to (%d,%d,%d) in world '%s' for explosion at %s",
                x1, y1, z1, x2, y2, z2, worldName, formatLocation(center)));

        Bukkit.dispatchCommand(console, "/world " + worldName); 
        Bukkit.dispatchCommand(console, "/sel cuboid"); 
        Bukkit.dispatchCommand(console, String.format("/pos1 %d,%d,%d", x1, y1, z1));
        Bukkit.dispatchCommand(console, String.format("/pos2 %d,%d,%d", x2, y2, z2));
        Bukkit.dispatchCommand(console, "/replace fire air");
        Bukkit.dispatchCommand(console, "/sel"); 

        plugin.getLogger().info("WorldEdit fire removal commands dispatched for explosion at " + formatLocation(details.center));
    }

    private String formatLocation(Location loc) {
        if (loc == null) return "null";
        return String.format("%s: %.1f, %.1f, %.1f", loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
    }
}
