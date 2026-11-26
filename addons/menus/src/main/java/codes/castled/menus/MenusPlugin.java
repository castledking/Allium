package codes.castled.menus;

import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.api.SettingsPresentation;
import codes.castled.allium.api.MenuData;
import org.bukkit.Material;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandSpec;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.ServicePriority;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class MenusPlugin extends AlliumAddon implements Listener {
    private static final String SETTINGS = "allium:settings";
    private static final String USE = "allium.gui.use";
    private static final List<String> DEFAULTS = List.of("config.yml", "dialogs/settings.yml",
            "dialogs/homes.yml", "dialogs/warps.yml", "dialogs/rules.yml", "dialogs/toggles.yml",
            "dialogs/teleport.yml", "guis/settings.yml", "shop.yml", "message-packs.yml", "warp-icons.yml");
    private static final long SESSION_MILLIS = 120_000L;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final List<Permission> registeredPermissions = new ArrayList<>();
    private volatile MenuConfig config;
    private Path directory;
    private DialogRenderer paper;
    private MenuViews views;
    private MenuForms forms;
    private volatile MessagePacks messagePacks;
    private WarpIcons warpIcons;
    /** An inventory entry may leave its label null to retain the icon's custom display name. */
    record Entry(int slot, String label, ItemStack icon, Runnable action) {
        Entry(int slot, String label, Material icon, Runnable action) {
            this(slot, label, new ItemStack(icon), action);
        }
    }

    @Override protected void onAddonEnable(AddonConnection connection) {
        directory = getServer().getPluginManager().getPlugin("Allium").getDataFolder().toPath().resolve("Menus");
        try {
            for (String name : DEFAULTS) {
                Path path = directory.resolve(name);
                if (Files.exists(path)) continue;
                Files.createDirectories(path.getParent());
                try (var in = getResource(name)) {
                    if (in == null) throw new IllegalStateException("Missing bundled " + name);
                    Files.copy(in, path);
                }
            }
            upgradeDefaults();
            paper = paperRenderer();
            views = new MenuViews(this);
            forms = new MenuForms(this, paper);
            warpIcons = new WarpIcons(directory.resolve("warp-icons.yml"));
            reload(true);
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Could not load Menus", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var plugins = getServer().getPluginManager();
        // Paper may load Allium's managed addons after the normal permission registration pass.
        for (Permission permission : getDescription().getPermissions()) {
            if (plugins.getPermission(permission.getName()) != null) continue;
            plugins.addPermission(permission);
            registeredPermissions.add(permission);
        }
        connection.commands().register(spec("menu", "/menu <id>"), this::command, this::complete);
        connection.commands().register(spec("menus", "/menus [validate|reload]"), this::command, this::complete);
        connection.commands().register(spec("punish", "/punish [player]"), this::featureCommand, null);
        connection.commands().register(spec("ptgui", "/ptgui"), this::featureCommand, null);
        connection.commands().register(spec("shop", "/shop"), this::featureCommand, null);
        connection.commands().register(new CommandSpec("joinmessages", "menus", "menus",
                List.of("jm", "qm", "quitmessages"), "Select join and quit messages", "/joinmessages",
                "/joinmessages", USE, null, List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of())), this::featureCommand);
        getServer().getServicesManager().register(codes.castled.allium.api.JoinQuitMessageSelection.class,
                (player, kind) -> messagePacks.selected(player, kind), this, ServicePriority.Normal);
        getServer().getServicesManager().register(SettingsPresentation.class, new SettingsPresentation() {
            @Override public boolean open(Player player) { return MenusPlugin.this.open(player, SETTINGS, MenuConfig.Trigger.COMMAND); }
            @Override public boolean open(Player player, Screen screen) {
                return views.openCore(player, screen);
            }
            @Override public boolean edit(Player player, String editor, ItemStack preview, List<String> initial,
                                          java.util.function.Consumer<List<String>> submit) {
                return forms.edit(player, editor, preview, initial, submit);
            }
        }, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
        connection.scheduler().repeatOnServer(() -> {
            long now = System.currentTimeMillis();
            MenuConfig current = config;
            forms.cleanup(now);
            sessions.entrySet().removeIf(entry -> entry.getValue().config() != current || entry.getValue().expires() <= now);
            return true;
        }, 30_000L, 30_000L);
    }

    @Override protected void onAddonDisable() {
        config = null;
        sessions.clear();
        if (forms != null) forms.clear();
        // Managed addon changes take effect at restart. Folia disconnects players during shutdown,
        // and the shutdown thread must not access their inventories.
        if (!allium().isFolia()) {
            for (Player viewer : getServer().getOnlinePlayers()) {
                if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) viewer.closeInventory();
            }
        }
        getServer().getServicesManager().unregisterAll(this);
        var plugins = getServer().getPluginManager();
        registeredPermissions.forEach(plugins::removePermission);
        registeredPermissions.clear();
    }

    private void reload(boolean publish) throws Exception {
        MenuConfig candidate = MenuConfig.load(directory);
        ShopCatalog shop = ShopCatalog.load(directory.resolve("shop.yml"));
        var messages = MessagePacks.load(directory.resolve("message-packs.yml"));
        synchronized (warpIcons) {
            var icons = warpIcons.read();
            if (!publish) return;
            if (paper != null) EscapeMenuPack.write(this, candidate);
            views.shop(shop);
            messagePacks = messages;
            warpIcons.install(icons);
            config = candidate;
        }
    }

    private static CommandSpec spec(String name, String usage) {
        return new CommandSpec(name, "menus", "menus", List.of(), "Open or manage Allium menus", usage, usage,
                USE, null, List.of(), List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
    }

    private void command(AddonCommands.Context context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        boolean management = context.spec().canonicalName().equals("menus");
        if (management && args.length == 1 && (args[0].equalsIgnoreCase("reload") || args[0].equalsIgnoreCase("validate"))) {
            if (!sender.hasPermission("allium.gui.manage")) {
                reply(sender, "&cYou cannot manage Menus.");
                return;
            }
            boolean publish = args[0].equalsIgnoreCase("reload");
            var scheduler = allium().scheduler();
            if (scheduler.isServerThread()) validateOrReload(sender, publish);
            else scheduler.runOnServer(() -> validateOrReload(sender, publish));
            return;
        }
        if (!(sender instanceof Player player)) {
            reply(sender, "&cOnly players can open menus.");
            return;
        }
        allium().onOwner(player, () -> playerCommand(player, args, management));
    }

    private void validateOrReload(CommandSender sender, boolean publish) {
        try {
            reload(publish);
            reply(sender, publish ? "&aMenus reloaded. Escape entries update on restart." : "&aMenus configuration is valid.");
        } catch (Exception invalid) {
            reply(sender, "&c" + invalid.getMessage());
        }
    }

    private void playerCommand(Player player, String[] args, boolean management) {
        if (management && args.length >= 2 && args.length <= 3 && args[0].equals("warp-icon")) {
            setWarpIcon(player, args[1], args.length == 3 ? args[2] : "");
            return;
        }
        if (management && args.length >= 2 && args[0].equals("input")) {
            forms.input(player, args[1], String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
            return;
        }
        if (management && args.length == 3 && args[0].equals("click")) {
            activate(player, args[1], args[2]);
            return;
        }
        if (management && (args.length == 2 || args.length == 3) && args[0].equals("escape")) {
            MenuConfig current = config;
            if (current != null && current.escapeMenu()) {
                if (args.length == 2) open(player, args[1], MenuConfig.Trigger.ESCAPE);
                else openEscapeShortcut(player, current, args[1], args[2]);
            }
            return;
        }
        if (management && args.length == 0 && open(player, SETTINGS, MenuConfig.Trigger.COMMAND)) return;
        if (!management && args.length == 1 && open(player, argumentsId(args[0]), MenuConfig.Trigger.COMMAND)) return;
        List<String> ids = available(player);
        reply(player, ids.isEmpty() ? "&7No menus available." : "&7Open with /menu &f" + String.join("&7, &f", ids));
    }

    private void openEscapeShortcut(Player player, MenuConfig current, String id, String action) {
        MenuConfig.Menu menu = current.menus().get(id);
        if (menu == null || !menu.triggers().contains(MenuConfig.Trigger.ESCAPE) || !allowed(player, menu.permission())
                || !menu.when().test(player)) return;
        for (var button : menu.buttons()) {
            if (button.action().equals(action) && EscapeMenuPack.publicShortcut(button)) {
                Runnable parent = () -> open(player, id, MenuConfig.Trigger.ESCAPE);
                executeButton(player, menu, button, current, parent, player::closeInventory);
                return;
            }
        }
    }

    private List<String> complete(AddonCommands.Context context) {
        String[] args = context.args();
        if (context.sender() instanceof Player player && args.length == 2 && args[0].equals("warp-icon")
                && data().canRun(player, "setwarp")) {
            var travel = data().travel(player, true);
            return java.util.stream.Stream.concat(travel.names().stream(), travel.locked().stream())
                    .filter(name -> name.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        if (context.spec().canonicalName().equals("menu")) {
            return context.sender() instanceof Player player ? available(player).stream()
                    .filter(id -> id.startsWith(prefix) || id.startsWith("allium:" + prefix)).toList() : List.of();
        }
        return context.sender().hasPermission("allium.gui.manage")
                ? List.of("validate", "reload").stream().filter(value -> value.startsWith(prefix)).toList() : List.of();
    }

    private List<String> available(Player player) {
        MenuConfig current = config;
        if (current == null) return List.of();
        return current.menus().values().stream().filter(menu -> allowed(player, menu.permission()))
                .filter(menu -> menu.when().test(player))
                .filter(menu -> menu.triggers().contains(MenuConfig.Trigger.COMMAND)).map(MenuConfig.Menu::id).toList();
    }

    private static String argumentsId(String text) {
        return text.contains(":") ? text : "allium:" + text;
    }

    boolean open(Player player, String id, MenuConfig.Trigger entry) {
        return open(player, id, entry, player::closeInventory);
    }

    private boolean open(Player player, String id, MenuConfig.Trigger entry, Runnable back) {
        MenuConfig current = config;
        if (current == null || !player.isOnline()) return false;
        MenuConfig.Menu menu = current.menus().get(id);
        if (menu == null || !allowed(player, menu.permission()) || !menu.when().test(player) || entry != null && !menu.triggers().contains(entry)) return false;
        if (!menu.source().isEmpty()) return views.openSource(player, menu.source(), menu.title(), menu.inventoryOnly(), back);
        Runnable parent = () -> open(player, id, null, back);
        List<Entry> entries = menu.buttons().stream().filter(button -> usable(player, current, button))
                .map(button -> new Entry(button.slot(), label(player, button), MenuIcons.resolve(this, player, button.icon(), Material.PAPER),
                        () -> executeButton(player, menu, button, current, parent, back))).toList();
        return show(player, MenuVariables.expand(player, menu.title()), menu.size(), menu.inventoryOnly(), entries);
    }

    private void executeButton(Player player, MenuConfig.Menu menu, MenuConfig.Button button, MenuConfig current,
                               Runnable parent, Runnable back) {
        if (config != current || !allowed(player, menu.permission()) || !menu.when().test(player) || !usable(player, current, button)) return;
        String action = button.action();
        if (action.startsWith("menu:")) open(player, action.substring(5), null, parent);
        else if (action.startsWith("view:")) views.openSource(player, action.substring(5), button.label(), false, parent);
        else if (action.startsWith("toggle:")) {
            String key = action.substring(7);
            var state = data().toggle(player, key);
            if (state.isPresent()) runCore(player, "toggle " + key + (state.get() ? " off" : " on"));
            else runCore(player, "toggle " + key);
        } else if (MenuVariables.isAction(action)) {
            MenuVariables.apply(player, action);
            parent.run();
        } else if (action.equals("back")) back.run();
        else if (action.equals("close")) player.closeInventory();
        else runCore(player, action);
    }

    private String label(Player player, MenuConfig.Button button) {
        String label = MenuVariables.expand(player, button.label());
        if (!button.action().startsWith("toggle:")) return label;
        return label + data().toggle(player, button.action().substring(7))
                .map(on -> on ? " &aOn" : " &cOff").orElse("");
    }

    boolean show(Player player, String title, int size, boolean inventoryOnly, List<Entry> entries) {
        MenuConfig current = config;
        if (current == null || !player.isOnline()) return false;
        forms.quit(player);
        String renderedTitle = allium().text().legacy(title);
        var session = new Session(player, UUID.randomUUID().toString(), current, entries,
                System.currentTimeMillis() + SESSION_MILLIS);
        if (paper != null && !current.inventoryOnly() && !inventoryOnly && !entries.isEmpty() && paper.supports(player)) {
            try {
                player.closeInventory();
                sessions.put(player.getUniqueId(), session);
                paper.show(player, renderedTitle, java.util.stream.IntStream.range(0, entries.size())
                        .mapToObj(index -> new DialogRenderer.Button(allium().text().legacy(entries.get(index).label()),
                                "/menus:menus click " + session.token() + " " + index)).toList());
                return true;
            } catch (RuntimeException | LinkageError failure) {
                getLogger().warning("Native dialog unavailable, using inventory: " + failure.getMessage());
            }
        }

        var holder = new MenuHolder(session);
        Inventory inventory = getServer().createInventory(holder, size, renderedTitle);
        holder.inventory = inventory;
        for (Entry entry : entries) {
            var icon = entry.icon().clone();
            var meta = icon.getItemMeta();
            if (meta != null) {
                MenuItemText.name(meta, entry.label() == null ? null : allium().text().legacy(entry.label()));
                icon.setItemMeta(meta);
            }
            inventory.setItem(entry.slot(), icon);
        }
        sessions.put(player.getUniqueId(), session);
        var opened = player.openInventory(inventory);
        if (opened != null && opened.getTopInventory() == inventory) return true;
        sessions.remove(player.getUniqueId(), session);
        return false;
    }

    void invalidateView(Player player) {
        sessions.remove(player.getUniqueId());
    }

    MenuData data() { return getServer().getServicesManager().load(MenuData.class); }
    MenuConfig config() { return config; }
    MenuForms forms() { return forms; }
    AddonConnection core() { return allium(); }

    void runCore(Player player, String command) {
        String root = command.split(" ", 2)[0];
        if (!data().canRun(player, root)) return;
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) player.closeInventory();
        player.performCommand("allium:" + command);
    }

    private void featureCommand(AddonCommands.Context context) {
        if (!(context.sender() instanceof Player player)) {
            reply(context.sender(), "&cOnly players can open menus.");
            return;
        }
        String command = context.spec().canonicalName();
        allium().onOwner(player, () -> {
            if (command.equals("punish") && player.hasPermission("allium.gui.punish")) {
                if (context.args().length == 1) views.punish(player, context.args()[0], player::closeInventory);
                else views.staffPlayers(player, 0, player::closeInventory);
            } else if (command.equals("ptgui")) views.openCore(player, SettingsPresentation.Screen.POWERTOOLS);
            else if (command.equals("shop")) views.openSource(player, "shop", "Shop", true);
            else if (command.equals("joinmessages")) views.openSource(player, "messages", "Join and Leave Messages", true);
        });
    }

    MessagePacks messages() { return messagePacks; }

    ItemStack warpIcon(Player player, String name, Material fallback) {
        return warpIcons.get(this, player, name, fallback);
    }

    void setWarpIcon(Player player, String rawName, String reference) {
        if (!data().canRun(player, "setwarp")) {
            reply(player, "&cYou cannot edit warp icons.");
            return;
        }
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
        var travel = data().travel(player, true);
        if (!name.matches("[a-z0-9_-]{1,32}") || !travel.names().contains(name) && !travel.locked().contains(name)) {
            reply(player, "&cThat warp is unavailable.");
            return;
        }
        ItemStack stack = null;
        if (!reference.equals("clear")) {
            stack = reference.isEmpty() ? player.getInventory().getItemInMainHand().clone()
                    : MenuIcons.resolve(this, player, reference, Material.AIR);
            if (stack.getType().isAir()) { reply(player, "&cHold an item or provide a valid item ID."); return; }
            stack.setAmount(1);
        }
        ItemStack icon = stack;
        allium().scheduler().runOnServer(() -> {
            try {
                warpIcons.set(name, icon);
                reply(player, icon == null ? "&aWarp icon cleared." : "&aWarp icon saved.");
            } catch (Exception failure) {
                getLogger().warning("Could not save warp icon " + name + ": " + failure.getMessage());
                reply(player, "&cCould not save the warp icon.");
            }
        });
    }

    private static boolean allowed(Player player, String permission) {
        return player.hasPermission(USE) && (permission.isEmpty() || player.hasPermission(permission));
    }

    private boolean usable(Player player, MenuConfig current, MenuConfig.Button button) {
        if (!allowed(player, button.permission()) || !button.when().test(player)) return false;
        String action = button.action();
        if (action.startsWith("menu:")) {
            MenuConfig.Menu destination = current.menus().get(action.substring(5));
            return destination != null && allowed(player, destination.permission()) && destination.when().test(player)
                    && (destination.source().isEmpty() || views.allowedView(player, destination.source()));
        }
        if (action.startsWith("view:")) return views.allowedView(player, action.substring(5));
        if (action.startsWith("toggle:")) {
            String key = action.substring(7);
            String legacy = switch (key) {
                case "tpa" -> "tptoggle";
                case "msg" -> "msgtoggle";
                case "pay" -> "paytoggle";
                default -> throw new IllegalStateException("Unknown toggle " + key);
            };
            return player.hasPermission("allium.toggles") && player.hasPermission("allium.toggle." + key)
                    && player.hasPermission("allium." + legacy);
        }
        return action.equals("close") || action.equals("back") || MenuVariables.isAction(action) || player.hasPermission("allium." + action);
    }

    private void activate(Player player, String token, String rawIndex) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player() != player || !session.token().equals(token)
                || session.config() != config || session.expires() <= System.currentTimeMillis()
                || !allowed(player, "")) return;
        int index;
        try {
            index = Integer.parseInt(rawIndex);
        } catch (NumberFormatException invalid) {
            return;
        }
        if (index < 0 || index >= session.buttons().size()) return;
        Entry entry = session.buttons().get(index);
        if (entry.action() != null && sessions.remove(player.getUniqueId(), session)) entry.action().run();
    }


    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        var player = event.getPlayer();
        MenuConfig current = config;
        if (current == null) return;
        boolean first = !player.hasPlayedBefore();
        allium().runForEntity(player, () -> {
            Session session = sessions.get(player.getUniqueId());
            if (config != current || session != null && session.config() == current) return;
            if (first && openTriggered(player, current, MenuConfig.Trigger.FIRST_JOIN)) return;
            openTriggered(player, current, MenuConfig.Trigger.JOIN);
        }, null, current.joinDelayMillis());
    }

    private boolean openTriggered(Player player, MenuConfig current, MenuConfig.Trigger trigger) {
        for (var menu : current.menus().values()) {
            if (menu.triggers().contains(trigger) && open(player, menu.id(), trigger)) return true;
        }
        return false;
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        forms.quit(player);
        sessions.computeIfPresent(player.getUniqueId(), (id, session) -> session.player() == player ? null : session);
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClick() != org.bukkit.event.inventory.ClickType.LEFT) return;
        Session session = holder.session;
        for (int index = 0; index < session.buttons().size(); index++) {
            if (session.buttons().get(index).slot() != event.getRawSlot()) continue;
            String buttonIndex = Integer.toString(index);
            allium().runForEntity(player, () -> {
                if (player.getOpenInventory().getTopInventory() == holder.inventory) activate(player, session.token(), buttonIndex);
            }, null, 0L);
            return;
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder holder) {
            sessions.remove(holder.session.player().getUniqueId(), holder.session);
        }
    }

    private void upgradeDefaults() throws Exception {
        // Upgrade exact bundled files from the starter so local customization is preserved.
        var old = Map.of(
                "dialogs/settings.yml", java.util.Set.of("dcd823b54262ba6af245023f036fb48542d18182c249e5b63c5375c8952fe951",
                        "d2c13e1a2159d20e9f59c0410939f196527f149af398130f72755951d2646c13"),
                "guis/settings.yml", java.util.Set.of("912f52ac772baae3365b70139b2de8f7710bc059a27844e88dbb768d6f8156df",
                        "bbefe621ff88b831386418db9010f2ffdba2e865c275b4b8fd83cffe3bf7a9ef"),
                "dialogs/toggles.yml", java.util.Set.of("d738611f49faffee113c4c8d7c1a2253aa92f2124da5d3a812af14880d012b34",
                        "5abe8772191056e0ba8459b073c09501baf1539df2b45aec690dec1bd08af59a"),
                "dialogs/homes.yml", java.util.Set.of("95e7875e6680cda2fc1974416a2ee2ba055bc3a477f3adcf2de72de6c9fe0993"),
                "dialogs/warps.yml", java.util.Set.of("00569bd61aef996e3211468ff4a60d2d6573a18c8bb20d4f173702fef52db846"),
                "shop.yml", java.util.Set.of("296203976fa9625568edc2d821cc23005b472ae40a29c5407f6e9cd62e9a294c",
                        "256bd1c976b347cd40261eaed742596d05f6cb20ce6d18169a348f25680f7e11"));
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var bundled = new java.util.HashSet<String>();
        for (var entry : old.entrySet()) {
            if (entry.getValue().contains(java.util.HexFormat.of().formatHex(digest.digest(
                    Files.readAllBytes(directory.resolve(entry.getKey())))))) bundled.add(entry.getKey());
        }
        // The settings panel and its inventory layout refer to the same button IDs.
        if (!(bundled.contains("dialogs/settings.yml") && bundled.contains("guis/settings.yml"))) {
            bundled.remove("dialogs/settings.yml");
            bundled.remove("guis/settings.yml");
        }
        for (String name : bundled) {
            try (var in = getResource(name)) {
                Files.copy(in, directory.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private DialogRenderer paperRenderer() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return (DialogRenderer) Class.forName("codes.castled.menus.PaperDialogs").getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    void reply(CommandSender sender, String text) {
        allium().text().send(sender, text);
    }

    private record Session(Player player, String token, MenuConfig config, List<Entry> buttons, long expires) {}

    private static final class MenuHolder implements InventoryHolder {
        private final Session session;
        private Inventory inventory;

        private MenuHolder(Session session) { this.session = session; }

        @Override public Inventory getInventory() { return inventory; }
    }
}
