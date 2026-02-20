package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.util.SchedulerAdapter;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class Invsee implements CommandExecutor, Listener {

    private final Lang lang;

    private static final int GUI_SIZE = 54;
    private static final int MAIN_INVENTORY_SIZE = 36;
    private static final int HELMET_SLOT = 5;
    private static final int CHESTPLATE_SLOT = 6;
    private static final int LEGGINGS_SLOT = 7;
    private static final int BOOTS_SLOT = 8;
    private static final int OFFHAND_SLOT = 44;

    public Invsee(PluginStart plugin) {
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        Player player = (Player) sender;
        
        // Permission check
        if (!player.hasPermission("allium.invsee")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "invsee");
            return true;
        }
        
        if (args.length < 1) {
            player.sendMessage("Usage: /" + label + " <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        boolean canEdit = player.hasPermission("allium.invsee.edit");

        String title = lang.get("invsee.inventory-title").replace("{player}", target.getName());
        Component titleComponent = LegacyComponentSerializer.legacySection().deserialize(Text.parseColors(title));

        InvseeHolder holder = new InvseeHolder(target.getUniqueId(), canEdit);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, titleComponent);
        holder.setInventory(inventory);

        copyFromTarget(target, inventory);

        player.openInventory(inventory);
        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        Player viewer = (Player) event.getWhoClicked();
        Player target = Bukkit.getPlayer(holder.getTargetUuid());

        if (target == null) {
            viewer.closeInventory();
            return;
        }

        if (!holder.isEditable()) {
            if (event.getClickedInventory() == topInventory || event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                event.setCancelled(true);
                viewer.updateInventory();
            }
            return;
        }

        // Allow normal behaviour then sync target inventory afterwards
        SchedulerAdapter.runAtEntity(viewer, () -> {
            Inventory currentTop = viewer.getOpenInventory().getTopInventory();
            if (!(currentTop.getHolder() instanceof InvseeHolder currentHolder) || !currentHolder.matches(holder)) {
                return;
            }
            InventorySnapshot snapshot = InventorySnapshot.fromInventory(currentTop);
            SchedulerAdapter.runAtEntity(target, () -> applySnapshot(target, snapshot));
        });
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        Player viewer = (Player) event.getWhoClicked();
        Player target = Bukkit.getPlayer(holder.getTargetUuid());

        if (target == null) {
            viewer.closeInventory();
            return;
        }

        boolean affectsTop = event.getRawSlots().stream().anyMatch(slot -> slot < topInventory.getSize());
        if (!affectsTop) {
            return;
        }

        if (!holder.isEditable()) {
            event.setCancelled(true);
            viewer.updateInventory();
            return;
        }

        SchedulerAdapter.runAtEntity(viewer, () -> {
            Inventory currentTop = viewer.getOpenInventory().getTopInventory();
            if (!(currentTop.getHolder() instanceof InvseeHolder currentHolder) || !currentHolder.matches(holder)) {
                return;
            }
            InventorySnapshot snapshot = InventorySnapshot.fromInventory(currentTop);
            SchedulerAdapter.runAtEntity(target, () -> applySnapshot(target, snapshot));
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory topInventory = event.getInventory();
        if (!(topInventory.getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        holder.clearInventory();
    }

    private void copyFromTarget(Player target, Inventory inventory) {
        PlayerInventory targetInventory = target.getInventory();

        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            inventory.setItem(i, cloneItem(targetInventory.getItem(i)));
        }

        inventory.setItem(HELMET_SLOT, cloneItem(targetInventory.getHelmet()));
        inventory.setItem(CHESTPLATE_SLOT, cloneItem(targetInventory.getChestplate()));
        inventory.setItem(LEGGINGS_SLOT, cloneItem(targetInventory.getLeggings()));
        inventory.setItem(BOOTS_SLOT, cloneItem(targetInventory.getBoots()));
        inventory.setItem(OFFHAND_SLOT, cloneItem(targetInventory.getItemInOffHand()));
    }

    private static void applySnapshot(Player target, InventorySnapshot snapshot) {
        if (!target.isOnline()) {
            return;
        }

        PlayerInventory targetInventory = target.getInventory();

        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            targetInventory.setItem(i, snapshot.main[i] == null ? null : snapshot.main[i].clone());
        }

        targetInventory.setHelmet(snapshot.helmet == null ? null : snapshot.helmet.clone());
        targetInventory.setChestplate(snapshot.chestplate == null ? null : snapshot.chestplate.clone());
        targetInventory.setLeggings(snapshot.leggings == null ? null : snapshot.leggings.clone());
        targetInventory.setBoots(snapshot.boots == null ? null : snapshot.boots.clone());
        targetInventory.setItemInOffHand(snapshot.offhand == null ? null : snapshot.offhand.clone());

        target.updateInventory();
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static final class InventorySnapshot {
        private final ItemStack[] main;
        private final ItemStack helmet;
        private final ItemStack chestplate;
        private final ItemStack leggings;
        private final ItemStack boots;
        private final ItemStack offhand;

        private InventorySnapshot(ItemStack[] main, ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots, ItemStack offhand) {
            this.main = main;
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;
            this.offhand = offhand;
        }

        private static InventorySnapshot fromInventory(Inventory inventory) {
            ItemStack[] main = new ItemStack[MAIN_INVENTORY_SIZE];
            for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
                ItemStack item = inventory.getItem(i);
                main[i] = item == null ? null : item.clone();
            }

            ItemStack helmet = cloneItem(inventory.getItem(HELMET_SLOT));
            ItemStack chestplate = cloneItem(inventory.getItem(CHESTPLATE_SLOT));
            ItemStack leggings = cloneItem(inventory.getItem(LEGGINGS_SLOT));
            ItemStack boots = cloneItem(inventory.getItem(BOOTS_SLOT));
            ItemStack offhand = cloneItem(inventory.getItem(OFFHAND_SLOT));

            return new InventorySnapshot(main, helmet, chestplate, leggings, boots, offhand);
        }
    }

    private static final class InvseeHolder implements InventoryHolder {
        private final UUID targetUuid;
        private final boolean editable;
        private Inventory inventory;

        private InvseeHolder(UUID targetUuid, boolean editable) {
            this.targetUuid = targetUuid;
            this.editable = editable;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private void clearInventory() {
            this.inventory = null;
        }

        private UUID getTargetUuid() {
            return targetUuid;
        }

        private boolean isEditable() {
            return editable;
        }

        private boolean matches(InvseeHolder other) {
            return other != null && targetUuid.equals(other.targetUuid) && editable == other.editable;
        }
    }
}