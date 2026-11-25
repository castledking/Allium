package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.economy.PaidCommandService;
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
import java.util.function.Supplier;

/** Shared command/sign entry point for self-service inventory utilities. */
public record UtilityCommand(PluginStart plugin, CommandSpec spec,
                             Supplier<PaidCommandService> paidCommands) implements SpecCommandHandler.Immediate {

    public UtilityCommand(PluginStart plugin, CommandSpec spec) {
        this(plugin, spec, () -> null);
    }

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
        PaidCommandService paid = paidCommands == null ? null : paidCommands.get();
        if (paid == null || !(context.sender() instanceof Player player)) {
            executeWithResult(context);
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
            String action = "all".equals(mode) ? "repair-all" : "repair";
            paid.execute(player, action, () -> canRepair(plugin, player, mode), () -> executeWithResult(context));
            return;
        }
        if (context.args().length != 0) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        paid.execute(player, spec.canonicalName(),
                () -> {
                    if (UtilitySupport.isInventoryBusy(plugin, player)) {
                        UtilitySupport.send(plugin, player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
                        return false;
                    }
                    return true;
                },
                () -> executeWithResult(context));
    }

    @Override
    public boolean executeWithResult(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return false;
        }

        if ("repair".equals(spec.canonicalName())) {
            if (context.args().length > 1) {
                context.sendInvalidArgs(spec.selfUsage());
                return false;
            }
            String mode = context.args().length == 0 ? "hand" : context.args()[0].toLowerCase(Locale.ROOT);
            if (!"hand".equals(mode) && !"all".equals(mode)) {
                context.sendInvalidArgs(spec.selfUsage());
                return false;
            }
            return repair(plugin,player,mode);
        }

        if (context.args().length != 0) {
            context.sendInvalidArgs(spec.selfUsage());
            return false;
        }
        if (UtilitySupport.isInventoryBusy(plugin, player)) {
            UtilitySupport.send(plugin,player,Messages.UTILITY_INVENTORY_BUSY,"{player}",player.getName());
            return false;
        }

        return switch (spec.canonicalName()) {
            case "disposal" -> {
                DisposalHolder holder = new DisposalHolder();
                Inventory disposal = plugin.getServer().createInventory(holder, 27, "Disposal");
                holder.setInventory(disposal);
                yield player.openInventory(disposal) != null;
            }
            case "workbench" -> player.openWorkbench(null, true) != null;
            case "anvil" -> player.openInventory(plugin.getServer().createInventory(null, InventoryType.ANVIL, "Anvil")) != null;
            case "grindstone", "cartographytable", "stonecutter", "loom", "smithing" -> openWorkstation(player);
            default -> throw new IllegalArgumentException("unknown utility");
        };
    }

    private boolean openWorkstation(Player player) {
        String name = spec.canonicalName();
        String field = name.equals("cartographytable") ? "CARTOGRAPHY_TABLE" : name.toUpperCase(Locale.ROOT);
        try {
            // Use the platform's real menu so result slots and input returns retain vanilla behavior.
            Class<?> menu = Class.forName("org.bukkit.inventory.MenuType");
            Class<?> typed = Class.forName("org.bukkit.inventory.MenuType$Typed");
            Object type = menu.getField(field).get(null);
            Object view = typed.getMethod("create", org.bukkit.entity.HumanEntity.class).invoke(type, player);
            org.bukkit.entity.HumanEntity.class.getMethod("openInventory", org.bukkit.inventory.InventoryView.class)
                    .invoke(player, view);
            return player.getOpenInventory() == view;
        } catch (ReflectiveOperationException | LinkageError | UnsupportedOperationException unavailable) {
            UtilitySupport.send(plugin, player, Messages.WORKSTATION_UNAVAILABLE, "{workstation}", name);
            return false;
        }
    }

    private static boolean canRepair(PluginStart plugin, Player player, String mode) {
        boolean all = "all".equals(mode);
        if (all && !player.hasPermission("allium.repair.all")) {
            UtilitySupport.send(plugin, player, Messages.NO_PERMISSION, "{cmd}", "repair all");
            return false;
        }
        if (UtilitySupport.isInventoryBusy(plugin, player)) {
            UtilitySupport.send(plugin, player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return false;
        }
        var inventory = player.getInventory();
        ItemStack[] items = all ? inventory.getContents() : new ItemStack[]{inventory.getItemInMainHand()};
        for (ItemStack item : items) {
            if (!UtilitySupport.isAir(item) && item.getItemMeta() instanceof Damageable damage && damage.hasDamage()) {
                return true;
            }
        }
        UtilitySupport.send(plugin, player, all ? Messages.UTILITY_REPAIR_NONE : Messages.REPAIR_HOLD_DAMAGED);
        return false;
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
