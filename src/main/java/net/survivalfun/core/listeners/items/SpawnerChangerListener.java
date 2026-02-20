package net.survivalfun.core.listeners.items;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.items.CustomItemRegistry;
import net.survivalfun.core.items.impl.SpawnerChangerItem;
import net.survivalfun.core.items.impl.SpawnerChangerManager;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class SpawnerChangerListener implements Listener {

    private final PluginStart plugin;
    private final CustomItemRegistry customItemRegistry;
    private final SpawnerChangerManager spawnerChangerManager;

    public SpawnerChangerListener(PluginStart plugin, CustomItemRegistry customItemRegistry, SpawnerChangerManager manager) {
        this.plugin = plugin;
        this.customItemRegistry = customItemRegistry;
        this.spawnerChangerManager = manager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.isCancelled()) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getClickedBlock() == null) {
            return;
        }

        if (event.getClickedBlock().getType() != Material.SPAWNER) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        if (!customItemRegistry.isCustomItem(item)) {
            return;
        }

        if (customItemRegistry.getItem(item) instanceof SpawnerChangerItem) {
            event.setCancelled(true);
            spawnerChangerManager.openIntakeMenuFromBlock(event.getPlayer(), event.getClickedBlock().getLocation());
        }
    }
}
