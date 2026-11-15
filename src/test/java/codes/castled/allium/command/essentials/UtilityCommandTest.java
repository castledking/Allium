package codes.castled.allium.command.essentials;
import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.kit.KitService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class UtilityCommandTest {
    @Test void repairUsesTheExistingMetadataWithoutReplacingIdentity(){
        var plugin=mock(PluginStart.class);when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        var p=mock(Player.class);var inventory=mock(PlayerInventory.class);var item=mock(ItemStack.class);var meta=mock(Damageable.class);
        when(p.getInventory()).thenReturn(inventory);when(inventory.getItemInMainHand()).thenReturn(item);when(item.getItemMeta()).thenReturn(meta);when(meta.hasDamage()).thenReturn(true);
        var context=mock(BukkitCommandContext.class);when(context.sender()).thenReturn(p);when(context.args()).thenReturn(new String[0]);
        new UtilityCommand(plugin,CoreCommandSpecs.UTILITY_SPECS.get(0)).execute(context);
        verify(meta).setDamage(0);verify(item).setItemMeta(same(meta));verify(inventory,never()).setItemInMainHand(any());
    }
    @Test void infoCountsOnlyVisiblePlayers(){
        var plugin=mock(PluginStart.class);when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        var server=mock(org.bukkit.Server.class);when(plugin.getServer()).thenReturn(server);when(server.getName()).thenReturn("TestServer");
        var p=mock(Player.class);var hidden=mock(Player.class);doReturn(java.util.List.of(p,hidden)).when(server).getOnlinePlayers();when(p.canSee(hidden)).thenReturn(false);
        var context=mock(BukkitCommandContext.class);when(context.sender()).thenReturn(p);when(context.args()).thenReturn(new String[0]);
        new UtilityCommand(plugin,CoreCommandSpecs.UTILITY_SPECS.stream().filter(spec->spec.canonicalName().equals("info")).findFirst().orElseThrow()).execute(context);
        verify(p).sendMessage("[Allium] TestServer — 1 player(s) online.");
    }
    @Test void inventoryRecoveryBlocksRepairBeforeReadingAnyItem(){
        var plugin=mock(PluginStart.class);var modules=mock(ModuleManager.class);when(plugin.getModuleManager()).thenReturn(modules);
        var p=mock(Player.class);var kits=mock(KitsModule.class);var service=mock(KitService.class);when(modules.getModule("kits")).thenReturn(kits);when(kits.isEnabled()).thenReturn(true);when(kits.kits()).thenReturn(service);when(service.busy(p)).thenReturn(true);
        var context=mock(BukkitCommandContext.class);when(context.sender()).thenReturn(p);when(context.args()).thenReturn(new String[0]);
        new UtilityCommand(plugin,CoreCommandSpecs.UTILITY_SPECS.get(0)).execute(context);verify(p,never()).getInventory();
    }
}
