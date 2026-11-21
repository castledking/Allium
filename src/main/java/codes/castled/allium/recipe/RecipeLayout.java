package codes.castled.allium.recipe;

import org.bukkit.Material;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How one recipe looks in the crafting-table window: workbench slot {@code 0} is the result and
 * {@code 1..9} the grid, row by row. Crafting fills the grid. A furnace, blast furnace or smoker
 * shows its input above the station block and the fuel below it; a campfire or stonecutter its input
 * above the station; smithing the template, base and addition across the middle row, under the
 * smithing table. A slot that accepts several items cycles through them.
 *
 * @param slots   for each grid slot, the items it accepts (each shown in turn)
 * @param station the station block's slot, or -1 for a crafting recipe
 */
public record RecipeLayout(Kind kind, ItemStack result, Map<Integer, List<ItemStack>> slots, int station, float experience,
                           int cookingTicks) {

    public enum Kind {
        SHAPED("Crafting", null), SHAPELESS("Crafting (any arrangement)", null), SMITHING("Smithing", Material.SMITHING_TABLE),
        STONECUTTING("Stonecutting", Material.STONECUTTER), SMELTING("Smelting", Material.FURNACE),
        BLASTING("Blasting", Material.BLAST_FURNACE), SMOKING("Smoking", Material.SMOKER), CAMPFIRE("Campfire cooking", Material.CAMPFIRE);

        private final String label;
        @Nullable
        private final Material station;

        Kind(String label, @Nullable Material station) {
            this.label = label;
            this.station = station;
        }

        public String label() {
            return label;
        }

        @Nullable
        public Material station() {
            return station;
        }

        boolean fuel() {
            return this == SMELTING || this == BLASTING || this == SMOKING;
        }
    }

    public static final int RESULT = 0;
    static final int TOP = 2;
    static final int CENTER = 5;
    static final int BOTTOM = 8;

    public RecipeLayout {
        Map<Integer, List<ItemStack>> copy = new LinkedHashMap<>();
        slots.forEach((slot, choices) -> {
            if (!choices.isEmpty()) copy.put(slot, List.copyOf(choices));
        });
        slots = Collections.unmodifiableMap(copy);
    }

    /** Whether any slot shows more than one item in turn. */
    public boolean cycles() {
        return slots.values().stream().anyMatch(choices -> choices.size() > 1);
    }

    /** The item a grid slot shows on {@code frame}, or null when it is empty. */
    @Nullable
    public ItemStack shown(int slot, long frame) {
        List<ItemStack> choices = slots.get(slot);
        return choices == null ? null : choices.get((int) Math.floorMod(frame, (long) choices.size()));
    }

    /** The layout of {@code recipe}, or null for one the viewer cannot show (special recipes, trims). */
    @Nullable
    public static RecipeLayout of(Recipe recipe) {
        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir()) return null;
        Map<Integer, List<ItemStack>> slots = new LinkedHashMap<>();
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (int row = 0; row < Math.min(3, shape.length); row++) {
                for (int column = 0; column < Math.min(3, shape[row].length()); column++) {
                    put(slots, 1 + row * 3 + column, choices.get(shape[row].charAt(column)));
                }
            }
            return new RecipeLayout(Kind.SHAPED, result, slots, -1, 0F, 0);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<RecipeChoice> choices = shapeless.getChoiceList();
            for (int i = 0; i < Math.min(9, choices.size()); i++) put(slots, 1 + i, choices.get(i));
            return new RecipeLayout(Kind.SHAPELESS, result, slots, -1, 0F, 0);
        }
        if (recipe instanceof SmithingTransformRecipe smithing) {
            put(slots, 4, smithing.getTemplate());
            put(slots, CENTER, smithing.getBase());
            put(slots, 6, smithing.getAddition());
            return new RecipeLayout(Kind.SMITHING, result, slots, TOP, 0F, 0);
        }
        if (recipe instanceof StonecuttingRecipe cutting) {
            put(slots, TOP, cutting.getInputChoice());
            return new RecipeLayout(Kind.STONECUTTING, result, slots, CENTER, 0F, 0);
        }
        if (recipe instanceof CookingRecipe<?> cooking) {
            Kind kind = recipe instanceof BlastingRecipe ? Kind.BLASTING : recipe instanceof SmokingRecipe ? Kind.SMOKING
                    : recipe instanceof CampfireRecipe ? Kind.CAMPFIRE : recipe instanceof FurnaceRecipe ? Kind.SMELTING : null;
            if (kind == null) return null;
            put(slots, TOP, cooking.getInputChoice());
            return new RecipeLayout(kind, result, slots, CENTER, cooking.getExperience(), cooking.getCookingTime());
        }
        // 1.21.2+: the input with a material, like dyeing a shulker box or a bundle.
        RecipeChoice input = choice(recipe, "getInput");
        RecipeChoice material = choice(recipe, "getMaterial");
        if (recipe.getClass().getSimpleName().contains("Transmute") && input != null && material != null) {
            put(slots, 1, input);
            put(slots, 2, material);
            return new RecipeLayout(Kind.SHAPELESS, result, slots, -1, 0F, 0);
        }
        return null;
    }

    /** Every recipe for {@code wanted} the viewer can show: crafting first, then smithing, cutting and cooking. */
    public static List<RecipeLayout> forItem(ItemStack wanted, List<Recipe> recipes) {
        List<RecipeLayout> layouts = new ArrayList<>();
        for (Recipe recipe : recipes) {
            ItemStack result = recipe.getResult();
            // Plain items do not list the recipes of custom items built on them, and the other way round.
            if (result == null || !result.isSimilar(wanted)) continue;
            RecipeLayout layout = of(recipe);
            if (layout != null && !layout.slots().isEmpty()) layouts.add(layout);
        }
        layouts.sort(Comparator.comparingInt(layout -> layout.kind().ordinal()));
        return layouts;
    }

    /** The items a choice accepts; tags such as any plank expand to each of them. */
    static List<ItemStack> items(@Nullable RecipeChoice choice) {
        List<ItemStack> items = new ArrayList<>();
        if (choice instanceof RecipeChoice.MaterialChoice materials) {
            for (Material material : materials.getChoices()) {
                if (material != null && !material.isAir() && material.isItem()) items.add(new ItemStack(material));
            }
        } else if (choice instanceof RecipeChoice.ExactChoice exact) {
            for (ItemStack stack : exact.getChoices()) {
                if (stack != null && !stack.getType().isAir()) {
                    ItemStack one = stack.clone();
                    one.setAmount(1);
                    items.add(one);
                }
            }
        } else if (choice != null) {
            try {
                @SuppressWarnings("deprecation") ItemStack stack = choice.getItemStack();
                if (stack != null && !stack.getType().isAir()) items.add(stack.clone());
            } catch (RuntimeException unknown) {
                // A choice type this version does not describe.
            }
        }
        return items;
    }

    private static void put(Map<Integer, List<ItemStack>> slots, int slot, @Nullable RecipeChoice choice) {
        List<ItemStack> items = items(choice);
        if (!items.isEmpty()) slots.put(slot, items);
    }

    @Nullable
    private static RecipeChoice choice(Recipe recipe, String method) {
        try {
            Method getter = recipe.getClass().getMethod(method);
            Object value = getter.invoke(recipe);
            return value instanceof RecipeChoice choice ? choice : null;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }
}
