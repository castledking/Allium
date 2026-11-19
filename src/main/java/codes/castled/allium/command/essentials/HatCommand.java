package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class HatCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public HatCommand(PluginStart plugin) {
        this.plugin = plugin;
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
        if (!UtilitySupport.guardInventoryNotBusy(plugin, player, player)) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        if (UtilitySupport.isAir(hand)) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_HOLD_ITEM);
            return;
        }
        ItemStack currentHelmet = inventory.getHelmet();
        ItemStack newHelmet = hand.clone();
        ItemStack swappedToHand = UtilitySupport.isAir(currentHelmet) ? null : currentHelmet.clone();
        inventory.setHelmet(newHelmet);
        inventory.setItemInMainHand(swappedToHand);
        UtilitySupport.send(plugin, player, Messages.UTILITY_HAT_EQUIPPED,
                "{item}", UtilitySupport.itemName(newHelmet));
    }
}
