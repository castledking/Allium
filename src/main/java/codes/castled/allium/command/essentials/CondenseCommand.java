package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.ItemsModule;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

import java.util.*;

public final class CondenseCommand implements SpecCommandHandler {

    public static final CommandSpec SPEC = new CommandSpec(
            "condense", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("compact", "blocks", "toblocks"),
            "Craft items into blocks using the server's recipes", "/condense [item]", "/condense [item]",
            "allium.condense", null, List.of(CommandArgSpec.optional("item", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    private record Compression(int count, ItemStack result) {}

    private final PluginStart plugin;

    public CondenseCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        Material only = args.length == 1 ? material(args[0]) : null;
        if (args.length > 1 || args.length == 1 && only == null) {
            context.sendInvalidArgs(SPEC.selfUsage());
            return;
        }
        if (UtilitySupport.isInventoryBusy(plugin, player)) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }

        var inventory = player.getInventory();
        ItemStack[] original = inventory.getStorageContents();
        var sources = new LinkedHashSet<Material>();
        for (ItemStack stack : original) {
            if (plain(stack) && (only == null || stack.getType() == only)) sources.add(stack.getType());
        }
        Map<Material, Compression> recipes = findRecipes(sources, only == null);
        ItemStack[] planned = cloneSlots(original);
        int crafted = 0;
        for (Material source : sources) {
            Compression recipe = recipes.get(source);
            if (recipe == null) continue;
            int total = Arrays.stream(planned).filter(stack -> plain(stack) && stack.getType() == source)
                    .mapToInt(ItemStack::getAmount).sum();
            int batches = total / recipe.count();
            if (batches == 0) continue;
            ItemStack[] candidate = cloneSlots(planned);
            remove(candidate, source, batches * recipe.count());
            int produced = batches * recipe.result().getAmount();
            if (insert(candidate, recipe.result(), produced, inventory.getMaxStackSize())) {
                planned = candidate;
                crafted += produced;
            }
        }
        if (crafted == 0) {
            UtilitySupport.send(plugin, player, Messages.CONDENSE_NONE);
            return;
        }
        inventory.setStorageContents(planned);
        UtilitySupport.send(plugin, player, Messages.CONDENSE_DONE, "{count}", Integer.toString(crafted));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        var module = plugin.getModuleManager().getModule("items");
        if (module instanceof ItemsModule items && items.isEnabled()) return items.complete(args[0]);
        return List.of();
    }

    private Material material(String text) {
        var module = plugin.getModuleManager().getModule("items");
        return module instanceof ItemsModule items && items.isEnabled() ? items.material(text) : Material.matchMaterial(text);
    }

    private Map<Material, Compression> findRecipes(Set<Material> sources, boolean requireReverse) {
        Map<Material, Compression> found = new EnumMap<>(Material.class);
        if (sources.isEmpty()) return found;
        var recipes = plugin.getServer().recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            List<RecipeChoice> inputs = ingredients(recipe);
            int count = inputs.size();
            if (count != 4 && count != 9) continue;
            ItemStack result = recipe.getResult();
            if (!plain(result) || !result.getType().isBlock() || result.getAmount() >= count) continue;
            for (Material source : sources) {
                if (source == result.getType() || source.getCraftingRemainingItem() != null
                        || !matches(inputs, new ItemStack(source))) continue;
                if (requireReverse && !hasReverse(source, result, count)) continue;
                Compression previous = found.get(source);
                if (previous == null || count > previous.count()) found.put(source, new Compression(count, result));
            }
        }
        return found;
    }

    private boolean hasReverse(Material source, ItemStack result, int count) {
        for (Recipe reverse : plugin.getServer().getRecipesFor(new ItemStack(source))) {
            ItemStack output = reverse.getResult();
            var inputs = ingredients(reverse);
            if (plain(output) && output.getAmount() * result.getAmount() == count
                    && inputs.size() == 1 && matches(inputs, result)) return true;
        }
        return false;
    }

    private static boolean matches(List<RecipeChoice> choices, ItemStack stack) {
        return choices.stream().allMatch(choice -> choice.test(stack));
    }

    private static List<RecipeChoice> ingredients(Recipe recipe) {
        if (recipe instanceof ShapelessRecipe shapeless) return shapeless.getChoiceList();
        if (!(recipe instanceof ShapedRecipe shaped)) return List.of();
        String[] shape = shaped.getShape();
        if (shape.length == 0 || shape.length != shape[0].length()) return List.of();
        var choices = shaped.getChoiceMap();
        List<RecipeChoice> ingredients = new ArrayList<>();
        for (String row : shape) {
            for (int i = 0; i < row.length(); i++) {
                RecipeChoice choice = choices.get(row.charAt(i));
                if (choice != null) ingredients.add(choice);
            }
        }
        return ingredients;
    }

    private static boolean plain(ItemStack stack) {
        return !UtilitySupport.isAir(stack) && !stack.hasItemMeta();
    }

    private static ItemStack[] cloneSlots(ItemStack[] slots) {
        return Arrays.stream(slots).map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new);
    }

    private static void remove(ItemStack[] slots, Material material, int remaining) {
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            ItemStack stack = slots[i];
            if (!plain(stack) || stack.getType() != material) continue;
            int count = Math.min(remaining, stack.getAmount());
            remaining -= count;
            if (count == stack.getAmount()) slots[i] = null;
            else stack.setAmount(stack.getAmount() - count);
        }
    }

    private static boolean insert(ItemStack[] slots, ItemStack result, int remaining, int inventoryLimit) {
        int max = Math.min(result.getMaxStackSize(), inventoryLimit);
        for (ItemStack stack : slots) {
            if (remaining == 0) break;
            if (stack == null || !stack.isSimilar(result) || stack.getAmount() >= max) continue;
            int count = Math.min(remaining, max - stack.getAmount());
            stack.setAmount(stack.getAmount() + count);
            remaining -= count;
        }
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            if (!UtilitySupport.isAir(slots[i])) continue;
            int count = Math.min(remaining, max);
            slots[i] = result.clone();
            slots[i].setAmount(count);
            remaining -= count;
        }
        return remaining == 0;
    }
}
