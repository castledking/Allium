package net.survivalfun.core.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.inventory.OfflineInventoryData;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;
import java.util.UUID;

public class Invsee implements CommandExecutor, Listener {

    private final PluginStart plugin;
    private final Lang lang;

    private static final int GUI_SIZE = 54;
    private static final int MAIN_INVENTORY_SIZE = 36;

    private static final int HELMET_SLOT = 45;
    private static final int CHESTPLATE_SLOT = 46;
    private static final int LEGGINGS_SLOT = 47;
    private static final int BOOTS_SLOT = 48;
    private static final int OFFHAND_SLOT = 49;
    private static final int MAIN_HAND_SLOT = 50;

    public Invsee(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        if (!player.hasPermission("allium.invsee")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "invsee");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("Usage: /" + label + " <player>");
            return true;
        }

        boolean canEdit = player.hasPermission("allium.invsee.edit");
        Player onlineTarget = Bukkit.getPlayer(args[0]);
        if (onlineTarget != null) {
            openInvsee(
                player,
                onlineTarget.getUniqueId(),
                onlineTarget.getName(),
                OfflineInventoryData.fromPlayer(onlineTarget),
                canEdit,
                true,
                onlineTarget.getInventory().getHeldItemSlot()
            );
            return true;
        }

        OfflinePlayer offlineTarget = resolveOfflineTarget(args[0]);
        if (offlineTarget == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        plugin.getOfflineInventoryManager().getEffectiveInventory(offlineTarget.getUniqueId())
            .whenComplete((data, error) -> SchedulerAdapter.runAtEntity(player, () -> {
                if (error != null) {
                    player.sendMessage("§cFailed to load offline inventory data: " + error.getMessage());
                    return;
                }

                String targetName = offlineTarget.getName() != null ? offlineTarget.getName() : args[0];
                openInvsee(player, offlineTarget.getUniqueId(), targetName, data, canEdit, false, -1);
            }));
        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        Player viewer = (Player) event.getWhoClicked();
        int rawSlot = event.getRawSlot();
        boolean clickingTop = rawSlot >= 0 && rawSlot < topInventory.getSize();
        boolean shiftClick = event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;

        if (!holder.isEditable()) {
            if (clickingTop || shiftClick) {
                event.setCancelled(true);
                viewer.updateInventory();
            }
            return;
        }

        if (shiftClick) {
            // Handle shift-click to move items between inventories
            if (holder.isEditable()) {
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null || clickedItem.getType().isAir()) {
                    event.setCancelled(true);
                    viewer.updateInventory();
                    return;
                }

                if (clickingTop) {
                    // Shift-click in top inventory: move to viewer's inventory
                    if (!isEditableTopSlot(rawSlot)) {
                        event.setCancelled(true);
                        viewer.updateInventory();
                        return;
                    }
                    // Let Bukkit handle the shift-click to move to bottom inventory
                    // Don't cancel - allow the default behavior
                } else {
                    // Shift-click in bottom inventory: move to top inventory
                    // Find first available slot in top inventory
                    ItemStack[] topContents = topInventory.getContents();
                    int firstEmpty = -1;
                    for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
                        if (isEditableTopSlot(i) && (topContents[i] == null || topContents[i].getType().isAir())) {
                            firstEmpty = i;
                            break;
                        }
                    }

                    if (firstEmpty == -1) {
                        // No space in top inventory
                        event.setCancelled(true);
                        viewer.updateInventory();
                        return;
                    }

                    // Move the item
                    event.setCancelled(true);
                    ItemStack itemToMove = clickedItem.clone();
                    event.getClickedInventory().setItem(rawSlot - topInventory.getSize(), null);
                    topInventory.setItem(firstEmpty, itemToMove);
                    viewer.updateInventory();
                }
            } else {
                // Not editable - cancel shift-click
                event.setCancelled(true);
                viewer.updateInventory();
            }
            return;
        }

        if (clickingTop && !isEditableTopSlot(rawSlot)) {
            event.setCancelled(true);
            viewer.updateInventory();
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        boolean affectsTop = event.getRawSlots().stream().anyMatch(slot -> slot < topInventory.getSize());
        if (!affectsTop) {
            return;
        }

        Player viewer = (Player) event.getWhoClicked();
        if (!holder.isEditable()) {
            event.setCancelled(true);
            viewer.updateInventory();
            return;
        }

        boolean touchesBlockedSlot = event.getRawSlots().stream()
            .anyMatch(slot -> slot < topInventory.getSize() && !isEditableTopSlot(slot));
        if (touchesBlockedSlot) {
            event.setCancelled(true);
            viewer.updateInventory();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory topInventory = event.getInventory();
        if (!(topInventory.getHolder() instanceof InvseeHolder holder)) {
            return;
        }

        if (holder.isEditable()) {
            ViewedInventory currentView = ViewedInventory.fromInventory(topInventory);
            ViewedInventory initialView = holder.getInitialView();

            if (!currentView.sameAs(initialView)) {
                Player onlineTarget = Bukkit.getPlayer(holder.getTargetUuid());
                if (onlineTarget != null) {
                    SchedulerAdapter.runAtEntity(onlineTarget, () -> {
                        OfflineInventoryData currentState = OfflineInventoryData.fromPlayer(onlineTarget);
                        OfflineInventoryData mergedState = currentView.mergeInto(currentState, initialView);
                        plugin.getOfflineInventoryManager().applyToOnlinePlayer(onlineTarget, mergedState);
                        plugin.getOfflineInventoryManager().savePlayerState(onlineTarget);
                    });
                } else {
                    plugin.getOfflineInventoryManager().getEffectiveInventory(holder.getTargetUuid())
                        .whenComplete((currentState, error) -> {
                            if (error != null || currentState == null) {
                                return;
                            }

                            OfflineInventoryData mergedState = currentView.mergeInto(currentState, initialView);
                            plugin.getOfflineInventoryManager().queueOfflineInventoryEdit(
                                holder.getTargetUuid(),
                                holder.getTargetName(),
                                mergedState,
                                mergedState
                            );
                        });
                }
            }
        }

        holder.clearInventory();
    }

    private void openInvsee(Player viewer,
                            UUID targetUuid,
                            String targetName,
                            OfflineInventoryData data,
                            boolean editable,
                            boolean targetOnline,
                            int heldHotbarSlot) {
        String title = "&8Invsee: &e" + targetName + (targetOnline ? "" : " &7(offline)");
        Component titleComponent = LegacyComponentSerializer.legacySection().deserialize(Text.parseColors(title));

        ViewedInventory initialView = ViewedInventory.fromData(data);
        InvseeHolder holder = new InvseeHolder(targetUuid, targetName, editable, initialView);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, titleComponent);
        holder.setInventory(inventory);

        copyFromData(data, inventory, heldHotbarSlot);
        viewer.openInventory(inventory);
    }

    private OfflinePlayer resolveOfflineTarget(String targetName) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(targetName);
        if (cached != null) {
            return cached;
        }

        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(targetName)) {
                return offlinePlayer;
            }
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetName);
        return offlinePlayer.hasPlayedBefore() ? offlinePlayer : null;
    }

    private void copyFromData(OfflineInventoryData data, Inventory inventory, int heldHotbarSlot) {
        ItemStack[] main = data.inventory() == null ? new ItemStack[MAIN_INVENTORY_SIZE] : OfflineInventoryData.resize(data.inventory(), MAIN_INVENTORY_SIZE);
        ItemStack[] armor = data.armor() == null ? new ItemStack[4] : OfflineInventoryData.resize(data.armor(), 4);

        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            inventory.setItem(i, cloneItem(main[i]));
        }

        inventory.setItem(HELMET_SLOT, armor.length > 3 ? cloneItem(armor[3]) : null);
        inventory.setItem(CHESTPLATE_SLOT, armor.length > 2 ? cloneItem(armor[2]) : null);
        inventory.setItem(LEGGINGS_SLOT, armor.length > 1 ? cloneItem(armor[1]) : null);
        inventory.setItem(BOOTS_SLOT, armor.length > 0 ? cloneItem(armor[0]) : null);
        inventory.setItem(OFFHAND_SLOT, cloneItem(data.offhand()));

        if (heldHotbarSlot >= 0 && heldHotbarSlot < 9) {
            inventory.setItem(MAIN_HAND_SLOT, cloneItem(main[heldHotbarSlot]));
        } else {
            inventory.setItem(MAIN_HAND_SLOT, createLockedLabel(Material.GRAY_STAINED_GLASS_PANE, "§7Main Hand", "§8Unavailable for offline view"));
        }
    }

    private static boolean isEditableTopSlot(int rawSlot) {
        return (rawSlot >= 0 && rawSlot < MAIN_INVENTORY_SIZE)
            || rawSlot == HELMET_SLOT
            || rawSlot == CHESTPLATE_SLOT
            || rawSlot == LEGGINGS_SLOT
            || rawSlot == BOOTS_SLOT
            || rawSlot == OFFHAND_SLOT;
    }

    private static ItemStack createLockedLabel(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(java.util.List.of(loreLine));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static final class ViewedInventory {
        private final ItemStack[] main;
        private final ItemStack helmet;
        private final ItemStack chestplate;
        private final ItemStack leggings;
        private final ItemStack boots;
        private final ItemStack offhand;

        private ViewedInventory(ItemStack[] main, ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots, ItemStack offhand) {
            this.main = main;
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;
            this.offhand = offhand;
        }

        private static ViewedInventory fromInventory(Inventory inventory) {
            ItemStack[] main = new ItemStack[MAIN_INVENTORY_SIZE];
            for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
                main[i] = cloneItem(inventory.getItem(i));
            }

            return new ViewedInventory(
                main,
                cloneItem(inventory.getItem(HELMET_SLOT)),
                cloneItem(inventory.getItem(CHESTPLATE_SLOT)),
                cloneItem(inventory.getItem(LEGGINGS_SLOT)),
                cloneItem(inventory.getItem(BOOTS_SLOT)),
                cloneItem(inventory.getItem(OFFHAND_SLOT))
            );
        }

        private static ViewedInventory fromData(OfflineInventoryData data) {
            ItemStack[] main = data.inventory() == null ? new ItemStack[MAIN_INVENTORY_SIZE] : OfflineInventoryData.resize(data.inventory(), MAIN_INVENTORY_SIZE);
            ItemStack[] armor = data.armor() == null ? new ItemStack[4] : OfflineInventoryData.resize(data.armor(), 4);

            return new ViewedInventory(
                main,
                armor.length > 3 ? cloneItem(armor[3]) : null,
                armor.length > 2 ? cloneItem(armor[2]) : null,
                armor.length > 1 ? cloneItem(armor[1]) : null,
                armor.length > 0 ? cloneItem(armor[0]) : null,
                cloneItem(data.offhand())
            );
        }

        private boolean sameAs(ViewedInventory other) {
            if (other == null) {
                return false;
            }

            for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
                if (!sameItem(main[i], other.main[i])) {
                    return false;
                }
            }

            return sameItem(helmet, other.helmet)
                && sameItem(chestplate, other.chestplate)
                && sameItem(leggings, other.leggings)
                && sameItem(boots, other.boots)
                && sameItem(offhand, other.offhand);
        }

        private OfflineInventoryData mergeInto(OfflineInventoryData currentState, ViewedInventory originalView) {
            ItemStack[] mergedMain = currentState.inventory() == null
                ? new ItemStack[MAIN_INVENTORY_SIZE]
                : OfflineInventoryData.resize(currentState.inventory(), MAIN_INVENTORY_SIZE);
            ItemStack[] mergedArmor = currentState.armor() == null
                ? new ItemStack[4]
                : OfflineInventoryData.resize(currentState.armor(), 4);
            ItemStack mergedOffhand = cloneItem(currentState.offhand());

            for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
                if (!sameItem(main[i], originalView.main[i])) {
                    mergedMain[i] = cloneItem(main[i]);
                }
            }

            if (!sameItem(boots, originalView.boots)) {
                mergedArmor[0] = cloneItem(boots);
            }
            if (!sameItem(leggings, originalView.leggings)) {
                mergedArmor[1] = cloneItem(leggings);
            }
            if (!sameItem(chestplate, originalView.chestplate)) {
                mergedArmor[2] = cloneItem(chestplate);
            }
            if (!sameItem(helmet, originalView.helmet)) {
                mergedArmor[3] = cloneItem(helmet);
            }
            if (!sameItem(offhand, originalView.offhand)) {
                mergedOffhand = cloneItem(offhand);
            }

            return new OfflineInventoryData(
                mergedMain,
                mergedArmor,
                mergedOffhand,
                currentState.enderChest(),
                currentState.experience()
            );
        }

        private static boolean sameItem(ItemStack left, ItemStack right) {
            return Objects.equals(left, right);
        }
    }

    private static final class InvseeHolder implements InventoryHolder {
        private final UUID targetUuid;
        private final String targetName;
        private final boolean editable;
        private final ViewedInventory initialView;
        private Inventory inventory;

        private InvseeHolder(UUID targetUuid, String targetName, boolean editable, ViewedInventory initialView) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.editable = editable;
            this.initialView = initialView;
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

        private String getTargetName() {
            return targetName;
        }

        private boolean isEditable() {
            return editable;
        }

        private ViewedInventory getInitialView() {
            return initialView;
        }
    }
}
