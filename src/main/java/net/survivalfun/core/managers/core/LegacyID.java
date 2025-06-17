package net.survivalfun.core.managers.core;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LegacyID {
    private static final Map<String, Material> LEGACY_IDS = new HashMap<>();
    private static final Map<Material, String> MATERIAL_TO_LEGACY = new HashMap<>();
    private static JavaPlugin plugin;
    private static boolean initialized = false;

    static {
        // Static initialization - will be replaced by initialize() call from plugin
        if (!initialized) {
            initializeLegacyIds();
        }
    }

    /**
     * Initialize the LegacyID system with plugin instance and load configuration
     * @param pluginInstance The plugin instance for file access
     */
    public static void initialize(JavaPlugin pluginInstance) {
        plugin = pluginInstance;
        loadLegacyIds();
        initialized = true;
    }

    /**
     * Load legacy IDs from itemdb.yml configuration file
     */
    private static void loadLegacyIds() {
        LEGACY_IDS.clear();
        MATERIAL_TO_LEGACY.clear();

        if (plugin == null) {
            plugin.getLogger().warning("LegacyID plugin instance is null, falling back to hardcoded legacy IDs");
            initializeLegacyIds();
            return;
        }

        try {
            // Ensure data folder exists
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            File configFile = new File(plugin.getDataFolder(), "itemdb.yml");
            
            // Create default itemdb.yml if it doesn't exist
            if (!configFile.exists()) {
                InputStream defaultConfig = plugin.getResource("itemdb.yml");
                if (defaultConfig != null) {
                    Files.copy(defaultConfig, configFile.toPath());
                    plugin.getLogger().info("Created default itemdb.yml configuration file");
                } else {
                    plugin.getLogger().warning("Default itemdb.yml not found in resources, falling back to hardcoded mappings");
                    initializeLegacyIds();
                    return;
                }
            }

            // Load configuration
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            
            if (config.contains("legacy-ids")) {
                Set<String> keys = config.getConfigurationSection("legacy-ids").getKeys(false);
                int loadedCount = 0;
                
                for (String legacyId : keys) {
                    String materialName = config.getString("legacy-ids." + legacyId);
                    
                    try {
                        Material material = Material.valueOf(materialName.toUpperCase());
                        addLegacyId(legacyId, material);
                        loadedCount++;
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid material '" + materialName + "' for legacy ID '" + legacyId + "' in itemdb.yml");
                    }
                }
                
                plugin.getLogger().info("Loaded " + loadedCount + " legacy ID mappings from itemdb.yml");
                
                if (loadedCount == 0) {
                    plugin.getLogger().warning("No valid legacy IDs loaded from itemdb.yml, falling back to hardcoded mappings");
                    initializeLegacyIds();
                }
            } else {
                plugin.getLogger().warning("No 'legacy-ids' section found in itemdb.yml, falling back to hardcoded mappings");
                initializeLegacyIds();
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading itemdb.yml: " + e.getMessage());
            plugin.getLogger().info("Falling back to hardcoded legacy ID mappings");
            initializeLegacyIds();
        }
    }

    /**
     * Reload legacy IDs from configuration file
     */
    public static void reload() {
        if (plugin != null) {
            loadLegacyIds();
            plugin.getLogger().info("Reloaded legacy ID mappings from itemdb.yml");
        }
    }

    /**
     * Add runtime legacy ID mapping
     */
    public static void addLegacyId(String legacyId, Material material) {
        LEGACY_IDS.put(legacyId, material);
        MATERIAL_TO_LEGACY.put(material, legacyId);
    }

    /**
     * Remove legacy ID mapping
     */
    public static void removeLegacyId(String legacyId) {
        Material material = LEGACY_IDS.remove(legacyId);
        if (material != null) {
            MATERIAL_TO_LEGACY.remove(material);
        }
    }

    /**
     * Check if legacy ID exists
     */
    public static boolean hasLegacyId(String legacyId) {
        return LEGACY_IDS.containsKey(legacyId);
    }

    /**
     * Get all legacy IDs
     */
    public static Set<String> getAllLegacyIds() {
        return LEGACY_IDS.keySet();
    }

    /**
     * Check if the LegacyID system is initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    private static void initializeLegacyIds() {
        // --- Stone and Stone Variants ---
        addLegacyId("0", Material.AIR);
        addLegacyId("1", Material.STONE);
        addLegacyId("1:1", Material.GRANITE);
        addLegacyId("1:2", Material.POLISHED_GRANITE);
        addLegacyId("1:3", Material.DIORITE);
        addLegacyId("1:4", Material.POLISHED_DIORITE);
        addLegacyId("1:5", Material.ANDESITE);
        addLegacyId("1:6", Material.POLISHED_ANDESITE);
        addLegacyId("1:7", Material.DEEPSLATE);
        addLegacyId("1:8", Material.COBBLED_DEEPSLATE);
        addLegacyId("1:9", Material.POLISHED_DEEPSLATE);
        addLegacyId("1:10", Material.CALCITE);
        addLegacyId("1:11", Material.TUFF);
        addLegacyId("1:12", Material.DRIPSTONE_BLOCK);
        addLegacyId("1:13", Material.POINTED_DRIPSTONE);

        // --- Dirt, Grass, and Soil ---
        addLegacyId("2", Material.GRASS_BLOCK);
        addLegacyId("3", Material.DIRT);
        addLegacyId("3:1", Material.COARSE_DIRT);
        addLegacyId("3:2", Material.PODZOL);
        addLegacyId("3:3", Material.ROOTED_DIRT);
        addLegacyId("3:4", Material.MUD);
        addLegacyId("3:5", Material.PACKED_MUD);
        addLegacyId("3:6", Material.MUD_BRICKS);
        addLegacyId("3:7", Material.CRIMSON_NYLIUM);
        addLegacyId("3:8", Material.WARPED_NYLIUM);
        addLegacyId("3:9", Material.MOSS_BLOCK);
        addLegacyId("3:10", Material.MUDDY_MANGROVE_ROOTS);

        // --- Cobblestone and Mossy ---
        addLegacyId("4", Material.COBBLESTONE);
        addLegacyId("4:1", Material.MOSSY_COBBLESTONE);

        // --- Planks ---
        addLegacyId("5", Material.OAK_PLANKS);
        addLegacyId("5:1", Material.SPRUCE_PLANKS);
        addLegacyId("5:2", Material.BIRCH_PLANKS);
        addLegacyId("5:3", Material.JUNGLE_PLANKS);
        addLegacyId("5:4", Material.ACACIA_PLANKS);
        addLegacyId("5:5", Material.DARK_OAK_PLANKS);
        addLegacyId("5:6", Material.CHERRY_PLANKS);
        addLegacyId("5:7", Material.MANGROVE_PLANKS);
        addLegacyId("5:8", Material.BAMBOO_PLANKS);
        addLegacyId("5:9", Material.CRIMSON_PLANKS);
        addLegacyId("5:10", Material.WARPED_PLANKS);
        addLegacyId("5:11", Material.BAMBOO_MOSAIC);

        // --- Saplings ---
        addLegacyId("6", Material.OAK_SAPLING);
        addLegacyId("6:1", Material.SPRUCE_SAPLING);
        addLegacyId("6:2", Material.BIRCH_SAPLING);
        addLegacyId("6:3", Material.JUNGLE_SAPLING);
        addLegacyId("6:4", Material.ACACIA_SAPLING);
        addLegacyId("6:5", Material.DARK_OAK_SAPLING);
        addLegacyId("6:6", Material.CHERRY_SAPLING);
        addLegacyId("6:7", Material.MANGROVE_PROPAGULE);

        // --- Logs ---
        addLegacyId("7", Material.OAK_LOG);
        addLegacyId("7:1", Material.SPRUCE_LOG);
        addLegacyId("7:2", Material.BIRCH_LOG);
        addLegacyId("7:3", Material.JUNGLE_LOG);
        addLegacyId("7:4", Material.ACACIA_LOG);
        addLegacyId("7:5", Material.DARK_OAK_LOG);
        addLegacyId("7:6", Material.MANGROVE_LOG);
        addLegacyId("7:7", Material.CHERRY_LOG);
        addLegacyId("7:8", Material.CRIMSON_STEM);
        addLegacyId("7:9", Material.WARPED_STEM);

        // --- Stripped Logs ---
        addLegacyId("7:10", Material.STRIPPED_OAK_LOG);
        addLegacyId("7:11", Material.STRIPPED_SPRUCE_LOG);
        addLegacyId("7:12", Material.STRIPPED_BIRCH_LOG);
        addLegacyId("7:13", Material.STRIPPED_JUNGLE_LOG);
        addLegacyId("7:14", Material.STRIPPED_ACACIA_LOG);
        addLegacyId("7:15", Material.STRIPPED_DARK_OAK_LOG);
        addLegacyId("7:16", Material.STRIPPED_MANGROVE_LOG);
        addLegacyId("7:17", Material.STRIPPED_CHERRY_LOG);
        addLegacyId("7:18", Material.STRIPPED_CRIMSON_STEM);
        addLegacyId("7:19", Material.STRIPPED_WARPED_STEM);

        // --- Bamboo Blocks ---
        addLegacyId("7:20", Material.BAMBOO_BLOCK);
        addLegacyId("7:21", Material.STRIPPED_BAMBOO_BLOCK);

        // --- Wood Blocks ---
        addLegacyId("7:22", Material.OAK_WOOD);
        addLegacyId("7:23", Material.SPRUCE_WOOD);
        addLegacyId("7:24", Material.BIRCH_WOOD);
        addLegacyId("7:25", Material.JUNGLE_WOOD);
        addLegacyId("7:26", Material.ACACIA_WOOD);
        addLegacyId("7:27", Material.DARK_OAK_WOOD);
        addLegacyId("7:28", Material.MANGROVE_WOOD);
        addLegacyId("7:29", Material.CHERRY_WOOD);
        addLegacyId("7:30", Material.CRIMSON_HYPHAE);
        addLegacyId("7:31", Material.WARPED_HYPHAE);

        // --- Stripped Wood Blocks ---
        addLegacyId("7:32", Material.STRIPPED_OAK_WOOD);
        addLegacyId("7:33", Material.STRIPPED_SPRUCE_WOOD);
        addLegacyId("7:34", Material.STRIPPED_BIRCH_WOOD);
        addLegacyId("7:35", Material.STRIPPED_JUNGLE_WOOD);
        addLegacyId("7:36", Material.STRIPPED_ACACIA_WOOD);
        addLegacyId("7:37", Material.STRIPPED_DARK_OAK_WOOD);
        addLegacyId("7:38", Material.STRIPPED_MANGROVE_WOOD);
        addLegacyId("7:39", Material.STRIPPED_CHERRY_WOOD);
        addLegacyId("7:40", Material.STRIPPED_CRIMSON_HYPHAE);
        addLegacyId("7:41", Material.STRIPPED_WARPED_HYPHAE);

        // --- Leaves ---
        addLegacyId("8", Material.OAK_LEAVES);
        addLegacyId("8:1", Material.SPRUCE_LEAVES);
        addLegacyId("8:2", Material.BIRCH_LEAVES);
        addLegacyId("8:3", Material.JUNGLE_LEAVES);
        addLegacyId("8:4", Material.ACACIA_LEAVES);
        addLegacyId("8:5", Material.DARK_OAK_LEAVES);
        addLegacyId("8:6", Material.MANGROVE_LEAVES);
        addLegacyId("8:7", Material.CHERRY_LEAVES);
        addLegacyId("8:8", Material.AZALEA_LEAVES);
        addLegacyId("8:9", Material.FLOWERING_AZALEA_LEAVES);

        // --- Azaleas ---
        addLegacyId("9", Material.AZALEA);
        addLegacyId("9:1", Material.FLOWERING_AZALEA);

        // Infested blocks
        addLegacyId("10", Material.INFESTED_STONE);
        addLegacyId("10:1", Material.INFESTED_COBBLESTONE);
        addLegacyId("10:2", Material.INFESTED_STONE_BRICKS);
        addLegacyId("10:3", Material.INFESTED_MOSSY_STONE_BRICKS);
        addLegacyId("10:4", Material.INFESTED_CRACKED_STONE_BRICKS);
        addLegacyId("10:5", Material.INFESTED_CHISELED_STONE_BRICKS);
        addLegacyId("10:6", Material.INFESTED_DEEPSLATE);

        // Deepslate variants
        addLegacyId("11", Material.DEEPSLATE_BRICKS);
        addLegacyId("11:1", Material.CRACKED_DEEPSLATE_BRICKS);
        addLegacyId("11:2", Material.DEEPSLATE_TILES);
        addLegacyId("11:3", Material.CRACKED_DEEPSLATE_TILES);
        addLegacyId("11:4", Material.CHISELED_DEEPSLATE);
        addLegacyId("11:5", Material.REINFORCED_DEEPSLATE);

        // Sand and gravel
        addLegacyId("12", Material.SAND);
        addLegacyId("12:1", Material.RED_SAND);
        addLegacyId("12:2", Material.SUSPICIOUS_SAND);
        addLegacyId("13", Material.GRAVEL);

        // Ores
        addLegacyId("14", Material.GOLD_ORE);
        addLegacyId("14:1", Material.DEEPSLATE_GOLD_ORE);
        addLegacyId("14:2", Material.NETHER_GOLD_ORE);
        addLegacyId("14:3", Material.ANCIENT_DEBRIS);
        addLegacyId("15", Material.IRON_ORE);
        addLegacyId("15:1", Material.DEEPSLATE_IRON_ORE);
        addLegacyId("16", Material.COAL_ORE);
        addLegacyId("16:1", Material.DEEPSLATE_COAL_ORE);
        addLegacyId("17", Material.COPPER_ORE);
        addLegacyId("17:1", Material.DEEPSLATE_COPPER_ORE);
        addLegacyId("18", Material.NOTE_BLOCK);

        // Other blocks
        addLegacyId("19", Material.SPONGE);
        addLegacyId("19:1", Material.WET_SPONGE);
        addLegacyId("20", Material.GLASS);
        addLegacyId("20:1", Material.TINTED_GLASS);
        addLegacyId("21", Material.LAPIS_ORE);
        addLegacyId("21:1", Material.DEEPSLATE_LAPIS_ORE);
        addLegacyId("22", Material.LAPIS_BLOCK);
        addLegacyId("22:1", Material.AMETHYST_BLOCK);
        addLegacyId("22:2", Material.BUDDING_AMETHYST);
        addLegacyId("23", Material.DISPENSER);

        // Sandstone
        addLegacyId("24", Material.SANDSTONE);
        addLegacyId("24:1", Material.CHISELED_SANDSTONE);
        addLegacyId("24:2", Material.CUT_SANDSTONE);

        // Slabs
        addLegacyId("25", Material.OAK_SLAB);
        addLegacyId("25:1", Material.SPRUCE_SLAB);
        addLegacyId("25:2", Material.BIRCH_SLAB);
        addLegacyId("25:3", Material.JUNGLE_SLAB);
        addLegacyId("25:4", Material.ACACIA_SLAB);
        addLegacyId("25:5", Material.DARK_OAK_SLAB);
        addLegacyId("25:6", Material.CHERRY_SLAB);
        addLegacyId("25:7", Material.MANGROVE_SLAB);
        addLegacyId("25:8", Material.PALE_OAK_SLAB);
        addLegacyId("25:9", Material.BAMBOO_SLAB);
        addLegacyId("25:10", Material.BAMBOO_MOSAIC_SLAB);
        addLegacyId("25:11", Material.CRIMSON_SLAB);
        addLegacyId("25:12", Material.WARPED_SLAB);
        addLegacyId("25:13", Material.BAMBOO_MOSAIC_SLAB);

        // Copper blocks
        addLegacyId("26", Material.COPPER_BLOCK);
        addLegacyId("26:1", Material.CUT_COPPER);
        addLegacyId("26:2", Material.CUT_COPPER_STAIRS);
        addLegacyId("26:3", Material.CUT_COPPER_SLAB);
        addLegacyId("26:4", Material.WAXED_COPPER_BLOCK);
        addLegacyId("26:5", Material.WAXED_CUT_COPPER);
        addLegacyId("26:6", Material.WAXED_CUT_COPPER_STAIRS);
        addLegacyId("26:7", Material.WAXED_CUT_COPPER_SLAB);

        // Exposed copper
        addLegacyId("27", Material.EXPOSED_COPPER);
        addLegacyId("27:1", Material.EXPOSED_CUT_COPPER);
        addLegacyId("27:2", Material.EXPOSED_CUT_COPPER_STAIRS);
        addLegacyId("27:3", Material.EXPOSED_CUT_COPPER_SLAB);
        addLegacyId("27:4", Material.WAXED_EXPOSED_COPPER);
        addLegacyId("27:5", Material.WAXED_EXPOSED_CUT_COPPER);
        addLegacyId("27:6", Material.WAXED_EXPOSED_CUT_COPPER_STAIRS);
        addLegacyId("27:7", Material.WAXED_EXPOSED_CUT_COPPER_SLAB);

        // Weathered copper
        addLegacyId("28", Material.WEATHERED_COPPER);
        addLegacyId("28:1", Material.WEATHERED_CUT_COPPER);
        addLegacyId("28:2", Material.WEATHERED_CUT_COPPER_STAIRS);
        addLegacyId("28:3", Material.WEATHERED_CUT_COPPER_SLAB);
        addLegacyId("28:4", Material.WAXED_WEATHERED_COPPER);
        addLegacyId("28:5", Material.WAXED_WEATHERED_CUT_COPPER);
        addLegacyId("28:6", Material.WAXED_WEATHERED_CUT_COPPER_STAIRS);
        addLegacyId("28:7", Material.WAXED_WEATHERED_CUT_COPPER_SLAB);

        // Oxidized copper
        addLegacyId("29", Material.OXIDIZED_COPPER);
        addLegacyId("29:1", Material.OXIDIZED_CUT_COPPER);
        addLegacyId("29:2", Material.OXIDIZED_CUT_COPPER_STAIRS);
        addLegacyId("29:3", Material.OXIDIZED_CUT_COPPER_SLAB);
        addLegacyId("29:4", Material.WAXED_OXIDIZED_COPPER);
        addLegacyId("29:5", Material.WAXED_OXIDIZED_CUT_COPPER);
        addLegacyId("29:6", Material.WAXED_OXIDIZED_CUT_COPPER_STAIRS);
        addLegacyId("29:7", Material.WAXED_OXIDIZED_CUT_COPPER_SLAB);

        // Plants and decorations
        addLegacyId("30", Material.COBWEB);
        addLegacyId("31:1", Material.SHORT_GRASS);
        addLegacyId("31:2", Material.FERN);
        addLegacyId("31:3", Material.SHORT_DRY_GRASS);
        addLegacyId("31:4", Material.BUSH);
        addLegacyId("31:5", Material.SEAGRASS);
        addLegacyId("31:6", Material.SEA_PICKLE);
        addLegacyId("32", Material.DEAD_BUSH);

        addLegacyId("33", Material.PISTON);
        addLegacyId("34", Material.STICKY_PISTON);

        // Wool colors
        addLegacyId("35", Material.WHITE_WOOL);
        addLegacyId("35:1", Material.ORANGE_WOOL);
        addLegacyId("35:2", Material.MAGENTA_WOOL);
        addLegacyId("35:3", Material.LIGHT_BLUE_WOOL);
        addLegacyId("35:4", Material.YELLOW_WOOL);
        addLegacyId("35:5", Material.LIME_WOOL);
        addLegacyId("35:6", Material.PINK_WOOL);
        addLegacyId("35:7", Material.GRAY_WOOL);
        addLegacyId("35:8", Material.LIGHT_GRAY_WOOL);
        addLegacyId("35:9", Material.CYAN_WOOL);
        addLegacyId("35:10", Material.PURPLE_WOOL);
        addLegacyId("35:11", Material.BLUE_WOOL);
        addLegacyId("35:12", Material.BROWN_WOOL);
        addLegacyId("35:13", Material.GREEN_WOOL);
        addLegacyId("35:14", Material.RED_WOOL);
        addLegacyId("35:15", Material.BLACK_WOOL);

        addLegacyId("36", Material.WHITE_BED);
        addLegacyId("36:1", Material.LIGHT_GRAY_BED);
        addLegacyId("36:2", Material.GRAY_BED);
        addLegacyId("36:3", Material.BLACK_BED);
        addLegacyId("36:4", Material.BROWN_BED);
        addLegacyId("36:5", Material.RED_BED);
        addLegacyId("36:6", Material.ORANGE_BED);
        addLegacyId("36:7", Material.YELLOW_BED);
        addLegacyId("36:8", Material.LIME_BED);
        addLegacyId("36:9", Material.GREEN_BED);
        addLegacyId("36:10", Material.CYAN_BED);
        addLegacyId("36:11", Material.LIGHT_BLUE_BED);
        addLegacyId("36:12", Material.BLUE_BED);
        addLegacyId("36:13", Material.PURPLE_BED);
        addLegacyId("36:14", Material.MAGENTA_BED);
        addLegacyId("36:15", Material.PINK_BED);

        // Flowers
        addLegacyId("37", Material.DANDELION);
        addLegacyId("38", Material.POPPY);
        addLegacyId("38:1", Material.BLUE_ORCHID);
        addLegacyId("38:2", Material.ALLIUM);
        addLegacyId("38:3", Material.AZURE_BLUET);
        addLegacyId("38:4", Material.RED_TULIP);
        addLegacyId("38:5", Material.ORANGE_TULIP);
        addLegacyId("38:6", Material.WHITE_TULIP);
        addLegacyId("38:7", Material.PINK_TULIP);
        addLegacyId("38:8", Material.OXEYE_DAISY);
        addLegacyId("38:9", Material.CORNFLOWER);
        addLegacyId("38:10", Material.LILY_OF_THE_VALLEY);
        addLegacyId("38:11", Material.TORCHFLOWER);
        addLegacyId("38:12", Material.CACTUS_FLOWER);
        addLegacyId("38:13", Material.CLOSED_EYEBLOSSOM);
        addLegacyId("38:14", Material.OPEN_EYEBLOSSOM);
        addLegacyId("38:15", Material.WITHER_ROSE);
        addLegacyId("38:16", Material.PINK_PETALS);
        addLegacyId("38:17", Material.WILDFLOWERS);
        addLegacyId("38:18", Material.LEAF_LITTER);
        addLegacyId("38:19", Material.SPORE_BLOSSOM);
        addLegacyId("38:20", Material.FIREFLY_BUSH);
        addLegacyId("38:21", Material.BAMBOO);
        addLegacyId("38:22", Material.SUGAR_CANE);
        addLegacyId("81", Material.CACTUS);
        addLegacyId("38:23", Material.CRIMSON_FUNGUS);
        addLegacyId("38:24", Material.WARPED_FUNGUS);
        addLegacyId("38:25", Material.CRIMSON_ROOTS);
        addLegacyId("38:26", Material.WARPED_ROOTS);
        addLegacyId("38:27", Material.NETHER_SPROUTS);
        addLegacyId("38:28", Material.WEEPING_VINES);
        addLegacyId("106", Material.VINE);
        addLegacyId("175:6", Material.TALL_GRASS);
        addLegacyId("175:3", Material.LARGE_FERN);
        addLegacyId("175:2", Material.TALL_DRY_GRASS);
        addLegacyId("175", Material.SUNFLOWER);
        addLegacyId("175:1", Material.LILAC);
        addLegacyId("175:4", Material.ROSE_BUSH);
        addLegacyId("175:5", Material.PEONY);
        addLegacyId("175:7", Material.PITCHER_PLANT);
        addLegacyId("38:29", Material.BIG_DRIPLEAF);
        addLegacyId("38:30", Material.SMALL_DRIPLEAF);
        addLegacyId("199", Material.CHORUS_PLANT);
        addLegacyId("200", Material.CHORUS_FLOWER);
        addLegacyId("38:31", Material.GLOW_LICHEN);
        addLegacyId("38:32", Material.HANGING_ROOTS);
        addLegacyId("38:33", Material.KELP);
        addLegacyId("39", Material.BROWN_MUSHROOM);
        addLegacyId("40", Material.RED_MUSHROOM);
        addLegacyId("41", Material.GOLD_BLOCK);
        addLegacyId("42", Material.IRON_BLOCK);
        addLegacyId("43", Material.RAW_COPPER_BLOCK);
        addLegacyId("43:1", Material.RAW_GOLD_BLOCK);
        addLegacyId("43:2", Material.RAW_IRON_BLOCK);
        addLegacyId("43:3", Material.AMETHYST_BLOCK);
        addLegacyId("43:4", Material.BUDDING_AMETHYST);
        addLegacyId("43:5", Material.SMOOTH_QUARTZ);
        addLegacyId("43:6", Material.SMOOTH_STONE);
        addLegacyId("44", Material.STONE_SLAB);
        addLegacyId("44:1", Material.SMOOTH_STONE_SLAB);
        addLegacyId("44:2", Material.STONE_BRICK_SLAB);
        addLegacyId("44:3", Material.MOSSY_STONE_BRICK_SLAB);
        addLegacyId("44:4", Material.COBBLESTONE_SLAB);
        addLegacyId("44:5", Material.MOSSY_COBBLESTONE_SLAB);
        addLegacyId("44:6", Material.GRANITE_SLAB);
        addLegacyId("44:7", Material.POLISHED_GRANITE_SLAB);
        addLegacyId("44:8", Material.DIORITE_SLAB);
        addLegacyId("44:9", Material.POLISHED_DIORITE_SLAB);
        addLegacyId("44:10", Material.ANDESITE_SLAB);
        addLegacyId("44:11", Material.POLISHED_ANDESITE_SLAB);
        addLegacyId("44:12", Material.COBBLED_DEEPSLATE_SLAB);
        addLegacyId("44:13", Material.POLISHED_DEEPSLATE_SLAB);
        addLegacyId("44:14", Material.DEEPSLATE_BRICK_SLAB);
        addLegacyId("44:15", Material.DEEPSLATE_TILE_SLAB);
        addLegacyId("44:16", Material.TUFF_SLAB);
        addLegacyId("44:17", Material.POLISHED_TUFF_SLAB);
        addLegacyId("44:18", Material.TUFF_BRICK_SLAB);
        addLegacyId("44:19", Material.BRICK_SLAB);
        addLegacyId("44:20", Material.MUD_BRICK_SLAB);
        addLegacyId("44:21", Material.RESIN_BRICK_SLAB);
        addLegacyId("44:22", Material.SANDSTONE_SLAB);
        addLegacyId("44:23", Material.SMOOTH_SANDSTONE_SLAB);
        addLegacyId("44:24", Material.CUT_SANDSTONE_SLAB);
        addLegacyId("44:25", Material.RED_SANDSTONE_SLAB);
        addLegacyId("44:26", Material.CUT_RED_SANDSTONE_SLAB);
        addLegacyId("44:27", Material.SMOOTH_RED_SANDSTONE_SLAB);
        addLegacyId("44:28", Material.PRISMARINE_SLAB);
        addLegacyId("44:29", Material.PRISMARINE_BRICK_SLAB);
        addLegacyId("44:30", Material.DARK_PRISMARINE_SLAB);
        addLegacyId("44:31", Material.NETHER_BRICK_SLAB);
        addLegacyId("44:32", Material.RED_NETHER_BRICK_SLAB);
        addLegacyId("44:33", Material.BLACKSTONE_SLAB);
        addLegacyId("44:34", Material.POLISHED_BLACKSTONE_SLAB);
        addLegacyId("44:35", Material.POLISHED_BLACKSTONE_BRICK_SLAB);
        addLegacyId("44:36", Material.END_STONE_BRICK_SLAB);
        addLegacyId("44:37", Material.PURPUR_SLAB);
        addLegacyId("44:38", Material.QUARTZ_SLAB);
        addLegacyId("44:39", Material.SMOOTH_QUARTZ_SLAB);
        addLegacyId("44:40", Material.CUT_COPPER_SLAB);
        addLegacyId("44:41", Material.EXPOSED_CUT_COPPER_SLAB);
        addLegacyId("44:42", Material.WEATHERED_CUT_COPPER_SLAB);
        addLegacyId("44:43", Material.OXIDIZED_CUT_COPPER_SLAB);
        addLegacyId("44:44", Material.WAXED_CUT_COPPER_SLAB);
        addLegacyId("44:45", Material.WAXED_EXPOSED_CUT_COPPER_SLAB);
        addLegacyId("44:46", Material.WAXED_WEATHERED_CUT_COPPER_SLAB);
        addLegacyId("44:47", Material.WAXED_OXIDIZED_CUT_COPPER_SLAB);
        addLegacyId("45", Material.BRICKS);
        addLegacyId("46", Material.TNT);
        addLegacyId("47", Material.BOOKSHELF);
        addLegacyId("48", Material.CHISELED_BOOKSHELF);
        addLegacyId("49", Material.OBSIDIAN);
        addLegacyId("49:1", Material.LODESTONE);
        addLegacyId("49:2", Material.CRYING_OBSIDIAN);
        addLegacyId("50", Material.TORCH);
        addLegacyId("50:1", Material.SOUL_TORCH);
        addLegacyId("50:2", Material.CAMPFIRE);
        addLegacyId("50:3", Material.SOUL_CAMPFIRE);
        addLegacyId("50:4", Material.LANTERN);
        addLegacyId("50:5", Material.SOUL_LANTERN);
        addLegacyId("51", Material.SPAWNER);
        addLegacyId("51:1", Material.TRIAL_SPAWNER);
        addLegacyId("54", Material.CHEST);
        addLegacyId("54:1", Material.TRAPPED_CHEST);
        addLegacyId("58", Material.CRAFTING_TABLE);
        addLegacyId("60", Material.FARMLAND);
        addLegacyId("61", Material.FURNACE);
        addLegacyId("65", Material.LADDER);
        addLegacyId("67", Material.COBBLESTONE_STAIRS);
        addLegacyId("78", Material.SNOW);
        addLegacyId("79", Material.ICE);
        addLegacyId("80", Material.SNOW_BLOCK);
        addLegacyId("81", Material.CACTUS);
        addLegacyId("81:1", Material.BASALT);
        addLegacyId("81:2", Material.POLISHED_BASALT);
        addLegacyId("81:3", Material.SMOOTH_BASALT);
        addLegacyId("84", Material.JUKEBOX);
        addLegacyId("85", Material.OAK_FENCE);
        addLegacyId("86", Material.PUMPKIN);
        addLegacyId("87", Material.NETHERRACK);
        addLegacyId("88", Material.SOUL_SAND);
        addLegacyId("89", Material.GLOWSTONE);
        addLegacyId("89:1", Material.SHROOMLIGHT);
        addLegacyId("91", Material.JACK_O_LANTERN);
        addLegacyId("98", Material.STONE_BRICKS);
        addLegacyId("98:1", Material.MOSSY_STONE_BRICKS);
        addLegacyId("98:2", Material.CRACKED_STONE_BRICKS);
        addLegacyId("98:3", Material.CHISELED_STONE_BRICKS);
        addLegacyId("98:4", Material.SCULK);
        addLegacyId("98:5", Material.SCULK_VEIN);
        addLegacyId("98:6", Material.SCULK_CATALYST);
        addLegacyId("98:7", Material.SCULK_SHRIEKER);
        addLegacyId("99", Material.BROWN_MUSHROOM_BLOCK);
        addLegacyId("100", Material.RED_MUSHROOM_BLOCK);
        addLegacyId("101", Material.IRON_BARS);
        addLegacyId("102", Material.GLASS_PANE);
        addLegacyId("106", Material.VINE);
        addLegacyId("106:1", Material.GLOW_LICHEN);
        addLegacyId("108", Material.BRICK_STAIRS);
        addLegacyId("109", Material.STONE_BRICK_STAIRS);
        addLegacyId("110", Material.MYCELIUM);
        addLegacyId("111", Material.LILY_PAD);
        addLegacyId("113", Material.NETHER_BRICK_FENCE);
        addLegacyId("114", Material.NETHER_BRICK_STAIRS);
        addLegacyId("120", Material.END_PORTAL_FRAME);
        addLegacyId("121", Material.END_STONE);
        addLegacyId("122", Material.DRAGON_EGG);
        addLegacyId("128", Material.SANDSTONE_STAIRS);
        addLegacyId("130", Material.ENDER_CHEST);
        addLegacyId("138", Material.BEACON);
        addLegacyId("139", Material.COBBLESTONE_WALL);
        addLegacyId("139:1", Material.MOSSY_COBBLESTONE_WALL);
        addLegacyId("145", Material.ANVIL);
        addLegacyId("145:1", Material.CHIPPED_ANVIL);
        addLegacyId("145:2", Material.DAMAGED_ANVIL);
        addLegacyId("19:2", Material.DECORATED_POT);

        addLegacyId("422", Material.NETHERITE_SHOVEL);
        addLegacyId("423", Material.NETHERITE_PICKAXE);
        addLegacyId("424", Material.NETHERITE_AXE);
        addLegacyId("425", Material.NETHERITE_HOE);

        // Other
        addLegacyId("255", Material.STRUCTURE_BLOCK);
        addLegacyId("259", Material.FLINT_AND_STEEL);
        addLegacyId("260", Material.APPLE);
        addLegacyId("261", Material.BOW);
        addLegacyId("262", Material.ARROW);
        addLegacyId("263", Material.COAL);
        addLegacyId("263:1", Material.CHARCOAL);
        addLegacyId("264", Material.DIAMOND);
        addLegacyId("265", Material.IRON_INGOT);
        addLegacyId("266", Material.GOLD_INGOT);
        addLegacyId("267", Material.IRON_SWORD);
        addLegacyId("268", Material.WOODEN_SWORD);
        addLegacyId("269", Material.WOODEN_SHOVEL);
        addLegacyId("270", Material.WOODEN_PICKAXE);
        addLegacyId("271", Material.WOODEN_AXE);
        addLegacyId("272", Material.STONE_SWORD);
        addLegacyId("273", Material.STONE_SHOVEL);
        addLegacyId("274", Material.STONE_PICKAXE);
        addLegacyId("275", Material.STONE_AXE);
        addLegacyId("276", Material.DIAMOND_SWORD);
        addLegacyId("277", Material.DIAMOND_SHOVEL);
        addLegacyId("278", Material.DIAMOND_PICKAXE);
        addLegacyId("279", Material.DIAMOND_AXE);
        addLegacyId("280", Material.STICK);
        addLegacyId("281", Material.BOWL);
        addLegacyId("282", Material.MUSHROOM_STEW);
        addLegacyId("283", Material.GOLDEN_SWORD);
        addLegacyId("284", Material.GOLDEN_SHOVEL);
        addLegacyId("285", Material.GOLDEN_PICKAXE);
        addLegacyId("286", Material.GOLDEN_AXE);
        addLegacyId("287", Material.STRING);
        addLegacyId("288", Material.FEATHER);
        addLegacyId("289", Material.GUNPOWDER);
        addLegacyId("290", Material.WOODEN_HOE);
        addLegacyId("291", Material.STONE_HOE);
        addLegacyId("292", Material.IRON_HOE);
        addLegacyId("293", Material.DIAMOND_HOE);
        addLegacyId("294", Material.GOLDEN_HOE);
        addLegacyId("295", Material.WHEAT_SEEDS);
        addLegacyId("296", Material.WHEAT);
        addLegacyId("297", Material.BREAD);
        addLegacyId("298", Material.LEATHER_HELMET);
        addLegacyId("299", Material.LEATHER_CHESTPLATE);
        addLegacyId("300", Material.LEATHER_LEGGINGS);
        addLegacyId("301", Material.LEATHER_BOOTS);
        addLegacyId("302", Material.CHAINMAIL_HELMET);
        addLegacyId("303", Material.CHAINMAIL_CHESTPLATE);
        addLegacyId("304", Material.CHAINMAIL_LEGGINGS);
        addLegacyId("305", Material.CHAINMAIL_BOOTS);
        addLegacyId("306", Material.IRON_HELMET);
        addLegacyId("307", Material.IRON_CHESTPLATE);
        addLegacyId("308", Material.IRON_LEGGINGS);
        addLegacyId("309", Material.IRON_BOOTS);
        addLegacyId("310", Material.DIAMOND_HELMET);
        addLegacyId("311", Material.DIAMOND_CHESTPLATE);
        addLegacyId("312", Material.DIAMOND_LEGGINGS);
        addLegacyId("313", Material.DIAMOND_BOOTS);
        addLegacyId("314", Material.GOLDEN_HELMET);
        addLegacyId("315", Material.GOLDEN_CHESTPLATE);
        addLegacyId("316", Material.GOLDEN_LEGGINGS);
        addLegacyId("317", Material.GOLDEN_BOOTS);
        addLegacyId("318", Material.FLINT);
        addLegacyId("319", Material.PORKCHOP);
        addLegacyId("320", Material.COOKED_PORKCHOP);
        addLegacyId("321", Material.PAINTING);
        addLegacyId("322", Material.GOLDEN_APPLE);
        addLegacyId("322:1", Material.ENCHANTED_GOLDEN_APPLE);
        addLegacyId("323", Material.OAK_SIGN);
        addLegacyId("324", Material.OAK_DOOR);
        addLegacyId("325", Material.BUCKET);
        addLegacyId("326", Material.WATER_BUCKET);
        addLegacyId("327", Material.LAVA_BUCKET);
        addLegacyId("328", Material.MINECART);
        addLegacyId("329", Material.SADDLE);
        addLegacyId("330", Material.IRON_DOOR);
        addLegacyId("331", Material.REDSTONE);
        addLegacyId("332", Material.SNOWBALL);
        addLegacyId("333", Material.OAK_BOAT);
        addLegacyId("334", Material.LEATHER);
        addLegacyId("335", Material.MILK_BUCKET);
        addLegacyId("336", Material.BRICK);
        addLegacyId("337", Material.CLAY_BALL);
        addLegacyId("338", Material.SUGAR_CANE);
        addLegacyId("339", Material.PAPER);
        addLegacyId("340", Material.BOOK);
        addLegacyId("341", Material.SLIME_BALL);
        addLegacyId("342", Material.CHEST_MINECART);
        addLegacyId("343", Material.FURNACE_MINECART);
        addLegacyId("344", Material.EGG);
        addLegacyId("345", Material.COMPASS);
        addLegacyId("346", Material.FISHING_ROD);
        addLegacyId("347", Material.CLOCK);
        addLegacyId("348", Material.GLOWSTONE_DUST);
        addLegacyId("349:1", Material.SALMON);
        addLegacyId("349:3", Material.PUFFERFISH);
        addLegacyId("350", Material.COOKED_COD);
        addLegacyId("350:1", Material.COOKED_SALMON);
        addLegacyId("351", Material.INK_SAC);
        addLegacyId("351:3", Material.COCOA_BEANS);
        addLegacyId("351:4", Material.LAPIS_LAZULI);
        addLegacyId("351:5", Material.PURPLE_DYE);
        addLegacyId("351:6", Material.CYAN_DYE);
        addLegacyId("351:7", Material.LIGHT_GRAY_DYE);
        addLegacyId("351:8", Material.GRAY_DYE);
        addLegacyId("351:9", Material.PINK_DYE);
        addLegacyId("351:10", Material.LIME_DYE);
        addLegacyId("351:11", Material.YELLOW_DYE);
        addLegacyId("351:12", Material.LIGHT_BLUE_DYE);
        addLegacyId("351:13", Material.MAGENTA_DYE);
        addLegacyId("351:14", Material.ORANGE_DYE);
        addLegacyId("351:15", Material.BONE_MEAL);
        addLegacyId("352", Material.BONE);
        addLegacyId("353", Material.SUGAR);
        addLegacyId("354", Material.CAKE);
        addLegacyId("355", Material.RED_BED);
        addLegacyId("357", Material.COOKIE);
        addLegacyId("358", Material.MAP);
        addLegacyId("359", Material.SHEARS);
        addLegacyId("360", Material.MELON_SLICE);
        addLegacyId("361", Material.PUMPKIN_SEEDS);
        addLegacyId("362", Material.MELON_SEEDS);
        addLegacyId("363", Material.BEEF);
        addLegacyId("364", Material.COOKED_BEEF);
        addLegacyId("365", Material.CHICKEN);
        addLegacyId("366", Material.COOKED_CHICKEN);
        addLegacyId("367", Material.ROTTEN_FLESH);
        addLegacyId("368", Material.ENDER_PEARL);
        addLegacyId("369", Material.BLAZE_ROD);
        addLegacyId("370", Material.GHAST_TEAR);
        addLegacyId("371", Material.GOLD_NUGGET);
        addLegacyId("372", Material.NETHER_WART);
        addLegacyId("373", Material.POTION);
        addLegacyId("374", Material.GLASS_BOTTLE);
        addLegacyId("375", Material.SPIDER_EYE);
        addLegacyId("376", Material.FERMENTED_SPIDER_EYE);
        addLegacyId("377", Material.BLAZE_POWDER);
        addLegacyId("378", Material.MAGMA_CREAM);
        addLegacyId("379", Material.BREWING_STAND);
        addLegacyId("380", Material.CAULDRON);
        addLegacyId("381", Material.ENDER_EYE);
        addLegacyId("382", Material.GLISTERING_MELON_SLICE);
        addLegacyId("384", Material.EXPERIENCE_BOTTLE);
        addLegacyId("385", Material.FIRE_CHARGE);
        addLegacyId("386", Material.WRITABLE_BOOK);
        addLegacyId("387", Material.WRITTEN_BOOK);
        addLegacyId("388", Material.EMERALD);
        addLegacyId("389", Material.ITEM_FRAME);
        addLegacyId("390", Material.FLOWER_POT);
        addLegacyId("391", Material.CARROT);
        addLegacyId("392", Material.POTATO);
        addLegacyId("393", Material.BAKED_POTATO);
        addLegacyId("394", Material.POISONOUS_POTATO);
        addLegacyId("395", Material.MAP);
        addLegacyId("396", Material.GOLDEN_CARROT);
        addLegacyId("397", Material.SKELETON_SKULL);
        addLegacyId("397:1", Material.WITHER_SKELETON_SKULL);
        addLegacyId("397:2", Material.ZOMBIE_HEAD);
        addLegacyId("397:3", Material.PLAYER_HEAD);
        addLegacyId("397:4", Material.CREEPER_HEAD);
        addLegacyId("397:5", Material.DRAGON_HEAD);
        addLegacyId("399", Material.NETHER_STAR);
        addLegacyId("400", Material.PUMPKIN_PIE);
        addLegacyId("401", Material.FIREWORK_ROCKET);
        addLegacyId("402", Material.FIREWORK_STAR);
        addLegacyId("403", Material.ENCHANTED_BOOK);
        addLegacyId("405", Material.NETHER_BRICK);
        addLegacyId("406", Material.QUARTZ);
        addLegacyId("407", Material.TNT_MINECART);
        addLegacyId("408", Material.HOPPER_MINECART);
        addLegacyId("409", Material.PRISMARINE_SHARD);
        addLegacyId("410", Material.PRISMARINE_CRYSTALS);
        addLegacyId("411", Material.RABBIT);
        addLegacyId("412", Material.COOKED_RABBIT);
        addLegacyId("413", Material.RABBIT_STEW);
        addLegacyId("414", Material.RABBIT_FOOT);
        addLegacyId("415", Material.RABBIT_HIDE);
        addLegacyId("416", Material.ARMOR_STAND);
        addLegacyId("417", Material.IRON_HORSE_ARMOR);
        addLegacyId("418", Material.GOLDEN_HORSE_ARMOR);
        addLegacyId("419", Material.DIAMOND_HORSE_ARMOR);
        addLegacyId("420", Material.LEAD);
        addLegacyId("421", Material.NAME_TAG);
        addLegacyId("422", Material.COMMAND_BLOCK_MINECART);
        addLegacyId("423", Material.MUTTON);
        addLegacyId("424", Material.COOKED_MUTTON);
        addLegacyId("425", Material.WHITE_BANNER);
        addLegacyId("426", Material.END_CRYSTAL);
        addLegacyId("432", Material.CHORUS_FRUIT);
        addLegacyId("433", Material.POPPED_CHORUS_FRUIT);
        addLegacyId("434", Material.BEETROOT);
        addLegacyId("435", Material.BEETROOT_SEEDS);
        addLegacyId("436", Material.BEETROOT_SOUP);
        addLegacyId("437", Material.DRAGON_BREATH);
        addLegacyId("438", Material.SPLASH_POTION);
        addLegacyId("439", Material.SPECTRAL_ARROW);
        addLegacyId("440", Material.TIPPED_ARROW);
        addLegacyId("441", Material.LINGERING_POTION);
        addLegacyId("442", Material.SHIELD);
        addLegacyId("443", Material.ELYTRA);
        addLegacyId("449", Material.TOTEM_OF_UNDYING);
        addLegacyId("450", Material.SHULKER_SHELL);
        addLegacyId("452", Material.IRON_NUGGET);
        addLegacyId("453", Material.KNOWLEDGE_BOOK);
        addLegacyId("2256", Material.MUSIC_DISC_13);
        addLegacyId("2257", Material.MUSIC_DISC_CAT);
        addLegacyId("2258", Material.MUSIC_DISC_BLOCKS);
        addLegacyId("2259", Material.MUSIC_DISC_CHIRP);
        addLegacyId("2260", Material.MUSIC_DISC_FAR);
        addLegacyId("2261", Material.MUSIC_DISC_MALL);
        addLegacyId("2262", Material.MUSIC_DISC_MELLOHI);
        addLegacyId("2263", Material.MUSIC_DISC_STAL);
        addLegacyId("2264", Material.MUSIC_DISC_STRAD);
        addLegacyId("2265", Material.MUSIC_DISC_WARD);
        addLegacyId("2266", Material.MUSIC_DISC_11);
        addLegacyId("2267", Material.MUSIC_DISC_WAIT);
        addLegacyId("162", Material.ACACIA_LOG);
        addLegacyId("162:1", Material.DARK_OAK_LOG);
        addLegacyId("161", Material.ACACIA_LEAVES);
        addLegacyId("161:1", Material.DARK_OAK_LEAVES);
        addLegacyId("182", Material.RED_SANDSTONE_SLAB);
        addLegacyId("205", Material.PURPUR_SLAB);
        addLegacyId("43:7", Material.SMOOTH_QUARTZ);
        addLegacyId("43:8", Material.SMOOTH_STONE);
        addLegacyId("198", Material.END_ROD);
        addLegacyId("201", Material.PURPUR_BLOCK);
        addLegacyId("202", Material.PURPUR_PILLAR);
        addLegacyId("203", Material.PURPUR_STAIRS);
        addLegacyId("53", Material.OAK_STAIRS);
        addLegacyId("53:1", Material.SPRUCE_STAIRS);
        addLegacyId("53:2", Material.BIRCH_STAIRS);
        addLegacyId("53:3", Material.JUNGLE_STAIRS);
        addLegacyId("53:4", Material.ACACIA_STAIRS);
        addLegacyId("53:5", Material.DARK_OAK_STAIRS);
        addLegacyId("53:6", Material.CHERRY_STAIRS);
        addLegacyId("53:7", Material.MANGROVE_STAIRS);
        addLegacyId("53:8", Material.BAMBOO_STAIRS);
        addLegacyId("53:9", Material.CRIMSON_STAIRS);
        addLegacyId("53:10", Material.WARPED_STAIRS);
        addLegacyId("53:11", Material.BAMBOO_MOSAIC_STAIRS);
        addLegacyId("107", Material.OAK_FENCE_GATE);
        addLegacyId("107:1", Material.SPRUCE_FENCE_GATE);
        addLegacyId("107:2", Material.BIRCH_FENCE_GATE);
        addLegacyId("107:3", Material.JUNGLE_FENCE_GATE);
        addLegacyId("107:4", Material.ACACIA_FENCE_GATE);
        addLegacyId("107:5", Material.DARK_OAK_FENCE_GATE);
        addLegacyId("107:6", Material.CHERRY_FENCE_GATE);
        addLegacyId("107:7", Material.MANGROVE_FENCE_GATE);
        addLegacyId("107:8", Material.BAMBOO_FENCE_GATE);
        addLegacyId("107:9", Material.CRIMSON_FENCE_GATE);
        addLegacyId("107:10", Material.WARPED_FENCE_GATE);
        addLegacyId("183", Material.SPRUCE_FENCE_GATE);
        addLegacyId("184", Material.BIRCH_FENCE_GATE);
        addLegacyId("185", Material.JUNGLE_FENCE_GATE);
        addLegacyId("187", Material.ACACIA_FENCE_GATE);
        addLegacyId("186", Material.DARK_OAK_FENCE_GATE);
        addLegacyId("112", Material.NETHER_BRICKS);
        addLegacyId("116", Material.ENCHANTING_TABLE);
        addLegacyId("206", Material.END_STONE_BRICKS);
        addLegacyId("133", Material.EMERALD_BLOCK);
        addLegacyId("57", Material.DIAMOND_BLOCK);
        addLegacyId("134", Material.SPRUCE_STAIRS);
        addLegacyId("135", Material.BIRCH_STAIRS);
        addLegacyId("136", Material.JUNGLE_STAIRS);
        addLegacyId("151", Material.DAYLIGHT_DETECTOR);
        addLegacyId("155:1", Material.CHISELED_QUARTZ_BLOCK);
        addLegacyId("155:2", Material.QUARTZ_PILLAR);
        addLegacyId("156", Material.QUARTZ_STAIRS);
        addLegacyId("159", Material.WHITE_TERRACOTTA);
        addLegacyId("159:1", Material.ORANGE_TERRACOTTA);
        addLegacyId("159:2", Material.MAGENTA_TERRACOTTA);
        addLegacyId("159:3", Material.LIGHT_BLUE_TERRACOTTA);
        addLegacyId("159:4", Material.YELLOW_TERRACOTTA);
        addLegacyId("159:5", Material.LIME_TERRACOTTA);
        addLegacyId("159:6", Material.PINK_TERRACOTTA);
        addLegacyId("159:7", Material.GRAY_TERRACOTTA);
        addLegacyId("159:8", Material.LIGHT_GRAY_TERRACOTTA);
        addLegacyId("159:9", Material.CYAN_TERRACOTTA);
        addLegacyId("159:10", Material.PURPLE_TERRACOTTA);
        addLegacyId("159:11", Material.BLUE_TERRACOTTA);
        addLegacyId("159:12", Material.BROWN_TERRACOTTA);
        addLegacyId("159:13", Material.GREEN_TERRACOTTA);
        addLegacyId("159:14", Material.RED_TERRACOTTA);
        addLegacyId("159:15", Material.BLACK_TERRACOTTA);
        addLegacyId("166", Material.BARRIER);
        addLegacyId("171", Material.WHITE_CARPET);
        addLegacyId("171:1", Material.ORANGE_CARPET);
        addLegacyId("171:2", Material.MAGENTA_CARPET);
        addLegacyId("171:3", Material.LIGHT_BLUE_CARPET);
        addLegacyId("171:4", Material.YELLOW_CARPET);
        addLegacyId("171:5", Material.LIME_CARPET);
        addLegacyId("171:6", Material.PINK_CARPET);
        addLegacyId("171:7", Material.GRAY_CARPET);
        addLegacyId("171:8", Material.LIGHT_GRAY_CARPET);
        addLegacyId("171:9", Material.CYAN_CARPET);
        addLegacyId("171:10", Material.PURPLE_CARPET);
        addLegacyId("171:11", Material.BLUE_CARPET);
        addLegacyId("171:12", Material.BROWN_CARPET);
        addLegacyId("171:13", Material.GREEN_CARPET);
        addLegacyId("171:14", Material.RED_CARPET);
        addLegacyId("171:15", Material.BLACK_CARPET);
        addLegacyId("172", Material.TERRACOTTA);
        addLegacyId("173", Material.COAL_BLOCK);
        addLegacyId("174", Material.PACKED_ICE);
        addLegacyId("163", Material.ACACIA_STAIRS);
        addLegacyId("164", Material.DARK_OAK_STAIRS);
        addLegacyId("165", Material.SLIME_BLOCK);
        addLegacyId("208", Material.DIRT_PATH);
        addLegacyId("95", Material.WHITE_STAINED_GLASS);
        addLegacyId("95:1", Material.ORANGE_STAINED_GLASS);
        addLegacyId("95:2", Material.MAGENTA_STAINED_GLASS);
        addLegacyId("95:3", Material.LIGHT_BLUE_STAINED_GLASS);
        addLegacyId("95:4", Material.YELLOW_STAINED_GLASS);
        addLegacyId("95:5", Material.LIME_STAINED_GLASS);
        addLegacyId("95:6", Material.PINK_STAINED_GLASS);
        addLegacyId("95:7", Material.GRAY_STAINED_GLASS);
        addLegacyId("95:8", Material.LIGHT_GRAY_STAINED_GLASS);
        addLegacyId("95:9", Material.CYAN_STAINED_GLASS);
        addLegacyId("95:10", Material.PURPLE_STAINED_GLASS);
        addLegacyId("95:11", Material.BLUE_STAINED_GLASS);
        addLegacyId("95:12", Material.BROWN_STAINED_GLASS);
        addLegacyId("95:13", Material.GREEN_STAINED_GLASS);
        addLegacyId("95:14", Material.RED_STAINED_GLASS);
        addLegacyId("95:15", Material.BLACK_STAINED_GLASS);
        addLegacyId("160", Material.WHITE_STAINED_GLASS_PANE);
        addLegacyId("160:1", Material.ORANGE_STAINED_GLASS_PANE);
        addLegacyId("160:2", Material.MAGENTA_STAINED_GLASS_PANE);
        addLegacyId("160:3", Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        addLegacyId("160:4", Material.YELLOW_STAINED_GLASS_PANE);
        addLegacyId("160:5", Material.LIME_STAINED_GLASS_PANE);
        addLegacyId("160:6", Material.PINK_STAINED_GLASS_PANE);
        addLegacyId("160:7", Material.GRAY_STAINED_GLASS_PANE);
        addLegacyId("160:8", Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        addLegacyId("160:9", Material.CYAN_STAINED_GLASS_PANE);
        addLegacyId("160:10", Material.PURPLE_STAINED_GLASS_PANE);
        addLegacyId("160:11", Material.BLUE_STAINED_GLASS_PANE);
        addLegacyId("160:12", Material.BROWN_STAINED_GLASS_PANE);
        addLegacyId("160:13", Material.GREEN_STAINED_GLASS_PANE);
        addLegacyId("160:14", Material.RED_STAINED_GLASS_PANE);
        addLegacyId("160:15", Material.BLACK_STAINED_GLASS_PANE);
        addLegacyId("168", Material.PRISMARINE);
        addLegacyId("168:1", Material.PRISMARINE_BRICKS);
        addLegacyId("168:2", Material.DARK_PRISMARINE);
        addLegacyId("169", Material.SEA_LANTERN);
        addLegacyId("179", Material.RED_SANDSTONE);
        addLegacyId("179:1", Material.CHISELED_RED_SANDSTONE);
        addLegacyId("179:2", Material.CUT_RED_SANDSTONE);
        addLegacyId("210", Material.REPEATING_COMMAND_BLOCK);
        addLegacyId("211", Material.CHAIN_COMMAND_BLOCK);
        addLegacyId("213", Material.MAGMA_BLOCK);
        addLegacyId("214", Material.NETHER_WART_BLOCK);
        addLegacyId("215", Material.RED_NETHER_BRICKS);
        addLegacyId("216", Material.BONE_BLOCK);
        addLegacyId("235", Material.WHITE_GLAZED_TERRACOTTA);
        addLegacyId("236", Material.ORANGE_GLAZED_TERRACOTTA);
        addLegacyId("237", Material.MAGENTA_GLAZED_TERRACOTTA);
        addLegacyId("238", Material.LIGHT_BLUE_GLAZED_TERRACOTTA);
        addLegacyId("239", Material.YELLOW_GLAZED_TERRACOTTA);
        addLegacyId("240", Material.LIME_GLAZED_TERRACOTTA);
        addLegacyId("241", Material.PINK_GLAZED_TERRACOTTA);
        addLegacyId("242", Material.GRAY_GLAZED_TERRACOTTA);
        addLegacyId("243", Material.LIGHT_GRAY_GLAZED_TERRACOTTA);
        addLegacyId("244", Material.CYAN_GLAZED_TERRACOTTA);
        addLegacyId("245", Material.PURPLE_GLAZED_TERRACOTTA);
        addLegacyId("246", Material.BLUE_GLAZED_TERRACOTTA);
        addLegacyId("247", Material.BROWN_GLAZED_TERRACOTTA);
        addLegacyId("248", Material.GREEN_GLAZED_TERRACOTTA);
        addLegacyId("249", Material.RED_GLAZED_TERRACOTTA);
        addLegacyId("250", Material.BLACK_GLAZED_TERRACOTTA);
        addLegacyId("251", Material.WHITE_CONCRETE);
        addLegacyId("251:1", Material.ORANGE_CONCRETE);
        addLegacyId("251:2", Material.MAGENTA_CONCRETE);
        addLegacyId("251:3", Material.LIGHT_BLUE_CONCRETE);
        addLegacyId("251:4", Material.YELLOW_CONCRETE);
        addLegacyId("251:5", Material.LIME_CONCRETE);
        addLegacyId("251:6", Material.PINK_CONCRETE);
        addLegacyId("251:7", Material.GRAY_CONCRETE);
        addLegacyId("251:8", Material.LIGHT_GRAY_CONCRETE);
        addLegacyId("251:9", Material.CYAN_CONCRETE);
        addLegacyId("251:10", Material.PURPLE_CONCRETE);
        addLegacyId("251:11", Material.BLUE_CONCRETE);
        addLegacyId("251:12", Material.BROWN_CONCRETE);
        addLegacyId("251:13", Material.GREEN_CONCRETE);
        addLegacyId("251:14", Material.RED_CONCRETE);
        addLegacyId("251:15", Material.BLACK_CONCRETE);

    }

    public static Material getMaterialFromLegacyId(String legacyId) {
        return LEGACY_IDS.get(legacyId);
    }

    public static String getLegacyIdFromMaterial(Material material) {
        return MATERIAL_TO_LEGACY.get(material);
    }
}