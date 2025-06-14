package net.survivalfun.core.listeners.jobs;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Creeper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Particle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class CreeperExplosionListener implements Listener {
    private static CreeperExplosionListener instance;
    private final JavaPlugin plugin;
    private final Map<Integer, List<RegenerationTask>> regenerationLayers = new ConcurrentHashMap<>();
    private final Set<Location> pendingRegeneration = Collections.synchronizedSet(new HashSet<>());
    private final Queue<Integer> layerQueue = new LinkedList<>();
    private boolean isProcessingLayers = false;
    private int regenSpeed = 5; // Default speed (1-10)

    // Time in ticks (20 ticks = 1 second)
    private static final int BASE_PLAYER_PLACED_DELAY = 10 * 20;     // 10 seconds base delay for player-placed
    private static final int BASE_NATURAL_DELAY = 30 * 20;            // 30 seconds base delay for natural
    private static final int LAYER_DELAY = 20;                         // 1-second delay between layers
    private static final int MIN_DELAY = 10;                           // Minimum delay between blocks (ticks)

    private static class RegenerationTask {
        final Location location;
        final BlockState state;
        final boolean isPlayerPlaced;
        final int baseDelay;

        RegenerationTask(Location location, BlockState state, boolean isPlayerPlaced, int baseDelay) {
            this.location = location;
            this.state = state;
            this.isPlayerPlaced = isPlayerPlaced;
            this.baseDelay = baseDelay;
        }
    }

    public CreeperExplosionListener(JavaPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        reloadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static CreeperExplosionListener getInstance() {
        return instance;
    }

    public void reloadConfig() {
        // Load the regeneration speed from config (0-10, where 0 is disabled and 10 is fastest)
        regenSpeed = plugin.getConfig().getInt("creeper-explosion-regen-speed", 5);
        regenSpeed = Math.max(0, Math.min(10, regenSpeed)); // Clamp between 0-10

        // Log the current regen speed
        if (regenSpeed == 0) {
            plugin.getLogger().info("Creeper explosion block regeneration is disabled");
        } else {
            plugin.getLogger().info("Creeper explosion regeneration speed set to: " + regenSpeed);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreeperExplode(EntityExplodeEvent event) {
        // Check if regeneration is disabled
        if (regenSpeed == 0) {
            return;
        }

        // Check if the exploding entity is a creeper
        if (!(event.getEntity() instanceof Creeper)) {
            return;
        }

        // Store the blocks that were destroyed
        List<Block> blocks = new ArrayList<>(event.blockList());
        if (blocks.isEmpty()) {
            return;
        }

        // Find the minimum Y-level among all affected blocks
        int minY = blocks.stream()
            .mapToInt(Block::getY)
            .min()
            .orElse(0);

        // Sort blocks by Y level (lowest first) to regenerate from bottom up
        blocks.sort(Comparator.comparingInt(Block::getY));

        // Log the explosion
        plugin.getLogger().info(String.format("Creeper explosion at %s destroyed %d blocks (minY=%d)",
            event.getLocation().toString(), blocks.size(), minY));

        // Group blocks by Y-level
        Map<Integer, List<Block>> blocksByLayer = new HashMap<>();
        for (Block block : blocks) {
            // Skip air blocks (shouldn't happen, but just in case)
            if (block.getType() == Material.AIR) {
                continue;
            }
            blocksByLayer.computeIfAbsent(block.getY(), k -> new ArrayList<>()).add(block);
        }

        // Process each layer
        for (Map.Entry<Integer, List<Block>> entry : blocksByLayer.entrySet()) {
            int yLevel = entry.getKey();
            List<Block> layerBlocks = entry.getValue();

            // Create regeneration tasks for this layer
            for (Block block : layerBlocks) {
                BlockState state = block.getState();
                Location location = block.getLocation();
                boolean isPlayerPlaced = block.getBlockData() != null &&
                                       !block.getBlockData().getAsString().isEmpty();

                // Calculate base delay based on Y-level (higher Y = more delay)
                int baseDelay = (yLevel - minY) * 2; // 2 ticks per block higher than minY

                // Add to the layer's task list
                regenerationLayers.computeIfAbsent(yLevel, k -> new ArrayList<>())
                    .add(new RegenerationTask(location, state, isPlayerPlaced, baseDelay));
                pendingRegeneration.add(location);
            }

            // Add to layer queue if not already there
            synchronized (layerQueue) {
                if (!layerQueue.contains(yLevel)) {
                    layerQueue.add(yLevel);
                }
            }
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
            int currentLayer = layerQueue.poll();
            List<RegenerationTask> currentLayerTasks = regenerationLayers.remove(currentLayer);

            if (currentLayerTasks == null || currentLayerTasks.isEmpty()) {
                // No tasks in this layer, move to next
                processNextLayer();
                return;
            }

            isProcessingLayers = true;

            // Process all blocks in this layer
            for (RegenerationTask task : currentLayerTasks) {
                // Skip if block was already placed back
                if (task.location.getBlock().getType() != Material.AIR) {
                    pendingRegeneration.remove(task.location);
                    continue;
                }

                // Calculate delay with some randomness (0-10 ticks)
                int randomDelay = ThreadLocalRandom.current().nextInt(10);
                int delay = calculateRegenDelay(task.baseDelay, task.isPlayerPlaced) + randomDelay;

                // Schedule the block regeneration
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (task.location.getBlock().getType() == Material.AIR) {
                            // Play regeneration effect
                            playRegenEffect(task.location);

                            // Actually regenerate the block
                            task.state.update(true, false);
                            pendingRegeneration.remove(task.location);
                        }
                    }
                }.runTaskLater(plugin, delay);
            }

            // Schedule processing of next layer after a delay
            new BukkitRunnable() {
                @Override
                public void run() {
                    processNextLayer();
                }
            }.runTaskLater(plugin, LAYER_DELAY);
        }
    }

    private int calculateRegenDelay(int baseDelay, boolean isPlayerPlaced) {
        int baseTime = isPlayerPlaced ? BASE_PLAYER_PLACED_DELAY : BASE_NATURAL_DELAY;
        // Scale delay based on regenSpeed (1-10, where 10 is fastest)
        // At speed 1: full delay, at speed 10: 1/5th delay
        // Using a square root function to make the speed progression feel more natural
        double speedFactor = 1.0 - (Math.sqrt(regenSpeed) / 3.16); // ~0.7 at speed 1, ~0 at speed 10
        int delay = (int) (baseTime * speedFactor) + baseDelay + MIN_DELAY;
        return Math.max(MIN_DELAY, delay);  // Ensure minimum delay
    }

    private void playRegenEffect(Location location) {
        try {
            // Play heart particle effect at the block location
            location.getWorld().spawnParticle(
                Particle.HEART,
                location.getX() + 0.5,  // Center of block
                location.getY() + 0.5,
                location.getZ() + 0.5,
                3,  // Number of particles
                0.2, // X offset
                0.2, // Y offset
                0.2, // Z offset
                0.1  // Speed
            );
            
            // Play a subtle regeneration sound
            location.getWorld().playSound(
                location,
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.5f, // Volume
                1.0f  // Pitch
            );
        } catch (Exception e) {
            // Log any errors but don't crash
            plugin.getLogger().warning("Error playing regeneration effect: " + e.getMessage());
        }
    }
}
