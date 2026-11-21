package codes.castled.allium.recipe;

import org.bukkit.Material;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Recipes are mocks here: real Bukkit recipes copy their result through the server's item factory. */
class RecipeLayoutTest {

    private static final RecipeChoice PLANKS = new RecipeChoice.MaterialChoice(Material.OAK_PLANKS, Material.SPRUCE_PLANKS,
            Material.BIRCH_PLANKS);

    /** A plain result: similar to any plain stack of its material. */
    private static ItemStack result(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.isSimilar(any())).thenAnswer(call -> call.getArgument(0) instanceof ItemStack other && other.getType() == material);
        return stack;
    }

    private static RecipeChoice of(Material material) {
        return new RecipeChoice.MaterialChoice(material);
    }

    private static <T extends Recipe> T cooking(Class<T> type, Material output, Material input, float xp, int ticks) {
        T recipe = mock(type);
        ItemStack out = result(output, 1);
        when(recipe.getResult()).thenReturn(out);
        org.bukkit.inventory.CookingRecipe<?> cook = (org.bukkit.inventory.CookingRecipe<?>) recipe;
        when(cook.getInputChoice()).thenReturn(of(input));
        when(cook.getExperience()).thenReturn(xp);
        when(cook.getCookingTime()).thenReturn(ticks);
        return recipe;
    }

    private static ShapedRecipe shaped(ItemStack out, Map<Character, RecipeChoice> choices, String... shape) {
        ShapedRecipe recipe = mock(ShapedRecipe.class);
        when(recipe.getResult()).thenReturn(out);
        when(recipe.getShape()).thenReturn(shape);
        when(recipe.getChoiceMap()).thenReturn(choices);
        return recipe;
    }

    private static ShapelessRecipe shapeless(ItemStack out, RecipeChoice... choices) {
        ShapelessRecipe recipe = mock(ShapelessRecipe.class);
        when(recipe.getResult()).thenReturn(out);
        when(recipe.getChoiceList()).thenReturn(List.of(choices));
        return recipe;
    }

    @Test
    void aShapedRecipeFillsTheGridRowByRowAndATagCycles() {
        RecipeLayout layout = RecipeLayout.of(shaped(result(Material.STICK, 4), Map.of('#', PLANKS), "#", "#"));
        assertEquals(RecipeLayout.Kind.SHAPED, layout.kind());
        assertEquals(Set.of(1, 4), layout.slots().keySet());
        assertEquals(4, layout.result().getAmount());
        assertTrue(layout.cycles());
        assertEquals(Material.OAK_PLANKS, layout.shown(1, 0).getType());
        assertEquals(Material.SPRUCE_PLANKS, layout.shown(1, 1).getType());
        assertEquals(Material.OAK_PLANKS, layout.shown(4, 3).getType());
        assertNull(layout.shown(2, 0));
        assertEquals(-1, layout.station());

        RecipeLayout table = RecipeLayout.of(shaped(result(Material.CRAFTING_TABLE, 1), Map.of('#', of(Material.OAK_PLANKS)), "##", "##"));
        assertEquals(Set.of(1, 2, 4, 5), table.slots().keySet());
        assertFalse(table.cycles());

        // A space in the shape is an empty slot.
        RecipeLayout bowl = RecipeLayout.of(shaped(result(Material.BOWL, 4), Map.of('#', PLANKS), "# #", " # "));
        assertEquals(Set.of(1, 3, 5), bowl.slots().keySet());
    }

    @Test
    void shapelessFillsInOrderAndStationsSitAboveOrBelowTheirInput() {
        RecipeLayout flint = RecipeLayout.of(shapeless(result(Material.FLINT_AND_STEEL, 1), of(Material.IRON_INGOT), of(Material.FLINT)));
        assertEquals(List.of(1, 2), List.copyOf(flint.slots().keySet()));

        RecipeLayout glass = RecipeLayout.of(cooking(FurnaceRecipe.class, Material.GLASS, Material.SAND, 0.1F, 200));
        assertEquals(RecipeLayout.Kind.SMELTING, glass.kind());
        assertEquals(Set.of(RecipeLayout.TOP), glass.slots().keySet());
        assertEquals(RecipeLayout.CENTER, glass.station());
        assertEquals(200, glass.cookingTicks());
        assertTrue(glass.kind().fuel());

        RecipeLayout potato = RecipeLayout.of(cooking(CampfireRecipe.class, Material.BAKED_POTATO, Material.POTATO, 0.35F, 600));
        assertEquals(RecipeLayout.Kind.CAMPFIRE, potato.kind());
        assertFalse(potato.kind().fuel(), "a campfire needs no fuel");

        StonecuttingRecipe slab = mock(StonecuttingRecipe.class);
        ItemStack slabs = result(Material.STONE_SLAB, 2);
        when(slab.getResult()).thenReturn(slabs);
        when(slab.getInputChoice()).thenReturn(of(Material.STONE));
        RecipeLayout cut = RecipeLayout.of(slab);
        assertEquals(Material.STONECUTTER, cut.kind().station());
        assertEquals(Set.of(RecipeLayout.TOP), cut.slots().keySet());

        SmithingTransformRecipe upgrade = mock(SmithingTransformRecipe.class);
        ItemStack sword = result(Material.NETHERITE_SWORD, 1);
        when(upgrade.getResult()).thenReturn(sword);
        when(upgrade.getTemplate()).thenReturn(of(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        when(upgrade.getBase()).thenReturn(of(Material.DIAMOND_SWORD));
        when(upgrade.getAddition()).thenReturn(of(Material.NETHERITE_INGOT));
        RecipeLayout netherite = RecipeLayout.of(upgrade);
        assertEquals(Set.of(4, 5, 6), netherite.slots().keySet());
        assertEquals(RecipeLayout.TOP, netherite.station());
    }

    @Test
    void onlyRecipesForThatExactItemAreShownCraftingFirst() {
        ItemStack glass = new ItemStack(Material.GLASS);
        Recipe smelt = cooking(FurnaceRecipe.class, Material.GLASS, Material.SAND, 0.1F, 200);
        Recipe craft = shapeless(result(Material.GLASS, 1), of(Material.SAND));
        Recipe other = shapeless(result(Material.SAND, 1), of(Material.GLASS));
        ItemStack custom = mock(ItemStack.class);
        when(custom.getType()).thenReturn(Material.GLASS);
        when(custom.isSimilar(any())).thenReturn(false);
        Recipe customGlass = shapeless(custom, of(Material.DIAMOND));
        List<RecipeLayout> layouts = RecipeLayout.forItem(glass, List.of(smelt, other, customGlass, craft));
        assertEquals(List.of(RecipeLayout.Kind.SHAPELESS, RecipeLayout.Kind.SMELTING), layouts.stream().map(RecipeLayout::kind).toList());
    }
}
