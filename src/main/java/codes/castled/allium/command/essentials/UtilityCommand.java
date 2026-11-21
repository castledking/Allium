package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.message.Messages;
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
            repair(plugin,player,mode);
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

    /** Plan changes before charging so refusals and undamaged inventories are free. */
    private static boolean repair(PluginStart plugin,Player player,String mode) {
        boolean all="all".equals(mode);
        if(all&&!player.hasPermission("allium.repair.all")){
            UtilitySupport.send(plugin,player,Messages.NO_PERMISSION,"{cmd}","repair all");
            return false;
        }
        if(UtilitySupport.isInventoryBusy(plugin,player)){
            UtilitySupport.send(plugin,player,Messages.UTILITY_INVENTORY_BUSY,"{player}",player.getName());
            return false;
        }
        var inventory=player.getInventory();
        ItemStack[] items=all?inventory.getContents():new ItemStack[]{inventory.getItemInMainHand()};
        var repaired=new java.util.LinkedHashMap<Integer,ItemStack>();
        var names=new java.util.ArrayList<String>();
        for(int i=0;i<items.length;i++){
            ItemStack item=items[i];
            if(UtilitySupport.isAir(item)||!(item.getItemMeta() instanceof Damageable damage)||!damage.hasDamage())continue;
            ItemStack replacement=item.clone();
            damage.setDamage(0);
            if(!replacement.setItemMeta(damage))continue;
            repaired.put(i,replacement);
            names.add(UtilitySupport.itemName(item));
        }
        if(repaired.isEmpty()){
            UtilitySupport.send(plugin,player,all?Messages.UTILITY_REPAIR_NONE:Messages.REPAIR_HOLD_DAMAGED);
            return false;
        }
        if(all)repaired.forEach(inventory::setItem);
        else inventory.setItemInMainHand(repaired.get(0));
        UtilitySupport.send(plugin,player,all?Messages.UTILITY_REPAIR_ALL:Messages.REPAIR_HELD,
                "{count}",Integer.toString(repaired.size()),"{items}",String.join(", ",names));
        if(all)UtilitySupport.audit(plugin,player,"repair_all","repaired="+repaired.size());
        return true;
    }
}
