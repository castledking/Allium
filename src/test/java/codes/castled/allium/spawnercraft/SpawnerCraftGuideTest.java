package codes.castled.allium.spawnercraft;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class SpawnerCraftGuideTest {
    private ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        return item;
    }

    @Test void allThreeRecipesUseTheCorrectCenterSurroundAndResult() {
        ItemStack head = item(), core = item(), wool = item(), paper = item();
        ItemStack spawner = item(), plushie = item(), card = item();
        for (var recipe : List.of(
            new SpawnerCraftGuideGui.Recipe(SpawnerCraftGuideGui.Page.SPAWNER, head, core, spawner),
            new SpawnerCraftGuideGui.Recipe(SpawnerCraftGuideGui.Page.PLUSHIE, wool, head, plushie),
            new SpawnerCraftGuideGui.Recipe(SpawnerCraftGuideGui.Page.CARD, head, paper, card))) {
            ItemStack[] slots = recipe.contents();
            assertEquals(10, slots.length);
            assertSame(recipe.result(), slots[0]);
            assertSame(recipe.center(), slots[5]);
            for (int slot : new int[]{1, 2, 3, 4, 6, 7, 8, 9}) assertSame(recipe.surrounding(), slots[slot]);
        }
    }

    @Test void rotatingChangesTheTitleAndSlotsOnTheSameOpenViewAndWraps() {
        var guide = new SpawnerCraftGuideGui.Guide(List.of(
            new SpawnerCraftGuideGui.Recipe(SpawnerCraftGuideGui.Page.SPAWNER, item(), item(), item()),
            new SpawnerCraftGuideGui.Recipe(SpawnerCraftGuideGui.Page.PLUSHIE, item(), item(), item()),
            new SpawnerCraftGuideGui.Recipe(SpawnerCraftGuideGui.Page.CARD, item(), item(), item())));
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        guide.attach(inventory);
        when(view.getTopInventory()).thenReturn(inventory);
        for (String title : List.of("§8Plushie Crafting Guide", "§8Card Crafting Guide", "§8Spawner Crafting Guide")) {
            guide.advance(view);
            assertEquals(title, guide.current().page().title());
            verify(view).setTitle(title);
        }
        verify(inventory, times(3)).setContents(any(ItemStack[].class));
        verify(view, never()).close();
    }

    @Test void unavailableRecipesAreSkippedAndLeavingTheGuideStopsUpdates() {
        var guide = new SpawnerCraftGuideGui.Guide(List.of(
            new SpawnerCraftGuideGui.Recipe(SpawnerCraftGuideGui.Page.SPAWNER, item(), item(), item()),
            new SpawnerCraftGuideGui.Recipe(SpawnerCraftGuideGui.Page.CARD, item(), item(), item())));
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        guide.attach(inventory);
        when(view.getTopInventory()).thenReturn(inventory);
        guide.advance(view);
        assertEquals(SpawnerCraftGuideGui.Page.CARD, guide.current().page());
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        guide.advance(view);
        assertEquals(SpawnerCraftGuideGui.Page.CARD, guide.current().page());
        verify(inventory, times(1)).setContents(any(ItemStack[].class));
    }

    @Test void guideIdentityUsesTheHolderAndClosingClearsIllustratedItems() {
        Inventory inventory = mock(Inventory.class);
        var guide = new SpawnerCraftGuideGui.Guide(List.of());
        when(inventory.getHolder()).thenReturn(guide);
        assertTrue(SpawnerCraftGuideGui.isGuide(inventory));
        assertFalse(SpawnerCraftGuideGui.isGuide(mock(Inventory.class)));
        SpawnerCraftGuideGui.close(inventory);
        verify(inventory).clear();
    }

    @Test void droppedHeadsShowRecipeBulletsAndSmallCapsGuideHint() {
        assertEquals(List.of("§7Used to craft:", "§7 - §fSpawners", "§7 - §fPlushies",
            "§7 - §fTrading Cards", "", "§x§5§5§5§5§5§5ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ ᴠɪᴇᴡ ɢᴜɪᴅᴇs"), MobHeadFactory.craftingLore());
    }
}
