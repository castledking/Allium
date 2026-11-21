package codes.castled.allium.command.worth;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.economy.Money;
import codes.castled.allium.message.Messages;
import codes.castled.allium.worth.WorthService;
import codes.castled.allium.worth.WorthSettings;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /setworth [hand|item] <price>}: sets an item's price in {@code worth.yml}; a negative price stops it selling. */
public final class SetWorthCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final WorthService worth;

    public SetWorthCommand(PluginStart plugin, WorthService worth) {
        this.plugin = plugin;
        this.worth = worth;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SETWORTH;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length == 0 || args.length > 2) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }
        Material material;
        if (args.length == 1 || args[0].equalsIgnoreCase("hand")) {
            if (!(sender instanceof Player player)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType().isAir()) {
                plugin.getMessageManager().send(sender, Messages.UTILITY_HOLD_ITEM);
                return;
            }
            material = held.getType();
        } else {
            material = worth.material(args[0]);
            if (material == null) {
                plugin.getMessageManager().send(sender, Messages.WORTH_UNKNOWN_ITEM, "{item}", args[0]);
                return;
            }
        }
        BigDecimal price;
        try {
            price = new BigDecimal(args[args.length - 1].replace(",", "").replace("$", "").trim());
        } catch (NumberFormatException invalid) {
            plugin.getMessageManager().send(sender, Messages.WORTH_INVALID_PRICE);
            return;
        }
        if (price.compareTo(WorthSettings.MAX_PRICE) > 0) {
            plugin.getMessageManager().send(sender, Messages.WORTH_INVALID_PRICE);
            return;
        }
        BigDecimal set = price.signum() < 0 ? null : price.setScale(2, RoundingMode.HALF_UP);
        String name = WorthService.name(new ItemStack(material));
        try {
            worth.setWorth(material, set);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException failed) {
            plugin.logger().warning("Could not save worth.yml for /setworth " + material + ": " + failed.getMessage());
            plugin.getMessageManager().send(sender, Messages.WORTH_SAVE_FAILED, "{reason}", String.valueOf(failed.getMessage()));
            return;
        }
        plugin.logger().info("Worth of " + material.name().toLowerCase(Locale.ROOT) + " set to "
                + (set == null ? "unsellable" : set.toPlainString()) + " by " + sender.getName() + ".");
        if (set == null) {
            plugin.getMessageManager().send(sender, Messages.WORTH_REMOVED, "{item}", name);
        } else {
            plugin.getMessageManager().send(sender, Messages.WORTH_SET, "{item}", name, "{price}", worth.format(Money.exact(set)));
        }
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0 || args.length > 2 || !sender.hasPermission(WorthService.PERMISSION_SETWORTH)) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            if (sender instanceof Player && "hand".startsWith(prefix)) values.add("hand");
            values.addAll(WorthCommand.items(plugin, prefix));
        } else {
            Material material = args[0].equalsIgnoreCase("hand") && sender instanceof Player player
                    ? player.getInventory().getItemInMainHand().getType() : worth.material(args[0]);
            if (material != null) {
                worth.price(material).ifPresent(price -> values.add(price.toPlainString()));
            }
            values.add("-1");
            values.removeIf(value -> !value.startsWith(prefix));
        }
        return values.stream().distinct().limit(64).toList();
    }
}
