package codes.castled.allium.command.worth;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.worth.SalePlan;
import codes.castled.allium.worth.SaleQuantity;
import codes.castled.allium.worth.SellService;
import codes.castled.allium.worth.WorthService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** {@code /sell <hand|inventory|all|blocks|item> [-][amount][s]}, as Essentials' {@code /sell}. */
public final class SellCommand implements SpecCommandHandler {

    private static final Set<String> INVENTORY = Set.of("inventory", "invent", "all");

    private final PluginStart plugin;
    private final WorthService worth;
    private final SellService sell;

    public SellCommand(PluginStart plugin, WorthService worth, SellService sell) {
        this.plugin = plugin;
        this.worth = worth;
        this.sell = sell;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SELL;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (!(context.sender() instanceof Player player)) {
            plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        if (args.length == 0 || args.length > 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        String what = args[0].toLowerCase(Locale.ROOT);
        String command = (context.label() + " " + String.join(" ", args)).toLowerCase(Locale.ROOT);
        boolean blocks = what.equals("blocks");
        if (INVENTORY.contains(what) || blocks) {
            if (!player.hasPermission(WorthService.PERMISSION_SELL_BULK)) {
                plugin.getMessageManager().send(player, Messages.NO_PERMISSION);
                return;
            }
            if (args.length > 1) {
                context.sendInvalidArgs("/" + context.label() + " " + what);
                return;
            }
            sell.sell(player, blocks ? SalePlan.Scope.BLOCKS : SalePlan.Scope.INVENTORY, null, SaleQuantity.ALL, command, true);
            return;
        }
        SaleQuantity quantity;
        try {
            quantity = SaleQuantity.parse(args.length > 1 ? args[1] : null);
        } catch (IllegalArgumentException invalid) {
            plugin.getMessageManager().send(player, Messages.WORTH_INVALID_AMOUNT);
            return;
        }
        if (what.equals("hand")) {
            if (!player.hasPermission(WorthService.PERMISSION_SELL_HAND)) {
                plugin.getMessageManager().send(player, Messages.NO_PERMISSION);
                return;
            }
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType().isAir()) {
                plugin.getMessageManager().send(player, Messages.UTILITY_HOLD_ITEM);
                return;
            }
            sell.sell(player, SalePlan.Scope.HAND, held.clone(), quantity, command, false);
            return;
        }
        ItemStack item = worth.item(args[0]);
        if (item == null) {
            plugin.getMessageManager().send(player, Messages.WORTH_UNKNOWN_ITEM, "{item}", args[0]);
            return;
        }
        sell.sell(player, SalePlan.Scope.ITEM, item, quantity, command, false);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length == 0 || args.length > 2) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            if (player.hasPermission(WorthService.PERMISSION_SELL_HAND)) values.add("hand");
            if (player.hasPermission(WorthService.PERMISSION_SELL_BULK)) values.addAll(List.of("inventory", "all", "blocks"));
            Set<String> held = new LinkedHashSet<>();
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                if (stack != null && !stack.getType().isAir() && worth.price(stack).isPresent()) {
                    held.add(worth.reference(stack));
                }
            }
            values.addAll(held);
        } else {
            String what = args[0].toLowerCase(Locale.ROOT);
            if (INVENTORY.contains(what) || what.equals("blocks")) return List.of();
            values.addAll(Arrays.asList("1", "16", "64", "-1", "1s"));
        }
        return values.stream().filter(value -> value.startsWith(prefix)).distinct().limit(64).toList();
    }
}
