package net.survivalfun.core.listeners.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;

/**
 * Prevents Oraxen noteblocks from breaking due to physics updates in the 'Resource' world.
 * This allows keeping disable-noteblock-updates: false globally while protecting ores in the resource world.
 */
public class NoteBlockPhysicsListener implements Listener {

    private static final String RESOURCE_WORLD_NAME = "Resource";
    
    /**
     * Check if a block is an Oraxen noteblock using reflection to avoid hard dependency
     */
    private boolean isOraxenNoteBlock(Block block) {
        if (block.getType() != Material.NOTE_BLOCK) return false;
        
        try {
            Class<?> oraxenBlocks = Class.forName("io.th0rgal.oraxen.api.OraxenBlocks");
            Object mechanic = oraxenBlocks
                .getMethod("getNoteBlockMechanic", Block.class)
                .invoke(null, block);
            return mechanic != null;
        } catch (Exception e) {
            // Oraxen not available or error
            return false;
        }
    }
    
    /**
     * Check if a block is an Oraxen noteblock from BlockData
     */
    private boolean isOraxenNoteBlock(org.bukkit.block.data.BlockData data) {
        if (!(data instanceof NoteBlock)) return false;
        
        try {
            Class<?> oraxenBlocks = Class.forName("io.th0rgal.oraxen.api.OraxenBlocks");
            Object mechanic = oraxenBlocks
                .getMethod("getNoteBlockMechanic", org.bukkit.block.data.BlockData.class)
                .invoke(null, data);
            return mechanic != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPhysics(final BlockPhysicsEvent event) {
        Block block = event.getBlock();
        
        // Only apply in Resource world
        if (!block.getWorld().getName().equals(RESOURCE_WORLD_NAME)) {
            return;
        }
        
        final Block aboveBlock = block.getRelative(BlockFace.UP);
        final Block belowBlock = block.getRelative(BlockFace.DOWN);
        
        // If block below is Oraxen NoteBlock, cancel physics to prevent breaking
        if (isOraxenNoteBlock(belowBlock)) {
            event.setCancelled(true);
            updateAndCheck(belowBlock);
            return;
        }
        
        // If above is Oraxen NoteBlock, also cancel
        if (isOraxenNoteBlock(aboveBlock)) {
            event.setCancelled(true);
            updateAndCheck(aboveBlock);
            return;
        }
        
        // If the block itself is an Oraxen NoteBlock, cancel physics
        if (isOraxenNoteBlock(block)) {
            event.setCancelled(true);
            updateAndCheck(block);
        }
    }
    
    /**
     * Update vertical stacks of noteblocks to prevent visual glitches
     * Copies Oraxen's updateAndCheck logic
     */
    private void updateAndCheck(Block block) {
        final Block blockAbove = block.getRelative(BlockFace.UP);
        if (isOraxenNoteBlock(blockAbove)) {
            // Force update without physics
            blockAbove.getState().update(true, false);
        }
        
        // Recursively check above
        Block nextBlock = blockAbove.getRelative(BlockFace.UP);
        if (isOraxenNoteBlock(nextBlock)) {
            updateAndCheck(blockAbove);
        }
    }
}
