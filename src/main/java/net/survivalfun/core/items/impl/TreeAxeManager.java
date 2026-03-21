package net.survivalfun.core.items.impl;

import net.survivalfun.core.managers.core.Text;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * Lazy axe (tree feller) manager.
 * Combines Essentials (leaf scan UP, valid ground, natural exclusions) and
 * <a href="https://github.com/ThizThizzyDizzy/tree-feller">tree-feller</a> ideas:
 * root-block handling (break mangrove roots → find trunk and chop), name-based structure check.
 */
public class TreeAxeManager {

    private static final int MAX_LOGS = 100;
    private static final int SCAN_DISTANCE = 32;
    private static final int LEAF_COUNT_RADIUS = 2;
    private static final int MIN_LEAVES_TALL = 6;
    private static final int MIN_LEAVES_SHORT = 2;
    private static final int MAX_TRUNK_SCAN_HEIGHT = 32;
    private static final int MAX_GROUND_SCAN = 8;
    private static final int MAX_AIR_BETWEEN_GROUND = 3;
    /** Max blocks to scan up/down when breaking a root to find the trunk (tree-feller ROOT_DISTANCE). */
    private static final int ROOT_SCAN_DISTANCE = 5;

    private static final Set<Material> LOG_MATERIALS = new HashSet<>(Arrays.asList(
        Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
        Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.CHERRY_LOG, Material.PALE_OAK_LOG,
        Material.MANGROVE_LOG,
        Material.STRIPPED_OAK_LOG, Material.STRIPPED_SPRUCE_LOG, Material.STRIPPED_BIRCH_LOG,
        Material.STRIPPED_JUNGLE_LOG, Material.STRIPPED_ACACIA_LOG, Material.STRIPPED_DARK_OAK_LOG,
        Material.STRIPPED_CHERRY_LOG, Material.STRIPPED_PALE_OAK_LOG, Material.STRIPPED_MANGROVE_LOG,
        Material.BAMBOO_BLOCK, Material.STRIPPED_BAMBOO_BLOCK,
        Material.CRIMSON_STEM, Material.WARPED_STEM,
        Material.STRIPPED_CRIMSON_STEM, Material.STRIPPED_WARPED_STEM
    ));

    private static final Set<Material> LEAF_MATERIALS = new HashSet<>(Arrays.asList(
        Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES, Material.JUNGLE_LEAVES,
        Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.CHERRY_LEAVES, Material.PALE_OAK_LEAVES,
        Material.MANGROVE_LEAVES,
        Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES
    ));

    /** Root blocks that count as part of a tree (tree-feller ROOTS); breaking them finds nearest trunk. */
    private static final Set<Material> ROOT_MATERIALS = new HashSet<>(Arrays.asList(
        Material.MANGROVE_ROOTS, Material.MUDDY_MANGROVE_ROOTS
    ));

    /** Natural blocks near trees; do not treat as player structure (dirt/grass so bottom log chops). */
    private static final Set<Material> NATURAL_EXCLUSIONS = new HashSet<>(Arrays.asList(
        Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.ROOTED_DIRT,
        Material.MYCELIUM, Material.MUD, Material.MANGROVE_ROOTS, Material.MUDDY_MANGROVE_ROOTS,
        Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM, Material.NETHERRACK,
        Material.SNOW_BLOCK, Material.SNOW, Material.POWDER_SNOW,
        Material.MOSS_BLOCK, Material.SHORT_GRASS, Material.SHORT_DRY_GRASS, Material.TALL_GRASS,
        Material.FERN, Material.LARGE_FERN, Material.FIREFLY_BUSH, Material.BUSH, Material.DEAD_BUSH,
        Material.VINE, Material.GLOW_LICHEN, Material.MOSS_CARPET, Material.LEAF_LITTER, Material.WILDFLOWERS, Material.PINK_PETALS,
        Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET,
        Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY,
        Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.WITHER_ROSE, Material.SUNFLOWER, Material.LILAC,
        Material.ROSE_BUSH, Material.PEONY, Material.TORCHFLOWER, Material.PITCHER_PLANT, Material.LILY_PAD,
        Material.SEAGRASS, Material.TALL_SEAGRASS, Material.SEA_PICKLE
    ));

    private static final Set<Material> STRUCTURE_MATERIALS = new HashSet<>(Arrays.asList(
        Material.STONE, Material.COBBLESTONE, Material.STONE_BRICKS, Material.BRICKS,
        Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.MAGENTA_TERRACOTTA,
        Material.LIGHT_BLUE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA,
        Material.PINK_TERRACOTTA, Material.GRAY_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA,
        Material.CYAN_TERRACOTTA, Material.PURPLE_TERRACOTTA, Material.BLUE_TERRACOTTA,
        Material.BROWN_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.RED_TERRACOTTA,
        Material.BLACK_TERRACOTTA, Material.TERRACOTTA, Material.POLISHED_BLACKSTONE_BRICKS,
        Material.POLISHED_BLACKSTONE, Material.BLACKSTONE, Material.END_STONE_BRICKS,
        Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK,
        Material.LAPIS_BLOCK, Material.REDSTONE_BLOCK, Material.COAL_BLOCK, Material.NETHER_BRICKS
    ));

    private static final BlockFace[] SIX_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final Plugin plugin;

    public TreeAxeManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isTreeLog(Block block) {
        return LOG_MATERIALS.contains(block.getType());
    }

    /** True if block is a root material (e.g. mangrove roots); tree-feller style. */
    public boolean isRootMaterial(Block block) {
        return ROOT_MATERIALS.contains(block.getType());
    }

    /**
     * Resolve the block that was broken to the block to use for tree detection.
     * If it's a log, return it. If it's a root (tree-feller ROOTS), find nearest trunk and return that, or null.
     */
    public Block resolveStartBlock(Block block) {
        if (LOG_MATERIALS.contains(block.getType())) {
            return block;
        }
        if (ROOT_MATERIALS.contains(block.getType())) {
            return findNearestTrunkFromRoot(block);
        }
        return null;
    }

    /** Find nearest trunk block when breaking a root (tree-feller getNearest from roots). */
    private Block findNearestTrunkFromRoot(Block rootBlock) {
        Material trunkType = getTrunkTypeForRoot(rootBlock.getType());
        if (trunkType == null) return null;
        for (int dy = -ROOT_SCAN_DISTANCE; dy <= ROOT_SCAN_DISTANCE; dy++) {
            Block at = rootBlock.getRelative(0, dy, 0);
            if (LOG_MATERIALS.contains(at.getType()) && matchesTrunkType(at.getType(), trunkType)) {
                return at;
            }
        }
        return null;
    }

    private static Material getTrunkTypeForRoot(Material rootMaterial) {
        if (rootMaterial == Material.MANGROVE_ROOTS || rootMaterial == Material.MUDDY_MANGROVE_ROOTS) {
            return Material.MANGROVE_LOG;
        }
        return null;
    }

    /**
     * Only cancel when we will actually chop (failed detection = vanilla break).
     * Essentials-style: leaves scan UP, valid tree ground (bottom log on dirt/grass), structure check.
     */
    public boolean willChopTree(Block startBlock, Player player) {
        if (!LOG_MATERIALS.contains(startBlock.getType())) {
            return false;
        }
        if (!hasEnoughLeavesByScanningUp(startBlock)) {
            return false;
        }
        List<Block> trunk = getTrunkBlocksLayered(startBlock);
        if (trunk.isEmpty()) {
            return false;
        }
        if (!isOnTreeGround(startBlock)) {
            return false;
        }
        if (isNearPlayerStructure(startBlock, player)) {
            return false;
        }
        return true;
    }

    /** Same trunk type (e.g. OAK_LOG and STRIPPED_OAK_LOG); handles _WOOD/_HYPHAE like Essentials. */
    private boolean matchesTrunkType(Material a, Material b) {
        if (!LOG_MATERIALS.contains(a) || !LOG_MATERIALS.contains(b)) return false;
        String nameA = normalizeTrunkName(a.name());
        String nameB = normalizeTrunkName(b.name());
        return nameA.equals(nameB);
    }

    private static String normalizeTrunkName(String name) {
        return name.replace("STRIPPED_", "").replace("_WOOD", "_LOG").replace("_HYPHAE", "_STEM");
    }

    /** Valid ground for trees (Essentials/LumberJack style). */
    private boolean isGroundMaterial(Material material) {
        return material == Material.DIRT || material == Material.GRASS_BLOCK
            || material == Material.PODZOL || material == Material.COARSE_DIRT
            || material == Material.MYCELIUM || material == Material.ROOTED_DIRT
            || material == Material.MUD || material == Material.MANGROVE_ROOTS
            || material == Material.MUDDY_MANGROVE_ROOTS || material == Material.CRIMSON_NYLIUM
            || material == Material.WARPED_NYLIUM || material == Material.NETHERRACK
            || material == Material.SNOW_BLOCK || material == Material.SNOW;
    }

    /** Valid ground types for a log type (overworld vs nether vs mangrove). */
    private Material[] getValidGroundTypes(Material logType) {
        String logName = normalizeTrunkName(logType.name());
        switch (logName) {
            case "OAK_LOG":
            case "BIRCH_LOG":
            case "SPRUCE_LOG":
            case "JUNGLE_LOG":
            case "ACACIA_LOG":
            case "DARK_OAK_LOG":
            case "CHERRY_LOG":
            case "PALE_OAK_LOG":
                return new Material[]{Material.DIRT, Material.GRASS_BLOCK, Material.MYCELIUM,
                    Material.COARSE_DIRT, Material.PODZOL, Material.SNOW_BLOCK, Material.SNOW};
            case "CRIMSON_STEM":
                return new Material[]{Material.CRIMSON_NYLIUM, Material.NETHERRACK};
            case "WARPED_STEM":
                return new Material[]{Material.WARPED_NYLIUM, Material.NETHERRACK};
            case "MANGROVE_LOG":
                return new Material[]{Material.MANGROVE_ROOTS, Material.MUDDY_MANGROVE_ROOTS, Material.MUD, Material.DIRT};
            default:
                return new Material[]{Material.DIRT, Material.GRASS_BLOCK};
        }
    }

    /** Whether the log is connected downward to valid ground (helps identify natural trees). */
    private boolean isConnectedToGround(Block block) {
        Block current = block;
        for (int i = 0; i < MAX_GROUND_SCAN && current.getY() >= block.getWorld().getMinHeight(); i++) {
            current = current.getRelative(BlockFace.DOWN);
            Material mat = current.getType();
            if (isGroundMaterial(mat)) return true;
            if (!LOG_MATERIALS.contains(mat) && !isGroundMaterial(mat)) return false;
        }
        return false;
    }

    /**
     * Whether the block is on valid tree ground (Essentials/LumberJack).
     * Scan down through logs and limited air; then require valid ground type for this log.
     */
    private boolean isOnTreeGround(Block block) {
        int airInBetween = 0;
        Block currentBlock = block;
        Material logType = block.getType();

        while (currentBlock.getY() >= block.getWorld().getMinHeight()) {
            Material mat = currentBlock.getType();
            if (mat.isAir()) {
                airInBetween++;
                if (airInBetween > MAX_AIR_BETWEEN_GROUND) return false;
            } else if (LOG_MATERIALS.contains(mat) && matchesTrunkType(mat, logType)) {
                airInBetween = 0;
            } else {
                break;
            }
            currentBlock = currentBlock.getRelative(BlockFace.DOWN);
        }

        Material[] validGround = getValidGroundTypes(logType);
        Material below = currentBlock.getType();
        for (Material g : validGround) {
            if (below == g) return true;
        }
        return false;
    }

    /**
     * Essentials style: scan UP along the trunk from the broken block, at each level count leaves in radius 2.
     * So we count leaves at the canopy (top), not just at the bottom. Require 6 for tall trees, 2 for short (don't change 6).
     */
    private boolean hasEnoughLeavesByScanningUp(Block startBlock) {
        Material originalType = startBlock.getType();
        int totalLeaves = 0;
        Block current = startBlock;
        int trunkHeight = 0;

        while (trunkHeight < MAX_TRUNK_SCAN_HEIGHT && matchesTrunkType(current.getType(), originalType)) {
            totalLeaves += countLeavesInRadius(current, LEAF_COUNT_RADIUS);
            if (totalLeaves >= MIN_LEAVES_TALL) {
                return true;
            }
            current = current.getRelative(BlockFace.UP);
            trunkHeight++;
        }

        if (!matchesTrunkType(current.getType(), originalType)) {
            totalLeaves += countLeavesInRadius(current, LEAF_COUNT_RADIUS);
        }

        int required = trunkHeight <= 2 ? MIN_LEAVES_SHORT : MIN_LEAVES_TALL;
        return totalLeaves >= required;
    }

    private int countLeavesInRadius(Block center, int radius) {
        int count = 0;
        World world = center.getWorld();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (LEAF_MATERIALS.contains(world.getBlockAt(cx + dx, cy + dy, cz + dz).getType())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Layered BFS from start block through same trunk type only (so we don't merge trees).
     */
    private List<Block> getTrunkBlocksLayered(Block startingBlock) {
        if (!LOG_MATERIALS.contains(startingBlock.getType())) {
            return Collections.emptyList();
        }
        Material trunkType = startingBlock.getType();
        Set<Block> allSeen = new HashSet<>();
        Map<Integer, List<Block>> byDistance = new HashMap<>();
        List<Block> layer0 = new ArrayList<>();
        layer0.add(startingBlock);
        allSeen.add(startingBlock);
        byDistance.put(0, layer0);
        int total = 1;

        for (int i = 0; i < SCAN_DISTANCE && total < MAX_LOGS; i++) {
            List<Block> lastLayer = byDistance.get(i);
            if (lastLayer == null || lastLayer.isEmpty()) break;
            List<Block> nextLayer = new ArrayList<>();
            for (Block block : lastLayer) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            Block neighbor = block.getRelative(dx, dy, dz);
                            if (!matchesTrunkType(neighbor.getType(), trunkType)) continue;
                            if (allSeen.contains(neighbor)) continue;
                            allSeen.add(neighbor);
                            nextLayer.add(neighbor);
                            total++;
                            if (total >= MAX_LOGS) break;
                        }
                        if (total >= MAX_LOGS) break;
                    }
                    if (total >= MAX_LOGS) break;
                }
            }
            if (nextLayer.isEmpty()) break;
            byDistance.put(i + 1, nextLayer);
        }

        List<Block> result = new ArrayList<>();
        for (List<Block> layer : byDistance.values()) {
            result.addAll(layer);
        }
        return result;
    }

    /**
     * Tree-feller / Essentials style: do not chop if non-natural (player structure) blocks are nearby.
     * Uses explicit STRUCTURE_MATERIALS plus name-based check for planks, fences, slabs, etc.
     */
    private boolean isNearPlayerStructure(Block block, Player player) {
        Location center = block.getLocation();
        int px = center.getBlockX(), py = center.getBlockY(), pz = center.getBlockZ();
        World world = center.getWorld();
        if (world == null) return false;
        for (int x = px - 3; x <= px + 3; x++) {
            for (int y = py - 3; y <= py + 3; y++) {
                for (int z = pz - 3; z <= pz + 3; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (NATURAL_EXCLUSIONS.contains(type)) continue;
                    if (STRUCTURE_MATERIALS.contains(type)) return true;
                    if (isStructureMaterialByName(type)) return true;
                }
            }
        }
        return false;
    }

    /** Name-based structure check (tree-feller / SmoothTimber) so we don't chop near any player building. */
    private boolean isStructureMaterialByName(Material material) {
        String name = material.name();
        return name.contains("STONE") || name.contains("COBBLE") || name.contains("BRICK")
            || name.contains("CONCRETE") || name.contains("TERRACOTTA") || name.contains("GLASS")
            || name.contains("WOOL") || name.contains("CARPET") || name.contains("PLANK")
            || name.contains("SLAB") || name.contains("STAIR") || name.contains("FENCE")
            || name.contains("DOOR") || name.contains("TRAPDOOR") || name.contains("BUTTON")
            || name.contains("PRESSURE_PLATE") || name.contains("TORCH") || name.contains("LANTERN")
            || name.contains("CRAFTING_TABLE") || name.contains("CHEST") || name.contains("FURNACE")
            || name.contains("BED") || name.contains("BOOKSHELF") || name.contains("BANNER")
            || name.contains("SIGN") || name.contains("RAIL") || name.contains("REDSTONE")
            || name.contains("HOPPER") || name.contains("REPEATER") || name.contains("COMPARATOR")
            || name.contains("PISTON") || name.contains("OBSIDIAN") || name.contains("ENDER_CHEST");
    }

    /**
     * Get trunk blocks for breaking. Same as detection; used when we know we will chop.
     */
    public List<Block> getConnectedLogs(Block startBlock) {
        List<Block> trunk = getTrunkBlocksLayered(startBlock);
        trunk.sort(Comparator.comparingInt(Block::getY));
        return trunk;
    }

    /** Break a single block and drop items (e.g. root block when chop started from root). */
    public void breakSingleBlock(Player player, Block block, ItemStack tool) {
        breakBlockOnly(player, block, tool);
    }

    private void breakBlockOnly(Player player, Block block, ItemStack tool) {
        for (ItemStack drop : block.getDrops(tool)) {
            block.getWorld().dropItemNaturally(block.getLocation(), drop);
        }
        block.setType(Material.AIR);
    }

    public void breakTree(Player player, Block startBlock, ItemStack axe) {
        if (!hasEnoughLeavesByScanningUp(startBlock)) {
            breakBlockOnly(player, startBlock, axe);
            return;
        }
        List<Block> logs = getTrunkBlocksLayered(startBlock);
        if (logs.isEmpty()) {
            breakBlockOnly(player, startBlock, axe);
            return;
        }
        if (!isOnTreeGround(startBlock)) {
            breakBlockOnly(player, startBlock, axe);
            return;
        }
        if (isNearPlayerStructure(startBlock, player)) {
            breakBlockOnly(player, startBlock, axe);
            return;
        }

        logs.sort(Comparator.comparingInt(Block::getY));
        Player finalPlayer = player;
        ItemStack finalAxe = axe;

        for (int i = 0; i < logs.size(); i++) {
            final Block log = logs.get(i);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (finalAxe.getType().getMaxDurability() > 0) {
                    short durability = finalAxe.getDurability();
                    if (durability >= finalAxe.getType().getMaxDurability()) {
                        finalPlayer.sendMessage(ChatColor.RED + "Your axe is broken!");
                        return;
                    }
                    finalAxe.setDurability((short) (durability + 1));
                }
                for (ItemStack drop : log.getDrops(finalAxe)) {
                    log.getWorld().dropItemNaturally(log.getLocation(), drop);
                }
                log.setType(Material.AIR);
            }, i * 2L);
        }

        Text.sendDebugLog(INFO, "Tree axe broke " + logs.size() + " blocks for " + player.getName());
    }
}
