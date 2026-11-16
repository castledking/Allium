package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class MoreCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public MoreCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.MORE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length > 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        Integer requestedAmount = null;
        Player target;
        if (args.length == 0) {
            if (!(context.sender() instanceof Player self)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = self;
        } else if (args.length == 1) {
            Integer parsed = tryParseInt(args[0]);
            if (parsed != null) {
                if (parsed < 1) {
                    UtilitySupport.send(plugin, context.sender(), Messages.NUMBER_INVALID, "{input}", args[0]);
                    return;
                }
                if (!(context.sender() instanceof Player self)) {
                    context.sendInvalidArgs(spec().consoleUsage());
                    return;
                }
                requestedAmount = parsed;
                target = self;
            } else {
                target = context.resolveSelfOrTarget(0);
                if (target == null) {
                    return;
                }
            }
        } else {
            Integer parsed = tryParseInt(args[0]);
            if (parsed == null || parsed < 1) {
                UtilitySupport.send(plugin, context.sender(), Messages.NUMBER_INVALID, "{input}", args[0]);
                return;
            }
            requestedAmount = parsed;
            target = context.resolveSelfOrTarget(1);
            if (target == null) {
                return;
            }
        }

        if (!UtilitySupport.guardInventoryNotBusy(plugin, context.sender(), target)) {
            return;
        }

        ItemStack held = target.getInventory().getItemInMainHand();
        if (UtilitySupport.isAir(held)) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_HOLD_ITEM);
            return;
        }
        int maxStack = Math.max(1, held.getMaxStackSize());
        if (requestedAmount == null && maxStack <= 1) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_MORE_CANNOT_STACK,
                    "{max}", Integer.toString(maxStack));
            return;
        }
        int targetAmount = requestedAmount == null ? maxStack : requestedAmount;
        if (targetAmount > maxStack) {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_MORE_CANNOT_STACK,
                    "{max}", Integer.toString(maxStack));
            return;
        }

        held.setAmount(targetAmount);
        target.getInventory().setItemInMainHand(held);
        if (target == context.sender()) {
            UtilitySupport.send(plugin, target, Messages.UTILITY_MORE_SELF,
                    "{amount}", Integer.toString(targetAmount));
        } else {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_MORE_OTHER,
                    "{player}", target.getName(),
                    "{amount}", Integer.toString(targetAmount));
            UtilitySupport.send(plugin, target, Messages.UTILITY_MORE_SELF,
                    "{amount}", Integer.toString(targetAmount));
            UtilitySupport.audit(plugin, context.sender(), "more",
                    "target=" + target.getName() + " amount=" + targetAmount
                            + " item=" + UtilitySupport.describeItem(plugin, held));
        }
    }

    private static Integer tryParseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
