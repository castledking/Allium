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

public class TreeAxeManager {

    private static final int MAX_BLOCKS = 100;
    private static final double LEAF_SEARCH_RADIUS = 2.0;
    private static final int MIN_LEAVES = 6;

    private static final Set<Material> LOG_MATERIALS = new HashSet<>(Arrays.asList(
        Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
        Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.CHERRY_LOG, Material.PALE_OAK_LOG,
        Material.STRIPPED_OAK_LOG, Material.STRIPPED_SPRUCE_LOG, Material.STRIPPED_BIRCH_LOG,
        Material.STRIPPED_JUNGLE_LOG, Material.STRIPPED_ACACIA_LOG, Material.STRIPPED_DARK_OAK_LOG,
        Material.STRIPPED_CHERRY_LOG, Material.STRIPPED_PALE_OAK_LOG,
        Material.BAMBOO_BLOCK, Material.STRIPPED_BAMBOO_BLOCK,
        Material.CRIMSON_STEM, Material.WARPED_STEM,
        Material.STRIPPED_CRIMSON_STEM, Material.STRIPPED_WARPED_STEM
    ));

    private static final Set<Material> LEAF_MATERIALS = new HashSet<>(Arrays.asList(
        Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES, Material.JUNGLE_LEAVES,
        Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.CHERRY_LEAVES, Material.PALE_OAK_LEAVES,
        Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES
    ));

    private static final Set<Material> GROUND_MATERIALS = new HashSet<>(Arrays.asList(
        Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM,
        Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.MUD, Material.CRIMSON_NYLIUM,
        Material.WARPED_NYLIUM
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

    private final Plugin plugin;

    public TreeAxeManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isTreeLog(Block block) {
        return LOG_MATERIALS.contains(block.getType());
    }

    public boolean isValidTree(Block startBlock, Player player) {
        if (!isTreeLog(startBlock)) {
            return false;
        }

        if (!hasEnoughLeaves(startBlock)) {
            return false;
        }

        if (!isConnectedToGround(startBlock)) {
            return false;
        }

        if (isNearPlayerStructure(startBlock, player)) {
            return false;
        }

        return true;
    }

    private boolean hasEnoughLeaves(Block logBlock) {
        int leafCount = 0;
        Location center = logBlock.getLocation();

        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block check = center.getWorld().getBlockAt(
                        center.getBlockX() + x,
                        center.getBlockY() + y,
                        center.getBlockZ() + z
                    );

                    if (LEAF_MATERIALS.contains(check.getType())) {
                        leafCount++;
                        if (leafCount >= MIN_LEAVES) {
                            return true;
                        }
                    }
                }
            }
        }

        return leafCount >= MIN_LEAVES;
    }

    private boolean isConnectedToGround(Block block) {
        Set<Block> visited = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        queue.add(block);
        visited.add(block);

        while (!queue.isEmpty()) {
            Block current = queue.poll();

            for (BlockFace face : BlockFace.values()) {
                if (face == BlockFace.SELF) continue;

                Block relative = current.getRelative(face);
                if (visited.contains(relative)) continue;

                Material type = relative.getType();

                if (GROUND_MATERIALS.contains(type)) {
                    return true;
                }

                if (LOG_MATERIALS.contains(type)) {
                    visited.add(relative);
                    queue.add(relative);
                }
            }
        }

        return false;
    }

    private boolean isNearPlayerStructure(Block block, Player player) {
        Location center = block.getLocation();
        int playerX = center.getBlockX();
        int playerY = center.getBlockY();
        int playerZ = center.getBlockZ();

        int minX = playerX - 3;
        int maxX = playerX + 3;
        int minY = playerY - 3;
        int maxY = playerY + 3;
        int minZ = playerZ - 3;
        int maxZ = playerZ + 3;

        World world = center.getWorld();
        if (world == null) return false;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block check = world.getBlockAt(x, y, z);
                    if (STRUCTURE_MATERIALS.contains(check.getType())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public List<Block> getConnectedLogs(Block startBlock) {
        List<Block> logs = new ArrayList<>();
        Set<Block> visited = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();

        queue.add(startBlock);
        visited.add(startBlock);

        while (!queue.isEmpty() && logs.size() < MAX_BLOCKS) {
            Block current = queue.poll();
            logs.add(current);

            for (BlockFace face : BlockFace.values()) {
                if (face == BlockFace.SELF) continue;

                Block relative = current.getRelative(face);
                if (visited.contains(relative)) continue;

                if (LOG_MATERIALS.contains(relative.getType())) {
                    visited.add(relative);
                    queue.add(relative);
                }
            }
        }

        logs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        return logs;
    }

    public void breakTree(Player player, Block startBlock, ItemStack axe) {
        if (!isValidTree(startBlock, player)) {
            return;
        }

        List<Block> logs = getConnectedLogs(startBlock);

        if (logs.isEmpty()) {
            return;
        }

        Player finalPlayer = player;
        ItemStack finalAxe = axe;

        for (int i = 0; i < logs.size(); i++) {
            final Block log = logs.get(i);
            final int index = i;
            
            int taskId = i;
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
