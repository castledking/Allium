package codes.castled.spawnercraft;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AddonItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.*;
import java.util.function.Supplier;

final class HeadRecipes {
    record Recipe(String id, String name, String mob, String pattern, Map<Character, String> ingredients,
                  String command, String item, boolean spawner, boolean card, Material preview, Map<String, Double> weights) {}
    record Match(Recipe recipe, String mob) {}
    interface Cards {
        boolean supports(String mob);
        OptionalInt previewModel(String mob, Map<String, Double> weights);
        Optional<ItemStack> craft(String mob, Map<String, Double> weights);
    }

    private final Heads heads;
    private final AddonItems items;
    private final AddonConnection connection;
    private final Supplier<Cards> cards;
    private volatile List<Recipe> recipes = List.of();

    HeadRecipes(Heads heads, AddonItems items, Supplier<Cards> cards) {
        this(heads, items, null, cards);
    }

    HeadRecipes(Heads heads, AddonConnection connection, Supplier<Cards> cards) {
        this(heads, connection.items(), connection, cards);
    }

    private HeadRecipes(Heads heads, AddonItems items, AddonConnection connection, Supplier<Cards> cards) {
        this.heads = heads;
        this.items = items;
        this.connection = connection;
        this.cards = cards;
    }

    void load(ConfigurationSection config, ConfigurationSection imported) {
        Map<String, Recipe> staged = new LinkedHashMap<>();
        read(imported, staged);
        read(config, staged);
        recipes = List.copyOf(staged.values());
    }

    private static void read(ConfigurationSection section, Map<String, Recipe> staged) {
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            var row = section.getConfigurationSection(id);
            if (row == null) throw new IllegalArgumentException("Recipe " + id + " must be a section");
            if (!row.getBoolean("enabled", true)) {
                staged.remove(id);
                continue;
            }
            List<String> rows = row.getStringList("pattern");
            String pattern = String.join("", rows);
            var result = row.getConfigurationSection("result");
            if (rows.size() != 3 || rows.stream().anyMatch(line -> line.length() != 3)
                    || !pattern.contains("H") || result == null) {
                throw new IllegalArgumentException("Recipe " + id + " needs a 3x3 pattern containing H and a result");
            }
            Map<Character, String> ingredients = new HashMap<>();
            var ingredientSection = row.getConfigurationSection("ingredients");
            for (char symbol : pattern.toCharArray()) {
                if ("HC. ".indexOf(symbol) >= 0 || ingredients.containsKey(symbol)) continue;
                String ingredient = ingredientSection == null ? "" : ingredientSection.getString(String.valueOf(symbol), "").trim();
                if (!ingredient.equals("#wool") && !ingredient.matches("[a-zA-Z0-9_]+(?::[a-z0-9_./-]+)?")) {
                    throw new IllegalArgumentException("Recipe " + id + " has an invalid ingredient for " + symbol);
                }
                ingredients.put(symbol, ingredient);
            }
            String command = result.getString("command", "").trim().replaceFirst("^/", "");
            String item = result.getString("item", "").trim();
            boolean spawner = result.getBoolean("spawner");
            boolean card = result.getBoolean("card");
            if (command.isEmpty() && (item.isEmpty() ? 0 : 1) + (spawner ? 1 : 0) + (card ? 1 : 0) != 1) {
                throw new IllegalArgumentException("Recipe " + id + " needs one item, command, spawner or card result");
            }
            if (command.contains("\n") || command.contains("\r")) {
                throw new IllegalArgumentException("Recipe " + id + " must use a single give command");
            }
            Material preview = Material.matchMaterial(result.getString("preview", "PAPER"));
            if (preview == null || !preview.isItem() || preview.isAir()) {
                throw new IllegalArgumentException("Recipe " + id + " has an invalid result preview material");
            }
            Map<String, Double> weights = new LinkedHashMap<>();
            var weightsSection = result.getConfigurationSection("weights");
            if (weightsSection != null) for (String tier : weightsSection.getKeys(false)) {
                double weight = weightsSection.getDouble(tier);
                if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Invalid card weight for " + tier);
                weights.put(tier, weight);
            }
            staged.put(id, new Recipe(id, row.getString("name", id), row.getString("mob", ""),
                    pattern, Map.copyOf(ingredients), command, item, spawner, card, preview, Map.copyOf(weights)));
        }
    }

    List<Recipe> forMob(String mob, boolean coreOnly) {
        return recipes.stream().filter(recipe -> (!coreOnly || recipe.pattern().contains("C"))
                && applies(recipe, mob)).toList();
    }

    ItemStack previewItem(Recipe recipe, String mob, String title, String... lore) {
        ItemStack stack = SpawnerCraft.label(recipe.preview(), title, lore);
        if (!recipe.card() || !recipe.command().isEmpty() || stack.getType() != Material.PAPER) return stack;
        Cards service = cards.get();
        if (service != null) service.previewModel(mob, recipe.weights()).ifPresent(model -> {
            var meta = stack.getItemMeta();
            meta.setCustomModelData(model);
            stack.setItemMeta(meta);
        });
        return stack;
    }

    private boolean applies(Recipe recipe, String mob) {
        if (!recipe.mob().isEmpty() && !recipe.mob().equals(mob)) return false;
        if (recipe.command().isEmpty() && recipe.spawner() && heads.type(mob) == null) return false;
        if (recipe.command().isEmpty() && recipe.card()) {
            Cards service = cards.get();
            return service != null && service.supports(mob);
        }
        return true;
    }

    Match match(ItemStack[] matrix) {
        if (matrix.length != 9) return null;
        // Decode each signed head once even when the recipe list contains many imported mobs.
        String[] mobs = new String[9];
        String mob = null;
        for (int i = 0; i < matrix.length; i++) {
            mobs[i] = heads.mob(matrix[i]);
            if (mobs[i] == null) continue;
            if (mob != null && !mob.equals(mobs[i])) return null;
            mob = mobs[i];
        }
        if (mob == null) return null;
        for (Recipe recipe : forMob(mob, false)) {
            if (matches(recipe, matrix, mobs, mob)) return new Match(recipe, mob);
        }
        return null;
    }

    private boolean matches(Recipe recipe, ItemStack[] matrix, String[] mobs, String mob) {
        for (int i = 0; i < matrix.length; i++) {
            char symbol = recipe.pattern().charAt(i);
            ItemStack item = matrix[i];
            boolean match = switch (symbol) {
                case 'H' -> mob.equals(mobs[i]);
                case 'C' -> mob.equals(heads.coreMob(item));
                case '.', ' ' -> item == null || item.getType().isAir() || item.getAmount() <= 0;
                default -> mobs[i] == null && matchesIngredient(item, recipe.ingredients().get(symbol));
            };
            if (!match) return false;
        }
        return true;
    }

    private boolean matchesIngredient(ItemStack item, String ingredient) {
        if (item == null || item.getAmount() <= 0 || item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta) return false;
        if (ingredient.equals("#wool")) return Tag.WOOL.isTagged(item.getType());
        if (ingredient.contains(":") && !ingredient.startsWith("minecraft:")) {
            return items.identify(item).filter(id -> id.appearance().equals(ingredient)).isPresent();
        }
        return item.getType() == Material.matchMaterial(ingredient);
    }

    boolean deliver(Player player, Match match) {
        Recipe recipe = match.recipe();
        if (!recipe.command().isEmpty()) {
            String command = recipe.command().replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString())
                    .replace("{mob}", match.mob()).replace("{mob_upper}", match.mob().toUpperCase(Locale.ROOT));
            if (connection != null && connection.isFolia()) {
                connection.scheduler().runOnServer(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
                return true;
            }
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        ItemStack output;
        if (recipe.spawner()) {
            output = new ItemStack(Material.SPAWNER);
            if (!(output.getItemMeta() instanceof BlockStateMeta meta)
                    || !(meta.getBlockState() instanceof CreatureSpawner spawner)) return false;
            spawner.setSpawnedType(heads.type(match.mob()));
            meta.setBlockState(spawner);
            output.setItemMeta(meta);
        } else if (recipe.card()) {
            Cards service = cards.get();
            output = service == null ? null : service.craft(match.mob(), recipe.weights()).orElse(null);
        } else {
            output = items.create(recipe.item().replace("{mob}", match.mob()), 1).stack().orElse(null);
        }
        if (output == null) return false;
        player.getInventory().addItem(output).values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        return true;
    }
}
