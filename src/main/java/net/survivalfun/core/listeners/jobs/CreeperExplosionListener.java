package net.survivalfun.core.listeners.jobs;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import net.survivalfun.core.PluginStart;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class CreeperExplosionListener implements Listener {
    private static CreeperExplosionListener instance;
    private final JavaPlugin plugin;
    private final Map<Integer, List<RegenerationTask>> regenerationLayers = new ConcurrentHashMap<>();
    private final Set<Location> pendingRegeneration = Collections.synchronizedSet(new HashSet<>());
    private final Queue<Integer> layerQueue = new LinkedList<>();
    private boolean isProcessingLayers = false;
    private int regenSpeed = 5; // Default speed (1-10)

    // Time in ticks (20 ticks = 1 second) - Ultra-fast regeneration
    private static final int BASE_PLAYER_PLACED_DELAY = 20;          // 1 second base delay for player-placed
    private static final int BASE_NATURAL_DELAY = 30;                // 1.5 seconds base delay for natural  
    private static final int LAYER_DELAY = 20;                       // 20 ticks delay between layers (1 second)
    private static final int MIN_DELAY = 1;                          // Minimum delay between blocks (1 tick)

    // Suffocation protection system
    private final Map<UUID, Long> playersInRegenZone = new ConcurrentHashMap<>();
    private final Set<Location> activeRegenerationAreas = Collections.synchronizedSet(new HashSet<>());
    private static final long SUFFOCATION_PROTECTION_DURATION = 10 * 1000; // 10 seconds in milliseconds

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

    public CreeperExplosionListener(PluginStart plugin) {
        this.plugin = plugin;
        
        // Unregister the previous instance if it exists
        if (instance != null) {
            HandlerList.unregisterAll(instance);
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("[SFCore] Unregistered previous CreeperExplosionListener instance");
            }
        }
        
        instance = this;
        reloadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        // Start cleanup task for player tracking
        startCleanupTask();
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

        // Debug: Check if we're already processing layers
        if (isProcessingLayers) {
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().warning(String.format("[SFCore] WARNING: New explosion while already processing layers! Queue size: %d", layerQueue.size()));
            }
            // Don't cancel - just log the warning and continue processing
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
        if (plugin.getConfig().getBoolean("debug-mode")) {
            plugin.getLogger().info(String.format("Creeper explosion at %s destroyed %d blocks (minY=%d)",
                event.getLocation().toString(), blocks.size(), minY));
        }

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
            int skippedCount = 0;

            // Create regeneration tasks for this layer
            for (Block block : layerBlocks) {
                BlockState state = block.getState();
                Location location = block.getLocation();
                
                // Skip if this block is already being regenerated
                if (pendingRegeneration.contains(location)) {
                    skippedCount++;
                    continue;
                }
                
                boolean isPlayerPlaced = block.getBlockData() != null &&
                                       !block.getBlockData().getAsString().isEmpty();

                // Calculate base delay based on Y-level (higher Y = more delay)
                int baseDelay = (yLevel - minY) * 10; // 10 ticks (0.5 seconds) per block higher than minY

                // Add to the layer's task list
                regenerationLayers.computeIfAbsent(yLevel, k -> new ArrayList<>())
                    .add(new RegenerationTask(location, state, isPlayerPlaced, baseDelay));
                pendingRegeneration.add(location);
                activeRegenerationAreas.add(location);
            }
            
            // Log summary of skipped blocks for this layer
            if (skippedCount > 0) {
                if (plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info(String.format("[SFCore] Layer Y=%d: Skipped %d blocks already being regenerated", yLevel, skippedCount));
                }
            }
        }

        // Add layers to queue in bottom-to-top order (sorted by Y-level)
        synchronized (layerQueue) {
            // Get all Y-levels and sort them (lowest first)
            List<Integer> sortedYLevels = new ArrayList<>(blocksByLayer.keySet());
            sortedYLevels.sort(Integer::compareTo); // Sort ascending - lowest Y first
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info(String.format("[SFCore] Adding layers to queue: %s", sortedYLevels.toString()));
            }
            
            // Add each Y-level to the queue in the correct order
            for (int yLevel : sortedYLevels) {
                layerQueue.add(yLevel);
                if (plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info(String.format("[SFCore] Added layer Y=%d to queue (queue size: %d)", yLevel, layerQueue.size()));
                }
            }
        }

        // Start processing layers if not already running - with a delay to let players see the destruction
        if (!isProcessingLayers) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    processNextLayer();
                }
            }.runTaskLater(plugin, 100); // 5 second delay before regeneration starts
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.SUFFOCATION) {
            Entity entity = event.getEntity();
            if (entity instanceof Player) {
                Player player = (Player) entity;
                if (isPlayerInRegenerationZone(player)) {
                    // Cancel the suffocation damage
                    event.setCancelled(true);
                    
                    // Teleport player to safety
                    teleportPlayerToSafety(player);
                }
            }
        }
    }
    
    private boolean isPlayerInRegenerationZone(Player player) {
        Location playerLoc = player.getLocation();
        
        // Check if player is within 2 blocks of any regenerating area
        for (Location regenLoc : activeRegenerationAreas) {
            if (regenLoc.getWorld().equals(playerLoc.getWorld())) {
                double distance = regenLoc.distance(playerLoc);
                if (distance <= 3.0) { // 3 block radius
                    // Track this player for 10 seconds
                    playersInRegenZone.put(player.getUniqueId(), System.currentTimeMillis());
                    return true;
                }
            }
        }
        
        // Clean up old entries
        playersInRegenZone.entrySet().removeIf(entry -> 
            System.currentTimeMillis() - entry.getValue() > SUFFOCATION_PROTECTION_DURATION);
        
        return false;
    }
    
    private void teleportPlayerToSafety(Player player) {
        Location playerLoc = player.getLocation();
        Location safeLoc = findTopOfRegenerationQueue(playerLoc);
        
        if (safeLoc != null) {
            // Add debug logging
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info(String.format("[SFCore] Teleporting player %s from %s to %s for regeneration safety", 
                    player.getName(), 
                    String.format("%.1f,%.1f,%.1f", playerLoc.getX(), playerLoc.getY(), playerLoc.getZ()),
                    String.format("%.1f,%.1f,%.1f", safeLoc.getX(), safeLoc.getY(), safeLoc.getZ())));
            }
            
            // Use async teleport to avoid anti-cheat issues
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.teleport(safeLoc);
                    
                    // Play teleport effect
                    safeLoc.getWorld().spawnParticle(
                        Particle.PORTAL,
                        safeLoc.getX(),
                        safeLoc.getY() + 1,
                        safeLoc.getZ(),
                        10, // Reduced particles
                        0.3, // X offset
                        0.3, // Y offset  
                        0.3, // Z offset
                        0.05  // Reduced speed
                    );
                    
                    safeLoc.getWorld().playSound(
                        safeLoc,
                        Sound.ENTITY_ENDERMAN_TELEPORT,
                        0.3f, // Reduced volume
                        1.5f  // Higher pitch
                    );
                    
                    // Start the loving heart particle effect for 5 seconds
                    startHeartParticleEffect(player);
                }
            }.runTask(plugin); // Run on main thread
        } else {
            // If no regeneration queue found, just push player up slightly
            Location pushLoc = playerLoc.clone().add(0, 5, 0); // Increased from 2 to 5 blocks
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info(String.format("[SFCore] No regen queue found, pushing player %s up 5 blocks to %s", 
                    player.getName(), 
                    String.format("%.1f,%.1f,%.1f", pushLoc.getX(), pushLoc.getY(), pushLoc.getZ())));
            }
            player.teleport(pushLoc);
            
            // Also show love for fallback teleportation
            startHeartParticleEffect(player);
        }
    }
    
    private void startHeartParticleEffect(Player player) {
        new BukkitRunnable() {
            int ticksElapsed = 0;
            final int maxTicks = 100; // 5 seconds (20 ticks per second)
            
            @Override
            public void run() {
                // Check if player is still online
                if (!player.isOnline()) {
                    this.cancel();
                    return;
                }
                
                // Stop after 5 seconds
                if (ticksElapsed >= maxTicks) {
                    this.cancel();
                    return;
                }
                
                Location playerLoc = player.getLocation();
                
                // Create a lovely heart pattern around the player
                // Spawn hearts every 4 ticks (5 times per second) for a nice gentle effect
                if (ticksElapsed % 4 == 0) {
                    // Create a circular pattern of hearts around the player
                    double angle = (ticksElapsed * 0.2) % (2 * Math.PI); // Rotating pattern
                    double radius = 1.5; // 1.5 blocks around player
                    
                    // Main heart above player
                    playerLoc.getWorld().spawnParticle(
                        Particle.HEART,
                        playerLoc.getX(),
                        playerLoc.getY() + 2.2,
                        playerLoc.getZ(),
                        1, // Single heart
                        0.1, 0.1, 0.1, // Small spread
                        0.0 // No speed
                    );
                    
                    // Rotating hearts around player
                    for (int i = 0; i < 3; i++) {
                        double heartAngle = angle + (i * 2 * Math.PI / 3); // 3 hearts, 120° apart
                        double x = playerLoc.getX() + Math.cos(heartAngle) * radius;
                        double z = playerLoc.getZ() + Math.sin(heartAngle) * radius;
                        double y = playerLoc.getY() + 1.5 + Math.sin(ticksElapsed * 0.1 + i) * 0.3; // Gentle bobbing
                        
                        playerLoc.getWorld().spawnParticle(
                            Particle.HEART,
                            x, y, z,
                            1, // Single heart
                            0.05, 0.05, 0.05, // Very small spread
                            0.0 // No speed
                        );
                    }
                }
                
                ticksElapsed++;
            }
        }.runTaskTimer(plugin, 0, 1); // Run every tick for smooth animation
    }
    
    private Location findTopOfRegenerationQueue(Location playerLocation) {
        World world = playerLocation.getWorld();
        List<Location> topBlocks = new ArrayList<>();
        
        // Find all the highest Y-level blocks in the regeneration areas near the player
        Map<String, Integer> highestYByColumn = new HashMap<>();
        for (Location regenLoc : activeRegenerationAreas) {
            if (regenLoc.getWorld().equals(world)) {
                double distance = regenLoc.distance(playerLocation);
                if (distance <= 10.0) { // Within 10 block radius of player
                    String columnKey = regenLoc.getBlockX() + "," + regenLoc.getBlockZ();
                    int currentY = regenLoc.getBlockY();
                    
                    // Track the highest Y for each X,Z column
                    highestYByColumn.merge(columnKey, currentY, Integer::max);
                }
            }
        }
        
        // Convert the highest blocks to locations
        for (Map.Entry<String, Integer> entry : highestYByColumn.entrySet()) {
            String[] coords = entry.getKey().split(",");
            int x = Integer.parseInt(coords[0]);
            int z = Integer.parseInt(coords[1]);
            int y = entry.getValue();
            // Fix: Correct parameter order is (world, x, y, z)
            topBlocks.add(new Location(world, x, y, z));
        }
        
        // Find the best location with 2 blocks of clearance
        for (Location topBlock : topBlocks) {
            // Place player 2 blocks above the top regenerating block for safety
            Location safeSpot = topBlock.clone().add(0.5, 2, 0.5); // Center of block + 2 blocks up
            if (hasProperClearance(safeSpot)) {
                return safeSpot;
            }
        }
        
        // If no location with clearance found, return the highest one anyway with extra height
        if (!topBlocks.isEmpty()) {
            // Sort by Y level (highest first)
            topBlocks.sort((a, b) -> Integer.compare(b.getBlockY(), a.getBlockY()));
            Location highest = topBlocks.get(0);
            return new Location(world, highest.getX() + 0.5, highest.getY() + 3, highest.getZ() + 0.5);
        }
        
        return null;
    }
    
    private boolean hasProperClearance(Location location) {
        World world = location.getWorld();
        
        // Check 2 blocks above the location for clearance
        for (int i = 0; i < 2; i++) {
            Block checkBlock = world.getBlockAt(location.getBlockX(), location.getBlockY() + i, location.getBlockZ());
            Material material = checkBlock.getType();
            
            // Allow air and passable blocks
            if (material != Material.AIR && !isPassableBlock(material)) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean isPassableBlock(Material material) {
        if (material.isCollidable()) {
            return false;
        }
        return true;
    }

    private boolean isContainerMaterial(Material material) {
        switch (material) {
            case CHEST:
            case TRAPPED_CHEST:
            case BARREL:
            case DISPENSER:
            case DROPPER:
            case HOPPER:
            case FURNACE:
            case BLAST_FURNACE:
            case SMOKER:
            case BREWING_STAND:
            case BEACON:
            case CAMPFIRE:
            case SOUL_CAMPFIRE:
            case LECTERN:
            case COMPOSTER:
            case CAULDRON:
            case LAVA_CAULDRON:
            case WATER_CAULDRON:
            case POWDER_SNOW_CAULDRON:
            case ENDER_CHEST:
            case SHULKER_BOX:
            case WHITE_SHULKER_BOX:
            case ORANGE_SHULKER_BOX:
            case MAGENTA_SHULKER_BOX:
            case LIGHT_BLUE_SHULKER_BOX:
            case YELLOW_SHULKER_BOX:
            case LIME_SHULKER_BOX:
            case PINK_SHULKER_BOX:
            case GRAY_SHULKER_BOX:
            case LIGHT_GRAY_SHULKER_BOX:
            case CYAN_SHULKER_BOX:
            case PURPLE_SHULKER_BOX:
            case BLUE_SHULKER_BOX:
            case BROWN_SHULKER_BOX:
            case GREEN_SHULKER_BOX:
            case RED_SHULKER_BOX:
            case BLACK_SHULKER_BOX:
                return true;
            default:
                return false;
        }
    }

    private void processNextLayer() {
        synchronized (layerQueue) {
            if (layerQueue.isEmpty()) {
                if (plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().info("[SFCore] Layer queue is empty, stopping regeneration processing");
                }
                isProcessingLayers = false;
                return;
            }

            // Get the next layer to process (lowest Y-level first)
            int currentLayer = layerQueue.poll();
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info(String.format("[SFCore] Processing layer Y=%d (queue remaining: %d)", currentLayer, layerQueue.size()));
            }
            
            // Get and remove tasks for this layer
            List<RegenerationTask> currentLayerTasks = regenerationLayers.remove(currentLayer);

            if (currentLayerTasks == null || currentLayerTasks.isEmpty()) {
                if (plugin.getConfig().getBoolean("debug-mode")) {
                    plugin.getLogger().warning(String.format("[SFCore] No tasks found for layer Y=%d, skipping to next layer", currentLayer));
                }
                // No tasks in this layer, move to next
                processNextLayer();
                return;
            }
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info(String.format("[SFCore] Found %d tasks for layer Y=%d", currentLayerTasks.size(), currentLayer));
            }
            isProcessingLayers = true;

            // Track completion of this layer
            final AtomicInteger remainingTasks = new AtomicInteger(currentLayerTasks.size());
            final int totalTasks = currentLayerTasks.size();

            // Process all blocks in this layer
            for (RegenerationTask task : currentLayerTasks) {
                // Skip if block was already placed back
                if (task.location.getBlock().getType() != Material.AIR) {
                    pendingRegeneration.remove(task.location);
                    // Decrement counter for skipped tasks
                    if (remainingTasks.decrementAndGet() == 0) {
                        // All tasks completed, move to next layer
                        scheduleNextLayer();
                    }
                    continue;
                }

                // Calculate delay with minimal randomness for faster regeneration
                int randomDelay = ThreadLocalRandom.current().nextInt(3); // 0-2 ticks only
                int delay = calculateRegenDelay(task.baseDelay, task.isPlayerPlaced) + randomDelay;

                // Schedule the block regeneration
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (task.location.getBlock().getType() == Material.AIR) {
                            // Play regeneration effect
                            playRegenEffect(task.location);

                            // Actually regenerate the block first
                            BlockState state = task.state;
                            state.update(true, false);
                            
                            // Then clear container inventory to prevent duping (must be done after placing)
                            if (state instanceof Container && isContainerMaterial(state.getType())) {
                                // Get the newly placed block and clear its inventory
                                Block placedBlock = task.location.getBlock();
                                if (placedBlock.getState() instanceof Container) {
                                    Container container = (Container) placedBlock.getState();
                                    Inventory inventory = container.getInventory();
                                    inventory.clear();
                                    
                                    // Debug logging for container regeneration
                                    if (plugin.getConfig().getBoolean("debug-mode")) {
                                        plugin.getLogger().info(String.format("[SFCore] Cleared inventory of regenerated container: %s at %s to prevent duping", 
                                            state.getType().name(), 
                                            String.format("%.0f,%.0f,%.0f", task.location.getX(), task.location.getY(), task.location.getZ())));
                                    }
                                }
                            }
                            
                            pendingRegeneration.remove(task.location);
                            activeRegenerationAreas.remove(task.location);
                        }
                        
                        // Check if this was the last task in the layer
                        int remaining = remainingTasks.decrementAndGet();
                        if (remaining == 0) {
                            if (plugin.getConfig().getBoolean("debug-mode")) {
                                plugin.getLogger().info(String.format("[SFCore] Layer Y=%d completed (%d/%d tasks), moving to next layer", 
                                    currentLayer, totalTasks, totalTasks));
                            }
                            // All tasks in this layer completed, move to next layer
                            scheduleNextLayer();
                        }
                    }
                }.runTaskLater(plugin, delay);
            }
        }
    }

    private void scheduleNextLayer() {
        // Add a small delay before processing next layer for visual effect
        new BukkitRunnable() {
            @Override
            public void run() {
                processNextLayer();
            }
        }.runTaskLater(plugin, LAYER_DELAY);
    }

    private int calculateRegenDelay(int baseDelay, boolean isPlayerPlaced) {
        int baseTime = isPlayerPlaced ? BASE_PLAYER_PLACED_DELAY : BASE_NATURAL_DELAY;
        
        // Balanced speed scaling for good visual pacing
        // Speed 1: ~90% of base delay (slower)
        // Speed 5: ~50% of base delay (balanced)  
        // Speed 10: ~10% of base delay (fast but visible)
        double speedFactor = Math.max(0.1, 1.0 - (regenSpeed * 0.08));
        
        int delay = (int) (baseTime * speedFactor) + baseDelay;
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
    
    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                playersInRegenZone.entrySet().removeIf(entry -> 
                    currentTime - entry.getValue() > SUFFOCATION_PROTECTION_DURATION);
            }
        }.runTaskTimer(plugin, 20 * 5, 20 * 5); // Run every 5 seconds
    }
}
