package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class LegacyRecipeCommand {
    private final Server server;

    LegacyRecipeCommand(Server server) {
        this.server = server;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (args.length > 2 || (args.length == 2 && !isNumber(args[1]))) return false;
        boolean numbered = args.length == 1 && isNumber(args[0]);
        String name = args.length == 0 || numbered ? "hand" : args[0];
        int number = numbered ? Integer.parseInt(args[0]) : args.length < 2 ? 1 : Integer.parseInt(args[1]);
        ItemStack item = namedItem(sender, name);
        if (item == null || item.getType() == Material.AIR || item.getAmount() == 0) {
            String failure = "hand".equalsIgnoreCase(name) ? "Hold an item or specify its name." : "Unknown item '" + name + "'.";
            sender.sendMessage(ChatColor.RED + failure);
            return true;
        }
        List<Recipe> recipes = server.getRecipesFor(item);
        if (recipes.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No recipes found for " + describe(item) + ".");
            return true;
        }
        if (number < 1 || number > recipes.size()) {
            sender.sendMessage(ChatColor.RED + "Choose a recipe from 1 to " + recipes.size() + ".");
            return true;
        }
        Recipe recipe = recipes.get(number - 1);
        sender.sendMessage(ChatColor.GOLD + "Recipe " + number + "/" + recipes.size() + ": " + describe(recipe.getResult()));
        showIngredients(sender, recipe);
        return true;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack namedItem(CommandSender sender, String name) {
        if ("hand".equalsIgnoreCase(name)) {
            return sender instanceof Player ? ((Player) sender).getInventory().getItemInMainHand() : null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        String[] parts = key.split(":", -1);
        Material material = LegacyCompat.matchMaterial(parts[0]);
        if (material == null || parts.length > 2) return null;
        if (parts.length == 1) return new ItemStack(material);
        if (!isNumber(parts[1])) return null;
        return new ItemStack(material, 1, Short.parseShort(parts[1]));
    }

    private static void showIngredients(CommandSender sender, Recipe recipe) {
        if (recipe instanceof ShapedRecipe) {
            ShapedRecipe shaped = (ShapedRecipe) recipe;
            Map<Character, ItemStack> ingredients = shaped.getIngredientMap();
            Map<?, ?> choices = invoke(shaped, "getChoiceMap", Map.class, Collections.emptyMap());
            for (String row : shaped.getShape()) {
                List<String> slots = new ArrayList<>();
                for (char slot : row.toCharArray()) {
                    slots.add(ingredient(choices.get(slot), ingredients.get(slot)));
                }
                sender.sendMessage(String.join(" | ", slots));
            }
        } else if (recipe instanceof ShapelessRecipe) {
            ShapelessRecipe shapeless = (ShapelessRecipe) recipe;
            List<ItemStack> ingredients = shapeless.getIngredientList();
            List<?> choices = invoke(shapeless, "getChoiceList", List.class, Collections.emptyList());
            List<String> slots = new ArrayList<>();
            for (int i = 0; i < ingredients.size(); i++) {
                slots.add(ingredient(i < choices.size() ? choices.get(i) : null, ingredients.get(i)));
            }
            sender.sendMessage("Combine in any order: " + String.join(", ", slots));
        } else {
            ItemStack input = recipe instanceof FurnaceRecipe ? ((FurnaceRecipe) recipe).getInput() : null;
            Object choice = invoke(recipe, "getInputChoice", Object.class, null);
            if (input != null || choice != null) {
                sender.sendMessage("Input (" + typeName(recipe) + "): " + ingredient(choice, input));
            } else {
                Object base = invoke(recipe, "getBase", Object.class, null);
                Object addition = invoke(recipe, "getAddition", Object.class, null);
                if (base != null && addition != null) {
                    sender.sendMessage("Smithing: " + ingredient(base, null) + " + " + ingredient(addition, null));
                } else {
                    sender.sendMessage("Ingredients are unavailable for this " + typeName(recipe) + ".");
                }
            }
        }
    }

    private static String ingredient(Object choice, ItemStack fallback) {
        if (choice != null) {
            List<?> alternatives = invoke(choice, "getChoices", List.class, Collections.emptyList());
            List<String> names = new ArrayList<>();
            for (Object alternative : alternatives) {
                if (alternative instanceof ItemStack) names.add(describe((ItemStack) alternative));
                else if (alternative instanceof Material) names.add(((Material) alternative).name());
            }
            if (!names.isEmpty()) return String.join(" / ", names);
            if (fallback == null) fallback = invoke(choice, "getItemStack", ItemStack.class, null);
        }
        return fallback == null || fallback.getType() == Material.AIR ? "-" : describe(fallback);
    }

    @SuppressWarnings("deprecation")
    private static String describe(ItemStack stack) {
        int data = stack.getDurability() & 0xffff;
        String item = stack.getType().name();
        if (data != 0 && data != Short.MAX_VALUE && stack.getType().getMaxDurability() == 0) item += ":" + data;
        return stack.getAmount() > 1 ? stack.getAmount() + "x " + item : item;
    }

    private static String typeName(Recipe recipe) {
        return recipe.getClass().getSimpleName().replaceFirst("^Craft", "").replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT);
    }

    private static <T> T invoke(Object target, String method, Class<T> type, T fallback) {
        try {
            Object result = target.getClass().getMethod(method).invoke(target);
            return type.isInstance(result) ? type.cast(result) : fallback;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return fallback;
        }
    }

    List<String> complete(String prefix) {
        List<String> items = new ArrayList<>();
        items.add("hand");
        for (Material material : Material.values()) {
            String name = material.name().toLowerCase(Locale.ROOT);
            if (!name.startsWith("legacy_") && material != Material.AIR) items.add(name);
        }
        return items.stream().filter(name -> name.startsWith(prefix)).limit(64).collect(Collectors.toList());
    }

    private static boolean isNumber(String argument) {
        return argument.matches("[0-9]{1,4}");
    }
}
