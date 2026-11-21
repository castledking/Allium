package codes.castled.allium.restore;

import codes.castled.allium.PluginStart;
import codes.castled.allium.gui.GuiIcons;
import codes.castled.allium.item.library.SkullProfiles;
import codes.castled.allium.mail.MailItemCodec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.InspectionLayout;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The {@code /restore} menus: a paged snapshot history, a restore panel per snapshot and its ender
 * chest panel. With {@code allium.restore.edit} the panels are an editable working copy: items can
 * be moved between the panel and the viewer's inventory, and every restore button uses what the
 * panel holds. Nothing is written to storage unless "Save as manual snapshot" is pressed.
 */
public final class RestoreMenus implements Listener {

    static final int PAGE_SIZE = 45;
    /** The sword marking the main hand, as in /invsee. */
    private static final int MAIN_HAND = 41;
    /** Panel cells that hold the 36 storage slots (main inventory and hotbar). */
    private static final int STORAGE_CELLS = 36;
    private static final int EVERYTHING = 45, SAVE = 46, INVENTORY_ONLY = 47, ARMOR_ONLY = 48, XP_ONLY = 49, TELEPORT = 50,
            ENDER = 51, INFO = 52, BACK = 53;
    private static final int ENDER_TO_OWNER = 27, ENDER_TO_SELF = 28, ENDER_BACK = 35;

    private final PluginStart plugin;
    private final RestoreService restore;
    private final Supplier<SkullProfiles> skulls;

    public RestoreMenus(PluginStart plugin, RestoreService restore) {
        this(plugin, restore, () -> null);
    }

    /** {@code skulls} gives the "Restore everything" head the target's skin; null keeps the plain head. */
    public RestoreMenus(PluginStart plugin, RestoreService restore, Supplier<SkullProfiles> skulls) {
        this.plugin = plugin;
        this.restore = restore;
        this.skulls = skulls;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            InventoryHolder holder = online.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof Menu) online.closeInventory();
        }
    }

    private interface Menu extends InventoryHolder {
    }

    private static final class ListMenu implements Menu {
        final UUID viewer;
        final UUID target;
        final String targetName;
        final int page;
        final int total;
        final List<SnapshotRepository.Snapshot> shown;
        Inventory inventory;

        ListMenu(UUID viewer, UUID target, String targetName, int page, int total, List<SnapshotRepository.Snapshot> shown) {
            this.viewer = viewer;
            this.target = target;
            this.targetName = targetName;
            this.page = page;
            this.total = total;
            this.shown = shown;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final class SessionMenu implements Menu {
        final UUID viewer;
        final SnapshotRepository.Snapshot snapshot;
        final String targetName;
        final boolean editable;
        final ListMenu back;
        ItemStack[] ender;
        int level;
        float progress;
        int held;
        Inventory inventory;
        boolean switching;

        SessionMenu(UUID viewer, SnapshotRepository.Snapshot snapshot, String targetName, boolean editable, ListMenu back) {
            this.viewer = viewer;
            this.snapshot = snapshot;
            this.targetName = targetName;
            this.editable = editable;
            this.back = back;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final class EnderMenu implements Menu {
        final SessionMenu session;
        Inventory inventory;
        boolean switching;

        EnderMenu(SessionMenu session) {
            this.session = session;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    // -- Opening -----------------------------------------------------------------------------------

    /** Opens {@code target}'s history at {@code page} for {@code viewer}; storage is read off-thread. */
    public void open(Player viewer, UUID target, String targetName, int page) {
        restore.execute(() -> {
            List<SnapshotRepository.Snapshot> shown;
            int total;
            try {
                total = restore.repository().count(target);
                shown = restore.repository().page(target, page * PAGE_SIZE, PAGE_SIZE);
            } catch (SQLException e) {
                plugin.getScheduler().runOnServer(() -> plugin.getMessageManager().send(viewer, Messages.RESTORE_FAILED));
                return;
            }
            plugin.getScheduler().runOnServer(() -> {
                if (!viewer.isOnline()) return;
                if (total == 0) {
                    plugin.getMessageManager().send(viewer, Messages.RESTORE_EMPTY, "{player}", targetName);
                    return;
                }
                ListMenu menu = new ListMenu(viewer.getUniqueId(), target, targetName, page, total, shown);
                Inventory inventory = plugin.getServer().createInventory(menu, 54, title("Restore: " + targetName));
                menu.inventory = inventory;
                for (int i = 0; i < shown.size(); i++) inventory.setItem(i, snapshotIcon(shown.get(i)));
                int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
                for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, GuiIcons.icon(GuiIcons.Icon.FILLER, " ", List.of()));
                if (page > 0) inventory.setItem(45, named(Material.ARROW, "§aPrevious page", List.of("§7Page " + page)));
                inventory.setItem(49, named(Material.BOOK, "§ePage " + (page + 1) + " of " + pages,
                        List.of("§7" + total + " snapshot(s) of " + targetName, "§7Newest first")));
                if (page + 1 < pages) inventory.setItem(53, named(Material.ARROW, "§aNext page", List.of("§7Page " + (page + 2))));
                viewer.openInventory(inventory);
            });
        }, new java.util.concurrent.CompletableFuture<Void>());
    }

    private void openSession(Player viewer, ListMenu list, SnapshotRepository.Snapshot snapshot) {
        RestoreService.Contents contents;
        try {
            contents = restore.decode(snapshot);
        } catch (MailItemCodec.UnreadableException unreadable) {
            plugin.getMessageManager().send(viewer, Messages.RESTORE_UNREADABLE, "{reason}", unreadable.getMessage());
            return;
        } catch (IOException | RuntimeException failure) {
            plugin.getMessageManager().send(viewer, Messages.RESTORE_UNREADABLE, "{reason}", String.valueOf(failure.getMessage()));
            return;
        }
        boolean editable = viewer.hasPermission(RestoreService.PERMISSION_EDIT);
        SessionMenu session = new SessionMenu(viewer.getUniqueId(), snapshot, list.targetName, editable, list);
        session.ender = contents.enderChest();
        session.level = contents.level();
        session.progress = contents.progress();
        session.held = contents.held();
        Inventory inventory = plugin.getServer().createInventory(session, 54,
                title((editable ? "Edit " : "View ") + snapshot.reason().toLowerCase(Locale.ROOT) + ": " + list.targetName));
        session.inventory = inventory;
        for (int slot = 41; slot <= 44; slot++) inventory.setItem(slot, GuiIcons.icon(GuiIcons.Icon.FILLER, " ", List.of()));
        // The held hotbar item sits in the main-hand cell (as in /invsee); its hotbar cell stays
        // empty and locked, so nothing can be put where the item no longer is.
        for (int pSlot = 0; pSlot < 41; pSlot++) {
            inventory.setItem(InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.INVSEE, pSlot, session.held), contents.inventory()[pSlot]);
        }
        paintIcons(inventory, session.held);
        boolean own = snapshot.player().equals(viewer.getUniqueId());
        List<String> uses = List.of("§7Uses what this panel holds now", "§8Nothing is saved unless you save a MANUAL copy");
        inventory.setItem(EVERYTHING, own
                ? named(Material.CHEST, "§aRestore everything to yourself",
                        join(List.of("§7Replaces your inventory, armor, offhand,", "§7main hand, experience and ender chest"), uses))
                : named(Material.PLAYER_HEAD, "§aRestore everything to " + list.targetName,
                        join(List.of("§7Inventory, armor, offhand, main hand,", "§7experience and ender chest",
                                plugin.getServer().getPlayer(snapshot.player()) == null ? "§eOffline: applied when they next join" : "§7Online now"), uses)));
        inventory.setItem(SAVE, editable
                ? named(Material.WRITABLE_BOOK, "§eSave as manual snapshot", List.of("§7Saves this panel as a new MANUAL",
                "§7snapshot for §f" + list.targetName, "§7The original snapshot stays unchanged"))
                : GuiIcons.icon(GuiIcons.Icon.FILLER, " ", List.of()));
        String owner = own ? "yourself" : list.targetName;
        inventory.setItem(INVENTORY_ONLY, named(Material.CHEST_MINECART, "§bRestore inventory only",
                join(List.of("§7To §f" + owner + "§7: the 36 storage slots", "§7and the main hand"), uses)));
        inventory.setItem(ARMOR_ONLY, named(Material.IRON_CHESTPLATE, "§bRestore armor and offhand only",
                join(List.of("§7To §f" + owner), uses)));
        inventory.setItem(XP_ONLY, named(Material.EXPERIENCE_BOTTLE, "§eRestore experience only",
                List.of("§7To §f" + owner + "§7: level " + snapshot.level())));
        inventory.setItem(TELEPORT, named(Material.ENDER_PEARL, "§dTeleport to the snapshot location",
                List.of("§7" + snapshot.world() + " " + (int) snapshot.x() + ", " + (int) snapshot.y() + ", " + (int) snapshot.z())));
        inventory.setItem(ENDER, named(Material.ENDER_CHEST, "§bEnder chest", List.of("§7View" + (editable ? ", edit" : "")
                + " and restore this snapshot's ender chest")));
        List<String> info = new ArrayList<>(List.of(
                "§7Taken: §f" + RestoreService.formatTime(snapshot.createdAt()),
                "§7World: §f" + snapshot.world() + " §7(" + (int) snapshot.x() + ", " + (int) snapshot.y() + ", " + (int) snapshot.z() + ")",
                "§7Level: §f" + snapshot.level(),
                "§7By: §f" + snapshot.createdBy(),
                editable ? "§aEditable: changes stay in this panel" : "§8Read-only (allium.restore.edit to edit)"));
        if (editable) info.add("§8Shift-click moves a whole stack in or out");
        inventory.setItem(INFO, named(Material.PAPER, "§e" + snapshot.reason(), info));
        inventory.setItem(BACK, named(Material.ARROW, "§cBack", List.of("§7Return to the history", "§8Discards panel edits")));
        viewer.openInventory(inventory);
        if (!own) paintTargetSkin(session, list.targetName);
    }

    /**
     * Gives the "Restore everything to &lt;player&gt;" head the target's skin once it is known: at once for
     * an online target, otherwise after a cached, rate-limited lookup off the server thread. The panel
     * opens with the plain head and keeps it when no skin can be found.
     */
    private void paintTargetSkin(SessionMenu session, String targetName) {
        SkullProfiles profiles = skulls.get();
        if (profiles == null) return;
        java.util.concurrent.CompletableFuture<SkullProfiles.Resolved> lookup;
        try {
            lookup = profiles.resolve(targetName);
        } catch (RuntimeException invalidName) {
            return;
        }
        lookup.whenComplete((resolved, error) -> plugin.getScheduler().runOnServer(() -> {
            if (error != null || resolved == null || resolved.degraded() || session.inventory == null) return;
            ItemStack head = session.inventory.getItem(EVERYTHING);
            if (head == null || head.getType() != Material.PLAYER_HEAD || !(head.getItemMeta() instanceof SkullMeta meta)) return;
            meta.setOwnerProfile(resolved.profile());
            head.setItemMeta(meta);
            session.inventory.setItem(EVERYTHING, head);
        }));
    }

    private void openEnder(Player viewer, SessionMenu session) {
        EnderMenu menu = new EnderMenu(session);
        Inventory inventory = plugin.getServer().createInventory(menu, 36, title("Ender chest: " + session.targetName));
        menu.inventory = inventory;
        for (int slot = 0; slot < 27; slot++) inventory.setItem(slot, session.ender[slot]);
        for (int slot = 27; slot < 36; slot++) inventory.setItem(slot, GuiIcons.icon(GuiIcons.Icon.FILLER, " ", List.of()));
        boolean own = session.snapshot.player().equals(viewer.getUniqueId());
        if (!own) inventory.setItem(ENDER_TO_OWNER, named(Material.ENDER_CHEST, "§aRestore ender chest to " + session.targetName,
                List.of("§7Replaces their ender chest with this panel")));
        inventory.setItem(ENDER_TO_SELF, named(Material.CHEST, "§aRestore ender chest to yourself",
                List.of("§7Replaces your ender chest with this panel")));
        inventory.setItem(ENDER_BACK, named(Material.ARROW, "§cBack", List.of("§7Return to the restore panel")));
        session.switching = true;
        viewer.openInventory(inventory);
        session.switching = false;
    }

    // -- Clicks ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Menu) || !(event.getWhoClicked() instanceof Player viewer)) return;
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        boolean inTop = raw >= 0 && raw < topSize;
        if (holder instanceof ListMenu list) {
            event.setCancelled(true);
            if (!inTop) return;
            if (raw < list.shown.size()) openSession(viewer, list, list.shown.get(raw));
            else if (raw == 45 && list.page > 0) open(viewer, list.target, list.targetName, list.page - 1);
            else if (raw == 53 && (list.page + 1) * PAGE_SIZE < list.total) open(viewer, list.target, list.targetName, list.page + 1);
            return;
        }
        if (holder instanceof SessionMenu session) {
            boolean mainHand = raw == MAIN_HAND && InspectionLayout.validHeld(session.held);
            if (inTop && (raw >= 41 && !mainHand || raw == InspectionLayout.heldMarkerGuiSlot(InspectionLayout.InspectionType.INVSEE, session.held))) {
                event.setCancelled(true);
                if (raw >= 45) control(viewer, session, raw);
                return;
            }
            contentClick(event, viewer, session.editable, inTop, raw, event.getView().getTopInventory(), STORAGE_CELLS,
                    InspectionLayout.heldMarkerGuiSlot(InspectionLayout.InspectionType.INVSEE, session.held));
            if (inTop) plugin.getScheduler().runOnServer(() -> paintIcons(session.inventory, session.held));
            return;
        }
        if (holder instanceof EnderMenu ender) {
            if (inTop && raw >= 27) {
                event.setCancelled(true);
                enderControl(viewer, ender, raw);
                return;
            }
            contentClick(event, viewer, ender.session.editable, inTop, raw, event.getView().getTopInventory(), 27, -1);
        }
    }

    /**
     * Content cells: vanilla moves for editors, never into controls and never picking up an icon.
     * Shift-click moves a whole stack: out of the panel into the viewer's inventory, or from the
     * viewer's inventory into the panel's first {@code storageCells} cells (never armor or controls).
     */
    private void contentClick(InventoryClickEvent event, Player viewer, boolean editable, boolean inTop, int raw, Inventory top,
                              int storageCells, int lockedCell) {
        InventoryAction action = event.getAction();
        if (!editable) {
            if (inTop || action == InventoryAction.MOVE_TO_OTHER_INVENTORY || action == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            // Collecting could pull from control cells.
            event.setCancelled(true);
            return;
        }
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            Inventory from = inTop ? top : event.getClickedInventory();
            int slot = inTop ? raw : event.getSlot();
            ItemStack stack = from == null ? null : from.getItem(slot);
            if (stack == null || stack.getType().isAir() || GuiIcons.isIcon(stack)) {
                return;
            }
            int remaining = inTop
                    ? viewer.getInventory().addItem(stack.clone()).values().stream().mapToInt(ItemStack::getAmount).sum()
                    : codes.castled.allium.gui.PanelMoves.moveInto(top, storageCells, lockedCell, stack.clone());
            if (remaining <= 0) {
                from.setItem(slot, null);
            } else {
                ItemStack rest = stack.clone();
                rest.setAmount(remaining);
                from.setItem(slot, rest);
            }
            plugin.getScheduler().runOnServer(viewer::updateInventory);
            return;
        }
        if (inTop && GuiIcons.isIcon(top.getItem(raw))) {
            event.setCancelled(true);
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()
                    && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)) {
                int amount = event.getClick() == ClickType.RIGHT ? 1 : cursor.getAmount();
                ItemStack placed = cursor.clone();
                placed.setAmount(amount);
                top.setItem(raw, placed);
                ItemStack rest = cursor.clone();
                rest.setAmount(cursor.getAmount() - amount);
                viewer.setItemOnCursor(rest.getAmount() <= 0 ? null : rest);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Menu)) return;
        Inventory top = event.getView().getTopInventory();
        boolean editable = holder instanceof SessionMenu s ? s.editable : holder instanceof EnderMenu e && e.session.editable;
        int contentEnd = holder instanceof SessionMenu s && InspectionLayout.validHeld(s.held) ? 42
                : holder instanceof SessionMenu ? 41 : holder instanceof EnderMenu ? 27 : 0;
        int lockedCell = holder instanceof SessionMenu s ? InspectionLayout.heldMarkerGuiSlot(InspectionLayout.InspectionType.INVSEE, s.held) : -1;
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize() && (!editable || raw >= contentEnd || raw == lockedCell || GuiIcons.isIcon(top.getItem(raw)))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        // Closing (or leaving) the ender panel keeps its edits in the restore panel's working copy.
        if (event.getInventory().getHolder() instanceof EnderMenu ender) {
            ender.session.ender = capture(ender.inventory, 27);
        }
    }

    private void control(Player viewer, SessionMenu session, int slot) {
        SnapshotRepository.Snapshot snapshot = session.snapshot;
        switch (slot) {
            case EVERYTHING -> restoreToOwner(viewer, session, EnumSet.allOf(RestoreService.Part.class));
            case SAVE -> {
                if (!session.editable) return;
                RestoreService.Contents edited = contents(session);
                restore.save(restore.manual(snapshot, edited, viewer.getName())).whenComplete((ok, error) ->
                        plugin.getScheduler().runOnServer(() -> plugin.getMessageManager().send(viewer,
                                error == null ? Messages.RESTORE_SAVED : Messages.RESTORE_FAILED, "{player}", session.targetName)));
            }
            case INVENTORY_ONLY -> restoreToOwner(viewer, session, EnumSet.of(RestoreService.Part.INVENTORY));
            case ARMOR_ONLY -> restoreToOwner(viewer, session, EnumSet.of(RestoreService.Part.ARMOR));
            case XP_ONLY -> restoreToOwner(viewer, session, EnumSet.of(RestoreService.Part.EXPERIENCE));
            case TELEPORT -> {
                World world = plugin.getServer().getWorld(snapshot.world());
                if (world == null) {
                    plugin.getMessageManager().send(viewer, Messages.RESTORE_FAILED);
                    return;
                }
                viewer.closeInventory();
                viewer.teleport(new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch()));
                plugin.getMessageManager().send(viewer, Messages.RESTORE_TELEPORTED);
            }
            case ENDER -> openEnder(viewer, session);
            case BACK -> open(viewer, session.back.target, session.back.targetName, session.back.page);
            default -> {
            }
        }
    }

    private void enderControl(Player viewer, EnderMenu ender, int slot) {
        SessionMenu session = ender.session;
        session.ender = capture(ender.inventory, 27);
        boolean own = session.snapshot.player().equals(viewer.getUniqueId());
        switch (slot) {
            case ENDER_TO_OWNER -> {
                if (!own) restoreToOwner(viewer, session, EnumSet.of(RestoreService.Part.ENDER_CHEST));
            }
            case ENDER_TO_SELF -> {
                if (!own && !viewer.hasPermission(RestoreService.PERMISSION_EDIT)) {
                    plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION, "{cmd}", "restore to yourself");
                    return;
                }
                restoreTo(viewer, viewer.getUniqueId(), viewer.getName(), session, EnumSet.of(RestoreService.Part.ENDER_CHEST));
            }
            case ENDER_BACK -> {
                ender.switching = true;
                viewer.openInventory(session.inventory);
                ender.switching = false;
            }
            default -> {
            }
        }
    }

    private void restoreToOwner(Player viewer, SessionMenu session, Set<RestoreService.Part> parts) {
        UUID owner = session.snapshot.player();
        if (!owner.equals(viewer.getUniqueId()) && !viewer.hasPermission(RestoreService.PERMISSION_OTHERS)) {
            plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION, "{cmd}", "restore others");
            return;
        }
        restoreTo(viewer, owner, session.targetName, session, parts);
    }

    /** Replaces {@code parts} of {@code target} with the panel's current contents (queued when offline). */
    private void restoreTo(Player viewer, UUID target, String targetName, SessionMenu session, Set<RestoreService.Part> parts) {
        RestoreService.Contents contents = contents(session);
        String described = RestoreService.Part.describe(parts);
        Player online = plugin.getServer().getPlayer(target);
        if (online == null) {
            SnapshotRepository.Snapshot frozen = restore.manual(session.snapshot, contents, viewer.getName());
            restore.queue(target, frozen, parts, viewer.getName()).whenComplete((ok, error) -> plugin.getScheduler().runOnServer(() -> {
                plugin.getMessageManager().send(viewer, error == null ? Messages.RESTORE_QUEUED : Messages.RESTORE_FAILED,
                        "{player}", targetName, "{parts}", described);
                if (error == null) plugin.logger().info("[RestoreAudit] " + viewer.getName() + " queued a restore of " + described
                        + " for offline " + targetName + " from snapshot " + session.snapshot.id());
            }));
            return;
        }
        if (online == viewer) {
            // The viewer's own inventory is about to be replaced: close the panel first.
            viewer.closeInventory();
        }
        if (!restore.apply(online, contents, parts)) {
            plugin.getMessageManager().send(viewer, Messages.RESTORE_BUSY, "{player}", online.getName());
            return;
        }
        plugin.getMessageManager().send(online, Messages.RESTORE_APPLIED, "{parts}", described, "{reason}", session.snapshot.reason(),
                "{time}", RestoreService.formatTime(session.snapshot.createdAt()));
        if (online != viewer) plugin.getMessageManager().send(viewer, Messages.RESTORE_APPLIED_OTHER, "{player}", online.getName(),
                "{parts}", described);
        plugin.logger().info("[RestoreAudit] " + viewer.getName() + " restored " + described + " to " + online.getName()
                + " from snapshot " + session.snapshot.id() + " (" + session.snapshot.reason() + ")");
    }

    // -- Helpers -----------------------------------------------------------------------------------

    private RestoreService.Contents contents(SessionMenu session) {
        ItemStack[] inventory = new ItemStack[41];
        for (int pSlot = 0; pSlot < 41; pSlot++) {
            ItemStack stack = session.inventory.getItem(InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.INVSEE, pSlot, session.held));
            inventory[pSlot] = GuiIcons.isIcon(stack) || stack == null || stack.getType().isAir() ? null : stack.clone();
        }
        ItemStack[] ender = new ItemStack[27];
        for (int i = 0; i < 27; i++) ender[i] = session.ender[i] == null ? null : session.ender[i].clone();
        return new RestoreService.Contents(inventory, ender, session.level, session.progress, session.held);
    }

    private static ItemStack[] capture(Inventory inventory, int size) {
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            out[i] = GuiIcons.isIcon(stack) || stack == null || stack.getType().isAir() ? null : stack.clone();
        }
        return out;
    }

    /** Empty armor, offhand and main-hand cells show their slot icon. */
    private static void paintIcons(Inventory inventory, int held) {
        ItemStack hand = inventory.getItem(MAIN_HAND);
        if (hand == null || hand.getType().isAir() || !InspectionLayout.validHeld(held)) {
            inventory.setItem(MAIN_HAND, GuiIcons.icon(GuiIcons.Icon.SWORD, "§7Main hand", InspectionLayout.validHeld(held)
                    ? List.of("§7Empty: hotbar slot §f" + (held + 1) + "§7 was selected", "§8Place an item here to put it in the hand")
                    : List.of("§8Not recorded for this snapshot")));
        }
        for (int pSlot = 36; pSlot <= 40; pSlot++) {
            int gui = InspectionLayout.toGuiSlot(InspectionLayout.InspectionType.INVSEE, pSlot);
            ItemStack current = inventory.getItem(gui);
            if (current == null || current.getType().isAir()) {
                inventory.setItem(gui, GuiIcons.icon(GuiIcons.equipmentIcon(pSlot), "§7Empty "
                        + InspectionLayout.slotLabel(InspectionLayout.InspectionType.INVSEE, pSlot).replace("armor[", "").replace("]", ""),
                        List.of("§8Place an item here")));
            }
        }
    }

    private ItemStack snapshotIcon(SnapshotRepository.Snapshot snapshot) {
        String reason = snapshot.reason().toUpperCase(Locale.ROOT);
        Material material = reason.startsWith("DEATH") ? Material.SKELETON_SKULL
                : reason.startsWith("LOGIN") ? Material.GREEN_BED
                : reason.startsWith("LOGOUT") ? Material.IRON_DOOR
                : reason.startsWith("AUTOSAVE") ? Material.CLOCK
                : reason.startsWith("WORLD") ? Material.ENDER_EYE
                : reason.startsWith("GAMEMODE") ? Material.COMMAND_BLOCK
                : reason.startsWith("MANUAL") ? Material.WRITABLE_BOOK
                : Material.CHEST;
        return named(material, "§e" + snapshot.reason(), List.of(
                "§7Taken: §f" + RestoreService.formatTime(snapshot.createdAt()),
                "§7World: §f" + snapshot.world(),
                "§7Level: §f" + snapshot.level(),
                "",
                "§eClick to open"));
    }

    private static ItemStack named(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static List<String> join(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String title(String text) {
        return text.length() > 32 ? text.substring(0, 32) : text;
    }
}
