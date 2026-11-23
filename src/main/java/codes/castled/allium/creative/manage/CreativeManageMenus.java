package codes.castled.allium.creative.manage;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.gui.GuiIcons;
import codes.castled.allium.item.library.SkullProfiles;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.placeholder.PlaceholderFormats;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The {@code /creativemanager} menus: a paged list of permission groups and players (filtered to
 * groups, players, online or offline players), and for one of them a menu with every creative flag,
 * where a click gives or removes it.
 */
public final class CreativeManageMenus implements Listener {

    /** What the list shows. */
    public enum Filter {
        ALL("Everything"), GROUPS("Groups"), PLAYERS("Players"), ONLINE("Online players"), OFFLINE("Offline players");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        Filter next(boolean back) {
            Filter[] all = values();
            return all[(ordinal() + (back ? all.length - 1 : 1)) % all.length];
        }
    }

    static final int PAGE_SIZE = 45;
    private static final String PERMISSION_INVSEE = "allium.invsee";
    /** Offline players listed, most recently seen first. */
    static final int OFFLINE_LIMIT = 500;
    private static final int PREVIOUS = 45, FILTER = 47, INFO = 49, NEXT = 53;
    private static final int BACK = 45, HOLDER_INFO = 47, REFRESH = 51, CLOSE = 53;
    /** Flag slot for each flag: two rows of nine with their state panes beneath, and bypass on its own. */
    static final int[] FLAG_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 18, 19, 20, 21, 22, 23, 24, 25, 26, 40};

    private final PluginStart plugin;
    private final CreativeAccess access;
    private final Supplier<SkullProfiles> skulls;
    private volatile boolean closed;

    public CreativeManageMenus(PluginStart plugin, CreativeAccess access) {
        this(plugin, access, () -> null);
    }

    /** {@code skulls} gives offline players' heads their skins; null keeps plain heads. */
    public CreativeManageMenus(PluginStart plugin, CreativeAccess access, Supplier<SkullProfiles> skulls) {
        this.plugin = plugin;
        this.access = access;
        this.skulls = skulls;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        closed = true;
        HandlerList.unregisterAll(this);
        if (BukkitServerThread.isFolia() && !plugin.isEnabled()) return;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            UtilitySupport.onOwner(plugin, online, () -> {
                if (online.getOpenInventory().getTopInventory().getHolder() instanceof Menu) online.closeInventory();
            });
        }
    }

    // -- Holders -----------------------------------------------------------------------------------

    private interface Menu extends InventoryHolder {
    }

    /** One row of the list: a group, or a player and whether the viewer sees them online. */
    record Entry(CreativeHolder holder, boolean online, long lastSeen) {
    }

    private static final class ListMenu implements Menu {
        final Filter filter;
        final int page;
        final List<Entry> shown;
        Inventory inventory;

        ListMenu(Filter filter, int page, List<Entry> shown) {
            this.filter = filter;
            this.page = page;
            this.shown = shown;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final class FlagMenu implements Menu {
        final CreativeHolder holder;
        @Nullable final Filter backFilter;
        final int backPage;
        Map<CreativeFlag, FlagValue> values;
        PermissionBackend backend;
        boolean busy;
        Inventory inventory;

        FlagMenu(CreativeHolder holder, @Nullable Filter backFilter, int backPage) {
            this.holder = holder;
            this.backFilter = backFilter;
            this.backPage = backPage;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    // -- List --------------------------------------------------------------------------------------

    /** Opens the list of groups and players at {@code page} for {@code filter}. */
    public void openList(Player viewer, Filter filter, int page) {
        PermissionBackend backend = access.backend();
        List<Entry> entries = entries(viewer, filter, backend);
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        List<Entry> shown = entries.subList(current * PAGE_SIZE, Math.min(entries.size(), (current + 1) * PAGE_SIZE));
        ListMenu menu = new ListMenu(filter, current, List.copyOf(shown));
        Inventory inventory = plugin.getServer().createInventory(menu, 54, "Creative manager: " + filter.label);
        menu.inventory = inventory;
        long now = System.currentTimeMillis();
        boolean invsee = viewer.hasPermission(PERMISSION_INVSEE);
        for (int i = 0; i < shown.size(); i++) {
            inventory.setItem(i, entryIcon(shown.get(i), now, invsee));
        }
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, GuiIcons.icon(GuiIcons.Icon.FILLER, " ", List.of()));
        }
        if (current > 0) {
            inventory.setItem(PREVIOUS, named(Material.ARROW, "§aPrevious page", List.of("§7Page " + current)));
        }
        if (current + 1 < pages) {
            inventory.setItem(NEXT, named(Material.ARROW, "§aNext page", List.of("§7Page " + (current + 2))));
        }
        List<String> filterLore = new ArrayList<>();
        for (Filter option : Filter.values()) {
            filterLore.add((option == filter ? "§a▶ " : "§7  ") + option.label);
        }
        filterLore.add("§8Click: next · right-click: previous");
        inventory.setItem(FILTER, named(Material.HOPPER, "§eShowing: §f" + filter.label, filterLore));
        List<String> info = new ArrayList<>(List.of("§7Permissions: §f" + backend.name(),
                "§7Page §f" + (current + 1) + "§7 of §f" + pages + "§7, " + entries.size() + " listed"));
        if (!backend.canWrite()) {
            info.add("§cRead-only: install LuckPerms or Vault");
            info.add("§cwith a permissions plugin to change flags");
        } else if (!backend.knowsSetNodes()) {
            info.add("§8Vault shows what applies, not where it is set");
        }
        inventory.setItem(INFO, named(Material.BOOK, "§dCreative manager", info));
        viewer.openInventory(inventory);
        List<Integer> offline = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            if (!shown.get(i).holder().isGroup() && !shown.get(i).online()) {
                offline.add(i);
            }
        }
        SkullProfiles profiles = skulls.get();
        if (profiles != null && !offline.isEmpty()) {
            paintSkins(viewer, menu, profiles, offline, 0);
        }
    }

    /**
     * Gives offline players' heads their skins, one lookup at a time (cached ones return at once), and
     * stops as soon as the viewer leaves this page. A skin that cannot be found leaves the plain head.
     */
    private void paintSkins(Player viewer, ListMenu menu, SkullProfiles profiles, List<Integer> slots, int index) {
        if (index >= slots.size() || !viewer.isOnline() || viewer.getOpenInventory().getTopInventory() != menu.inventory) {
            return;
        }
        int slot = slots.get(index);
        CompletableFuture<SkullProfiles.Resolved> lookup;
        try {
            lookup = profiles.resolve(menu.shown.get(slot).holder().name());
        } catch (RuntimeException invalidName) {
            paintSkins(viewer, menu, profiles, slots, index + 1);
            return;
        }
        lookup.whenComplete((resolved, error) -> nextTick(viewer, () -> {
            ItemStack head = menu.inventory.getItem(slot);
            if (error == null && resolved != null && !resolved.degraded() && head != null
                    && head.getType() == Material.PLAYER_HEAD && head.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwnerProfile(resolved.profile());
                head.setItemMeta(skull);
                menu.inventory.setItem(slot, head);
            }
            paintSkins(viewer, menu, profiles, slots, index + 1);
        }));
    }

    /** Groups (sorted), then visible online players (sorted), then other known players (newest first). */
    List<Entry> entries(Player viewer, Filter filter, PermissionBackend backend) {
        List<Entry> out = new ArrayList<>();
        if (filter == Filter.ALL || filter == Filter.GROUPS) {
            for (String group : backend.groups()) {
                out.add(new Entry(CreativeHolder.group(group), false, 0L));
            }
        }
        if (filter == Filter.GROUPS) {
            return out;
        }
        Set<UUID> visibleOnline = new HashSet<>();
        List<Player> online = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (PlayerNameVisibility.canSeeTarget(viewer, player)) {
                online.add(player);
                visibleOnline.add(player.getUniqueId());
            }
        }
        if (filter != Filter.OFFLINE) {
            online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
            for (Player player : online) {
                out.add(new Entry(CreativeHolder.player(player.getUniqueId(), player.getName()), true, System.currentTimeMillis()));
            }
        }
        if (filter != Filter.ONLINE && plugin.getPlayerNames() != null) {
            // Vanished players the viewer cannot see are listed as offline, as everywhere else.
            for (NameCache.Entry known : plugin.getPlayerNames().cache().recent(OFFLINE_LIMIT + visibleOnline.size())) {
                if (!visibleOnline.contains(known.uuid())) {
                    out.add(new Entry(CreativeHolder.player(known.uuid(), known.name()), false, known.lastSeen()));
                }
            }
        }
        return out;
    }

    private ItemStack entryIcon(Entry entry, long now, boolean invsee) {
        CreativeHolder holder = entry.holder();
        if (holder.isGroup()) {
            return named(Material.WRITABLE_BOOK, "§bGroup §f" + holder.name(), List.of("§7Click to edit its creative flags"));
        }
        List<String> lore = new ArrayList<>();
        lore.add(entry.online() ? "§aOnline" : "§8Offline, last seen " + ago(now - entry.lastSeen()));
        lore.add("§7Click to edit their creative flags");
        if (invsee) {
            lore.add("§7Shift-click to open their inventory §8(/invsee)");
        }
        ItemStack head = named(Material.PLAYER_HEAD, (entry.online() ? "§a" : "§7") + holder.name(), lore);
        Player online = entry.online() ? plugin.getServer().getPlayer(holder.uuid()) : null;
        // Only a profile that already carries its skin: a bare one makes the server ask Mojang for it.
        org.bukkit.profile.PlayerProfile profile = online == null ? null : online.getPlayerProfile();
        if (profile != null && profile.getTextures().getSkin() != null && head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwnerProfile(profile);
            head.setItemMeta(skull);
        }
        return head;
    }

    private static String ago(long millis) {
        String full = PlaceholderFormats.duration(Math.max(0L, millis) / 1000L);
        String[] parts = full.split(" ");
        return (parts.length > 2 ? parts[0] + " " + parts[1] : full) + " ago";
    }

    // -- Flags -------------------------------------------------------------------------------------

    /**
     * Reads {@code holder}'s flags and opens their menu. {@code backFilter} is the list to return to
     * (null: the back button closes).
     */
    public void openFlags(Player viewer, CreativeHolder holder, @Nullable Filter backFilter, int backPage) {
        FlagMenu menu = new FlagMenu(holder, backFilter, backPage);
        access.read(viewer, holder, (values, error) -> {
            if (closed || !viewer.isOnline()) {
                return;
            }
            if (error != null) {
                failed(viewer, holder, error);
                return;
            }
            menu.values = values;
            menu.backend = access.backend();
            String title = (holder.isGroup() ? "Creative group: " : "Creative: ") + holder.name();
            menu.inventory = plugin.getServer().createInventory(menu, 54, title.length() > 32 ? title.substring(0, 32) : title);
            paint(menu);
            viewer.openInventory(menu.inventory);
        });
    }

    private void paint(FlagMenu menu) {
        Inventory inventory = menu.inventory;
        for (int slot = 0; slot < 54; slot++) {
            inventory.setItem(slot, null);
        }
        CreativeFlag[] flags = CreativeFlag.values();
        for (int i = 0; i < flags.length; i++) {
            FlagValue value = menu.values.get(flags[i]);
            inventory.setItem(FLAG_SLOTS[i], flagIcon(menu, flags[i], value));
            inventory.setItem(FLAG_SLOTS[i] + 9, statePane(menu, flags[i], value));
        }
        for (int slot = 45; slot < 54; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, GuiIcons.icon(GuiIcons.Icon.FILLER, " ", List.of()));
            }
        }
        inventory.setItem(BACK, named(Material.ARROW, menu.backFilter == null ? "§cClose" : "§cBack", List.of(
                menu.backFilter == null ? "§7Close this menu" : "§7Return to the list")));
        List<String> info = new ArrayList<>(List.of("§7" + (menu.holder.isGroup() ? "Group" : "Player") + ": §f" + menu.holder.name(),
                "§7Permissions: §f" + menu.backend.name()));
        if (!menu.backend.canWrite()) {
            info.add("§cRead-only: install LuckPerms or Vault");
        } else if (menu.backend.knowsSetNodes()) {
            info.add("§8Changes are global nodes, logged in /lp log");
        }
        if (menu.busy) {
            info.add("§eSaving...");
        }
        inventory.setItem(HOLDER_INFO, named(menu.holder.isGroup() ? Material.WRITABLE_BOOK : Material.PLAYER_HEAD,
                "§d" + menu.holder.describe(), info));
        inventory.setItem(REFRESH, named(Material.CLOCK, "§eRefresh", List.of("§7Read the flags again")));
        inventory.setItem(CLOSE, named(Material.BARRIER, "§cClose", List.of()));
    }

    private static ItemStack flagIcon(FlagMenu menu, CreativeFlag flag, FlagValue value) {
        List<String> lore = new ArrayList<>();
        lore.add("§7" + flag.description());
        lore.add("§8" + flag.node());
        lore.add("");
        if (value.setKnown()) {
            lore.add("§7Set here: " + (Boolean.TRUE.equals(value.set()) ? "§ayes"
                    : Boolean.FALSE.equals(value.set()) ? "§cdenied" : "§7no"));
        }
        lore.add("§7Applies: " + color(value.describeEffective()));
        lore.add("");
        lore.add(clickHint(menu, value));
        ItemStack icon = named(flag.icon(), (Boolean.TRUE.equals(value.effective()) ? "§a" : "§c") + flag.key(), lore);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack statePane(FlagMenu menu, CreativeFlag flag, FlagValue value) {
        Material material;
        String name;
        if (value.setKnown() && Boolean.TRUE.equals(value.set())) {
            material = Material.LIME_STAINED_GLASS_PANE;
            name = "§aGiven here";
        } else if (value.setKnown() && Boolean.FALSE.equals(value.set())) {
            material = Material.RED_STAINED_GLASS_PANE;
            name = "§cDenied here";
        } else if (Boolean.TRUE.equals(value.effective())) {
            material = value.setKnown() ? Material.CYAN_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
            name = value.setKnown() ? "§3Allowed, not set here" : "§aAllowed";
        } else {
            material = Material.GRAY_STAINED_GLASS_PANE;
            name = "§7Not allowed";
        }
        return named(material, name, List.of("§7" + flag.key() + ": " + color(value.describeEffective()), clickHint(menu, value)));
    }

    private static String clickHint(FlagMenu menu, FlagValue value) {
        if (!menu.backend.canWrite()) {
            return "§8Read-only: needs LuckPerms or Vault";
        }
        return value.givenHere() ? "§eClick to remove it" : "§aClick to give it";
    }

    // -- Clicks ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Menu) || !(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= 54) {
            return;
        }
        if (!viewer.hasPermission(CreativeAccess.PERMISSION_MANAGE)) {
            nextTick(viewer, viewer::closeInventory);
            plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION);
            return;
        }
        boolean right = event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT;
        // Opening another inventory inside the click event is unsafe; do it on the next tick.
        if (holder instanceof ListMenu list) {
            if (raw < list.shown.size()) {
                Entry entry = list.shown.get(raw);
                if (event.isShiftClick() && !entry.holder().isGroup()) {
                    nextTick(viewer, () -> openInvsee(viewer, entry.holder(), list.filter, list.page));
                } else {
                    nextTick(viewer, () -> openFlags(viewer, entry.holder(), list.filter, list.page));
                }
            } else if (raw == PREVIOUS && list.page > 0) {
                nextTick(viewer, () -> openList(viewer, list.filter, list.page - 1));
            } else if (raw == NEXT) {
                nextTick(viewer, () -> openList(viewer, list.filter, list.page + 1));
            } else if (raw == FILTER) {
                nextTick(viewer, () -> openList(viewer, list.filter.next(right), 0));
            }
            return;
        }
        if (holder instanceof FlagMenu menu) {
            flagClick(viewer, menu, raw);
        }
    }

    private void flagClick(Player viewer, FlagMenu menu, int raw) {
        if (raw == BACK) {
            nextTick(viewer, () -> {
                if (menu.backFilter == null) {
                    viewer.closeInventory();
                } else {
                    openList(viewer, menu.backFilter, menu.backPage);
                }
            });
            return;
        }
        if (raw == CLOSE) {
            nextTick(viewer, viewer::closeInventory);
            return;
        }
        if (raw == REFRESH) {
            reload(viewer, menu);
            return;
        }
        CreativeFlag flag = flagAt(raw);
        if (flag == null || menu.busy) {
            return;
        }
        if (!menu.backend.canWrite()) {
            plugin.getMessageManager().send(viewer, Messages.CREATIVE_MANAGE_UNAVAILABLE);
            return;
        }
        boolean give = !menu.values.get(flag).givenHere();
        menu.busy = true;
        paint(menu);
        access.write(viewer, menu.holder, flag, give, error -> {
            menu.busy = false;
            if (error != null) {
                failed(viewer, menu.holder, error);
            } else {
                plugin.getMessageManager().send(viewer, give ? Messages.CREATIVE_MANAGE_ADDED : Messages.CREATIVE_MANAGE_REMOVED,
                        "{flag}", flag.key(), "{holder}", menu.holder.describe());
            }
            reload(viewer, menu);
        });
    }

    /**
     * Opens {@code player}'s inventory exactly as {@code /invsee <name>} would: the same permission
     * ({@code allium.invsee}, plus {@code .offline} and {@code .edit}), vanish and audit rules.
     */
    private void openInvsee(Player viewer, CreativeHolder player, Filter filter, int page) {
        if (!viewer.isOnline()) {
            return;
        }
        boolean disabled = plugin.getCommandManager() != null && plugin.getCommandManager().getFamilyPolicy().isDisabled("invsee");
        if (disabled || !(plugin.getModuleManager().getModule("moderation") instanceof codes.castled.allium.module.ModerationModule moderation)
                || !moderation.isEnabled() || moderation.inspection() == null) {
            plugin.getMessageManager().send(viewer, Messages.MODULE_DISABLED);
            return;
        }
        moderation.inspection().dispatchInvsee(viewer, new String[]{player.name()},
                new codes.castled.allium.moderation.InspectionService.BackButton("Return to the creative manager",
                        () -> openList(viewer, filter, page)));
    }

    /** The flag whose icon or state pane is at {@code raw}, or null. */
    @Nullable
    static CreativeFlag flagAt(int raw) {
        CreativeFlag[] flags = CreativeFlag.values();
        for (int i = 0; i < FLAG_SLOTS.length; i++) {
            if (raw == FLAG_SLOTS[i] || raw == FLAG_SLOTS[i] + 9) {
                return flags[i];
            }
        }
        return null;
    }

    private void reload(Player viewer, FlagMenu menu) {
        access.read(viewer, menu.holder, (values, error) -> {
            if (closed) return;
            if (error != null) {
                failed(viewer, menu.holder, error);
                return;
            }
            menu.values = values;
            menu.backend = access.backend();
            paint(menu);
        });
    }

    private void nextTick(Player viewer, Runnable action) {
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), viewer, () -> {
            if (!closed && viewer.isOnline()) action.run();
        }, () -> {}, 0L);
    }

    private void failed(Player viewer, CreativeHolder holder, Throwable error) {
        if (error instanceof IllegalArgumentException && holder.isGroup()) {
            plugin.getMessageManager().send(viewer, Messages.CREATIVE_MANAGE_UNKNOWN_GROUP, "{group}", holder.name());
        } else if (error instanceof UnsupportedOperationException) {
            plugin.getMessageManager().send(viewer, Messages.CREATIVE_MANAGE_UNAVAILABLE);
        } else {
            plugin.getMessageManager().send(viewer, Messages.CREATIVE_MANAGE_FAILED, "{holder}", holder.describe(),
                    "{reason}", String.valueOf(error.getMessage()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // -- Items -------------------------------------------------------------------------------------

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
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
}
