package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.item.spec.GiveSpec;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.ItemsModule;
import codes.castled.allium.recipe.RecipeLayout;
import codes.castled.allium.recipe.RecipeViewer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /recipe [item|hand] [number]} ({@code /formula}, {@code /method}, {@code /recipes}): opens the
 * read-only recipe window. With no item it shows the item in your hand. Items are named as for
 * {@code /i} and {@code /give}.
 */
public final class RecipeCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final Supplier<RecipeViewer> viewer;

    public RecipeCommand(PluginStart plugin, Supplier<RecipeViewer> viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.RECIPE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (!(sender instanceof Player player)) {
            UtilitySupport.send(plugin, sender, Messages.PLAYER_ONLY);
            return;
        }
        RecipeViewer recipes = viewer.get();
        if (recipes == null) {
            UtilitySupport.send(plugin, sender, Messages.MODULE_DISABLED);
            return;
        }
        if (args.length > 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        int number = 1;
        String name = args.length == 0 ? "hand" : args[0];
        if (args.length == 1 && args[0].matches("[0-9]{1,4}")) {
            // /recipe 2: the held item's second recipe.
            name = "hand";
            number = Integer.parseInt(args[0]);
        } else if (args.length == 2) {
            if (!args[1].matches("[0-9]{1,4}")) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            number = Integer.parseInt(args[1]);
        }
        ItemStack item;
        if (name.equalsIgnoreCase("hand")) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType().isAir()) {
                UtilitySupport.send(plugin, player, Messages.UTILITY_HOLD_ITEM);
                return;
            }
            item = recipes.lookup(held);
        } else {
            item = resolve(name);
            if (item == null) {
                UtilitySupport.send(plugin, player, Messages.RECIPE_UNKNOWN_ITEM, "{item}", name);
                return;
            }
        }
        String itemName = UtilitySupport.itemName(item);
        List<RecipeLayout> layouts = recipes.recipes(item);
        if (layouts.isEmpty()) {
            UtilitySupport.send(plugin, player, Messages.RECIPE_NONE, "{item}", itemName);
            return;
        }
        if (number < 1 || number > layouts.size()) {
            UtilitySupport.send(plugin, player, Messages.RECIPE_BAD_NUMBER, "{item}", itemName, "{count}", Integer.toString(layouts.size()));
            return;
        }
        recipes.open(player, item, number - 1);
    }

    /** An item named as for {@code /i}: a vanilla name or alias, or an Allium or provider item. */
    @Nullable
    private ItemStack resolve(String name) {
        if (plugin.getModuleManager().getModule("items") instanceof ItemsModule items && items.isEnabled()) {
            try {
                GiveSpec spec = items.parser().parse(name);
                ItemLibrary.Prepared prepared = items.library().build(spec, true);
                if (!prepared.items().isEmpty()) {
                    ItemStack stack = prepared.items().get(0).stack().clone();
                    stack.setAmount(1);
                    return viewer.get() == null ? stack : viewer.get().lookup(stack);
                }
            } catch (RuntimeException unresolved) {
                // Fall through to a plain material name.
            }
            Material material = items.material(name);
            if (material != null && material.isItem()) return new ItemStack(material);
        }
        Material material = Material.matchMaterial(name);
        return material != null && material.isItem() && !material.isAir() ? new ItemStack(material) : null;
    }

    /** Items as for {@code /i}; {@code hand} is not offered (no item already means the held one). */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (prefix.isEmpty()) return List.of();
        return codes.castled.allium.command.worth.WorthCommand.items(plugin, prefix);
    }
}
