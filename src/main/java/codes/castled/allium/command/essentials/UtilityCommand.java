package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Locale;

/** Shared command/sign entry point for self-service inventory utilities. */
public record UtilityCommand(PluginStart plugin, CommandSpec spec) implements SpecCommandHandler {

    public static final class DisposalHolder implements InventoryHolder {
        private Inventory inventory;

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class DisposalListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            if (event.getInventory().getHolder() instanceof DisposalHolder) {
                event.getInventory().clear();
            }
        }
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if ("info".equals(spec.canonicalName())) {
            if (context.args().length != 0) {
                context.sendInvalidArgs(spec.selfUsage());
                return;
            }
            long count = plugin.getServer().getOnlinePlayers().stream()
                    .filter(target -> !(context.sender() instanceof Player viewer) || PlayerNameVisibility.canSee(viewer, target))
                    .count();
            UtilitySupport.send(plugin,context.sender(),Messages.UTILITY_SERVER_INFO,"{server}",plugin.getServer().getName(),"{count}",Long.toString(count));
            return;
        }

        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }

        if ("repair".equals(spec.canonicalName())) {
            if (context.args().length > 1) {
                context.sendInvalidArgs(spec.selfUsage());
                return;
            }
            String mode = context.args().length == 0 ? "hand" : context.args()[0].toLowerCase(Locale.ROOT);
            if (!"hand".equals(mode) && !"all".equals(mode)) {
                context.sendInvalidArgs(spec.selfUsage());
                return;
            }
            if ("all".equals(mode) && !player.hasPermission("allium.repair.all")) {
                UtilitySupport.send(plugin, player, Messages.NO_PERMISSION, "{cmd}", "repair all");
                return;
            }
            if (UtilitySupport.isInventoryBusy(plugin, player)) {
                UtilitySupport.send(plugin,player,Messages.UTILITY_INVENTORY_BUSY,"{player}",player.getName());
                return;
            }
            if ("hand".equals(mode)) {
                var item = player.getInventory().getItemInMainHand();
                var meta = item == null ? null : item.getItemMeta();
                if (meta instanceof Damageable damageable && damageable.hasDamage()) {
                    damageable.setDamage(0);
                    item.setItemMeta(meta);
                    UtilitySupport.send(plugin,player,Messages.REPAIR_HELD);
                } else {
                    UtilitySupport.send(plugin,player,Messages.REPAIR_HOLD_DAMAGED);
                }
                return;
            }
            int repaired = repairAll(player);
            if (repaired > 0) {
                UtilitySupport.send(plugin, player, Messages.UTILITY_REPAIR_ALL, "{count}", Integer.toString(repaired));
                UtilitySupport.audit(plugin, player, "repair_all", "repaired=" + repaired);
            } else {
                UtilitySupport.send(plugin, player, Messages.UTILITY_REPAIR_NONE);
            }
            return;
        }

        if (context.args().length != 0) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        if (UtilitySupport.isInventoryBusy(plugin, player)) {
            UtilitySupport.send(plugin,player,Messages.UTILITY_INVENTORY_BUSY,"{player}",player.getName());
            return;
        }

        switch (spec.canonicalName()) {
            case "disposal" -> {
                DisposalHolder holder = new DisposalHolder();
                Inventory disposal = plugin.getServer().createInventory(holder, 27, "Disposal");
                holder.setInventory(disposal);
                player.openInventory(disposal);
            }
            case "workbench" -> player.openWorkbench(null, true);
            case "anvil" -> player.openInventory(plugin.getServer().createInventory(null, InventoryType.ANVIL, "Anvil"));
            default -> throw new IllegalArgumentException("unknown utility");
        }
    }

    private static int repairAll(Player player) {
        int repaired = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack item : contents) {
            if (UtilitySupport.isAir(item)) {
                continue;
            }
            var meta = item.getItemMeta();
            if (meta instanceof Damageable damageable && damageable.hasDamage()) {
                damageable.setDamage(0);
                item.setItemMeta(meta);
                repaired++;
            }
        }
        return repaired;
    }
}
