package codes.castled.allium.listeners.items;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import codes.castled.allium.PluginStart;
import codes.castled.allium.items.CustomItemRegistry;
import codes.castled.allium.items.impl.SpawnerChangerItem;
import codes.castled.allium.items.impl.SpawnerChangerManager;

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

    @EventHandler
    public void onPlayerInteractAir(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (customItemRegistry.getItem(item) instanceof SpawnerChangerItem) {
            spawnerChangerManager.sendClickMessage(event.getPlayer());
        }
    }
}
