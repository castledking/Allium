package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Java 8 / 1.12.2-1.19.4 port of {@code /creativemanager} ({@code /cm}, {@code /allium creativemanager}):
 * paged list &amp; flag-editor GUIs, text subcommands ({@code edit|list|check|add|remove}), and
 * reflective LuckPerms / Vault / ReadOnly permission backend.
 */
final class LegacyCreativeManage implements Listener {

    static final String PERMISSION_MANAGE = "allium.gamemode.creative.manage";
    private static final String PERMISSION_INVSEE = "allium.invsee";
    private static final List<String> ACTIONS = Arrays.asList("edit", "list", "check", "add", "remove");
    private static final int PAGE_SIZE = 45;
    private static final int OFFLINE_LIMIT = 500;
    private static final int PREVIOUS = 45, FILTER = 47, INFO = 49, NEXT = 53;
    private static final int BACK = 45, HOLDER_INFO = 47, REFRESH = 51, CLOSE = 53;
    private static final int[] FLAG_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 18, 19, 20, 21, 22, 23, 24, 25, 26, 40};

    enum Flag {
        PLACE("place", "GRASS_BLOCK", "GRASS", "Place blocks"),
        BREAK("break", "IRON_PICKAXE", null, "Break blocks"),
        INTERACT("interact", "LEVER", null, "Doors, beds, item frames, mobs, food and projectiles"),
        USE("use", "COMPASS", null, "Take items from the creative menu"),
        DROP("drop", "DROPPER", null, "Drop items"),
        PICKUP("pickup", "HOPPER", null, "Pick up items"),
        CONTAINER("container", "CHEST", null, "Open chests, furnaces, crafting tables and villagers"),
        SPAWN("spawn", "ZOMBIE_SPAWN_EGG", "MONSTER_EGG", "Use spawn eggs"),
        PICKBLOCK("pickblock", "SPYGLASS", "EYE_OF_ENDER", "Pick block (Paper 1.21.4 and later)"),
        BLACKLIST("blacklist", "BARRIER", null, "Ignore creative-manager.blacklist and protect-tnt"),
        FILLED_CONTAINERS("filled-containers", "SHULKER_BOX", "PURPLE_SHULKER_BOX", "Place containers that already hold items"),
        LOOT("loot", "GOLD_INGOT", null, "Normal drops from blocks placed in creative"),
        UNTAGGED("untagged", "NAME_TAG", null, "Creative-menu items without the creative lore"),
        ANY_ITEM("any-item", "ENCHANTED_BOOK", null, "Take enchanted or unsanitized items from the menu"),
        KEEP_EFFECTS("keep-effects", "BREWING_STAND", "BREWING_STAND_ITEM", "Keep potion effects when switching gamemode"),
        OWN_ARMOR("own-armor", "IRON_CHESTPLATE", null, "Keep their own armour in creative"),
        OWN_GLOW("own-glow", "GLOWSTONE_DUST", null, "Keep their own glow in restricted creative"),
        INVENTORY("inventory", "ENDER_CHEST", null, "One shared inventory across gamemodes"),
        BYPASS("bypass", "NETHER_STAR", null, "Unrestricted creative: every flag above except inventory");

        final String key;
        final String modernIcon;
        final String legacyIcon;
        final String description;

        Flag(String key, String modernIcon, String legacyIcon, String description) {
            this.key = key;
            this.modernIcon = modernIcon;
            this.legacyIcon = legacyIcon;
            this.description = description;
        }

        String node() {
            return LegacyCreativeManager.PERMISSION_PREFIX + key;
        }

        boolean coveredByBypass() {
            return this != BYPASS && this != INVENTORY;
        }

        static Flag byKey(String raw) {
            if (raw == null) return null;
            String wanted = raw.trim().toLowerCase(Locale.ROOT);
            for (Flag f : values()) {
                if (f.key.equals(wanted)) return f;
            }
            return null;
        }

        static List<String> keys() {
            List<String> out = new ArrayList<String>(values().length);
            for (Flag f : values()) out.add(f.key);
            return out;
        }
    }

    enum Filter {
        ALL("Everything"),
        GROUPS("Groups"),
        PLAYERS("Players"),
        ONLINE("Online players"),
        OFFLINE("Offline players");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        Filter next(boolean back) {
            Filter[] all = values();
            return all[(ordinal() + (back ? all.length - 1 : 1)) % all.length];
        }
    }

    static final class Holder {
        final boolean group;
        final UUID uuid;
        final String name;

        private Holder(boolean group, UUID uuid, String name) {
            this.group = group;
            this.uuid = uuid;
            this.name = name;
        }

        static Holder group(String name) {
            return new Holder(true, null, name);
        }

        static Holder player(UUID uuid, String name) {
            return new Holder(false, uuid, name);
        }

        String describe() {
            return (group ? "group " : "") + name;
        }
    }

    static final class FlagValue {
        final Boolean set;
        final Boolean effective;
        final boolean setKnown;
        final String source;

        FlagValue(Boolean set, Boolean effective, boolean setKnown, String source) {
            this.set = set;
            this.effective = effective;
            this.setKnown = setKnown;
            this.source = source == null ? "" : source;
        }

        boolean givenHere() {
            return setKnown ? Boolean.TRUE.equals(set) : Boolean.TRUE.equals(effective);
        }

        String describeEffective() {
            String state = effective == null ? "&7unknown" : (effective.booleanValue() ? "&aallowed" : "&cnot allowed");
            return source.isEmpty() ? state : state + " &7(" + source + ")";
        }
    }

    private interface Backend {
        String name();
        boolean canWrite();
        boolean knowsSetNodes();
        List<String> groups();
        String findGroup(String name);
        Map<Flag, FlagValue> read(Holder holder);
        void write(Holder holder, Flag flag, boolean give, CommandSender actor);
    }

    private interface Menu extends InventoryHolder {}

    private static final class Entry {
        final Holder holder;
        final boolean online;
        final long lastSeen;

        Entry(Holder holder, boolean online, long lastSeen) {
            this.holder = holder;
            this.online = online;
            this.lastSeen = lastSeen;
        }
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
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class FlagMenu implements Menu {
        final Holder holder;
        final Filter backFilter;
        final int backPage;
        Map<Flag, FlagValue> values;
        Backend backend;
        Inventory inventory;

        FlagMenu(Holder holder, Filter backFilter, int backPage) {
            this.holder = holder;
            this.backFilter = backFilter;
            this.backPage = backPage;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final LegacyPlayerInfoModule playerInfo;
    private final LegacyModerationModule moderation;
    private final LegacyCommandManager commandManager;
    private final BiPredicate<CommandSender, Player> canSeeTarget;

    LegacyCreativeManage(JavaPlugin plugin, LegacyMessageManager messages, LegacyPlayerInfoModule playerInfo,
                         LegacyModerationModule moderation, LegacyCommandManager commandManager,
                         BiPredicate<CommandSender, Player> canSeeTarget) {
        this.plugin = plugin;
        this.messages = messages;
        this.playerInfo = playerInfo;
        this.moderation = moderation;
        this.commandManager = commandManager;
        this.canSeeTarget = canSeeTarget;
    }

    void disable() {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            try {
                if (online.getOpenInventory() != null && online.getOpenInventory().getTopInventory() != null
                        && online.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                    online.closeInventory();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== Command & Tab Completion ====================

    boolean execute(CommandSender sender, String root, String[] args) {
        if (!sender.hasPermission(PERMISSION_MANAGE) && !sender.isOp()) {
            messages.send(sender, "no-permission",
                    "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", "creativemanager");
            return true;
        }
        String sub = args.length == 0 ? "gui" : args[0].toLowerCase(Locale.ROOT);
        if ("gui".equals(sub) || "active".equals(sub)) {
            if (args.length > 1) {
                LegacyUsageCards.invalid(sender, root + " " + sub);
            } else if (!(sender instanceof Player)) {
                messages.send(sender, "player-only", "{prefix} &cThis command can only be run by a player.");
            } else {
                openList((Player) sender, "active".equals(sub) ? Filter.PLAYERS : Filter.ALL, 0);
            }
            return true;
        }
        if ("player".equals(sub) || "group".equals(sub)) {
            handleHolderCommand(sender, "group".equals(sub), Arrays.copyOfRange(args, 1, args.length), root);
            return true;
        }
        LegacyUsageCards.invalid(sender, root);
        return true;
    }

    private void handleHolderCommand(CommandSender sender, boolean group, String[] args, String root) {
        String form = root + " " + (group ? "group" : "player");
        if (args.length < 1 || args.length > 3) {
            LegacyUsageCards.invalid(sender, form);
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "edit";
        if (!ACTIONS.contains(action)) {
            LegacyUsageCards.invalid(sender, form);
            return;
        }
        boolean fits = ("add".equals(action) || "remove".equals(action))
                ? args.length == 3
                : ("check".equals(action) || args.length <= 2);
        if (!fits) {
            String syntax = "edit".equals(action) ? "[edit]"
                    : ("check".equals(action) ? "check [flag]"
                            : ("list".equals(action) ? "list" : action + " <flag>"));
            LegacyUsageCards.invalid(sender, form + (group ? " <group> " : " <player> ") + syntax);
            return;
        }
        Flag flag = null;
        if (args.length == 3) {
            flag = Flag.byKey(args[2]);
            if (flag == null) {
                messages.send(sender, "creative.manage.unknown-flag",
                        "{prefix} &cUnknown creative flag &f{flag}&c. Flags: &f{flags}",
                        "{flag}", args[2], "{flags}", joinStrings(Flag.keys(), ", "));
                return;
            }
        }
        if ("edit".equals(action) && !(sender instanceof Player)) {
            messages.send(sender, "player-only", "{prefix} &cThis command can only be run by a player.");
            return;
        }
        Holder holder = resolveHolder(sender, group, args[0]);
        if (holder == null) return;

        Backend backend = currentBackend();
        if ("edit".equals(action)) {
            openFlags((Player) sender, holder, null, 0);
            return;
        }
        try {
            Map<Flag, FlagValue> values = resolveEffective(holder, backend.read(holder), backend);
            if ("list".equals(action)) {
                List<String> shown = listedFlags(values);
                if (shown.isEmpty()) {
                    messages.send(sender, "creative.manage.list-empty",
                            "{prefix} &7No creative flags are set on &f{holder} &7({backend}).",
                            "{holder}", holder.describe(), "{backend}", backend.name());
                } else {
                    messages.send(sender, "creative.manage.list",
                            "{prefix} &dCreative flags on &f{holder} &7({backend})&d: &f{flags}",
                            "{holder}", holder.describe(), "{backend}", backend.name(),
                            "{flags}", joinStrings(shown, ", "));
                }
                return;
            }
            if ("check".equals(action)) {
                List<Flag> list = flag == null ? Arrays.asList(Flag.values()) : Collections.singletonList(flag);
                for (Flag each : list) {
                    messages.send(sender, "creative.manage.check",
                            "{prefix} &f{flag}&7: {state}",
                            "{flag}", each.key, "{state}", values.get(each).describeEffective());
                }
                return;
            }
            boolean give = "add".equals(action);
            if (!backend.canWrite()) {
                messages.send(sender, "creative.manage.unavailable",
                        "{prefix} &cChanging creative flags needs LuckPerms, or Vault with a permissions plugin.");
                return;
            }
            FlagValue current = values.get(flag);
            if (current.setKnown) {
                if (give && Boolean.TRUE.equals(current.set)) {
                    messages.send(sender, "creative.manage.already",
                            "{prefix} &f{holder} &ealready has &f{flag} &eset.",
                            "{holder}", holder.describe(), "{flag}", flag.key);
                    return;
                }
                if (!give && current.set == null) {
                    messages.send(sender, "creative.manage.not-set",
                            "{prefix} &f{holder} &ehas no &f{flag} &eflag of its own to remove.",
                            "{holder}", holder.describe(), "{flag}", flag.key);
                    return;
                }
            }
            backend.write(holder, flag, give, sender);
            plugin.getLogger().info("Creative manager: " + sender.getName() + (give ? " gave " : " removed ")
                    + flag.node() + (give ? " to " : " from ") + holder.describe() + " (" + backend.name() + ").");
            messages.send(sender, give ? "creative.manage.added" : "creative.manage.removed",
                    give ? "{prefix} &aGave &f{holder} &athe creative flag &f{flag}&a."
                            : "{prefix} &eRemoved the creative flag &f{flag} &efrom &f{holder}&e.",
                    "{flag}", flag.key, "{holder}", holder.describe());
        } catch (IllegalArgumentException missing) {
            messages.send(sender, "creative.manage.unknown-group",
                    "{prefix} &cNo permissions group named &f{group}&c.", "{group}", holder.name);
        } catch (UnsupportedOperationException readOnly) {
            messages.send(sender, "creative.manage.unavailable",
                    "{prefix} &cChanging creative flags needs LuckPerms, or Vault with a permissions plugin.");
        } catch (RuntimeException failure) {
            messages.send(sender, "creative.manage.failed",
                    "{prefix} &cCould not update creative flags on &f{holder}&c: &7{reason}",
                    "{holder}", holder.describe(), "{reason}", String.valueOf(failure.getMessage()));
        }
    }

    private Holder resolveHolder(CommandSender sender, boolean group, String name) {
        if (group) {
            Backend backend = currentBackend();
            if (!backend.canWrite()) {
                messages.send(sender, "creative.manage.unavailable",
                        "{prefix} &cChanging creative flags needs LuckPerms, or Vault with a permissions plugin.");
                return null;
            }
            String canonical = backend.findGroup(name);
            if (canonical == null) {
                messages.send(sender, "creative.manage.unknown-group",
                        "{prefix} &cNo permissions group named &f{group}&c.", "{group}", name);
                return null;
            }
            return Holder.group(canonical);
        }
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null && canSeeTarget.test(sender, online)) {
            return Holder.player(online.getUniqueId(), online.getName());
        }
        for (Map.Entry<UUID, String> e : playerInfo.knownPlayerNames().entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(name)) {
                return Holder.player(e.getKey(), e.getValue());
            }
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer op = plugin.getServer().getOfflinePlayer(name);
        if (op != null && op.hasPlayedBefore() && op.getName() != null) {
            return Holder.player(op.getUniqueId(), op.getName());
        }
        messages.send(sender, "player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", name);
        return null;
    }

    List<String> complete(CommandSender sender, String[] args) {
        if ((!sender.hasPermission(PERMISSION_MANAGE) && !sender.isOp()) || args.length == 0) {
            return Collections.emptyList();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values;
        if (args.length == 1) {
            values = Arrays.asList("gui", "active", "player", "group");
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (!"player".equals(sub) && !"group".equals(sub)) return Collections.emptyList();
            if (args.length == 2) {
                if ("group".equals(sub)) {
                    values = currentBackend().groups();
                } else {
                    Set<String> names = new HashSet<String>();
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (canSeeTarget.test(sender, p)) names.add(p.getName());
                    }
                    names.addAll(playerInfo.knownPlayerNames().values());
                    values = new ArrayList<String>(names);
                    Collections.sort(values, String.CASE_INSENSITIVE_ORDER);
                }
            } else if (args.length == 3) {
                values = ACTIONS;
            } else if (args.length == 4
                    && ("add".equalsIgnoreCase(args[2]) || "remove".equalsIgnoreCase(args[2]) || "check".equalsIgnoreCase(args[2]))) {
                values = Flag.keys();
            } else {
                return Collections.emptyList();
            }
        }
        List<String> out = new ArrayList<String>();
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(v);
                if (out.size() >= 64) break;
            }
        }
        return out;
    }

    // ==================== GUIs ====================

    void openList(Player viewer, Filter filter, int page) {
        Backend backend = currentBackend();
        List<Entry> entries = collectEntries(viewer, filter, backend);
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        int to = Math.min(entries.size(), (current + 1) * PAGE_SIZE);
        List<Entry> shown = new ArrayList<Entry>(entries.subList(current * PAGE_SIZE, to));
        ListMenu menu = new ListMenu(filter, current, shown);
        String title = "Creative manager: " + filter.label;
        Inventory inv = plugin.getServer().createInventory(menu, 54, title.length() > 32 ? title.substring(0, 32) : title);
        menu.inventory = inv;

        long now = System.currentTimeMillis();
        boolean invsee = viewer.hasPermission(PERMISSION_INVSEE);
        for (int i = 0; i < shown.size(); i++) {
            inv.setItem(i, entryIcon(shown.get(i), now, invsee));
        }
        ItemStack filler = paneIcon("GRAY_STAINED_GLASS_PANE", (short) 7, " ", Collections.<String>emptyList());
        for (int slot = 45; slot < 54; slot++) inv.setItem(slot, filler);
        if (current > 0) {
            inv.setItem(PREVIOUS, namedIcon("ARROW", null, "\u00a7aPrevious page", Collections.singletonList("\u00a77Page " + current)));
        }
        if (current + 1 < pages) {
            inv.setItem(NEXT, namedIcon("ARROW", null, "\u00a7aNext page", Collections.singletonList("\u00a77Page " + (current + 2))));
        }
        List<String> filterLore = new ArrayList<String>();
        for (Filter option : Filter.values()) {
            filterLore.add((option == filter ? "\u00a7a\u25b6 " : "\u00a77  ") + option.label);
        }
        filterLore.add("\u00a78Click: next \u00b7 right-click: previous");
        inv.setItem(FILTER, namedIcon("HOPPER", null, "\u00a7eShowing: \u00a7f" + filter.label, filterLore));

        List<String> info = new ArrayList<String>();
        info.add("\u00a77Permissions: \u00a7f" + backend.name());
        info.add("\u00a77Page \u00a7f" + (current + 1) + "\u00a77 of \u00a7f" + pages + "\u00a77, " + entries.size() + " listed");
        if (!backend.canWrite()) {
            info.add("\u00a7cRead-only: install LuckPerms or Vault");
            info.add("\u00a7cwith a permissions plugin to change flags");
        } else if (!backend.knowsSetNodes()) {
            info.add("\u00a78Vault shows what applies, not where it is set");
        }
        inv.setItem(INFO, namedIcon("BOOK", null, "\u00a7dCreative manager", info));
        viewer.openInventory(inv);
    }

    void openFlags(Player viewer, Holder holder, Filter backFilter, int backPage) {
        Backend backend = currentBackend();
        try {
            FlagMenu menu = new FlagMenu(holder, backFilter, backPage);
            menu.backend = backend;
            menu.values = resolveEffective(holder, backend.read(holder), backend);
            String title = (holder.group ? "Creative group: " : "Creative: ") + holder.name;
            menu.inventory = plugin.getServer().createInventory(menu, 54, title.length() > 32 ? title.substring(0, 32) : title);
            paintFlags(menu);
            viewer.openInventory(menu.inventory);
        } catch (IllegalArgumentException missing) {
            messages.send(viewer, "creative.manage.unknown-group",
                    "{prefix} &cNo permissions group named &f{group}&c.", "{group}", holder.name);
        } catch (RuntimeException failure) {
            messages.send(viewer, "creative.manage.failed",
                    "{prefix} &cCould not load creative flags on &f{holder}&c: &7{reason}",
                    "{holder}", holder.describe(), "{reason}", String.valueOf(failure.getMessage()));
        }
    }

    private void paintFlags(FlagMenu menu) {
        Inventory inv = menu.inventory;
        for (int slot = 0; slot < 54; slot++) inv.setItem(slot, null);
        Flag[] flags = Flag.values();
        for (int i = 0; i < flags.length; i++) {
            FlagValue val = menu.values.get(flags[i]);
            inv.setItem(FLAG_SLOTS[i], flagIcon(menu, flags[i], val));
            inv.setItem(FLAG_SLOTS[i] + 9, statePane(menu, flags[i], val));
        }
        ItemStack filler = paneIcon("GRAY_STAINED_GLASS_PANE", (short) 7, " ", Collections.<String>emptyList());
        for (int slot = 45; slot < 54; slot++) {
            if (inv.getItem(slot) == null) inv.setItem(slot, filler);
        }
        inv.setItem(BACK, namedIcon("ARROW", null,
                menu.backFilter == null ? "\u00a7cClose" : "\u00a7cBack",
                Collections.singletonList(menu.backFilter == null ? "\u00a77Close this menu" : "\u00a77Return to the list")));
        List<String> info = new ArrayList<String>();
        info.add("\u00a77" + (menu.holder.group ? "Group" : "Player") + ": \u00a7f" + menu.holder.name);
        info.add("\u00a77Permissions: \u00a7f" + menu.backend.name());
        if (!menu.backend.canWrite()) {
            info.add("\u00a7cRead-only: install LuckPerms or Vault");
        } else if (menu.backend.knowsSetNodes()) {
            info.add("\u00a78Changes are global nodes, logged in /lp log");
        }
        inv.setItem(HOLDER_INFO, menu.holder.group
                ? namedIcon("WRITABLE_BOOK", "BOOK_AND_QUILL", "\u00a7d" + menu.holder.describe(), info)
                : playerHeadIcon(menu.holder.name, "\u00a7d" + menu.holder.describe(), info));
        inv.setItem(REFRESH, namedIcon("CLOCK", "WATCH", "\u00a7eRefresh", Collections.singletonList("\u00a77Read the flags again")));
        inv.setItem(CLOSE, namedIcon("BARRIER", null, "\u00a7cClose", Collections.<String>emptyList()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView() == null || event.getView().getTopInventory() == null) return;
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Menu) || !(event.getWhoClicked() instanceof Player)) return;
        event.setCancelled(true);
        final Player viewer = (Player) event.getWhoClicked();
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= 54) return;
        if (!viewer.hasPermission(PERMISSION_MANAGE) && !viewer.isOp()) {
            runNextTick(viewer, new Runnable() {
                @Override public void run() { viewer.closeInventory(); }
            });
            messages.send(viewer, "no-permission", "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", "creativemanager");
            return;
        }
        boolean right = event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT;
        if (holder instanceof ListMenu) {
            final ListMenu list = (ListMenu) holder;
            if (raw < list.shown.size()) {
                final Entry entry = list.shown.get(raw);
                if (event.isShiftClick() && !entry.holder.group) {
                    runNextTick(viewer, new Runnable() {
                        @Override public void run() {
                            if (commandManager.isDisabled("invsee") || commandManager.isModuleDisabled("invsee")) {
                                messages.send(viewer, "module-disabled", "{prefix} &cThat module is disabled.");
                                return;
                            }
                            moderation.onCommand(viewer, "invsee", new String[]{entry.holder.name});
                        }
                    });
                } else {
                    runNextTick(viewer, new Runnable() {
                        @Override public void run() { openFlags(viewer, entry.holder, list.filter, list.page); }
                    });
                }
            } else if (raw == PREVIOUS && list.page > 0) {
                runNextTick(viewer, new Runnable() {
                    @Override public void run() { openList(viewer, list.filter, list.page - 1); }
                });
            } else if (raw == NEXT) {
                runNextTick(viewer, new Runnable() {
                    @Override public void run() { openList(viewer, list.filter, list.page + 1); }
                });
            } else if (raw == FILTER) {
                final Filter nextFilter = list.filter.next(right);
                runNextTick(viewer, new Runnable() {
                    @Override public void run() { openList(viewer, nextFilter, 0); }
                });
            }
            return;
        }
        if (holder instanceof FlagMenu) {
            final FlagMenu menu = (FlagMenu) holder;
            if (raw == BACK) {
                runNextTick(viewer, new Runnable() {
                    @Override public void run() {
                        if (menu.backFilter == null) viewer.closeInventory();
                        else openList(viewer, menu.backFilter, menu.backPage);
                    }
                });
                return;
            }
            if (raw == CLOSE) {
                runNextTick(viewer, new Runnable() {
                    @Override public void run() { viewer.closeInventory(); }
                });
                return;
            }
            if (raw == REFRESH) {
                reloadFlagMenu(viewer, menu);
                return;
            }
            Flag flag = flagAt(raw);
            if (flag == null) return;
            if (!menu.backend.canWrite()) {
                messages.send(viewer, "creative.manage.unavailable",
                        "{prefix} &cChanging creative flags needs LuckPerms, or Vault with a permissions plugin.");
                return;
            }
            boolean give = !menu.values.get(flag).givenHere();
            try {
                menu.backend.write(menu.holder, flag, give, viewer);
                messages.send(viewer, give ? "creative.manage.added" : "creative.manage.removed",
                        give ? "{prefix} &aGave &f{holder} &athe creative flag &f{flag}&a."
                                : "{prefix} &eRemoved the creative flag &f{flag} &efrom &f{holder}&e.",
                        "{flag}", flag.key, "{holder}", menu.holder.describe());
            } catch (RuntimeException failure) {
                messages.send(viewer, "creative.manage.failed",
                        "{prefix} &cCould not update creative flags on &f{holder}&c: &7{reason}",
                        "{holder}", menu.holder.describe(), "{reason}", String.valueOf(failure.getMessage()));
            }
            reloadFlagMenu(viewer, menu);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView() != null && event.getView().getTopInventory() != null
                && event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private void reloadFlagMenu(Player viewer, FlagMenu menu) {
        Backend backend = currentBackend();
        menu.backend = backend;
        try {
            menu.values = resolveEffective(menu.holder, backend.read(menu.holder), backend);
            paintFlags(menu);
        } catch (RuntimeException failure) {
            messages.send(viewer, "creative.manage.failed",
                    "{prefix} &cCould not refresh creative flags on &f{holder}&c: &7{reason}",
                    "{holder}", menu.holder.describe(), "{reason}", String.valueOf(failure.getMessage()));
        }
    }

    private List<Entry> collectEntries(Player viewer, Filter filter, Backend backend) {
        List<Entry> out = new ArrayList<Entry>();
        if (filter == Filter.ALL || filter == Filter.GROUPS) {
            for (String g : backend.groups()) {
                out.add(new Entry(Holder.group(g), false, 0L));
            }
        }
        if (filter == Filter.GROUPS) return out;

        Set<UUID> visibleOnline = new HashSet<UUID>();
        List<Player> online = new ArrayList<Player>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (canSeeTarget.test(viewer, p)) {
                online.add(p);
                visibleOnline.add(p.getUniqueId());
            }
        }
        long now = System.currentTimeMillis();
        if (filter != Filter.OFFLINE) {
            Collections.sort(online, new Comparator<Player>() {
                @Override public int compare(Player a, Player b) { return a.getName().compareToIgnoreCase(b.getName()); }
            });
            for (Player p : online) {
                out.add(new Entry(Holder.player(p.getUniqueId(), p.getName()), true, now));
            }
        }
        if (filter != Filter.ONLINE) {
            List<Entry> offline = new ArrayList<Entry>();
            for (Map.Entry<UUID, String> e : playerInfo.knownPlayerNames().entrySet()) {
                if (e.getValue() == null || visibleOnline.contains(e.getKey())) continue;
                offline.add(new Entry(Holder.player(e.getKey(), e.getValue()), false, playerInfo.lastSeenOf(e.getKey())));
            }
            Collections.sort(offline, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) { return Long.compare(b.lastSeen, a.lastSeen); }
            });
            for (int i = 0; i < offline.size() && i < OFFLINE_LIMIT; i++) {
                out.add(offline.get(i));
            }
        }
        return out;
    }

    private ItemStack entryIcon(Entry entry, long now, boolean invsee) {
        if (entry.holder.group) {
            return namedIcon("WRITABLE_BOOK", "BOOK_AND_QUILL", "\u00a7bGroup \u00a7f" + entry.holder.name,
                    Collections.singletonList("\u00a77Click to edit its creative flags"));
        }
        List<String> lore = new ArrayList<String>();
        lore.add(entry.online ? "\u00a7aOnline" : "\u00a78Offline, last seen " + LegacyCompat.formatDuration(Math.max(0L, now - entry.lastSeen)) + " ago");
        lore.add("\u00a77Click to edit their creative flags");
        if (invsee) lore.add("\u00a77Shift-click to open their inventory \u00a78(/invsee)");
        return playerHeadIcon(entry.holder.name, (entry.online ? "\u00a7a" : "\u00a77") + entry.holder.name, lore);
    }

    private static ItemStack flagIcon(FlagMenu menu, Flag flag, FlagValue value) {
        List<String> lore = new ArrayList<String>();
        lore.add("\u00a77" + flag.description);
        lore.add("\u00a78" + flag.node());
        lore.add("");
        if (value.setKnown) {
            lore.add("\u00a77Set here: " + (Boolean.TRUE.equals(value.set) ? "\u00a7ayes"
                    : (Boolean.FALSE.equals(value.set) ? "\u00a7cdenied" : "\u00a77no")));
        }
        lore.add("\u00a77Applies: " + LegacyCompat.color(value.describeEffective()));
        lore.add("");
        lore.add(clickHint(menu, value));
        ItemStack icon = namedIcon(flag.modernIcon, flag.legacyIcon,
                (Boolean.TRUE.equals(value.effective) ? "\u00a7a" : "\u00a7c") + flag.key, lore);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack statePane(FlagMenu menu, Flag flag, FlagValue value) {
        String modern;
        short legacyData;
        String name;
        if (value.setKnown && Boolean.TRUE.equals(value.set)) {
            modern = "LIME_STAINED_GLASS_PANE"; legacyData = 5; name = "\u00a7aGiven here";
        } else if (value.setKnown && Boolean.FALSE.equals(value.set)) {
            modern = "RED_STAINED_GLASS_PANE"; legacyData = 14; name = "\u00a7cDenied here";
        } else if (Boolean.TRUE.equals(value.effective)) {
            modern = value.setKnown ? "CYAN_STAINED_GLASS_PANE" : "LIME_STAINED_GLASS_PANE";
            legacyData = (short) (value.setKnown ? 9 : 5);
            name = value.setKnown ? "\u00a73Allowed, not set here" : "\u00a7aAllowed";
        } else {
            modern = "GRAY_STAINED_GLASS_PANE"; legacyData = 7; name = "\u00a77Not allowed";
        }
        return paneIcon(modern, legacyData, name, Arrays.asList(
                "\u00a77" + flag.key + ": " + LegacyCompat.color(value.describeEffective()),
                clickHint(menu, value)));
    }

    private static String clickHint(FlagMenu menu, FlagValue value) {
        if (!menu.backend.canWrite()) return "\u00a78Read-only: needs LuckPerms or Vault";
        return value.givenHere() ? "\u00a7eClick to remove it" : "\u00a7aClick to give it";
    }

    private static Flag flagAt(int raw) {
        Flag[] all = Flag.values();
        for (int i = 0; i < FLAG_SLOTS.length; i++) {
            if (raw == FLAG_SLOTS[i] || raw == FLAG_SLOTS[i] + 9) return all[i];
        }
        return null;
    }

    private static List<String> listedFlags(Map<Flag, FlagValue> values) {
        List<String> out = new ArrayList<String>();
        for (Flag f : Flag.values()) {
            FlagValue v = values.get(f);
            if (v.setKnown ? v.set != null : Boolean.TRUE.equals(v.effective)) {
                out.add(v.setKnown && Boolean.FALSE.equals(v.set) ? "-" + f.key : f.key);
            }
        }
        return out;
    }

    private Map<Flag, FlagValue> resolveEffective(Holder holder, Map<Flag, FlagValue> read, Backend backend) {
        if (holder.group) return read;
        Player online = plugin.getServer().getPlayer(holder.uuid);
        OfflinePlayer offline = online != null ? online : plugin.getServer().getOfflinePlayer(holder.uuid);
        Map<Flag, FlagValue> out = new EnumMap<Flag, FlagValue>(Flag.class);
        for (Flag flag : Flag.values()) {
            FlagValue val = read.get(flag);
            boolean unresolved = val.effective == null || "default".equals(val.source);
            Boolean eff = val.effective;
            String src = val.source;
            if (online != null) {
                eff = Boolean.valueOf(online.hasPermission(flag.node()));
                if (unresolved || !backend.knowsSetNodes()) {
                    src = unresolved && online.isOp() ? "op default" : (backend.knowsSetNodes() ? "default" : "online");
                }
            } else if (unresolved && offline != null) {
                eff = Boolean.valueOf(offline.isOp());
                src = eff.booleanValue() ? "op default" : "default";
            }
            out.put(flag, new FlagValue(val.set, eff, val.setKnown, src));
        }
        return out;
    }

    // ==================== Reflective Permission Backends ====================

    private Backend currentBackend() {
        Server server = plugin.getServer();
        PluginManager pm = server.getPluginManager();
        if (pm != null) {
            Plugin lp = pm.getPlugin("LuckPerms");
            if (lp != null && lp.isEnabled()) {
                Backend found = LuckPermsReflectionBackend.tryCreate(server, lp);
                if (found != null) return found;
            }
            if (pm.getPlugin("Vault") != null) {
                Backend found = VaultReflectionBackend.tryCreate(server);
                if (found != null) return found;
            }
        }
        return ReadOnlyBackend.INSTANCE;
    }

    private static final class ReadOnlyBackend implements Backend {
        static final ReadOnlyBackend INSTANCE = new ReadOnlyBackend();

        @Override public String name() { return "none"; }
        @Override public boolean canWrite() { return false; }
        @Override public boolean knowsSetNodes() { return false; }
        @Override public List<String> groups() { return Collections.emptyList(); }
        @Override public String findGroup(String name) { return null; }

        @Override
        public Map<Flag, FlagValue> read(Holder holder) {
            if (holder.group) throw new IllegalArgumentException("no group " + holder.name);
            Map<Flag, FlagValue> out = new EnumMap<Flag, FlagValue>(Flag.class);
            for (Flag f : Flag.values()) out.put(f, new FlagValue(null, null, false, "default"));
            return out;
        }

        @Override
        public void write(Holder holder, Flag flag, boolean give, CommandSender actor) {
            throw new UnsupportedOperationException("no permissions plugin");
        }
    }

    private static final class VaultReflectionBackend implements Backend {
        private final Server server;
        private final Object perm;
        private final Class<?> permClass;
        private final String providerName;

        private VaultReflectionBackend(Server server, Object perm, Class<?> permClass, String providerName) {
            this.server = server;
            this.perm = perm;
            this.permClass = permClass;
            this.providerName = providerName;
        }

        static Backend tryCreate(Server server) {
            try {
                ServicesManager sm = server.getServicesManager();
                if (sm == null) return null;
                Class<?> permClass = Class.forName("net.milkbowl.vault.permission.Permission");
                Object perm = sm.load(permClass);
                if (perm == null) return null;
                if (!Boolean.TRUE.equals(permClass.getMethod("isEnabled").invoke(perm))) return null;
                String name = String.valueOf(permClass.getMethod("getName").invoke(perm));
                return new VaultReflectionBackend(server, perm, permClass, name);
            } catch (Throwable unavailable) {
                return null;
            }
        }

        @Override public String name() { return "Vault (" + providerName + ")"; }
        @Override public boolean canWrite() { return true; }
        @Override public boolean knowsSetNodes() { return false; }

        @Override
        public List<String> groups() {
            try {
                if (!Boolean.TRUE.equals(permClass.getMethod("hasGroupSupport").invoke(perm))) {
                    return Collections.emptyList();
                }
                String[] raw = (String[]) permClass.getMethod("getGroups").invoke(perm);
                if (raw == null || raw.length == 0) return Collections.emptyList();
                List<String> out = new ArrayList<String>(Arrays.asList(raw));
                Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
                return out;
            } catch (Throwable ignored) {
                return Collections.emptyList();
            }
        }

        @Override
        public String findGroup(String name) {
            for (String g : groups()) {
                if (g.equalsIgnoreCase(name)) return g;
            }
            return null;
        }

        @Override
        public Map<Flag, FlagValue> read(Holder holder) {
            try {
                Map<Flag, FlagValue> out = new EnumMap<Flag, FlagValue>(Flag.class);
                if (holder.group) {
                    String g = findGroup(holder.name);
                    if (g == null) throw new IllegalArgumentException("no group " + holder.name);
                    Method groupHas = permClass.getMethod("groupHas", String.class, String.class, String.class);
                    for (Flag f : Flag.values()) {
                        boolean has = Boolean.TRUE.equals(groupHas.invoke(perm, null, g, f.node()));
                        out.put(f, new FlagValue(null, Boolean.valueOf(has), false, ""));
                    }
                } else {
                    OfflinePlayer op = server.getOfflinePlayer(holder.uuid);
                    Method playerHas = permClass.getMethod("playerHas", String.class, OfflinePlayer.class, String.class);
                    for (Flag f : Flag.values()) {
                        boolean has = Boolean.TRUE.equals(playerHas.invoke(perm, null, op, f.node()));
                        out.put(f, new FlagValue(null, Boolean.valueOf(has), false, ""));
                    }
                }
                return out;
            } catch (IllegalArgumentException rethrow) {
                throw rethrow;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }

        @Override
        public void write(Holder holder, Flag flag, boolean give, CommandSender actor) {
            try {
                boolean changed;
                if (holder.group) {
                    String g = findGroup(holder.name);
                    if (g == null) throw new IllegalArgumentException("no group " + holder.name);
                    Method m = permClass.getMethod(give ? "groupAdd" : "groupRemove", String.class, String.class, String.class);
                    changed = Boolean.TRUE.equals(m.invoke(perm, null, g, flag.node()));
                } else {
                    OfflinePlayer op = server.getOfflinePlayer(holder.uuid);
                    Method m = permClass.getMethod(give ? "playerAdd" : "playerRemove", String.class, OfflinePlayer.class, String.class);
                    changed = Boolean.TRUE.equals(m.invoke(perm, null, op, flag.node()));
                }
                if (!changed) throw new IllegalStateException(name() + " refused the change");
            } catch (IllegalArgumentException rethrow) {
                throw rethrow;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }
    }

    private static final class LuckPermsReflectionBackend implements Backend {
        private final Server server;
        private final ClassLoader loader;
        private final Object api;

        private LuckPermsReflectionBackend(Server server, ClassLoader loader, Object api) {
            this.server = server;
            this.loader = loader;
            this.api = api;
        }

        static Backend tryCreate(Server server, Plugin lpPlugin) {
            try {
                ClassLoader loader = lpPlugin.getClass().getClassLoader();
                Object api = Class.forName("net.luckperms.api.LuckPermsProvider", false, loader)
                        .getMethod("get").invoke(null);
                if (api == null) return null;
                return new LuckPermsReflectionBackend(server, loader, api);
            } catch (Throwable unavailable) {
                return null;
            }
        }

        @Override public String name() { return "LuckPerms"; }
        @Override public boolean canWrite() { return true; }
        @Override public boolean knowsSetNodes() { return true; }

        @Override
        public List<String> groups() {
            try {
                Object gm = api.getClass().getMethod("getGroupManager").invoke(api);
                Collection<?> loaded = (Collection<?>) gm.getClass().getMethod("getLoadedGroups").invoke(gm);
                List<String> out = new ArrayList<String>();
                for (Object g : loaded) {
                    out.add(String.valueOf(g.getClass().getMethod("getName").invoke(g)));
                }
                Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
                return out;
            } catch (Throwable ignored) {
                return Collections.emptyList();
            }
        }

        @Override
        public String findGroup(String name) {
            try {
                Object gm = api.getClass().getMethod("getGroupManager").invoke(api);
                Object g = gm.getClass().getMethod("getGroup", String.class).invoke(gm, name.toLowerCase(Locale.ROOT));
                return g == null ? null : String.valueOf(g.getClass().getMethod("getName").invoke(g));
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public Map<Flag, FlagValue> read(Holder holder) {
            try {
                Object lpHolder;
                if (holder.group) {
                    Object gm = api.getClass().getMethod("getGroupManager").invoke(api);
                    lpHolder = gm.getClass().getMethod("getGroup", String.class).invoke(gm, holder.name.toLowerCase(Locale.ROOT));
                    if (lpHolder == null) throw new IllegalArgumentException("no group " + holder.name);
                } else {
                    Object um = api.getClass().getMethod("getUserManager").invoke(api);
                    lpHolder = um.getClass().getMethod("getUser", UUID.class).invoke(um, holder.uuid);
                    if (lpHolder == null) {
                        Object future = um.getClass().getMethod("loadUser", UUID.class, String.class)
                                .invoke(um, holder.uuid, holder.name);
                        lpHolder = future.getClass().getMethod("join").invoke(future);
                    }
                }
                Object cached = lpHolder.getClass().getMethod("getCachedData").invoke(lpHolder);
                Object permData = cached.getClass().getMethod("getPermissionData").invoke(cached);
                Method checkPerm = permData.getClass().getMethod("checkPermission", String.class);
                Collection<?> nodes = (Collection<?>) lpHolder.getClass().getMethod("getNodes").invoke(lpHolder);

                String bypassState = String.valueOf(checkPerm.invoke(permData, Flag.BYPASS.node()));
                boolean bypass = "TRUE".equals(bypassState);
                Map<Flag, FlagValue> out = new EnumMap<Flag, FlagValue>(Flag.class);
                for (Flag flag : Flag.values()) {
                    Boolean set = ownGlobalNode(nodes, flag.node());
                    String state = String.valueOf(checkPerm.invoke(permData, flag.node()));
                    FlagValue val;
                    if (!"UNDEFINED".equals(state)) {
                        val = new FlagValue(set, Boolean.valueOf("TRUE".equals(state)), true, set != null ? "set here" : "inherited");
                    } else if (bypass && flag.coveredByBypass()) {
                        val = new FlagValue(set, Boolean.TRUE, true, "bypass");
                    } else if (!holder.group) {
                        val = new FlagValue(set, null, true, "default");
                    } else {
                        val = new FlagValue(set, Boolean.FALSE, true, "not set");
                    }
                    out.put(flag, val);
                }
                return out;
            } catch (IllegalArgumentException rethrow) {
                throw rethrow;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }

        private Boolean ownGlobalNode(Collection<?> nodes, String key) {
            if (nodes == null) return null;
            try {
                Class<?> nodeClass = Class.forName("net.luckperms.api.node.Node", false, loader);
                Method getKey = nodeClass.getMethod("getKey");
                Method getContexts = nodeClass.getMethod("getContexts");
                Method getValue = nodeClass.getMethod("getValue");
                for (Object node : nodes) {
                    if (!key.equals(getKey.invoke(node))) continue;
                    Object ctx = getContexts.invoke(node);
                    if (Boolean.TRUE.equals(ctx.getClass().getMethod("isEmpty").invoke(ctx))) {
                        return (Boolean) getValue.invoke(node);
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        @Override
        public void write(Holder holder, Flag flag, boolean give, CommandSender actor) {
            String cmd = holder.group
                    ? ("lp group " + holder.name.toLowerCase(Locale.ROOT)
                            + (give ? " permission set " + flag.node() + " true" : " permission unset " + flag.node()))
                    : ("lp user " + holder.uuid.toString()
                            + (give ? " permission set " + flag.node() + " true" : " permission unset " + flag.node()));
            if (!server.dispatchCommand(server.getConsoleSender(), cmd)) {
                throw new IllegalStateException("LuckPerms command failed: " + cmd);
            }
        }
    }

    // ==================== Item & Scheduler Helpers ====================

    private void runNextTick(final Player viewer, final Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    if (viewer.isOnline()) action.run();
                }
            });
        } catch (Throwable ignored) {
            if (viewer.isOnline()) action.run();
        }
    }

    @SuppressWarnings("deprecation")
    private static ItemStack paneIcon(String modernName, short legacyData, String display, List<String> lore) {
        Material m = Material.matchMaterial(modernName);
        ItemStack stack;
        if (m != null) {
            stack = new ItemStack(m, 1);
        } else {
            Material legacy = Material.matchMaterial("STAINED_GLASS_PANE");
            stack = new ItemStack(legacy != null ? legacy : Material.GLASS, 1, legacyData);
        }
        applyMeta(stack, display, lore);
        return stack;
    }

    private static ItemStack namedIcon(String modernName, String legacyName, String display, List<String> lore) {
        Material m = Material.matchMaterial(modernName);
        if (m == null && legacyName != null) m = Material.matchMaterial(legacyName);
        if (m == null) m = Material.PAPER;
        ItemStack stack = new ItemStack(m, 1);
        applyMeta(stack, display, lore);
        return stack;
    }

    private static ItemStack playerHeadIcon(String ownerName, String display, List<String> lore) {
        ItemStack head = LegacyCompat.createPlayerHead(ownerName, null);
        applyMeta(head, display, lore);
        return head;
    }

    private static void applyMeta(ItemStack stack, String display, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(display);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
    }

    private static String joinStrings(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
