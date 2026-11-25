package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.economy.PaidCommandService;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.function.Supplier;

public final class HatCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final Supplier<PaidCommandService> paidCommands;

    public HatCommand(PluginStart plugin) {
        this(plugin, () -> null);
    }

    public HatCommand(PluginStart plugin, Supplier<PaidCommandService> paidCommands) {
        this.plugin = plugin;
        this.paidCommands = paidCommands == null ? () -> null : paidCommands;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.HAT;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        if (context.args().length != 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        PaidCommandService paid = paidCommands.get();
        if (paid != null) {
            paid.execute(player, "hat", () -> canHat(player), () -> deliverHat(player));
            return;
        }
        deliverHat(player);
    }

    private boolean canHat(Player player) {
        if (!UtilitySupport.guardInventoryNotBusy(plugin, player, player)) {
            return false;
        }
        if (UtilitySupport.isAir(player.getInventory().getItemInMainHand())) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_HOLD_ITEM);
            return false;
        }
        return true;
    }

    private boolean deliverHat(Player player) {
        if (!canHat(player)) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        ItemStack currentHelmet = inventory.getHelmet();
        ItemStack newHelmet = hand.clone();
        ItemStack swappedToHand = UtilitySupport.isAir(currentHelmet) ? null : currentHelmet.clone();
        inventory.setHelmet(newHelmet);
        inventory.setItemInMainHand(swappedToHand);
        UtilitySupport.send(plugin, player, Messages.UTILITY_HAT_EQUIPPED,
                "{item}", UtilitySupport.itemName(newHelmet));
        return true;
    }
}
