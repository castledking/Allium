package net.survivalfun.core.managers.core;

import java.util.HashMap;
import java.util.Map;

public class Alias {

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        // Define aliases for materials
        ALIASES.put("enderpearl", "ENDER_PEARL");
        ALIASES.put("water_bottle", "POTION");
        ALIASES.put("pearl", "ENDER_PEARL");
        ALIASES.put("stonebrick", "STONE_BRICKS");
        ALIASES.put("goldapple", "GOLDEN_APPLE");
        ALIASES.put("godapple", "ENCHANTED_GOLDEN_APPLE");
        ALIASES.put("xp", "EXPERIENCE_BOTTLE");
        ALIASES.put("seeds", "wheat_seeds");
        ALIASES.put("seed", "wheat_seeds");
        ALIASES.put("mseeds", "melon_seeds");
        ALIASES.put("mseed", "melon_seeds");
        ALIASES.put("leaves", "oak_leaves");
        ALIASES.put("sleaves", "spruce_leaves");
        ALIASES.put("bleaves", "birch_leaves");
        ALIASES.put("jleaves", "jungle_leaves");
        ALIASES.put("dleaves", "dark_oak_leaves");
        ALIASES.put("cleaves", "cherry_leaves");
        ALIASES.put("mleaves", "mangrove_leaves");
        ALIASES.put("aleaves", "acacia_leaves");
        ALIASES.put("fazleaves", "flowering_azalea_leaves");
        ALIASES.put("azleaves", "azalea_leaves");
        ALIASES.put("oakleaf", "OAK_LEAVES");
        ALIASES.put("birchleaf", "BIRCH_LEAVES");
        ALIASES.put("spruceleaf", "SPRUCE_LEAVES");
        ALIASES.put("jungleaf", "JUNGLE_LEAVES");
        ALIASES.put("acacialeaf", "ACACIA_LEAVES");
        ALIASES.put("darkleaf", "DARK_OAK_LEAVES");
        ALIASES.put("mangroveleaf", "MANGROVE_LEAVES");
        ALIASES.put("cherryleaf", "CHERRY_LEAVES");
        ALIASES.put("flowerpot", "FLOWER_POT");
        ALIASES.put("lilypad", "LILY_PAD");
        ALIASES.put("nwart", "nether_wart");
        ALIASES.put("pseed", "pumpkin_seeds");
        ALIASES.put("pseeds", "pumpkin_seeds");
        ALIASES.put("bseed", "beetroot_seeds");
        ALIASES.put("bseeds", "beetroot_seeds");
        ALIASES.put("crroots", "crimson_roots");
        ALIASES.put("waroots", "warped_roots");
        ALIASES.put("nsprouts", "nether_sprouts"); // Warped biome plant
        ALIASES.put("flower", "dandelion");
        ALIASES.put("flower:1", "poppy");
        ALIASES.put("flower:2", "blue_orchid");
        ALIASES.put("flower:3", "allium");
        ALIASES.put("flower:4", "azure_bluet");
        ALIASES.put("flower:5", "red_tulip");
        ALIASES.put("flower:6", "orange_tulip");
        ALIASES.put("flower:7", "white_tulip");
        ALIASES.put("flower:8", "pink_tulip");
        ALIASES.put("flower:9", "oxeye_daisy");
        ALIASES.put("flower:10", "cornflower");
        ALIASES.put("flower:11", "lily_of_the_valley");
        ALIASES.put("flower:12", "wither_rose");
        ALIASES.put("flower:13", "sunflower");
        ALIASES.put("flower:14", "lilac");
        ALIASES.put("flower:15", "rose_bush");
        ALIASES.put("flower:16", "peony");
        ALIASES.put("osapling", "oak_sapling");
        ALIASES.put("ssapling", "spruce_sapling");
        ALIASES.put("bsapling", "birch_sapling");
        ALIASES.put("jsapling", "jungle_sapling");
        ALIASES.put("dsapling", "dark_oak_sapling");
        ALIASES.put("csapling", "cherry_sapling");
        ALIASES.put("msapling", "mangrove_sapling");
        ALIASES.put("asapling", "acacia_sapling");
        ALIASES.put("azsapling", "azalea");
        ALIASES.put("fazsapling", "flowering_azalea");

        ALIASES.put("planks", "oak_planks");
        ALIASES.put("splanks", "spruce_planks");
        ALIASES.put("bplanks", "birch_planks");
        ALIASES.put("jplanks", "jungle_planks");
        ALIASES.put("dplanks", "dark_oak_planks");
        ALIASES.put("cplanks", "cherry_planks");
        ALIASES.put("mplanks", "mangrove_planks");
        ALIASES.put("pplanks", "pale_oak_planks");
        ALIASES.put("baplanks", "bamboo_planks");
        ALIASES.put("crplanks", "crimson_planks");
        ALIASES.put("wplanks", "warped_planks");

        ALIASES.put("log", "oak_log");
        ALIASES.put("olog", "oak_log");
        ALIASES.put("slog", "spruce_log");
        ALIASES.put("blog", "birch_log");
        ALIASES.put("jlog", "jungle_log");
        ALIASES.put("dlog", "dark_oak_log");
        ALIASES.put("clog", "cherry_log");
        ALIASES.put("mlog", "mangrove_log");
        ALIASES.put("plog", "pale_oak_log");
        ALIASES.put("balog", "bamboo_block");
        ALIASES.put("crstem", "crimson_stem");
        ALIASES.put("wstem", "warped_stem");


        ALIASES.put("wood", "oak_wood");
        ALIASES.put("owood", "oak_wood");
        ALIASES.put("swood", "spruce_wood");
        ALIASES.put("bwood", "birch_wood");
        ALIASES.put("jwood", "jungle_wood");
        ALIASES.put("dwood", "dark_oak_wood");
        ALIASES.put("cwood", "cherry_wood");
        ALIASES.put("mwood", "mangrove_wood");
        ALIASES.put("pwood", "pale_oak_wood");
        ALIASES.put("bawood", "bamboo_block");
        ALIASES.put("bablock", "bamboo_block");
        ALIASES.put("bamosaic", "bamboo_mosaic");
        ALIASES.put("crhyphae", "crimson_hyphae");
        ALIASES.put("crwood", "crimson_hyphae");
        ALIASES.put("whyphae", "warped_hyphae");
        ALIASES.put("wwood", "warped_hyphae");

        ALIASES.put("oswood", "stripped_oak_wood");
        ALIASES.put("sowood", "stripped_oak_wood");
        ALIASES.put("sswood", "stripped_spruce_wood");
        ALIASES.put("sbwood", "stripped_birch_wood");
        ALIASES.put("bswood", "stripped_birch_wood");
        ALIASES.put("jswood", "stripped_jungle_wood");
        ALIASES.put("sjwood", "stripped_jungle_wood");
        ALIASES.put("sdwood", "stripped_dark_oak_wood");
        ALIASES.put("dswood", "stripped_dark_oak_wood");
        ALIASES.put("scwood", "stripped_cherry_wood");
        ALIASES.put("cswood", "stripped_cherry_wood");
        ALIASES.put("smwood", "stripped_mangrove_wood");
        ALIASES.put("mswood", "stripped_mangrove_wood");
        ALIASES.put("spwood", "stripped_pale_oak_wood");
        ALIASES.put("pswood", "stripped_pale_oak_wood");
        ALIASES.put("sbablock", "stripped_bamboo_block");
        ALIASES.put("basblock", "stripped_bamboo_block");
        ALIASES.put("scrhyphae", "stripped_crimson_hyphae");
        ALIASES.put("crshyphae", "stripped_crimson_hyphae");
        ALIASES.put("scrwood", "stripped_crimson_hyphae");
        ALIASES.put("crswood", "stripped_crimson_hyphae");
        ALIASES.put("wshyphae", "stripped_warped_hyphae");
        ALIASES.put("swhyphae", "stripped_warped_hyphae");
        ALIASES.put("wswood", "stripped_warped_hyphae");
        ALIASES.put("swwood", "stripped_warped_hyphae");

        ALIASES.put("oslog", "stripped_oak_log");
        ALIASES.put("solog", "stripped_oak_log");
        ALIASES.put("sslog", "stripped_spruce_log");
        ALIASES.put("bslog", "stripped_birch_log");
        ALIASES.put("sblog", "stripped_birch_log");
        ALIASES.put("jslog", "stripped_jungle_log");
        ALIASES.put("sjlog", "stripped_jungle_log");
        ALIASES.put("dslog", "stripped_dark_oak_log");
        ALIASES.put("sdlog", "stripped_dark_oak_log");
        ALIASES.put("cslog", "stripped_cherry_log");
        ALIASES.put("sclog", "stripped_cherry_log");
        ALIASES.put("mslog", "stripped_mangrove_log");
        ALIASES.put("smlog", "stripped_mangrove_log");
        ALIASES.put("splog", "stripped_pale_oak_log");
        ALIASES.put("pslog", "stripped_pale_oak_log");
        ALIASES.put("scrstem", "stripped_crimson_stem");
        ALIASES.put("crsstem", "stripped_crimson_stem");
        ALIASES.put("swstem", "stripped_warped_stem");
        ALIASES.put("wsstem", "stripped_warped_stem");

        ALIASES.put("fence", "oak_fence");
        ALIASES.put("sfence", "spruce_fence");
        ALIASES.put("bfence", "birch_fence");
        ALIASES.put("jfence", "jungle_fence");
        ALIASES.put("dfence", "dark_oak_fence");
        ALIASES.put("cfence", "cherry_fence");
        ALIASES.put("mfence", "mangrove_fence");
        ALIASES.put("pfence", "pale_oak_fence");
        ALIASES.put("bafence", "bamboo_fence");
        ALIASES.put("crfence", "crimson_fence");
        ALIASES.put("wfence", "warped_fence");

        ALIASES.put("gate", "oak_fence_gate");
        ALIASES.put("sgate", "spruce_fence_gate");
        ALIASES.put("bgate", "birch_fence_gate");
        ALIASES.put("jgate", "jungle_fence_gate");
        ALIASES.put("dgate", "dark_oak_fence_gate");
        ALIASES.put("cgate", "cherry_fence_gate");
        ALIASES.put("mgate", "mangrove_fence_gate");
        ALIASES.put("pgate", "pale_oak_fence_gate");
        ALIASES.put("bagate", "bamboo_fence_gate");
        ALIASES.put("crgate", "crimson_fence_gate");
        ALIASES.put("wgate", "warped_fence_gate");

        ALIASES.put("door", "oak_door");
        ALIASES.put("sdoor", "spruce_door");
        ALIASES.put("bdoor", "birch_door");
        ALIASES.put("jdoor", "jungle_door");
        ALIASES.put("ddoor", "dark_oak_door");
        ALIASES.put("cdoor", "cherry_door");
        ALIASES.put("mdoor", "mangrove_door");
        ALIASES.put("pdoor", "pale_oak_door");
        ALIASES.put("badoor", "bamboo_door");
        ALIASES.put("crdoor", "crimson_door");
        ALIASES.put("wdoor", "warped_door");

        ALIASES.put("oakdoor", "OAK_DOOR");
        ALIASES.put("birchdoor", "BIRCH_DOOR");
        ALIASES.put("sprucedoor", "SPRUCE_DOOR");
        ALIASES.put("jungdoor", "JUNGLE_DOOR");
        ALIASES.put("acaciadoor", "ACACIA_DOOR");
        ALIASES.put("darkdoor", "DARK_OAK_DOOR");
        ALIASES.put("mangrovedoor", "MANGROVE_DOOR");
        ALIASES.put("bamboodoor", "BAMBOO_DOOR");
        ALIASES.put("irondoor", "IRON_DOOR");
        ALIASES.put("ironbar", "IRON_BARS");

        ALIASES.put("trapdoor", "oak_trapdoor");
        ALIASES.put("strapdoor", "spruce_trapdoor");
        ALIASES.put("btrapdoor", "birch_trapdoor");
        ALIASES.put("jtrapdoor", "jungle_trapdoor");
        ALIASES.put("dtrapdoor", "dark_oak_trapdoor");
        ALIASES.put("ctrapdoor", "cherry_trapdoor");
        ALIASES.put("mtrapdoor", "mangrove_trapdoor");
        ALIASES.put("ptrapdoor", "pale_oak_trapdoor");
        ALIASES.put("batrapdoor", "bamboo_trapdoor");
        ALIASES.put("crtrapdoor", "crimson_trapdoor");
        ALIASES.put("wtrapdoor", "warped_trapdoor");

        ALIASES.put("slab", "oak_slab");
        ALIASES.put("sslab", "spruce_slab");
        ALIASES.put("bslab", "birch_slab");
        ALIASES.put("jslab", "jungle_slab");
        ALIASES.put("dslab", "dark_oak_slab");
        ALIASES.put("cslab", "cherry_slab");
        ALIASES.put("mslab", "mangrove_slab");
        ALIASES.put("pslab", "pale_oak_slab");
        ALIASES.put("baslab", "bamboo_slab");
        ALIASES.put("bamoslab", "bamboo_mosaic_slab");
        ALIASES.put("crslab", "crimson_slab");
        ALIASES.put("wslab", "warped_slab");


        ALIASES.put("stairs", "oak_stairs");
        ALIASES.put("sstairs", "spruce_stairs");
        ALIASES.put("bstairs", "birch_stairs");
        ALIASES.put("jstairs", "jungle_stairs");
        ALIASES.put("dstairs", "dark_oak_stairs");
        ALIASES.put("cstairs", "cherry_stairs");
        ALIASES.put("mstairs", "mangrove_stairs");
        ALIASES.put("pstairs", "pale_oak_stairs");
        ALIASES.put("bastairs", "bamboo_stairs");
        ALIASES.put("bamostairs", "bamboo_mosaic_stairs");
        ALIASES.put("crstairs", "crimson_stairs");
        ALIASES.put("wstairs", "warped_stairs");

        ALIASES.put("sign", "oak_sign");
        ALIASES.put("ssign", "spruce_sign");
        ALIASES.put("bsign", "birch_sign");
        ALIASES.put("jsign", "jungle_sign");
        ALIASES.put("dsign", "dark_oak_sign");
        ALIASES.put("csign", "cherry_sign");
        ALIASES.put("msign", "mangrove_sign");
        ALIASES.put("psign", "pale_oak_sign");
        ALIASES.put("basign", "bamboo_sign");
        ALIASES.put("crsign", "crimson_sign");
        ALIASES.put("wsign", "warped_sign");


        ALIASES.put("hsign", "oak_hanging_sign");
        ALIASES.put("shsign", "spruce_hanging_sign");
        ALIASES.put("bhsign", "birch_hanging_sign");
        ALIASES.put("jhsign", "jungle_hanging_sign");
        ALIASES.put("dhsign", "dark_oak_hanging_sign");
        ALIASES.put("chsign", "cherry_hanging_sign");
        ALIASES.put("mhsign", "mangrove_hanging_sign");
        ALIASES.put("phsign", "pale_oak_hanging_sign");
        ALIASES.put("bahsign", "bamboo_hanging_sign");
        ALIASES.put("crhsign", "crimson_hanging_sign");
        ALIASES.put("whsign", "warped_hanging_sign");

        ALIASES.put("boat", "oak_boat");
        ALIASES.put("chestboat", "oak_chest_boat");
        ALIASES.put("sboat", "spruce_boat");
        ALIASES.put("schestboat", "spruce_chest_boat");
        ALIASES.put("bboat", "birch_boat");
        ALIASES.put("bchestboat", "birch_chest_boat");
        ALIASES.put("jboat", "jungle_boat");
        ALIASES.put("jchestboat", "jungle_chest_boat");
        ALIASES.put("dboat", "dark_oak_boat");
        ALIASES.put("dchestboat", "dark_oak_chest_boat");
        ALIASES.put("cboat", "cherry_boat");
        ALIASES.put("cchestboat", "cherry_chest_boat");
        ALIASES.put("mboat", "mangrove_boat");
        ALIASES.put("mchestboat", "mangrove_chest_boat");
        ALIASES.put("pboat", "pale_oak_boat");
        ALIASES.put("pchestboat", "pale_oak_chest_boat");
        ALIASES.put("baboat", "bamboo_raft");
        ALIASES.put("bachestboat", "bamboo_chest_raft");

        ALIASES.put("wsword", "wooden_sword");
        ALIASES.put("waxe", "wooden_axe");
        ALIASES.put("wpick", "wooden_pickaxe");
        ALIASES.put("wshovel", "wooden_shovel");
        ALIASES.put("wspade", "wooden_shovel");
        ALIASES.put("whoe", "wooden_hoe");
        ALIASES.put("gsword", "golden_sword");
        ALIASES.put("gaxe", "golden_axe");
        ALIASES.put("gpick", "golden_pickaxe");
        ALIASES.put("gshovel", "golden_shovel");
        ALIASES.put("gspade", "golden_shovel");
        ALIASES.put("ghoe", "golden_hoe");
        ALIASES.put("ssword", "stone_sword");
        ALIASES.put("saxe", "stone_axe");
        ALIASES.put("spick", "stone_pickaxe");
        ALIASES.put("sshovel", "stone_shovel");
        ALIASES.put("sspade", "stone_shovel");
        ALIASES.put("shoe", "stone_hoe");
        ALIASES.put("isword", "iron_sword");
        ALIASES.put("iaxe", "iron_axe");
        ALIASES.put("ipick", "iron_pickaxe");
        ALIASES.put("ishovel", "iron_shovel");
        ALIASES.put("ispade", "iron_shovel");
        ALIASES.put("ihoe", "iron_hoe");
        ALIASES.put("dsword", "diamond_sword");
        ALIASES.put("daxe", "diamond_axe");
        ALIASES.put("dpick", "diamond_pickaxe");
        ALIASES.put("dshovel", "diamond_shovel");
        ALIASES.put("dpade", "diamond_shovel");
        ALIASES.put("dhoe", "diamond_hoe");
        ALIASES.put("nsword", "netherite_sword");
        ALIASES.put("naxe", "netherite_axe");
        ALIASES.put("npick", "netherite_pickaxe");
        ALIASES.put("nshovel", "netherite_shovel");
        ALIASES.put("nspade", "netherite_shovel");
        ALIASES.put("nhoe", "netherite_hoe");
        // Helmets
        ALIASES.put("nhelm", "netherite_helmet");
        ALIASES.put("dhelm", "diamond_helmet");
        ALIASES.put("ihelm", "iron_helmet");
        ALIASES.put("ghelm", "golden_helmet");
        ALIASES.put("chelm", "chainmail_helmet");
        ALIASES.put("lhelm", "leather_helmet");
        ALIASES.put("nhelmet", "netherite_helmet");
        ALIASES.put("dhelmet", "diamond_helmet");
        ALIASES.put("ihelmet", "iron_helmet");
        ALIASES.put("ghelmet", "golden_helmet");
        ALIASES.put("chelmet", "chainmail_helmet");
        ALIASES.put("lhelmet", "leather_helmet");
        ALIASES.put("nchest", "netherite_chestplate");
        ALIASES.put("dchest", "diamond_chestplate");
        ALIASES.put("ichest", "iron_chestplate");
        ALIASES.put("gchest", "golden_chestplate");
        ALIASES.put("cchest", "chainmail_chestplate");
        ALIASES.put("lchest", "leather_chestplate");
        ALIASES.put("nchestplate", "netherite_chestplate");
        ALIASES.put("dchestplate", "diamond_chestplate");
        ALIASES.put("ichestplate", "iron_chestplate");
        ALIASES.put("gchestplate", "golden_chestplate");
        ALIASES.put("cchestplate", "chainmail_chestplate");
        ALIASES.put("lchestplate", "leather_chestplate");
        ALIASES.put("nlegs", "netherite_leggings");
        ALIASES.put("dlegs", "diamond_leggings");
        ALIASES.put("ilegs", "iron_leggings");
        ALIASES.put("glegs", "golden_leggings");
        ALIASES.put("clegs", "chainmail_leggings");
        ALIASES.put("llegs", "leather_leggings");
        ALIASES.put("nleggings", "netherite_leggings");
        ALIASES.put("dleggings", "diamond_leggings");
        ALIASES.put("ileggings", "iron_leggings");
        ALIASES.put("gleggings", "golden_leggings");
        ALIASES.put("cleggings", "chainmail_leggings");
        ALIASES.put("lleggings", "leather_leggings");
        ALIASES.put("nboots", "netherite_boots");
        ALIASES.put("dboots", "diamond_boots");
        ALIASES.put("iboots", "iron_boots");
        ALIASES.put("gboots", "golden_boots");
        ALIASES.put("cboots", "chainmail_boots");
        ALIASES.put("lboots", "leather_boots");
        ALIASES.put("fns", "flint_and_steel");
        ALIASES.put("lighter", "flint_and_steel");
        ALIASES.put("crystal", "end_crystal");
        ALIASES.put("fruit", "chorus_fruit");
        ALIASES.put("rod", "fishing_rod");
        ALIASES.put("wglass", "white_stained_glass");
        ALIASES.put("lgglass", "light_gray_stained_glass");
        ALIASES.put("gglass", "gray_stained_glass");
        ALIASES.put("bglass", "black_stained_glass");
        ALIASES.put("brglass", "brown_stained_glass");
        ALIASES.put("rglass", "red_stained_glass");
        ALIASES.put("oglass", "orange_stained_glass");
        ALIASES.put("yglass", "yellow_stained_glass");
        ALIASES.put("liglass", "lime_stained_glass");
        ALIASES.put("grglass", "green_stained_glass");
        ALIASES.put("cglass", "cyan_stained_glass");
        ALIASES.put("lbglass", "light_blue_stained_glass");
        ALIASES.put("blglass", "blue_stained_glass");
        ALIASES.put("pglass", "purple_stained_glass");
        ALIASES.put("mglass", "magenta_stained_glass");
        ALIASES.put("piglass", "pink_stained_glass");
        ALIASES.put("wgpane", "white_stained_glass_pane");
        ALIASES.put("lggpane", "light_gray_stained_glass_pane");
        ALIASES.put("ggpane", "gray_stained_glass_pane");
        ALIASES.put("bgpane", "black_stained_glass_pane");
        ALIASES.put("brgpane", "brown_stained_glass_pane");
        ALIASES.put("rgpane", "red_stained_glass_pane");
        ALIASES.put("ogpane", "orange_stained_glass_pane");
        ALIASES.put("ygpane", "yellow_stained_glass_pane");
        ALIASES.put("ligpane", "lime_stained_glass_pane");
        ALIASES.put("grgpane", "green_stained_glass_pane");
        ALIASES.put("cgpane", "cyan_stained_glass_pane");
        ALIASES.put("lbgpane", "light_blue_stained_glass_pane");
        ALIASES.put("blgpane", "blue_stained_glass_pane");
        ALIASES.put("pgpane", "purple_stained_glass_pane");
        ALIASES.put("mgpane", "magenta_stained_glass_pane");
        ALIASES.put("pigpane", "pink_stained_glass_pane");
        ALIASES.put("tglass", "tinted_glass");
        ALIASES.put("wterracotta", "white_terracotta");
        ALIASES.put("lterracotta", "light_gray_terracotta");
        ALIASES.put("gterracotta", "gray_terracotta");
        ALIASES.put("bterracotta", "black_terracotta");
        ALIASES.put("brterracotta", "brown_terracotta");
        ALIASES.put("rterracotta", "red_terracotta");
        ALIASES.put("oterracotta", "orange_terracotta");
        ALIASES.put("yterracotta", "yellow_terracotta");
        ALIASES.put("literracotta", "lime_terracotta");
        ALIASES.put("grterracotta", "green_terracotta");
        ALIASES.put("cterracotta", "cyan_terracotta");
        ALIASES.put("lbterracotta", "light_blue_terracotta");
        ALIASES.put("blterracotta", "blue_terracotta");
        ALIASES.put("pterracotta", "purple_terracotta");
        ALIASES.put("mterracotta", "magenta_terracotta");
        ALIASES.put("piterracotta", "pink_terracotta");
        ALIASES.put("wt", "white_terracotta");
        ALIASES.put("lt", "light_gray_terracotta");
        ALIASES.put("gt", "gray_terracotta");
        ALIASES.put("bt", "black_terracotta");
        ALIASES.put("brt", "brown_terracotta"); // or "br" if unambiguous
        ALIASES.put("rt", "red_terracotta");
        ALIASES.put("ot", "orange_terracotta");
        ALIASES.put("yt", "yellow_terracotta");
        ALIASES.put("lit", "lime_terracotta");
        ALIASES.put("grt", "green_terracotta");
        ALIASES.put("ct", "cyan_terracotta");
        ALIASES.put("lbt", "light_blue_terracotta");
        ALIASES.put("blt", "blue_terracotta");
        ALIASES.put("pt", "purple_terracotta");
        ALIASES.put("mt", "magenta_terracotta");
        ALIASES.put("pit", "pink_terracotta");
        ALIASES.put("wconcrete", "white_concrete");
        ALIASES.put("lconcrete", "light_gray_concrete");
        ALIASES.put("gconcrete", "gray_concrete");
        ALIASES.put("bconcrete", "black_concrete"); // or "bc" for shorter
        ALIASES.put("brconcrete", "brown_concrete"); // or "brc"
        ALIASES.put("rconcrete", "red_concrete");
        ALIASES.put("oconcrete", "orange_concrete");
        ALIASES.put("yconcrete", "yellow_concrete");
        ALIASES.put("liconcrete", "lime_concrete");
        ALIASES.put("grconcrete", "green_concrete");
        ALIASES.put("cconcrete", "cyan_concrete");
        ALIASES.put("lbconcrete", "light_blue_concrete");
        ALIASES.put("blconcrete", "blue_concrete");
        ALIASES.put("pconcrete", "purple_concrete");
        ALIASES.put("mconcrete", "magenta_concrete");
        ALIASES.put("piconcrete", "pink_concrete");
        ALIASES.put("wc", "white_concrete");
        ALIASES.put("lgc", "light_gray_concrete");
        ALIASES.put("gc", "gray_concrete");
        ALIASES.put("bc", "black_concrete");
        ALIASES.put("brc", "brown_concrete");
        ALIASES.put("rc", "red_concrete");
        ALIASES.put("oc", "orange_concrete");
        ALIASES.put("yc", "yellow_concrete");
        ALIASES.put("lic", "lime_concrete");
        ALIASES.put("grc", "green_concrete");
        ALIASES.put("cc", "cyan_concrete");
        ALIASES.put("lbc", "light_blue_concrete");
        ALIASES.put("blc", "blue_concrete");
        ALIASES.put("pc", "purple_concrete");
        ALIASES.put("mc", "magenta_concrete");
        ALIASES.put("pic", "pink_concrete");
        // Wool (w = white, bw = black wool, rw = red wool, etc.)
        ALIASES.put("ww", "white_wool");
        ALIASES.put("lgw", "light_gray_wool");
        ALIASES.put("gw", "gray_wool");
        ALIASES.put("bw", "black_wool");
        ALIASES.put("brw", "brown_wool");
        ALIASES.put("rw", "red_wool");
        ALIASES.put("ow", "orange_wool");
        ALIASES.put("yw", "yellow_wool");
        ALIASES.put("liw", "lime_wool");
        ALIASES.put("grw", "green_wool");
        ALIASES.put("cw", "cyan_wool");
        ALIASES.put("lbw", "light_blue_wool");
        ALIASES.put("blw", "blue_wool");
        ALIASES.put("pw", "purple_wool");
        ALIASES.put("mw", "magenta_wool");
        ALIASES.put("piw", "pink_wool");
        // Carpets (color abbreviation + "carpet")
        ALIASES.put("wcarpet", "white_carpet");
        ALIASES.put("lgcarpet", "light_gray_carpet");
        ALIASES.put("gcarpet", "gray_carpet");
        ALIASES.put("bcarpet", "black_carpet");
        ALIASES.put("brcarpet", "brown_carpet");
        ALIASES.put("rcarpet", "red_carpet");
        ALIASES.put("ocarpet", "orange_carpet");
        ALIASES.put("ycarpet", "yellow_carpet");
        ALIASES.put("licarpet", "lime_carpet");
        ALIASES.put("grcarpet", "green_carpet");
        ALIASES.put("ccarpet", "cyan_carpet");
        ALIASES.put("lbcarpet", "light_blue_carpet");
        ALIASES.put("blcarpet", "blue_carpet");
        ALIASES.put("pcarpet", "purple_carpet");
        ALIASES.put("mcarpet", "magenta_carpet");
        ALIASES.put("picarpet", "pink_carpet");

        // Glazed Terracotta (gt = glazed terracotta)
        ALIASES.put("wgt", "white_glazed_terracotta");
        ALIASES.put("lggt", "light_gray_glazed_terracotta");
        ALIASES.put("ggt", "gray_glazed_terracotta");
        ALIASES.put("bgt", "black_glazed_terracotta");
        ALIASES.put("brgt", "brown_glazed_terracotta");
        ALIASES.put("rgt", "red_glazed_terracotta");
        ALIASES.put("ogt", "orange_glazed_terracotta");
        ALIASES.put("ygt", "yellow_glazed_terracotta");
        ALIASES.put("ligt", "lime_glazed_terracotta");
        ALIASES.put("grgt", "green_glazed_terracotta");
        ALIASES.put("cgt", "cyan_glazed_terracotta");
        ALIASES.put("lbgt", "light_blue_glazed_terracotta");
        ALIASES.put("blgt", "blue_glazed_terracotta");
        ALIASES.put("pgt", "purple_glazed_terracotta");
        ALIASES.put("mgt", "magenta_glazed_terracotta");
        ALIASES.put("pigt", "pink_glazed_terracotta");
        // Concrete Powder (cp = concrete powder)
        ALIASES.put("wcpowder", "white_concrete_powder");
        ALIASES.put("lgcpowder", "light_gray_concrete_powder");
        ALIASES.put("gcpowder", "gray_concrete_powder");
        ALIASES.put("bcpowder", "black_concrete_powder");
        ALIASES.put("brcpowder", "brown_concrete_powder");
        ALIASES.put("rcpowder", "red_concrete_powder");
        ALIASES.put("ocpowder", "orange_concrete_powder");
        ALIASES.put("ycpowder", "yellow_concrete_powder");
        ALIASES.put("licpowder", "lime_concrete_powder");
        ALIASES.put("grcpowder", "green_concrete_powder");
        ALIASES.put("ccpowder", "cyan_concrete_powder");
        ALIASES.put("lbcpowder", "light_blue_concrete_powder");
        ALIASES.put("blcpowder", "blue_concrete_powder");
        ALIASES.put("pcpowder", "purple_concrete_powder");
        ALIASES.put("mcpowder", "magenta_concrete_powder");
        ALIASES.put("picpowder", "pink_concrete_powder");
        ALIASES.put("wcp", "white_concrete_powder");
        ALIASES.put("lgcp", "light_gray_concrete_powder");
        ALIASES.put("gcp", "gray_concrete_powder");
        ALIASES.put("bcp", "black_concrete_powder");
        ALIASES.put("brcp", "brown_concrete_powder");
        ALIASES.put("rcp", "red_concrete_powder");
        ALIASES.put("ocp", "orange_concrete_powder");
        ALIASES.put("ycp", "yellow_concrete_powder");
        ALIASES.put("licp", "lime_concrete_powder");
        ALIASES.put("grcp", "green_concrete_powder");
        ALIASES.put("ccp", "cyan_concrete_powder");
        ALIASES.put("lbcp", "light_blue_concrete_powder");
        ALIASES.put("blcp", "blue_concrete_powder");
        ALIASES.put("pcp", "purple_concrete_powder");
        ALIASES.put("mcp", "magenta_concrete_powder");
        ALIASES.put("picp", "pink_concrete_powder");
        // Shulker Boxes (color abbreviation + "shulker")
        ALIASES.put("wshulker", "white_shulker_box");
        ALIASES.put("lgshulker", "light_gray_shulker_box");
        ALIASES.put("gshulker", "gray_shulker_box");
        ALIASES.put("bshulker", "black_shulker_box");
        ALIASES.put("brshulker", "brown_shulker_box");
        ALIASES.put("rshulker", "red_shulker_box");
        ALIASES.put("oshulker", "orange_shulker_box");
        ALIASES.put("yshulker", "yellow_shulker_box");
        ALIASES.put("lishulker", "lime_shulker_box");
        ALIASES.put("grshulker", "green_shulker_box");
        ALIASES.put("cshulker", "cyan_shulker_box");
        ALIASES.put("lbshulker", "light_blue_shulker_box");
        ALIASES.put("blshulker", "blue_shulker_box");
        ALIASES.put("pshulker", "purple_shulker_box");
        ALIASES.put("mshulker", "magenta_shulker_box");
        ALIASES.put("pishulker", "pink_shulker_box");
        ALIASES.put("wbox", "white_shulker_box");  // wb = white box
        ALIASES.put("lgbox", "light_gray_shulker_box");
        ALIASES.put("gbox", "gray_shulker_box");
        ALIASES.put("bbox", "black_shulker_box");  // bb = black box
        ALIASES.put("brbox", "brown_shulker_box");
        ALIASES.put("rbox", "red_shulker_box");
        ALIASES.put("obox", "orange_shulker_box");
        ALIASES.put("ybox", "yellow_shulker_box");
        ALIASES.put("libox", "lime_shulker_box");
        ALIASES.put("grbox", "green_shulker_box");
        ALIASES.put("cbox", "cyan_shulker_box");
        ALIASES.put("lbbox", "light_blue_shulker_box");
        ALIASES.put("blbox", "blue_shulker_box");
        ALIASES.put("pbox", "purple_shulker_box");
        ALIASES.put("mbox", "magenta_shulker_box");
        ALIASES.put("pibox", "pink_shulker_box");
        // Beds (color abbreviation + "bed")
        ALIASES.put("bed", "white_bed");
        ALIASES.put("lgbed", "light_gray_bed");
        ALIASES.put("gbed", "gray_bed");
        ALIASES.put("bbed", "black_bed");
        ALIASES.put("brbed", "brown_bed");
        ALIASES.put("rbed", "red_bed");
        ALIASES.put("obed", "orange_bed");
        ALIASES.put("ybed", "yellow_bed");
        ALIASES.put("libed", "lime_bed");
        ALIASES.put("grbed", "green_bed");
        ALIASES.put("cbed", "cyan_bed");
        ALIASES.put("lbbed", "light_blue_bed");
        ALIASES.put("blbed", "blue_bed");
        ALIASES.put("pbed", "purple_bed");
        ALIASES.put("mbed", "magenta_bed");
        ALIASES.put("pibed", "pink_bed");
        ALIASES.put("bed:1", "light_gray_bed");
        ALIASES.put("bed:2", "gray_bed");
        ALIASES.put("bed:3", "black_bed");
        ALIASES.put("bed:4", "brown_bed");
        ALIASES.put("bed:5", "red_bed");
        ALIASES.put("bed:6", "orange_bed");
        ALIASES.put("bed:7", "yellow_bed");
        ALIASES.put("bed:8", "lime_bed");
        ALIASES.put("bed:9", "green_bed");
        ALIASES.put("bed:10", "cyan_bed");
        ALIASES.put("bed:11", "light_blue_bed");
        ALIASES.put("bed:12", "blue_bed");
        ALIASES.put("bed:13", "purple_bed");
        ALIASES.put("bed:14", "magenta_bed");
        ALIASES.put("bed:15", "pink_bed");
        // Banners (color abbreviation + "banner")
        ALIASES.put("wbanner", "white_banner");
        ALIASES.put("lgbanner", "light_gray_banner");
        ALIASES.put("gbanner", "gray_banner");
        ALIASES.put("bbanner", "black_banner");
        ALIASES.put("brbanner", "brown_banner");
        ALIASES.put("rbanner", "red_banner");
        ALIASES.put("obanner", "orange_banner");
        ALIASES.put("ybanner", "yellow_banner");
        ALIASES.put("libanner", "lime_banner");
        ALIASES.put("grbanner", "green_banner");
        ALIASES.put("cbanner", "cyan_banner");
        ALIASES.put("lbbanner", "light_blue_banner");
        ALIASES.put("blbanner", "blue_banner");
        ALIASES.put("pbanner", "purple_banner");
        ALIASES.put("mbanner", "magenta_banner");
        ALIASES.put("pibanner", "pink_banner");
        ALIASES.put("wcandle", "white_candle");
        ALIASES.put("lgcandle", "light_gray_candle");
        ALIASES.put("gcandle", "gray_candle");
        ALIASES.put("bcandle", "black_candle");
        ALIASES.put("brcandle", "brown_candle");
        ALIASES.put("rcandle", "red_candle");
        ALIASES.put("ocandle", "orange_candle");
        ALIASES.put("ycandle", "yellow_candle");
        ALIASES.put("licandle", "lime_candle");
        ALIASES.put("grcandle", "green_candle");
        ALIASES.put("ccandle", "cyan_candle");
        ALIASES.put("lbcandle", "light_blue_candle");
        ALIASES.put("blcandle", "blue_candle");
        ALIASES.put("pcandle", "purple_candle");
        ALIASES.put("mcandle", "magenta_candle");
        ALIASES.put("picandle", "pink_candle");
        ALIASES.put("wdye", "white_dye");
        ALIASES.put("lgdye", "light_gray_dye");
        ALIASES.put("gdye", "gray_dye");
        ALIASES.put("bdye", "black_dye");
        ALIASES.put("brdye", "brown_dye");
        ALIASES.put("rdye", "red_dye");
        ALIASES.put("odye", "orange_dye");
        ALIASES.put("ydye", "yellow_dye");
        ALIASES.put("lidy", "lime_dye");
        ALIASES.put("grdye", "green_dye");
        ALIASES.put("cdye", "cyan_dye");
        ALIASES.put("lbdye", "light_blue_dye");
        ALIASES.put("bldye", "blue_dye");
        ALIASES.put("pdye", "purple_dye");
        ALIASES.put("mdye", "magenta_dye");
        ALIASES.put("pidye", "pink_dye");
        ALIASES.put("craft", "crafting_table");    // /give @p craft
        ALIASES.put("echest", "ender_chest");
        ALIASES.put("brewer", "brewing_stand");    // /give @p brewer
        ALIASES.put("enchant", "enchanting_table"); // /give @p enchant
        ALIASES.put("rdust", "redstone");
        ALIASES.put("redstoneb", "redstone_block");
        ALIASES.put("comp", "comparator");
        ALIASES.put("disp", "dispenser");
        ALIASES.put("sticky", "sticky_piston");   // /give @p sticky
        ALIASES.put("fert", "bone_meal");         // Fertilizer
        ALIASES.put("watercan", "water_bucket");  // For farming
        ALIASES.put("berry", "sweet_berries");
        ALIASES.put("steak", "cooked_beef");
        ALIASES.put("bacon", "cooked_porkchop");  // Fun alias
        ALIASES.put("blazerod", "blaze_rod");
        ALIASES.put("smoothstone", "smooth_stone");
        ALIASES.put("brimstone", "netherrack"); // Fun alias
        ALIASES.put("salt", "quartz_block");    // For RP
        ALIASES.put("coalb", "coal_block");
        ALIASES.put("copperb", "copper_block");
        ALIASES.put("ironb", "iron_block");
        ALIASES.put("goldb", "gold_block");
        ALIASES.put("diamondb", "diamond_block");
        ALIASES.put("netheriteb", "netherite_block");
        ALIASES.put("lamp", "redstone_lamp");
        ALIASES.put("clayb", "clay_block");
        ALIASES.put("hardclay", "terracotta");
        ALIASES.put("powerrail", "powered_rail"); // or "prail"
        ALIASES.put("prail", "powered_rail");
        ALIASES.put("drail", "detector_rail");
        ALIASES.put("detectrail", "detector_rail"); // or "drail"
        ALIASES.put("fire", "flint_and_steel");
        ALIASES.put("fns", "flint_and_steel");
        ALIASES.put("cart", "minecart");      // or "cart"
        ALIASES.put("chestcart", "chest_minecart");
        ALIASES.put("hoppercart", "hopper_minecart");
        ALIASES.put("cmd", "command_block");      // /give @p cmd
        ALIASES.put("chaincmd", "chain_command_block");
        ALIASES.put("repeatcmd", "repeating_command_block");

        // --- Additional Common Aliases (Abbreviations & Natural Language) ---

        // Building Blocks
        ALIASES.put("stonebricks", "STONE_BRICKS");
        ALIASES.put("sbrick", "STONE_BRICKS");
        ALIASES.put("mossycobblestone", "MOSSY_COBBLESTONE");
        ALIASES.put("mcobble", "MOSSY_COBBLESTONE");
        ALIASES.put("sandstoneblk", "SANDSTONE"); // To distinguish from item if ever needed
        ALIASES.put("glassblk", "GLASS");
        ALIASES.put("netherbricks", "NETHER_BRICKS");
        ALIASES.put("nbricks", "NETHER_BRICKS");
        ALIASES.put("quartzblk", "QUARTZ_BLOCK");
        ALIASES.put("qblk", "QUARTZ_BLOCK");
        ALIASES.put("glowstoneblk", "GLOWSTONE"); // 'glowst' exists
        ALIASES.put("dirtblk", "DIRT");
        ALIASES.put("gravelblk", "GRAVEL");
        ALIASES.put("sandblk", "SAND");
        ALIASES.put("wool", "WHITE_WOOL"); // Default wool to white
        ALIASES.put("redwool", "RED_WOOL");
        ALIASES.put("bluewool", "BLUE_WOOL");
        ALIASES.put("greenwool", "GREEN_WOOL");
        ALIASES.put("blackwool", "BLACK_WOOL");
        ALIASES.put("concrete", "WHITE_CONCRETE"); // Default concrete to white
        ALIASES.put("redconcrete", "RED_CONCRETE");
        ALIASES.put("blueconcrete", "BLUE_CONCRETE");
        ALIASES.put("blackconcrete", "BLACK_CONCRETE");
        ALIASES.put("obs", "OBSIDIAN"); // 'obsidian' exists

        // Ores & Minerals
        ALIASES.put("coalore", "COAL_ORE");
        ALIASES.put("ironore", "IRON_ORE");
        ALIASES.put("goldore", "GOLD_ORE");
        ALIASES.put("diamondore", "DIAMOND_ORE");
        ALIASES.put("emeraldore", "EMERALD_ORE");
        ALIASES.put("lapisore", "LAPIS_ORE");
        ALIASES.put("redstoneore", "REDSTONE_ORE");
        ALIASES.put("nethergoldore", "NETHER_GOLD_ORE");
        ALIASES.put("ngoldore", "NETHER_GOLD_ORE");
        ALIASES.put("netherquartzore", "NETHER_QUARTZ_ORE");
        ALIASES.put("nquartzore", "NETHER_QUARTZ_ORE");
        ALIASES.put("debris", "ANCIENT_DEBRIS");
        ALIASES.put("ancdebris", "ANCIENT_DEBRIS");
        ALIASES.put("coalitem", "COAL"); // 'coal' can be ambiguous with block
        ALIASES.put("ironingot", "IRON_INGOT");
        ALIASES.put("goldingot", "GOLD_INGOT");
        ALIASES.put("diamonditem", "DIAMOND"); // 'diamond' can be ambiguous with block
        ALIASES.put("dia", "DIAMOND");
        ALIASES.put("emeralditem", "EMERALD"); // 'emerald' can be ambiguous
        ALIASES.put("emmy", "EMERALD");
        ALIASES.put("lapis", "LAPIS_LAZULI");
        ALIASES.put("lapislaz", "LAPIS_LAZULI");
        ALIASES.put("netheriteingot", "NETHERITE_INGOT");
        ALIASES.put("ningot", "NETHERITE_INGOT");
        ALIASES.put("netheritescrap", "NETHERITE_SCRAP");
        ALIASES.put("nscrap", "NETHERITE_SCRAP");
        ALIASES.put("quartzitem", "QUARTZ");
        ALIASES.put("nquartz", "QUARTZ");

        // Tools & Weapons
        ALIASES.put("dsword", "DIAMOND_SWORD");
        ALIASES.put("isword", "IRON_SWORD");
        ALIASES.put("gsword", "GOLDEN_SWORD");
        ALIASES.put("ssword", "STONE_SWORD"); // careful with stripped spruce wood
        ALIASES.put("wsword", "WOODEN_SWORD");
        ALIASES.put("dpick", "DIAMOND_PICKAXE");
        ALIASES.put("ipick", "IRON_PICKAXE");
        ALIASES.put("gpick", "GOLDEN_PICKAXE");
        ALIASES.put("spick", "STONE_PICKAXE");
        ALIASES.put("wpick", "WOODEN_PICKAXE");
        ALIASES.put("daxe", "DIAMOND_AXE");
        ALIASES.put("dshovel", "DIAMOND_SHOVEL");
        ALIASES.put("dhoe", "DIAMOND_HOE");
        ALIASES.put("fishrod", "FISHING_ROD");
        ALIASES.put("flintsteel", "FLINT_AND_STEEL"); // 'fns' exists

        // Armor
        ALIASES.put("dhelm", "DIAMOND_HELMET");
        ALIASES.put("dchest", "DIAMOND_CHESTPLATE");
        ALIASES.put("dlegs", "DIAMOND_LEGGINGS");
        ALIASES.put("dboots", "DIAMOND_BOOTS");
        ALIASES.put("ihelm", "IRON_HELMET");
        ALIASES.put("ichest", "IRON_CHESTPLATE");
        ALIASES.put("ilegs", "IRON_LEGGINGS");
        ALIASES.put("iboots", "IRON_BOOTS");
        ALIASES.put("wings", "ELYTRA"); // 'elytra' is good too

        // Food & Farming
        ALIASES.put("gapple", "GOLDEN_APPLE"); // 'goldapple' exists
        ALIASES.put("gap", "GOLDEN_APPLE");
        ALIASES.put("egapple", "ENCHANTED_GOLDEN_APPLE"); // 'godapple' exists
        ALIASES.put("egap", "ENCHANTED_GOLDEN_APPLE");
        ALIASES.put("notchapple", "ENCHANTED_GOLDEN_APPLE");
        ALIASES.put("bakedpotato", "BAKED_POTATO");
        ALIASES.put("bpotato", "BAKED_POTATO");
        ALIASES.put("pumpkinpie", "PUMPKIN_PIE");
        ALIASES.put("ppie", "PUMPKIN_PIE");
        ALIASES.put("sugarcane", "SUGAR_CANE");
        ALIASES.put("reeds", "SUGAR_CANE");
        ALIASES.put("melonslice", "MELON_SLICE");
        ALIASES.put("gmslice", "GLISTERING_MELON_SLICE");
        ALIASES.put("gmelon", "GLISTERING_MELON_SLICE");
        ALIASES.put("bonemeal", "BONE_MEAL"); // 'fert' exists
        ALIASES.put("bmeal", "BONE_MEAL");

        // Redstone & Utility
        ALIASES.put("rtorch", "REDSTONE_TORCH");
        ALIASES.put("redtorch", "REDSTONE_TORCH");
        ALIASES.put("sbutton", "STONE_BUTTON");
        ALIASES.put("stonebutton", "STONE_BUTTON");
        ALIASES.put("obutton", "OAK_BUTTON");
        ALIASES.put("oakbutton", "OAK_BUTTON");
        ALIASES.put("splate", "STONE_PRESSURE_PLATE");
        ALIASES.put("stoneplate", "STONE_PRESSURE_PLATE");
        ALIASES.put("oplate", "OAK_PRESSURE_PLATE");
        ALIASES.put("oakplate", "OAK_PRESSURE_PLATE");
        ALIASES.put("spiston", "STICKY_PISTON"); // 'sticky' exists
        ALIASES.put("rep", "REPEATER");
        ALIASES.put("trapchest", "TRAPPED_CHEST");
        ALIASES.put("tchest", "TRAPPED_CHEST");
        ALIASES.put("epearl", "ENDER_PEARL"); // 'enderpearl', 'pearl' exist
        ALIASES.put("ep", "ENDER_PEARL");
        ALIASES.put("eeye", "EYE_OF_ENDER");
        ALIASES.put("endereye", "EYE_OF_ENDER");
        ALIASES.put("shulker", "SHULKER_BOX"); // Defaults to undyed
        ALIASES.put("sbox", "SHULKER_BOX");
        ALIASES.put("redshulker", "RED_SHULKER_BOX");
        ALIASES.put("bed", "RED_BED"); // Default bed to red
        ALIASES.put("whitebed", "WHITE_BED");
        ALIASES.put("bluebed", "BLUE_BED");

        // Potions & Brewing
        ALIASES.put("gbottle", "GLASS_BOTTLE");
        ALIASES.put("netherwartitem", "NETHER_WART"); // 'nwart' exists
        ALIASES.put("bpowder", "BLAZE_POWDER");
        ALIASES.put("blazepowder", "BLAZE_POWDER");
        ALIASES.put("gtear", "GHAST_TEAR");
        ALIASES.put("ghasttear", "GHAST_TEAR");
        ALIASES.put("mcream", "MAGMA_CREAM");
        ALIASES.put("magmacream", "MAGMA_CREAM");
        ALIASES.put("fspidereye", "FERMENTED_SPIDER_EYE");
        ALIASES.put("fermenteye", "FERMENTED_SPIDER_EYE");
        ALIASES.put("gpowder", "GUNPOWDER");
        ALIASES.put("sulfur", "GUNPOWDER"); // Common alternative name
        ALIASES.put("dbreath", "DRAGON_BREATH");
        ALIASES.put("dragonbreath", "DRAGON_BREATH");
        ALIASES.put("pmembrane", "PHANTOM_MEMBRANE");

        // Miscellaneous
        ALIASES.put("wbook", "WRITABLE_BOOK");
        ALIASES.put("writebook", "WRITABLE_BOOK");
        ALIASES.put("emptymap", "MAP");
        ALIASES.put("iframe", "ITEM_FRAME");
        ALIASES.put("itemframe", "ITEM_FRAME");
        ALIASES.put("giframe", "GLOW_ITEM_FRAME");
        ALIASES.put("glowitemframe", "GLOW_ITEM_FRAME");
        ALIASES.put("leash", "LEAD"); // 'lead' is good too
        ALIASES.put("ntag", "NAME_TAG");
        ALIASES.put("nametag", "NAME_TAG");
        ALIASES.put("totem", "TOTEM_OF_UNDYING");
        ALIASES.put("firework", "FIREWORK_ROCKET");
        ALIASES.put("frocket", "FIREWORK_ROCKET");
        ALIASES.put("nautilus", "NAUTILUS_SHELL");
        ALIASES.put("nshell", "NAUTILUS_SHELL");
        ALIASES.put("hots", "HEART_OF_THE_SEA");
        ALIASES.put("wbucket", "WATER_BUCKET"); // 'watercan' exists
        ALIASES.put("lbucket", "LAVA_BUCKET");
        ALIASES.put("lavabucket", "LAVA_BUCKET");
        ALIASES.put("mbucket", "MILK_BUCKET");
        ALIASES.put("milkbucket", "MILK_BUCKET");
        ALIASES.put("xpbottle", "EXPERIENCE_BOTTLE"); // 'xp' exists
        ALIASES.put("expbottle", "EXPERIENCE_BOTTLE");
        ALIASES.put("brickitem", "BRICK");
        ALIASES.put("netherbrickitem", "NETHER_BRICK");
        ALIASES.put("nbrickitem", "NETHER_BRICK");
        ALIASES.put("catdisc", "MUSIC_DISC_CAT");
        ALIASES.put("warddisc", "MUSIC_DISC_WARD");
        ALIASES.put("blocksdisc", "MUSIC_DISC_BLOCKS");
        ALIASES.put("chirpdisc", "MUSIC_DISC_CHIRP");
        ALIASES.put("fardisc", "MUSIC_DISC_FAR");
        ALIASES.put("malldisc", "MUSIC_DISC_MALL");
        ALIASES.put("mellohidisc", "MUSIC_DISC_MELLOHI");
        ALIASES.put("staldisc", "MUSIC_DISC_STAL");
        ALIASES.put("straddisc", "MUSIC_DISC_STRAD");
        ALIASES.put("waitdisc", "MUSIC_DISC_WAIT");
        ALIASES.put("pigstepdisc", "MUSIC_DISC_PIGSTEP");
        ALIASES.put("othersidedisc", "MUSIC_DISC_OTHERSIDE");
        ALIASES.put("5disc", "MUSIC_DISC_5");
        ALIASES.put("relicdisc", "MUSIC_DISC_RELIC");

        // --- Even More Unique Short Aliases for Common Blocks ---
        // Building blocks
        ALIASES.put("slab", "STONE_SLAB");
        ALIASES.put("sstairs", "STONE_STAIRS");
        ALIASES.put("brick", "BRICKS");
        ALIASES.put("bricks", "BRICKS");
        ALIASES.put("plank", "OAK_PLANKS");
        ALIASES.put("stairs", "OAK_STAIRS");
        ALIASES.put("dplank", "DARK_OAK_PLANKS");
        ALIASES.put("spruce", "SPRUCE_PLANKS");
        ALIASES.put("birch", "BIRCH_PLANKS");
        ALIASES.put("acacia", "ACACIA_PLANKS");
        ALIASES.put("jungle", "JUNGLE_PLANKS");
        ALIASES.put("mangrove", "MANGROVE_PLANKS");
        ALIASES.put("bamboo", "BAMBOO_BLOCK");
        ALIASES.put("mud", "MUD");
        ALIASES.put("packedmud", "PACKED_MUD");
        ALIASES.put("mudbrick", "MUD_BRICKS");
        ALIASES.put("mudbricks", "MUD_BRICKS");
        ALIASES.put("cobblewall", "COBBLESTONE_WALL");
        ALIASES.put("brickwall", "BRICK_WALL");
        ALIASES.put("sandwall", "SANDSTONE_WALL");
        ALIASES.put("redwall", "RED_SANDSTONE_WALL");
        ALIASES.put("netherwall", "NETHER_BRICK_WALL");
        ALIASES.put("prismwall", "PRISMARINE_WALL");
        ALIASES.put("deepslate", "DEEPSLATE");
        ALIASES.put("dsbrick", "DEEPSLATE_BRICKS");
        ALIASES.put("dsbrickwall", "DEEPSLATE_BRICK_WALL");
        ALIASES.put("dstitles", "DEEPSLATE_TILES");
        ALIASES.put("dstitleswall", "DEEPSLATE_TILE_WALL");
        ALIASES.put("tuff", "TUFF");
        ALIASES.put("tuffbrick", "TUFF_BRICKS");
        ALIASES.put("tuffwall", "TUFF_WALL");
        ALIASES.put("granite", "GRANITE");
        ALIASES.put("diorite", "DIORITE");
        ALIASES.put("andesite", "ANDESITE");
        ALIASES.put("polishedgranite", "POLISHED_GRANITE");
        ALIASES.put("polisheddiorite", "POLISHED_DIORITE");
        ALIASES.put("polishedandesite", "POLISHED_ANDESITE");
        ALIASES.put("calcite", "CALCITE");
        ALIASES.put("dripstone", "DRIPSTONE_BLOCK");
        ALIASES.put("basalt", "BASALT");
        ALIASES.put("smoothbasalt", "SMOOTH_BASALT");
        ALIASES.put("blackstone", "BLACKSTONE");
        ALIASES.put("gildedblack", "GILDED_BLACKSTONE");
        ALIASES.put("polishedblack", "POLISHED_BLACKSTONE");
        ALIASES.put("pbbrick", "POLISHED_BLACKSTONE_BRICKS");
        ALIASES.put("pbbrickwall", "POLISHED_BLACKSTONE_BRICK_WALL");
        ALIASES.put("polishedwall", "POLISHED_BLACKSTONE_WALL");
        // Utility/Redstone
        ALIASES.put("note", "NOTE_BLOCK");
        ALIASES.put("jukebox", "JUKEBOX");
        ALIASES.put("observer", "OBSERVER");
        ALIASES.put("dropper", "DROPPER");
        ALIASES.put("hopper", "HOPPER");
        ALIASES.put("daylight", "DAYLIGHT_DETECTOR");
        ALIASES.put("furnace", "FURNACE");
        ALIASES.put("blast", "BLAST_FURNACE");
        ALIASES.put("loom", "LOOM");
        ALIASES.put("compost", "COMPOSTER");
        ALIASES.put("anvil", "ANVIL");
        ALIASES.put("grind", "GRINDSTONE");
        ALIASES.put("smith", "SMITHING_TABLE");
        ALIASES.put("fletch", "FLETCHING_TABLE");
        ALIASES.put("carto", "CARTOGRAPHY_TABLE");
        ALIASES.put("barrel", "BARREL");
        // Glass
        ALIASES.put("bglass", "BLUE_STAINED_GLASS");
        ALIASES.put("rglass", "RED_STAINED_GLASS");
        ALIASES.put("gglass", "GREEN_STAINED_GLASS");
        ALIASES.put("yglass", "YELLOW_STAINED_GLASS");
        // Stripped logs
        ALIASES.put("strippedoak", "STRIPPED_OAK_LOG");
        ALIASES.put("strippedspruce", "STRIPPED_SPRUCE_LOG");
        ALIASES.put("strippedbirch", "STRIPPED_BIRCH_LOG");
        ALIASES.put("strippedacacia", "STRIPPED_ACACIA_LOG");
        ALIASES.put("strippedjungle", "STRIPPED_JUNGLE_LOG");
        ALIASES.put("strippeddark", "STRIPPED_DARK_OAK_LOG");
        ALIASES.put("strippedmangrove", "STRIPPED_MANGROVE_LOG");
        // Wool
        ALIASES.put("wwool", "WHITE_WOOL");
        ALIASES.put("rwool", "RED_WOOL");
        ALIASES.put("bwool", "BLUE_WOOL");
        ALIASES.put("gwool", "GREEN_WOOL");
        ALIASES.put("ywool", "YELLOW_WOOL");
        // Terracotta/Concrete
        ALIASES.put("terracotta", "TERRACOTTA");
        ALIASES.put("redterra", "RED_TERRACOTTA");
        ALIASES.put("blterra", "BLUE_TERRACOTTA");
        ALIASES.put("concrete", "WHITE_CONCRETE");
        ALIASES.put("rconcrete", "RED_CONCRETE");
        ALIASES.put("bconcrete", "BLUE_CONCRETE");
        // Add more aliases as needed...
    }
    public static String getAlias(String alias) {
        return ALIASES.get(alias);
    }

}