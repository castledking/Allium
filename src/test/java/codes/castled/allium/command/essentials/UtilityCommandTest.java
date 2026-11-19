package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.module.KitsModule;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class UtilityCommandTest {

    @Test
    void repairPreservesMetadataAndWritesTheRepairedStack() {
        var plugin = mock(PluginStart.class);
        when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        var p = mock(Player.class);
        var inventory = mock(PlayerInventory.class);
        var item = mock(ItemStack.class);
        var meta = mock(Damageable.class);
        when(p.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(item);
        when(item.getType()).thenReturn(Material.DIAMOND_PICKAXE);
        when(item.getAmount()).thenReturn(1);
        var repaired = mock(ItemStack.class);
        when(item.clone()).thenReturn(repaired);
        when(repaired.setItemMeta(meta)).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.hasDamage()).thenReturn(true);
        var context = mock(BukkitCommandContext.class);
        when(context.sender()).thenReturn(p);
        when(context.args()).thenReturn(new String[0]);

        new UtilityCommand(plugin, CoreCommandSpecs.UTILITY_SPECS.get(0)).execute(context);

        verify(meta).setDamage(0);
        verify(repaired).setItemMeta(same(meta));
        verify(inventory).setItemInMainHand(repaired);
    }

    @Test
    void infoCountsOnlyVisiblePlayers() {
        var plugin = mock(PluginStart.class);
        when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getName()).thenReturn("TestServer");
        var p = mock(Player.class);
        var hidden = mock(Player.class);
        doReturn(java.util.List.of(p, hidden)).when(server).getOnlinePlayers();
        when(p.canSee(hidden)).thenReturn(false);
        var context = mock(BukkitCommandContext.class);
        when(context.sender()).thenReturn(p);
        when(context.args()).thenReturn(new String[0]);

        new UtilityCommand(plugin, CoreCommandSpecs.UTILITY_SPECS.stream()
                .filter(spec -> spec.canonicalName().equals("info")).findFirst().orElseThrow()).execute(context);

        verify(p).sendMessage("§7[§dAllium§7] §fTestServer §7— §f1 §7player(s) online.");
    }

    @Test
    void inventoryRecoveryBlocksRepairBeforeReadingAnyItem() {
        var plugin = mock(PluginStart.class);
        var modules = mock(ModuleManager.class);
        when(plugin.getModuleManager()).thenReturn(modules);
        var p = mock(Player.class);
        when(p.getName()).thenReturn("TestPlayer");
        var kits = mock(KitsModule.class);
        var service = mock(KitService.class);
        when(modules.getModule("kits")).thenReturn(kits);
        when(kits.isEnabled()).thenReturn(true);
        when(kits.kits()).thenReturn(service);
        when(service.busy(p)).thenReturn(true);
        var context = mock(BukkitCommandContext.class);
        when(context.sender()).thenReturn(p);
        when(context.args()).thenReturn(new String[0]);

        new UtilityCommand(plugin, CoreCommandSpecs.UTILITY_SPECS.get(0)).execute(context);

        verify(p, never()).getInventory();
    }

    @Test
    void repairAllRequiresPermissionAndRepairsAllDamagedSlotsInPlace() {
        var plugin = mock(PluginStart.class);
        when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        var p = mock(Player.class);
        var inventory = mock(PlayerInventory.class);
        when(p.getInventory()).thenReturn(inventory);

        var deniedCtx = mock(BukkitCommandContext.class);
        when(deniedCtx.sender()).thenReturn(p);
        when(deniedCtx.args()).thenReturn(new String[]{"all"});
        when(p.hasPermission("allium.repair.all")).thenReturn(false);
        new UtilityCommand(plugin, CoreCommandSpecs.REPAIR).execute(deniedCtx);
        verify(inventory, never()).getContents();

        ItemStack damagedA = mock(ItemStack.class);
        when(damagedA.getType()).thenReturn(Material.DIAMOND_PICKAXE);
        when(damagedA.getAmount()).thenReturn(1);
        var metaA = mock(Damageable.class);
        var repaired = mock(ItemStack.class);
        when(damagedA.clone()).thenReturn(repaired);
        when(repaired.setItemMeta(metaA)).thenReturn(true);
        when(damagedA.getItemMeta()).thenReturn(metaA);
        when(metaA.hasDamage()).thenReturn(true);

        ItemStack intact = mock(ItemStack.class);
        when(intact.getType()).thenReturn(Material.IRON_SWORD);
        when(intact.getAmount()).thenReturn(1);
        var metaB = mock(Damageable.class);
        when(intact.getItemMeta()).thenReturn(metaB);
        when(metaB.hasDamage()).thenReturn(false);

        ItemStack damagedB=mock(ItemStack.class);
        ItemStack repairedB=mock(ItemStack.class);
        Damageable metaC=mock(Damageable.class);
        when(damagedB.getType()).thenReturn(Material.NETHERITE_SWORD);
        when(damagedB.getAmount()).thenReturn(1);
        when(damagedB.getItemMeta()).thenReturn(metaC);
        when(metaC.hasDamage()).thenReturn(true);
        when(damagedB.clone()).thenReturn(repairedB);
        when(repairedB.setItemMeta(metaC)).thenReturn(true);
        when(inventory.getContents()).thenReturn(new ItemStack[]{damagedA, damagedB, intact});
        when(p.hasPermission("allium.repair.all")).thenReturn(true);
        new UtilityCommand(plugin, CoreCommandSpecs.REPAIR).execute(deniedCtx);

        verify(metaA).setDamage(0);
        verify(repaired).setItemMeta(same(metaA));
        verify(inventory).setItem(0,repaired);
        verify(inventory).setItem(1,repairedB);
        verify(metaB, never()).setDamage(anyInt());
        verify(p).sendMessage(argThat((String msg) ->
                msg.contains("Repaired") && msg.contains("Diamond Pickaxe, Netherite Sword")));
    }

    @Test
    void disposalOpensViaServerAndClearsItemsOnClose() {
        var plugin = mock(PluginStart.class);
        when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        var inv = mock(Inventory.class);
        when(server.createInventory(any(UtilityCommand.DisposalHolder.class), eq(27), eq("Disposal"))).thenReturn(inv);
        var p = mock(Player.class);
        var context = mock(BukkitCommandContext.class);
        when(context.sender()).thenReturn(p);
        when(context.args()).thenReturn(new String[0]);

        new UtilityCommand(plugin, CoreCommandSpecs.DISPOSAL).execute(context);
        verify(p).openInventory(same(inv));

        var holder = new UtilityCommand.DisposalHolder();
        holder.setInventory(inv);
        when(inv.getHolder()).thenReturn(holder);
        var view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inv);
        var closeEvent = mock(InventoryCloseEvent.class);
        when(closeEvent.getInventory()).thenReturn(inv);

        new UtilityCommand.DisposalListener().onClose(closeEvent);
        verify(inv).clear();
    }
}
