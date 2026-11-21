package codes.castled.allium.command.worth;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.economy.Money;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.ItemsModule;
import codes.castled.allium.worth.SalePlan;
import codes.castled.allium.worth.SaleQuantity;
import codes.castled.allium.worth.SellService;
import codes.castled.allium.worth.WorthService;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** {@code /worth [hand|inventory|blocks|item] [amount]} ({@code /price}): what items sell for. */
public final class WorthCommand implements SpecCommandHandler {

    private static final Set<String> INVENTORY = Set.of("inventory", "invent", "all");

    private final PluginStart plugin;
    private final WorthService worth;
    private final SellService sell;

    public WorthCommand(PluginStart plugin, WorthService worth, SellService sell) {
        this.plugin = plugin;
        this.worth = worth;
        this.sell = sell;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.WORTH;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length > 2) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }
        String what = args.length == 0 ? "hand" : args[0].toLowerCase(Locale.ROOT);
        boolean blocks = what.equals("blocks");
        boolean self = what.equals("hand") || blocks || INVENTORY.contains(what);
        if (self && !(sender instanceof Player)) {
            context.sendInvalidArgs(spec().consoleUsage());
            return;
        }
        if (blocks || INVENTORY.contains(what)) {
            Player player = (Player) sender;
            SalePlan plan = sell.plan(player, blocks ? SalePlan.Scope.BLOCKS : SalePlan.Scope.INVENTORY, null, SaleQuantity.ALL);
            if (plan != null) {
                plugin.getMessageManager().send(player, blocks ? Messages.WORTH_BLOCKS : Messages.WORTH_INVENTORY,
                        "{total}", worth.format(plan.total()), "{items}", SellService.describe(plan));
            }
            return;
        }
        SaleQuantity quantity;
        try {
            quantity = SaleQuantity.parse(args.length > 1 ? args[1] : null);
        } catch (IllegalArgumentException invalid) {
            plugin.getMessageManager().send(sender, Messages.WORTH_INVALID_AMOUNT);
            return;
        }
        ItemStack item;
        int amount;
        if (what.equals("hand")) {
            ItemStack held = ((Player) sender).getInventory().getItemInMainHand();
            if (held == null || held.getType().isAir()) {
                plugin.getMessageManager().send(sender, Messages.UTILITY_HOLD_ITEM);
                return;
            }
            item = held.clone();
            amount = held.getAmount();
            SalePlan.Refusal refusal = worth.refusal(item);
            if (refusal != null) {
                sell.sendRefusal((Player) sender, item, refusal);
                return;
            }
        } else {
            Material material = worth.material(args[0]);
            if (material == null) {
                plugin.getMessageManager().send(sender, Messages.WORTH_UNKNOWN_ITEM, "{item}", args[0]);
                return;
            }
            item = new ItemStack(material);
            amount = 1;
        }
        if (quantity.count() > 0) {
            amount = quantity.stacks() ? quantity.count() * item.getMaxStackSize() : quantity.count();
        }
        String name = WorthService.name(item);
        BigDecimal base = worth.price(item.getType()).orElse(null);
        if (base == null) {
            plugin.getMessageManager().send(sender, Messages.WORTH_NOT_SELLABLE, "{item}", name);
            return;
        }
        BigDecimal multiplier = sender instanceof Player player ? worth.multiplier(player) : BigDecimal.ONE;
        Money each = Money.exact(base.multiply(multiplier).setScale(2, RoundingMode.HALF_UP));
        if (each.minor() < 1) {
            plugin.getMessageManager().send(sender, Messages.WORTH_WORTHLESS, "{item}", name);
            return;
        }
        if (amount == 1) {
            plugin.getMessageManager().send(sender, Messages.WORTH_EACH, "{item}", name, "{each}", worth.format(each));
            return;
        }
        Money total;
        try {
            total = new Money(Math.multiplyExact(each.minor(), (long) amount));
        } catch (ArithmeticException | IllegalArgumentException tooMany) {
            plugin.getMessageManager().send(sender, Messages.WORTH_INVALID_AMOUNT);
            return;
        }
        plugin.getMessageManager().send(sender, Messages.WORTH_AMOUNT, "{amount}", Integer.toString(amount), "{item}", name,
                "{total}", worth.format(total), "{each}", worth.format(each));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0 || args.length > 2) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            String what = args[0].toLowerCase(Locale.ROOT);
            if (INVENTORY.contains(what) || what.equals("blocks")) return List.of();
            return List.of("1", "16", "64", "1s").stream().filter(v -> v.startsWith(prefix)).toList();
        }
        List<String> values = new ArrayList<>();
        if (sender instanceof Player) values.addAll(List.of("hand", "inventory", "blocks"));
        values.removeIf(value -> !value.startsWith(prefix));
        values.addAll(items(plugin, prefix));
        return values.stream().distinct().limit(64).toList();
    }

    /** Item names and aliases as {@code /i} completes them, without multi-item forms. */
    public static List<String> items(PluginStart plugin, String prefix) {
        if (prefix.isEmpty()) return List.of();
        return plugin.getModuleManager().getModule("items") instanceof ItemsModule items && items.isEnabled()
                ? items.complete(prefix).stream().filter(name -> !name.contains(";") && !name.contains(",")).toList()
                : List.of();
    }
}
