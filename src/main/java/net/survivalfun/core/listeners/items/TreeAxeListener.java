package net.survivalfun.core.listeners.items;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.items.CustomItemRegistry;
import net.survivalfun.core.items.impl.TreeAxeItem;
import net.survivalfun.core.items.impl.TreeAxeManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

public class TreeAxeListener implements Listener {

    private final PluginStart plugin;
    private final TreeAxeManager treeAxeManager;
    private final CustomItemRegistry customItemRegistry;

    public TreeAxeListener(PluginStart plugin, CustomItemRegistry customItemRegistry) {
        this.plugin = plugin;
        this.customItemRegistry = customItemRegistry;
        this.treeAxeManager = new TreeAxeManager(plugin);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }

        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        if (!customItemRegistry.isCustomItem(item)) {
            return;
        }

        if (customItemRegistry.getItem(item) instanceof TreeAxeItem) {
            Block block = event.getBlock();
            // Tree-feller style: also trigger when breaking root blocks (e.g. mangrove roots)
            if (treeAxeManager.isRootMaterial(block)) {
                Block trunk = treeAxeManager.resolveStartBlock(block);
                if (trunk != null && treeAxeManager.willChopTree(trunk, event.getPlayer())) {
                    event.setCancelled(true);
                    treeAxeManager.breakSingleBlock(event.getPlayer(), block, item);
                    treeAxeManager.breakTree(event.getPlayer(), trunk, item);
                }
            } else if (treeAxeManager.isTreeLog(block)) {
                Block start = treeAxeManager.resolveStartBlock(block);
                if (start != null && treeAxeManager.willChopTree(start, event.getPlayer())) {
                    event.setCancelled(true);
                    treeAxeManager.breakTree(event.getPlayer(), start, item);
                }
            }
        }
    }
}
