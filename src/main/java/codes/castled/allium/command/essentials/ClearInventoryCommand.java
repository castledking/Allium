package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.essentials.InventoryClearFilter;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

public final class ClearInventoryCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public ClearInventoryCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.CLEARINVENTORY;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length > 3) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        Player target;
        Material filter = null;
        int maxCount = InventoryClearFilter.UNLIMITED;

        if (args.length == 0) {
            if (!(context.sender() instanceof Player self)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = self;
        } else if (args.length == 1) {
            Player candidate = resolveIfVisible(context, args[0]);
            if (candidate != null && context.sender().hasPermission("allium.clearinventory.others")) {
                target = candidate;
            } else if (context.sender() instanceof Player self && InventoryClearFilter.isAllToken(args[0])) {
                target = self;
            } else if (context.sender() instanceof Player self && matchMaterial(args[0]) != null) {
                target = self;
                filter = matchMaterial(args[0]);
            } else {
                target = context.resolveSelfOrTarget(0);
                return;
            }
        } else if (args.length == 2) {
            if (context.sender() instanceof Player self
                    && (InventoryClearFilter.isAllToken(args[0]) || matchMaterial(args[0]) != null)
                    && InventoryClearFilter.parseMaxCount(args[1]) != null) {
                target = self;
                filter = InventoryClearFilter.isAllToken(args[0]) ? null : matchMaterial(args[0]);
                maxCount = InventoryClearFilter.parseMaxCount(args[1]);
            } else {
                target = context.resolveSelfOrTarget(0);
                if (target == null) {
                    return;
                }
                if (!InventoryClearFilter.isAllToken(args[1])) {
                    filter = matchMaterial(args[1]);
                    if (filter == null) {
                        UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_CLEAR_INVALID_MATERIAL,
                                "{material}", args[1]);
                        return;
                    }
                }
            }
        } else {
            target = context.resolveSelfOrTarget(0);
            if (target == null) {
                return;
            }
            if (!InventoryClearFilter.isAllToken(args[1])) {
                filter = matchMaterial(args[1]);
                if (filter == null) {
                    UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_CLEAR_INVALID_MATERIAL,
                            "{material}", args[1]);
                    return;
                }
            }
            Integer parsedMax = InventoryClearFilter.parseMaxCount(args[2]);
            if (parsedMax == null) {
                UtilitySupport.send(plugin, context.sender(), Messages.NUMBER_INVALID, "{input}", args[2]);
                return;
            }
            maxCount = parsedMax;
        }

        if (!UtilitySupport.guardInventoryNotBusy(plugin, context.sender(), target)) {
            return;
        }

        int removed = clearMatching(target, filter, maxCount);
        if (target == context.sender()) {
            UtilitySupport.send(plugin, target, Messages.UTILITY_CLEAR_SELF,
                    "{count}", Integer.toString(removed));
        } else {
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_CLEAR_OTHER,
                    "{count}", Integer.toString(removed),
                    "{player}", target.getName());
            UtilitySupport.send(plugin, target, Messages.UTILITY_CLEAR_SELF,
                    "{count}", Integer.toString(removed));
        }
        UtilitySupport.audit(plugin, context.sender(), "clearinventory",
                "target=" + target.getName()
                        + " material=" + (filter == null ? "*" : filter.getKey().toString())
                        + " max=" + (maxCount == InventoryClearFilter.UNLIMITED ? "unlimited" : maxCount)
                        + " removed=" + removed);
    }

    private static int clearMatching(Player target, Material filter, int maxCount) {
        ItemStack[] contents = target.getInventory().getContents();
        if (contents == null) {
            return 0;
        }
        int remaining = maxCount;
        int removed = 0;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (UtilitySupport.isAir(item)) {
                continue;
            }
            if (filter != null && item.getType() != filter) {
                continue;
            }
            int amount = item.getAmount();
            if (amount <= remaining) {
                removed += amount;
                remaining -= amount;
                contents[i] = null;
            } else {
                item.setAmount(amount - remaining);
                removed += remaining;
                remaining = 0;
            }
        }
        target.getInventory().setContents(contents);
        return removed;
    }

    private Player resolveIfVisible(BukkitCommandContext context, String name) {
        if (plugin.getServer() == null) {
            return null;
        }
        Player target = plugin.getServer().getPlayer(name);
        if (target == null || !PlayerNameVisibility.canSeeTarget(context.sender(), target)) {
            return null;
        }
        return target;
    }

    private static Material matchMaterial(String raw) {
        String normalized = InventoryClearFilter.normalizeMaterialToken(raw);
        if (normalized.isEmpty()) {
            return null;
        }
        Material matched = Material.matchMaterial(normalized);
        if (matched == null) {
            matched = Material.matchMaterial(normalized.toUpperCase(Locale.ROOT));
        }
        return matched;
    }
}
