package codes.castled.allium.spawnercraft;

import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class HeadGuideAccessTest {
    @Test void recognizedHeadsOutsideTheSpawnerDropListCanReachTheirOtherGuides() {
        PluginStart plugin = mock(PluginStart.class);
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.namespace()).thenReturn("allium");
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        SpawnerCraftListener listener = new SpawnerCraftListener(plugin, mock(SpawnerCoreManager.class));
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Player player = mock(Player.class);
        ItemStack head = mock(ItemStack.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(head);
        try (var registry = mockStatic(MobHeadRegistry.class);
             var gui = mockStatic(SpawnerCraftGuideGui.class)) {
            registry.when(() -> MobHeadRegistry.isMobHeadForPlacement(head, false)).thenReturn(true);
            registry.when(() -> MobHeadRegistry.getEntityType(head, false)).thenReturn(EntityType.AXOLOTL);
            registry.when(() -> MobHeadRegistry.getEntityType(head, true)).thenReturn(EntityType.AXOLOTL);
            gui.when(() -> SpawnerCraftGuideGui.openHead(player, EntityType.AXOLOTL, head)).thenReturn(true);
            listener.onRightClick(event);
            // Access must not require the mob to exist in spawner_heads.yml.
            gui.verify(() -> SpawnerCraftGuideGui.openHead(player, EntityType.AXOLOTL, head));
            verify(event).setCancelled(true);
        }
    }
}
