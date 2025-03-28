package net.survivalfun.core.commands.core;

import net.survivalfun.core.utils.LegacyIDUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class Give implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if ((args.length < 1 && label.equalsIgnoreCase("i")) || (args.length < 2 && !label.equalsIgnoreCase("i"))) {
            sender.sendMessage("§cUsage: " + (label.equalsIgnoreCase("i") ? "/i <item> [amount]" : "/give <player> <item> [amount]"));
            return true;
        }

        String materialName = getMaterialFromAlias(args[label.equalsIgnoreCase("i") ? 0 : 1]);
        Material material = Material.getMaterial(materialName.toUpperCase());

        if (material == null) {
            sender.sendMessage("§cInvalid item name! Make sure it's a valid Material.");
            return true;
        }

        int amount = material.getMaxStackSize();

        if ((label.equalsIgnoreCase("i") && args.length > 1) || (!label.equalsIgnoreCase("i") && args.length > 2)) {
            try {
                amount = Integer.parseInt(args[label.equalsIgnoreCase("i") ? 1 : 2]);
                if (amount <= 0) {
                    sender.sendMessage("§cThe amount must be greater than 0.");
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid amount specified! Please enter a valid number.");
                return true;
            }
        }

        Player target;
        if (label.equalsIgnoreCase("i")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            target = (Player) sender;
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found! Make sure the player is online.");
                return true;
            }
        }

        ItemStack item = new ItemStack(material, amount);
        int remainingItems = giveItems(target, item);
        int givenAmount = amount - remainingItems;

        if (givenAmount > 0) {
            target.sendMessage("§aYou have been given §e" + givenAmount + " §aof §e" + formatMaterialName(material) + "§a!");
        }
        if (!target.equals(sender) && givenAmount > 0) {
            sender.sendMessage("§aGave §e" + givenAmount + " §aof §e" + formatMaterialName(material) + " §ato §e" + target.getName() + "§a!");
        }

        if (remainingItems > 0) {
            sender.sendMessage("§cCould not give §e" + remainingItems + " §cof §e" + formatMaterialName(material) + " §cto §e" + target.getName() + "§c. Not enough space!");
        }

        return true;
    }

    private int giveItems(Player player, ItemStack item) {
        Inventory inventory = player.getInventory();
        int remaining = item.getAmount();
        int maxStackSize = item.getMaxStackSize();

        for (int i = 0; i < 36; i++) {
            if (remaining <= 0) break;
            ItemStack slot = inventory.getItem(i);

            if (slot == null || slot.getType() == Material.AIR) {
                int toAdd = Math.min(remaining, maxStackSize);
                inventory.setItem(i, new ItemStack(item.getType(), toAdd));
                remaining -= toAdd;
            } else if (slot.isSimilar(item)) {
                int spaceLeft = maxStackSize - slot.getAmount();
                if (spaceLeft > 0) {
                    int toAdd = Math.min(remaining, spaceLeft);
                    slot.setAmount(slot.getAmount() + toAdd);
                    remaining -= toAdd;
                }
            }
        }

        return remaining;
    }

    private String formatMaterialName(Material material) {
        String[] words = material.name().split("_");
        StringBuilder formattedName = new StringBuilder();
        for (String word : words) {
            formattedName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase()).append(" ");
        }
        return formattedName.toString().trim();
    }

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        // Define aliases for materials
        ALIASES.put("enderpearl", "ENDER_PEARL");
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
        ALIASES.put("minecart", "minecart");      // or "cart"
        ALIASES.put("chestcart", "chest_minecart");
        ALIASES.put("hoppercart", "hopper_minecart");
        ALIASES.put("cmd", "command_block");      // /give @p cmd
        ALIASES.put("chaincmd", "chain_command_block");
        ALIASES.put("repeatcmd", "repeating_command_block");
        // Add more aliases as needed...

    }

    /**
     * Converts an alias or legacy item ID to a valid Material name.
     * - If input is a number or in "number:number" format (legacy ID), it checks the legacy ID map.
     * - If input is an alias, it uses the alias map.
     * - Otherwise, it returns the input in uppercase as a fallback.
     */
    private String getMaterialFromAlias(String input) {
        // First check for legacy ID format (either "id" or "id:data")
        if (input.matches("\\d+:\\d+") || input.matches("\\d+")) {
            Material material = LegacyIDUtils.getMaterialFromLegacyId(input);
            return material != null ? material.name() : "UNKNOWN";
        }

        // Fall back to checking aliases
        return ALIASES.getOrDefault(input.toLowerCase(), input.toUpperCase());
    }
}
