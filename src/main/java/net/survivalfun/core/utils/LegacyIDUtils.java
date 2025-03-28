package net.survivalfun.core.utils;

import org.bukkit.Material;
import java.util.HashMap;
import java.util.Map;

public class LegacyIDUtils {
    private static final Map<String, Material> LEGACY_IDS = new HashMap<>();
    private static final Map<Material, String> MATERIAL_TO_LEGACY = new HashMap<>();

    static {
        initializeLegacyIds();
    }

    private static void initializeLegacyIds() {
        // Blocks
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

        addLegacyId("4", Material.COBBLESTONE);
        addLegacyId("4:1", Material.MOSSY_COBBLESTONE);

        // Planks and wood types
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

        // Saplings
        addLegacyId("6", Material.OAK_SAPLING);
        addLegacyId("6:1", Material.SPRUCE_SAPLING);
        addLegacyId("6:2", Material.BIRCH_SAPLING);
        addLegacyId("6:3", Material.JUNGLE_SAPLING);
        addLegacyId("6:4", Material.ACACIA_SAPLING);
        addLegacyId("6:5", Material.DARK_OAK_SAPLING);
        addLegacyId("6:6", Material.CHERRY_SAPLING);
        addLegacyId("6:7", Material.MANGROVE_PROPAGULE);

        // Logs and wood
        addLegacyId("7:0", Material.OAK_LOG);
        addLegacyId("7:1", Material.SPRUCE_LOG);
        addLegacyId("7:2", Material.BIRCH_LOG);
        addLegacyId("7:3", Material.JUNGLE_LOG);
        addLegacyId("7:4", Material.ACACIA_LOG);
        addLegacyId("7:5", Material.DARK_OAK_LOG);
        addLegacyId("7:6", Material.MANGROVE_LOG);
        addLegacyId("7:7", Material.CHERRY_LOG);
        addLegacyId("7:8", Material.CRIMSON_STEM);
        addLegacyId("7:9", Material.WARPED_STEM);

        // Stripped logs
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

        // Bamboo blocks
        addLegacyId("7:20", Material.BAMBOO_BLOCK);
        addLegacyId("7:21", Material.STRIPPED_BAMBOO_BLOCK);

        // Wood blocks
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

        // Stripped wood blocks
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

        // Leaves
        addLegacyId("8:0", Material.OAK_LEAVES);
        addLegacyId("8:1", Material.SPRUCE_LEAVES);
        addLegacyId("8:2", Material.BIRCH_LEAVES);
        addLegacyId("8:3", Material.JUNGLE_LEAVES);
        addLegacyId("8:4", Material.ACACIA_LEAVES);
        addLegacyId("8:5", Material.DARK_OAK_LEAVES);
        addLegacyId("8:6", Material.MANGROVE_LEAVES);
        addLegacyId("8:7", Material.CHERRY_LEAVES);
        addLegacyId("8:8", Material.AZALEA_LEAVES);
        addLegacyId("8:9", Material.FLOWERING_AZALEA_LEAVES);

        // Azaleas
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
        addLegacyId("15", Material.IRON_ORE);
        addLegacyId("15:1", Material.DEEPSLATE_IRON_ORE);
        addLegacyId("16", Material.COAL_ORE);
        addLegacyId("16:1", Material.DEEPSLATE_COAL_ORE);
        addLegacyId("17", Material.COPPER_ORE);
        addLegacyId("18", Material.NOTE_BLOCK);




        // Other blocks
        addLegacyId("19", Material.SPONGE);
        addLegacyId("19:1", Material.WET_SPONGE);
        addLegacyId("20", Material.GLASS);
        addLegacyId("20:1", Material.TINTED_GLASS);
        addLegacyId("21", Material.LAPIS_ORE);
        addLegacyId("21:1", Material.DEEPSLATE_LAPIS_ORE);
        addLegacyId("22", Material.LAPIS_BLOCK);
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
        addLegacyId("31:3", Material.SEAGRASS);
        addLegacyId("31:4", Material.SEA_PICKLE);
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
        addLegacyId("38:11", Material.WITHER_ROSE);
        addLegacyId("38:12", Material.TORCHFLOWER);
        addLegacyId("38:13", Material.SUNFLOWER);
        addLegacyId("38:14", Material.LILAC);
        addLegacyId("38:15", Material.ROSE_BUSH);
        addLegacyId("38:16", Material.PEONY);
        addLegacyId("38:17", Material.SPORE_BLOSSOM);
        addLegacyId("38:18", Material.CRIMSON_FUNGUS);
        addLegacyId("38:19", Material.WARPED_FUNGUS);
        addLegacyId("38:20", Material.CRIMSON_ROOTS);
        addLegacyId("38:21", Material.WARPED_ROOTS);
        addLegacyId("38:22", Material.NETHER_SPROUTS);
        addLegacyId("38:23", Material.WEEPING_VINES);
        addLegacyId("38:24", Material.TWISTING_VINES);
        addLegacyId("38:25", Material.SUGAR_CANE);
        addLegacyId("38:26", Material.KELP);
        addLegacyId("38:27", Material.MOSS_CARPET);
        addLegacyId("38:28", Material.PINK_PETALS);
        addLegacyId("38:29", Material.MOSS_BLOCK);
        addLegacyId("38:30", Material.HANGING_ROOTS);
        addLegacyId("38:31", Material.BIG_DRIPLEAF);
        addLegacyId("38:32", Material.SMALL_DRIPLEAF);
        addLegacyId("38:33", Material.BAMBOO);
    }

    private static void addLegacyId(String legacyId, Material material) {
        LEGACY_IDS.put(legacyId, material);
        MATERIAL_TO_LEGACY.put(material, legacyId);
    }

    public static Material getMaterialFromLegacyId(String legacyId) {
        return LEGACY_IDS.get(legacyId);
    }

    public static String getLegacyIdFromMaterial(Material material) {
        return MATERIAL_TO_LEGACY.get(material);
    }
}