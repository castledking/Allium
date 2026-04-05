package net.survivalfun.core.managers.world;

import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages custom ore generation for Silver and Galena ores.
 * Uses Oraxen noteblocks to represent custom ore blocks.
 */
public class OreGenerationManager {
    
    private final PluginStart plugin;
    private final Map<String, OreConfig> oreConfigs;
    private final Random random;
    private final boolean debugLogging;
    private BukkitTask generationTask;
    private final AtomicInteger generatedChunks = new AtomicInteger(0);
    private final AtomicLong generatedOres = new AtomicLong(0);
    private volatile boolean isGenerating = false;
    private volatile boolean paused = false;
    
    // Persistent data key to track generated chunks
    private final NamespacedKey ALLIUM_GENERATED_KEY;
    
    // Default ore configurations
    private static final int DEFAULT_VEIN_SIZE = 6;           // 6 ores per vein on average
    private static final int DEFAULT_MIN_HEIGHT = -64;
    private static final int DEFAULT_MAX_HEIGHT = 64;
    private static final double DEFAULT_CHANCE_PER_CHUNK = 1.0; // 100% chance per chunk
    private static final int DEFAULT_VEINS_PER_CHUNK = 2;     // 2 veins per chunk on average
    
    public OreGenerationManager(PluginStart plugin) {
        this.plugin = plugin;
        this.oreConfigs = new HashMap<>();
        this.random = new Random();
        this.debugLogging = plugin.getConfig().getBoolean("ore-generation.debug", false);
        this.ALLIUM_GENERATED_KEY = new NamespacedKey(plugin, "allium_ore_generated");
        loadConfig();
    }
    
    /**
     * Load ore generation configuration from config.yml
     */
    private void loadConfig() {
        ConfigurationSection oreSection = plugin.getConfig().getConfigurationSection("ore-generation");
        if (oreSection == null) {
            // Create default configurations with deepslate variants
            // Silver: mountainous Y=-8 to 118, non-mountainous Y=-56 to -8
            // Galena: all biomes Y=-56 to -8
            oreConfigs.put("silver", new OreConfig("silver", "silver_ore", "deepslate_silver_ore",
                    DEFAULT_VEIN_SIZE, -8, 118, DEFAULT_CHANCE_PER_CHUNK, DEFAULT_VEINS_PER_CHUNK));
            oreConfigs.put("galena", new OreConfig("galena", "galena_ore", "deepslate_galena_ore",
                    DEFAULT_VEIN_SIZE, -56, -8, DEFAULT_CHANCE_PER_CHUNK, DEFAULT_VEINS_PER_CHUNK));
            return;
        }
        
        for (String oreName : oreSection.getKeys(false)) {
            ConfigurationSection ore = oreSection.getConfigurationSection(oreName);
            if (ore == null) continue;
            
            String oraxenId = ore.getString("oraxen-id", oreName + "_ore");
            String deepslateId = ore.getString("deepslate-oraxen-id", "deepslate_" + oreName + "_ore");
            int veinSize = ore.getInt("vein-size", DEFAULT_VEIN_SIZE);
            int minHeight = ore.getInt("min-height", DEFAULT_MIN_HEIGHT);
            int maxHeight = ore.getInt("max-height", DEFAULT_MAX_HEIGHT);
            double chance = ore.getDouble("chance-per-chunk", DEFAULT_CHANCE_PER_CHUNK);
            int veinsPerChunk = ore.getInt("veins-per-chunk", DEFAULT_VEINS_PER_CHUNK);
            
            oreConfigs.put(oreName, new OreConfig(oreName, oraxenId, deepslateId, veinSize, minHeight, maxHeight, chance, veinsPerChunk));
        }
        
        // Ensure silver and galena exist with deepslate variants
        if (!oreConfigs.containsKey("silver")) {
            oreConfigs.put("silver", new OreConfig("silver", "silver_ore", "deepslate_silver_ore",
                    DEFAULT_VEIN_SIZE, DEFAULT_MIN_HEIGHT, DEFAULT_MAX_HEIGHT, DEFAULT_CHANCE_PER_CHUNK, DEFAULT_VEINS_PER_CHUNK));
        }
        if (!oreConfigs.containsKey("galena")) {
            oreConfigs.put("galena", new OreConfig("galena", "galena_ore", "deepslate_galena_ore",
                    DEFAULT_VEIN_SIZE, DEFAULT_MIN_HEIGHT, DEFAULT_MAX_HEIGHT, DEFAULT_CHANCE_PER_CHUNK, DEFAULT_VEINS_PER_CHUNK));
        }
    }
    
    /**
     * Generate ores in a specific chunk by replacing stone/deepslate
     * Only runs once per chunk (checked via persistent data container)
     * Uses rarity to avoid lag - only replaces a percentage of blocks
     */
    public void generateOresInChunk(Chunk chunk) {
        if (!isGenerating) {
            plugin.getLogger().fine("[OreGen] Skipping chunk (" + chunk.getX() + "," + chunk.getZ() + ") - generation not active");
            return;
        }
        
        // Check if chunk was already processed
        if (chunk.getPersistentDataContainer().has(ALLIUM_GENERATED_KEY, PersistentDataType.BYTE)) {
            plugin.getLogger().fine("[OreGen] Skipping chunk (" + chunk.getX() + "," + chunk.getZ() + ") - already generated");
            return;
        }
        
        if (debugLogging) {
            plugin.getLogger().info("[OreGen] Processing chunk (" + chunk.getX() + "," + chunk.getZ() + ") in world '" + chunk.getWorld().getName() + "'");
        }
        
        World world = chunk.getWorld();
        int chunkX = chunk.getX() << 4;
        int chunkZ = chunk.getZ() << 4;
        
        // Seed random with chunk coordinates for deterministic but varied generation
        Random chunkRandom = new Random(world.getSeed() + chunk.getX() * 341873128712L + chunk.getZ() * 132897987541L);
        
        int oresPlaced = 0;
        int stoneChecked = 0;
        int deepslateChecked = 0;
        
        // Process each ore type
        for (OreConfig config : oreConfigs.values()) {
            // Skip this ore type for this chunk based on chance
            if (chunkRandom.nextDouble() > config.chancePerChunk()) {
                plugin.getLogger().fine("[OreGen] Skipping " + config.name() + " in chunk (" + chunk.getX() + "," + chunk.getZ() + ") - failed chance check");
                continue;
            }
            
            // Determine how many veins to place (1-3 range, centered around veinsPerChunk)
            int numVeins = 1 + chunkRandom.nextInt(config.veinsPerChunk() + 1);
            if (debugLogging) {
                plugin.getLogger().info("[OreGen] Placing " + numVeins + " " + config.name() + " vein(s) in chunk (" + chunk.getX() + "," + chunk.getZ() + ")");
            }
            
            // Get effective height range based on ore type
            int effectiveMinHeight = config.minHeight();
            int effectiveMaxHeight = config.maxHeight();
            
            if (config.name().equals("silver")) {
                Biome biome = chunk.getBlock(0, 64, 0).getBiome();
                boolean isMountainous = isMountainousBiome(biome);
                if (!isMountainous) {
                    // Non-mountainous: restrict to Y=-8 to 56
                    effectiveMinHeight = -8;
                    effectiveMaxHeight = 56;
                    if (debugLogging) {
                        plugin.getLogger().info("[OreGen] Non-mountainous biome " + biome + ", silver restricted to Y=-8 to 56");
                    }
                } else {
                    if (debugLogging) {
                        plugin.getLogger().info("[OreGen] Mountainous biome " + biome + ", silver allowed Y=-8 to 118");
                    }
                }
            } else if (config.name().equals("galena")) {
                // Galena stone ore only spawns Y=-8 to 8 (transition layer)
                effectiveMinHeight = -8;
                effectiveMaxHeight = 8;
                if (debugLogging) {
                    plugin.getLogger().info("[OreGen] Galena restricted to Y=-8 to 8 (stone transition layer)");
                }
            } else if (config.name().equals("silver") && config.deepslateOraxenId().equals("deepslate_silver_ore")) {
                // Deepslate silver ore only spawns Y=-8 to 8 (transition layer)
                effectiveMinHeight = -8;
                effectiveMaxHeight = 8;
                if (debugLogging) {
                    plugin.getLogger().info("[OreGen] Deepslate silver restricted to Y=-8 to 8 (transition layer)");
                }
            }
            
            for (int vein = 0; vein < numVeins; vein++) {
                // Pick a random starting point for this vein
                int startX = chunkX + chunkRandom.nextInt(16);
                int startZ = chunkZ + chunkRandom.nextInt(16);
                int startY = effectiveMinHeight + chunkRandom.nextInt(effectiveMaxHeight - effectiveMinHeight);
                
                Block startBlock = world.getBlockAt(startX, startY, startZ);
                Material startType = startBlock.getType();
                
                // Only start vein if we hit valid stone/deepslate
                if (startType != Material.STONE && startType != Material.DEEPSLATE) continue;
                
                // High-altitude silver in mountains (Y>=59) = single ores only (emerald-style)
                boolean isHighAltitudeSilver = config.name().equals("silver") && startY >= 59 && 
                                               isMountainousBiome(chunk.getBlock(0, 64, 0).getBiome());
                
                if (isHighAltitudeSilver) {
                    // Place single ore only (no vein blob)
                    if (placeOreBlock(startBlock, getOreIdForBlock(startBlock, config))) {
                        oresPlaced++;
                        plugin.getLogger().fine("[OreGen] Placed single high-altitude silver at Y=" + startY);
                    }
                } else {
                    // Generate vein blob
                    int veinOres = 3 + chunkRandom.nextInt(config.veinSize() - 2); // 3 to veinSize ores
                    oresPlaced += generateVeinBlob(world, startX, startY, startZ, veinOres, config, chunkRandom, effectiveMinHeight, effectiveMaxHeight);
                }
            }
        }
        
        // Mark chunk as generated
        chunk.getPersistentDataContainer().set(ALLIUM_GENERATED_KEY, PersistentDataType.BYTE, (byte) 1);
        
        generatedOres.addAndGet(oresPlaced);
        generatedChunks.incrementAndGet();
        
        if (debugLogging) {
            plugin.getLogger().info("[OreGen] Chunk (" + chunk.getX() + "," + chunk.getZ() + ") complete - placed " + oresPlaced + " total ores (checked " + stoneChecked + " stone, " + deepslateChecked + " deepslate)");
        }
    }
    
    /**
     * Generate a vein/blob of ores at the specified location
     * Uses a simple blob algorithm to cluster ores together
     * @return number of ores actually placed
     */
    private int generateVeinBlob(World world, int startX, int startY, int startZ, int targetOres, OreConfig config, Random random, int effectiveMinHeight, int effectiveMaxHeight) {
        int placed = 0;
        List<Block> placedBlocks = new ArrayList<>();
        
        // Place the first ore at the starting position
        Block startBlock = world.getBlockAt(startX, startY, startZ);
        if (canReplaceWithOre(startBlock)) {
            String oreId = getOreIdForBlock(startBlock, config);
            if (placeOreBlock(startBlock, oreId)) {
                placed++;
                placedBlocks.add(startBlock);
            }
        }
        
        // Place remaining ores adjacent to existing ones (forming a blob)
        int attempts = 0;
        while (placed < targetOres && attempts < targetOres * 3 && !placedBlocks.isEmpty()) {
            // Pick a random placed block to expand from
            Block expandFrom = placedBlocks.get(random.nextInt(placedBlocks.size()));
            
            // Try random adjacent positions
            int[] dx = {1, -1, 0, 0, 0, 0};
            int[] dy = {0, 0, 1, -1, 0, 0};
            int[] dz = {0, 0, 0, 0, 1, -1};
            
            int dir = random.nextInt(6);
            int newX = expandFrom.getX() + dx[dir];
            int newY = expandFrom.getY() + dy[dir];
            int newZ = expandFrom.getZ() + dz[dir];
            
            // Check if this position is within the effective height range
            if (newY < effectiveMinHeight || newY > effectiveMaxHeight) {
                attempts++;
                continue;
            }
            
            Block newBlock = world.getBlockAt(newX, newY, newZ);
            
            // Only replace stone/deepslate
            if (canReplaceWithOre(newBlock)) {
                String oreId = getOreIdForBlock(newBlock, config);
                if (placeOreBlock(newBlock, oreId)) {
                    placed++;
                    placedBlocks.add(newBlock);
                    if (placed % 2 == 0) {
                        plugin.getLogger().fine("[OreGen] Placed ore " + placed + "/" + targetOres + " in vein at (" + newX + "," + newY + "," + newZ + ")");
                    }
                }
            }
            attempts++;
        }
        
        return placed;
    }
    
    /**
     * Check if a block can be replaced with ore (stone or deepslate only)
     */
    private boolean canReplaceWithOre(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        return type == Material.STONE || type == Material.DEEPSLATE;
    }
    
    /**
     * Get the appropriate Oraxen ore ID based on block type
     */
    private String getOreIdForBlock(Block block, OreConfig config) {
        Material type = block.getType();
        if (type == Material.DEEPSLATE) {
            return config.deepslateOraxenId();
        }
        return config.oraxenId();
    }
    
    /**
     * Check if a biome is considered mountainous (has surface stone for silver generation)
     */
    private boolean isMountainousBiome(Biome biome) {
        String name = biome.name().toUpperCase();
        return name.contains("PEAK") || name.contains("SLOPE") || name.contains("MEADOW") || 
               name.contains("GROVE") || name.contains("WINDSWEPT") || name.contains("BADLAND") ||
               name.contains("PLATEAU") || name.equals("STONY_SHORE");
    }
    
    /**
     * Discover Oraxen ore IDs containing silver/galena in their name
     * Uses Oraxen API via reflection
     * @return Set of matching Oraxen item IDs
     */
    @SuppressWarnings("unchecked")
    private Set<String> discoverOraxenOreIds() {
        Set<String> oreIds = new HashSet<>();
        try {
            if (Bukkit.getPluginManager().getPlugin("Oraxen") == null) return oreIds;
            
            Class<?> oraxenBlocks = Class.forName("io.th0rgal.oraxen.api.OraxenBlocks");
            
            // Get all noteblock IDs: OraxenBlocks.getNoteBlockIDs()
            Set<String> noteBlockIds = (Set<String>) oraxenBlocks
                .getMethod("getNoteBlockIDs")
                .invoke(null);
            
            // Filter for silver/galena
            for (String id : noteBlockIds) {
                String lowerId = id.toLowerCase();
                if (lowerId.contains("silver") || lowerId.contains("galena")) {
                    oreIds.add(id);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not discover Oraxen ore IDs: " + e.getMessage());
        }
        return oreIds;
    }
    
    /**
     * Get noteblock properties for an Oraxen ore ID
     * Returns the NoteBlock data (instrument type, note, powered) via reflection
     * @param oreId The Oraxen item ID
     * @return NoteBlockData containing the properties, or null if not found
     */
    private NoteBlockData getOreNoteBlockData(String oreId) {
        try {
            if (Bukkit.getPluginManager().getPlugin("Oraxen") == null) {
                plugin.getLogger().severe("[OreGen-DEBUG] Oraxen plugin is null!");
                return null;
            }
            
            plugin.getLogger().severe("[OreGen-DEBUG] Looking up NoteBlockMechanic for: " + oreId);
            
            Class<?> oraxenBlocks = Class.forName("io.th0rgal.oraxen.api.OraxenBlocks");
            
            // Get NoteBlockMechanic: OraxenBlocks.getNoteBlockMechanic(itemID)
            Object mechanic = oraxenBlocks
                .getMethod("getNoteBlockMechanic", String.class)
                .invoke(null, oreId);
            
            if (mechanic == null) {
                plugin.getLogger().severe("[OreGen-DEBUG] getNoteBlockMechanic returned NULL for " + oreId);
                return null;
            }
            
            plugin.getLogger().severe("[OreGen-DEBUG] Got mechanic: " + mechanic.getClass().getName());
            
            // List ALL available methods for debugging
            java.lang.reflect.Method[] methods = mechanic.getClass().getMethods();
            StringBuilder allMethods = new StringBuilder("ALL methods: ");
            StringBuilder filteredMethods = new StringBuilder("Filtered: ");
            for (java.lang.reflect.Method m : methods) {
                allMethods.append(m.getName()).append("(), ");
                if (m.getName().toLowerCase().contains("note") || 
                    m.getName().toLowerCase().contains("power") ||
                    m.getName().toLowerCase().contains("instrument") ||
                    m.getName().toLowerCase().contains("block") ||
                    m.getName().toLowerCase().contains("data")) {
                    filteredMethods.append(m.getName()).append("(), ");
                }
            }
            plugin.getLogger().severe("[OreGen-DEBUG] " + allMethods.toString().substring(0, Math.min(500, allMethods.length())));
            plugin.getLogger().severe("[OreGen-DEBUG] " + filteredMethods.toString());
            
            // Try to get note value - try multiple method names
            int noteId = -1;
            String[] noteMethods = {"getNoteId", "getNoteID", "getNote", "noteId", "noteID"};
            for (String methodName : noteMethods) {
                try {
                    Object result = mechanic.getClass().getMethod(methodName).invoke(mechanic);
                    if (result instanceof Integer) {
                        noteId = (Integer) result;
                        plugin.getLogger().severe("[OreGen-DEBUG] Found note via " + methodName + " = " + noteId);
                        break;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            if (noteId == -1) {
                // Try field access
                try {
                    java.lang.reflect.Field noteField = mechanic.getClass().getDeclaredField("noteId");
                    noteField.setAccessible(true);
                    noteId = (int) noteField.get(mechanic);
                    plugin.getLogger().severe("[OreGen-DEBUG] Found note via field = " + noteId);
                } catch (NoSuchFieldException e2) {
                    plugin.getLogger().severe("[OreGen-DEBUG] Could not find note via methods or fields");
                }
            }
            
            // Try to get powered value
            boolean powered = false;
            String[] powerMethods = {"isPowered", "getPowered", "powered"};
            for (String methodName : powerMethods) {
                try {
                    Object result = mechanic.getClass().getMethod(methodName).invoke(mechanic);
                    if (result instanceof Boolean) {
                        powered = (Boolean) result;
                        plugin.getLogger().severe("[OreGen-DEBUG] Found powered via " + methodName + " = " + powered);
                        break;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            
            // Try to get instrument
            int instrumentType = 0;
            String[] instrumentMethods = {"getInstrument", "instrument"};
            Object instrument = null;
            for (String methodName : instrumentMethods) {
                try {
                    instrument = mechanic.getClass().getMethod(methodName).invoke(mechanic);
                    plugin.getLogger().severe("[OreGen-DEBUG] Found instrument via " + methodName + " = " + instrument);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            
            if (instrument != null) {
                try {
                    instrumentType = (int) instrument.getClass().getMethod("getType").invoke(instrument);
                    plugin.getLogger().severe("[OreGen-DEBUG] Instrument type = " + instrumentType);
                } catch (Exception e) {
                    plugin.getLogger().severe("[OreGen-DEBUG] Could not get instrument type: " + e.getMessage());
                }
            }
            
            if (noteId >= 0) {
                plugin.getLogger().severe("[OreGen-DEBUG] SUCCESS - noteId=" + noteId + ", instrumentType=" + instrumentType + ", powered=" + powered);
                return new NoteBlockData(noteId, instrumentType, powered);
            } else {
                plugin.getLogger().severe("[OreGen-DEBUG] Could not extract noteId from mechanic");
                return null;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[OreGen-DEBUG] FAILED to get noteblock data for " + oreId + ": " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Record to hold noteblock data from Oraxen
     */
    private record NoteBlockData(int noteId, int instrumentType, boolean powered) {}
    
    /**
     * Check if a block can be replaced with ore
     */
    private boolean isReplaceable(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        return type == Material.STONE || 
               type == Material.DEEPSLATE ||
               type == Material.ANDESITE ||
               type == Material.DIORITE ||
               type == Material.GRANITE ||
               type == Material.TUFF;
    }
    
    /**
     * Place an Oraxen ore block using Oraxen's own placement method
     * Uses NoteBlockMechanicFactory.setBlockModel() to properly configure the noteblock
     */
    private boolean placeOreBlock(Block block, String oraxenId) {
        if (debugLogging) {
            plugin.getLogger().info("[OreGen] placeOreBlock called for " + oraxenId + " at " + block.getLocation());
        }
        try {
            if (Bukkit.getPluginManager().getPlugin("Oraxen") == null) {
                plugin.getLogger().warning("[OreGen] Oraxen plugin NOT detected");
                return placeFallbackNoteblock(block);
            }
            
            if (debugLogging) {
                plugin.getLogger().info("[OreGen] Oraxen plugin detected, placing " + oraxenId + " via NoteBlockMechanicFactory");
            }
            
            // Use Oraxen's NoteBlockMechanicFactory to place the block properly
            Class<?> noteBlockFactory = Class.forName("io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock.NoteBlockMechanicFactory");
            Object factoryInstance = noteBlockFactory.getMethod("getInstance").invoke(null);
            
            // Check if this is a valid noteblock item
            boolean isNotImplemented = (boolean) factoryInstance.getClass().getMethod("isNotImplementedIn", String.class).invoke(factoryInstance, oraxenId);
            if (isNotImplemented) {
                // Silently skip pregen_ore placeholders - only log real ores
                if (!oraxenId.contains("pregen")) {
                    plugin.getLogger().warning("[OreGen] " + oraxenId + " is not a valid NoteBlock item in Oraxen");
                }
                return placeFallbackNoteblock(block);
            }
            
            // Use setBlockModel to properly place and configure the noteblock
            noteBlockFactory.getMethod("setBlockModel", Block.class, String.class).invoke(null, block, oraxenId);
            
            if (debugLogging) {
                plugin.getLogger().info("[OreGen] Successfully placed " + oraxenId + " via Oraxen NoteBlockMechanicFactory");
            }
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().warning("[OreGen] FAILED to place Oraxen block " + oraxenId + ": " + e.getMessage());
            e.printStackTrace();
            return placeFallbackNoteblock(block);
        }
    }
    
    /**
     * Fallback method to place a generic powered noteblock
     */
    private boolean placeFallbackNoteblock(Block block) {
        if (debugLogging) {
            plugin.getLogger().warning("[OreGen] Using fallback noteblock at " + block.getLocation());
        }
        block.setType(Material.NOTE_BLOCK);
        if (block.getBlockData() instanceof NoteBlock noteBlock) {
            noteBlock.setPowered(true);
            block.setBlockData(noteBlock);
        }
        return true;
    }
    
    /**
     * TEST METHOD: Replace a stone block at specific coordinates with a Silver Ore noteblock
     * Coordinates: -1100, 74, -334
     * Uses Oraxen's setBlockModel to properly place the block
     */
    public void runTestReplacement(World world) {
        int testX = -1100;
        int testY = 74;
        int testZ = -334;
        String oreId = "silver_ore";
        
        plugin.getLogger().info("[OreGen-TEST] ==========================================");
        plugin.getLogger().info("[OreGen-TEST] Starting TEST replacement at (" + testX + "," + testY + "," + testZ + ")");
        plugin.getLogger().info("[OreGen-TEST] Expected: Silver Ore via Oraxen setBlockModel");
        
        Block block = world.getBlockAt(testX, testY, testZ);
        Material originalType = block.getType();
        
        plugin.getLogger().info("[OreGen-TEST] Original block type: " + originalType);
        
        // Try to use Oraxen to place the block properly
        boolean success = false;
        if (Bukkit.getPluginManager().getPlugin("Oraxen") != null) {
            try {
                Class<?> noteBlockFactory = Class.forName("io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock.NoteBlockMechanicFactory");
                noteBlockFactory.getMethod("setBlockModel", Block.class, String.class).invoke(null, block, oreId);
                success = true;
                plugin.getLogger().info("[OreGen-TEST] Successfully placed " + oreId + " via Oraxen NoteBlockMechanicFactory");
            } catch (Exception e) {
                plugin.getLogger().warning("[OreGen-TEST] Oraxen setBlockModel failed: " + e.getMessage());
            }
        }
        
        if (!success) {
            // Fallback
            plugin.getLogger().warning("[OreGen-TEST] Using fallback noteblock");
            block.setType(Material.NOTE_BLOCK);
            if (block.getBlockData() instanceof NoteBlock noteBlock) {
                noteBlock.setPowered(true);
                block.setBlockData(noteBlock);
            }
        }
        
        // Verify placement
        Block verifyBlock = world.getBlockAt(testX, testY, testZ);
        plugin.getLogger().info("[OreGen-TEST] Verification - Block type: " + verifyBlock.getType());
        if (verifyBlock.getBlockData() instanceof NoteBlock verifyNote) {
            plugin.getLogger().info("[OreGen-TEST] Verification - Powered: " + verifyNote.isPowered());
            plugin.getLogger().info("[OreGen-TEST] Verification - Instrument: " + verifyNote.getInstrument());
            plugin.getLogger().info("[OreGen-TEST] Verification - Note: " + verifyNote.getNote());
        }
        
        plugin.getLogger().info("[OreGen-TEST] TEST COMPLETE - Replaced " + originalType + " with " + oreId);
        plugin.getLogger().info("[OreGen-TEST] ==========================================");
    }
    
    /**
     * Start ore generation in a world with a radius
     */
    public CompletableFuture<Void> startGeneration(World world, int centerX, int centerZ, int radius) {
        if (isGenerating) {
            return CompletableFuture.failedFuture(new IllegalStateException("Generation already in progress"));
        }
        
        isGenerating = true;
        paused = false;
        generatedChunks.set(0);
        generatedOres.set(0);
        
        int totalChunks = (radius * 2 + 1) * (radius * 2 + 1);
        
        return CompletableFuture.runAsync(() -> {
            Semaphore semaphore = new Semaphore(4); // Limit concurrent chunk loading
            
            for (int dx = -radius; dx <= radius && isGenerating; dx++) {
                for (int dz = -radius; dz <= radius && isGenerating; dz++) {
                    if (paused) {
                        while (paused && isGenerating) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                    
                    try {
                        semaphore.acquire();
                        final int chunkX = centerX + dx;
                        final int chunkZ = centerZ + dz;
                        
                        // Load chunk and generate ores
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                                if (!chunk.isLoaded()) {
                                    chunk.load(true);
                                }
                                generateOresInChunk(chunk);
                            } finally {
                                semaphore.release();
                            }
                        });
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }
    
    /**
     * Stop generation
     */
    public void stopGeneration() {
        isGenerating = false;
        paused = false;
        if (generationTask != null) {
            generationTask.cancel();
            generationTask = null;
        }
    }
    
    /**
     * Pause generation
     */
    public void pauseGeneration() {
        paused = true;
    }
    
    /**
     * Resume generation
     */
    public void resumeGeneration() {
        paused = false;
    }
    
    /**
     * Check if generation is in progress
     */
    public boolean isGenerating() {
        return isGenerating;
    }
    
    /**
     * Check if generation is paused
     */
    public boolean isPaused() {
        return paused;
    }
    
    /**
     * Get number of generated chunks
     */
    public int getGeneratedChunks() {
        return generatedChunks.get();
    }
    
    /**
     * Get number of generated ores
     */
    public long getGeneratedOres() {
        return generatedOres.get();
    }
    
    /**
     * Ore configuration record
     */
    public record OreConfig(String name, String oraxenId, String deepslateOraxenId, int veinSize, 
                           int minHeight, int maxHeight, 
                           double chancePerChunk, int veinsPerChunk) {}
}
