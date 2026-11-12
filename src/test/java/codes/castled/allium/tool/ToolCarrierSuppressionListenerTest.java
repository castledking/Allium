package codes.castled.allium.tool;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.MockItemStacks;
import org.bukkit.Material;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolCarrierSuppressionListenerTest {

    private ItemService items;
    private Plugin plugin;
    private ToolCarrierSuppressionListener listener;

    private ItemStack customMembrane;
    private ItemStack plainMembrane;
    private ItemStack customHoe;
    private ItemStack plainHoe;

    @BeforeEach
    void setUp() {
        items = new ItemService(MockItemStacks::create);
        plugin = mock(Plugin.class);
        listener = new ToolCarrierSuppressionListener(plugin, items);

        items.registerAlliumItem(new AlliumItemDefinition("phantom_obliterator", Material.PHANTOM_MEMBRANE,
                "allium:obliterate_phantoms", true, 2577030, "Phantom Obliterator", Map.of(), true));
        items.registerAlliumItem(new AlliumItemDefinition("mob_disarmer", Material.GOLDEN_HOE,
                "allium:mob_disarmer", false, 2577000, "Mob Disarmer", Map.of(), true));

        customMembrane = items.create(new ItemRef(ProviderKey.of("allium", "phantom_obliterator"), "allium:obliterate_phantoms", 1, Map.of())).itemStack().orElseThrow();
        plainMembrane = MockItemStacks.create(Material.PHANTOM_MEMBRANE);
        customHoe = items.create(new ItemRef(ProviderKey.of("allium", "mob_disarmer"), "allium:mob_disarmer", 1, Map.of())).itemStack().orElseThrow();
        plainHoe = MockItemStacks.create(Material.GOLDEN_HOE);
    }

    @Test
    void detectsCustomToolsAndLeavesPlainItemsUnaffected() {
        assertTrue(listener.isSuppressedTool(customMembrane));
        assertTrue(listener.isSuppressedTool(customHoe));

        assertFalse(listener.isSuppressedTool(plainMembrane));
        assertFalse(listener.isSuppressedTool(plainHoe));
        assertFalse(listener.isSuppressedTool(null));
        assertFalse(listener.isSuppressedTool(MockItemStacks.create(Material.AIR)));
    }

    @Test
    void craftPreparationSuppressesResultWhenCustomToolInMatrix() {
        CraftingInventory inv = mock(CraftingInventory.class);
        when(inv.getMatrix()).thenReturn(new ItemStack[]{customMembrane, plainHoe});
        PrepareItemCraftEvent event = new PrepareItemCraftEvent(inv, mock(org.bukkit.inventory.InventoryView.class), false);

        listener.onPrepareItemCraft(event);
        verify(inv).setResult(null);

        reset(inv);
        when(inv.getMatrix()).thenReturn(new ItemStack[]{plainMembrane, plainHoe});
        listener.onPrepareItemCraft(event);
        verify(inv, never()).setResult(null);
    }

    @Test
    void craftItemEventIsCancelledWhenCustomToolInMatrix() {
        CraftingInventory inv = mock(CraftingInventory.class);
        when(inv.getMatrix()).thenReturn(new ItemStack[]{customHoe});
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getInventory()).thenReturn(inv);

        listener.onCraftItem(event);
        verify(event).setCancelled(true);

        reset(event);
        when(event.getInventory()).thenReturn(inv);
        when(inv.getMatrix()).thenReturn(new ItemStack[]{plainHoe});
        listener.onCraftItem(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void furnaceSmeltAndBurnCancelledForCustomTools() {
        FurnaceSmeltEvent smeltEvent = mock(FurnaceSmeltEvent.class);
        when(smeltEvent.getSource()).thenReturn(customMembrane);
        listener.onFurnaceSmelt(smeltEvent);
        verify(smeltEvent).setCancelled(true);

        reset(smeltEvent);
        when(smeltEvent.getSource()).thenReturn(plainMembrane);
        listener.onFurnaceSmelt(smeltEvent);
        verify(smeltEvent, never()).setCancelled(true);

        FurnaceBurnEvent burnEvent = mock(FurnaceBurnEvent.class);
        when(burnEvent.getFuel()).thenReturn(customHoe);
        listener.onFurnaceBurn(burnEvent);
        verify(burnEvent).setCancelled(true);

        reset(burnEvent);
        when(burnEvent.getFuel()).thenReturn(plainHoe);
        listener.onFurnaceBurn(burnEvent);
        verify(burnEvent, never()).setCancelled(true);
    }

    @Test
    void brewingCancelledForCustomIngredient() {
        BrewEvent brewEvent = mock(BrewEvent.class);
        BrewerInventory contents = mock(BrewerInventory.class);
        when(brewEvent.getContents()).thenReturn(contents);
        when(contents.getIngredient()).thenReturn(customMembrane);

        listener.onBrew(brewEvent);
        verify(brewEvent).setCancelled(true);

        reset(brewEvent);
        when(brewEvent.getContents()).thenReturn(contents);
        when(contents.getIngredient()).thenReturn(plainMembrane);
        listener.onBrew(brewEvent);
        verify(brewEvent, never()).setCancelled(true);
    }

    @Test
    void anvilSuppressesResultWhenCustomToolUsedAsRepairMaterialOrBase() {
        AnvilInventory inv = mock(AnvilInventory.class);
        PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        when(event.getInventory()).thenReturn(inv);

        ItemStack elytra = MockItemStacks.create(Material.ELYTRA);
        ItemStack gold = MockItemStacks.create(Material.GOLD_INGOT);

        // Custom tool as sacrifice / repair ingredient (slot 1)
        when(inv.getItem(0)).thenReturn(elytra);
        when(inv.getItem(1)).thenReturn(customMembrane);
        listener.onPrepareAnvil(event);
        verify(event).setResult(null);

        // Plain phantom membrane as repair ingredient
        reset(event);
        when(event.getInventory()).thenReturn(inv);
        when(inv.getItem(0)).thenReturn(elytra);
        when(inv.getItem(1)).thenReturn(plainMembrane);
        listener.onPrepareAnvil(event);
        verify(event, never()).setResult(null);

        // Custom tool as base item in slot 0
        reset(event);
        when(event.getInventory()).thenReturn(inv);
        when(inv.getItem(0)).thenReturn(customHoe);
        when(inv.getItem(1)).thenReturn(gold);
        listener.onPrepareAnvil(event);
        verify(event).setResult(null);
    }

    @Test
    void smithingSuppressedForCustomTools() {
        SmithingInventory inv = mock(SmithingInventory.class);
        PrepareSmithingEvent prep = mock(PrepareSmithingEvent.class);
        when(prep.getInventory()).thenReturn(inv);
        when(inv.getContents()).thenReturn(new ItemStack[]{customHoe, plainHoe});

        listener.onPrepareSmithing(prep);
        verify(prep).setResult(null);

        SmithItemEvent smith = mock(SmithItemEvent.class);
        when(smith.getInventory()).thenReturn(inv);
        listener.onSmithItem(smith);
        verify(smith).setCancelled(true);

        reset(prep, smith);
        when(prep.getInventory()).thenReturn(inv);
        when(smith.getInventory()).thenReturn(inv);
        when(inv.getContents()).thenReturn(new ItemStack[]{plainHoe});
        listener.onPrepareSmithing(prep);
        verify(prep, never()).setResult(null);
        listener.onSmithItem(smith);
        verify(smith, never()).setCancelled(true);
    }

    @Test
    void hopperTransfersIntoStationsAreCancelledOnlyForCustomTools() {
        Inventory brewingInv = mock(Inventory.class);
        when(brewingInv.getType()).thenReturn(InventoryType.BREWING);

        Inventory furnaceInv = mock(Inventory.class);
        when(furnaceInv.getType()).thenReturn(InventoryType.FURNACE);

        Inventory chestInv = mock(Inventory.class);
        when(chestInv.getType()).thenReturn(InventoryType.CHEST);

        // 1. Custom tool into brewing stand -> cancelled
        InventoryMoveItemEvent e1 = mock(InventoryMoveItemEvent.class);
        when(e1.getItem()).thenReturn(customMembrane);
        when(e1.getDestination()).thenReturn(brewingInv);
        listener.onInventoryMoveItem(e1);
        verify(e1).setCancelled(true);

        // 2. Plain membrane into brewing stand -> not cancelled
        InventoryMoveItemEvent e2 = mock(InventoryMoveItemEvent.class);
        when(e2.getItem()).thenReturn(plainMembrane);
        when(e2.getDestination()).thenReturn(brewingInv);
        listener.onInventoryMoveItem(e2);
        verify(e2, never()).setCancelled(true);

        // 3. Custom tool into furnace -> cancelled
        InventoryMoveItemEvent e3 = mock(InventoryMoveItemEvent.class);
        when(e3.getItem()).thenReturn(customHoe);
        when(e3.getDestination()).thenReturn(furnaceInv);
        listener.onInventoryMoveItem(e3);
        verify(e3).setCancelled(true);

        // 4. Plain tool into furnace -> not cancelled
        InventoryMoveItemEvent e4 = mock(InventoryMoveItemEvent.class);
        when(e4.getItem()).thenReturn(plainHoe);
        when(e4.getDestination()).thenReturn(furnaceInv);
        listener.onInventoryMoveItem(e4);
        verify(e4, never()).setCancelled(true);

        // 5. Custom tool into regular chest -> NOT cancelled (chest storage allowed)
        InventoryMoveItemEvent e5 = mock(InventoryMoveItemEvent.class);
        when(e5.getItem()).thenReturn(customMembrane);
        when(e5.getDestination()).thenReturn(chestInv);
        listener.onInventoryMoveItem(e5);
        verify(e5, never()).setCancelled(true);
    }
}
