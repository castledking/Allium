package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.plugin.Plugin;

/** Exact custom-item recipes shared across Nexo, CraftEngine and vanilla. */
final class KitchenRecipes implements AutoCloseable {
    private final Plugin plugin;
    private final ItemResolverChain items;
    private final Set<NamespacedKey> registered = new LinkedHashSet<>();
    KitchenRecipes(Plugin plugin, ItemResolverChain items) { this.plugin = plugin; this.items = items; }

    void reload(ConfigurationSection config) {
        close();
        if (!config.getBoolean("enabled", true)) return;
        load(config.getConfigurationSection("crafting"), false);
        load(config.getConfigurationSection("smelting"), true);
    }
    private void load(ConfigurationSection root, boolean cook) {
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            var section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;
            try {
                NamespacedKey key = new NamespacedKey(plugin, "kitchen_" + id);
                ItemStack result = resolve(section.getString("result.item"));
                int amount = section.getInt("result.amount", 1);
                if (amount < 1 || amount > result.getMaxStackSize()) throw new IllegalArgumentException("invalid result amount");
                result.setAmount(amount);
                Recipe recipe;
                if (cook) {
                    RecipeChoice ingredient = choice(section.get("ingredient"));
                    int time = section.getInt("time", 200);
                    float experience = (float) section.getDouble("experience", 0.1);
                    if (time < 1 || !Float.isFinite(experience) || experience < 0) throw new IllegalArgumentException("invalid cook time/experience");
                    recipe = switch (section.getString("type", "furnace").toLowerCase(java.util.Locale.ROOT)) {
                        case "furnace", "smelting" -> new FurnaceRecipe(key, result, ingredient, experience, time);
                        case "smoker", "smoking" -> new SmokingRecipe(key, result, ingredient, experience, time);
                        case "blast_furnace", "blasting" -> new BlastingRecipe(key, result, ingredient, experience, time);
                        default -> throw new IllegalArgumentException("unknown cooking type");
                    };
                } else {
                    List<?> ingredients = section.getList("ingredients", List.of());
                    if (ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException("need 1..9 ingredients");
                    ShapelessRecipe shaped = new ShapelessRecipe(key, result);
                    for (Object raw : ingredients) shaped.addIngredient(choice(raw));
                    recipe = shaped;
                }
                if (!Bukkit.addRecipe(recipe)) throw new IllegalArgumentException("recipe key is already registered");
                registered.add(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[Kitchen] Recipe '" + id + "' skipped: " + e.getMessage());
            }
        }
    }
    private ItemStack resolve(String ref) {
        ItemRef item = ItemRef.parse(ref);
        return items.create(item, 1).orElseThrow(() -> new IllegalArgumentException("item does not resolve: " + ref));
    }
    private RecipeChoice choice(Object raw) {
        List<?> refs = raw instanceof List<?> list ? list : raw == null ? List.of() : List.of(raw);
        if (refs.isEmpty()) throw new IllegalArgumentException("ingredient is empty");
        List<ItemStack> stacks = new ArrayList<>();
        for (Object ref : refs) stacks.add(resolve(String.valueOf(ref)));
        return new RecipeChoice.ExactChoice(stacks);
    }
    @Override public void close() {
        for (NamespacedKey key : registered) Bukkit.removeRecipe(key);
        registered.clear();
    }
}
