package codes.castled.menus;

import codes.castled.allium.api.MenuData;
import codes.castled.allium.api.SettingsPresentation;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class MenuViews {
    private static final int PAGE_SIZE = 36;
    private static final NamespacedKey TELEPORT_FAVORITES = new NamespacedKey("menus", "teleport_favorites");
    private static final List<String> PUNISHMENTS = List.of("notes", "warn", "kick", "tempban", "ban", "escalate");
    private final MenusPlugin plugin;
    private volatile ShopCatalog shop;

    MenuViews(MenusPlugin plugin) { this.plugin = plugin; }
    void shop(ShopCatalog catalog) { shop = catalog; }

    boolean openCore(Player player, SettingsPresentation.Screen screen) {
        return switch (screen) {
            case HOMES -> openSource(player, "homes", "&8Homes", true);
            case WARPS -> openSource(player, "warps", "&8Warps", true);
            case SELL -> openSource(player, "sell", "&8Sell Items", true);
            case POWERTOOLS -> openSource(player, "powertools", "&8Powertools", true);
        };
    }

    boolean allowedView(Player player, String source) {
        if (plugin.config() == null || plugin.data() == null || !player.hasPermission("allium.gui.use")) return false;
        if (source.equals("staff")) return canPunish(player) || allowedView(player, "powertools");
        if (!player.hasPermission("allium.gui." + source)) return false;
        return switch (source) {
            case "homes" -> can(player, "home") || can(player, "homes") || can(player, "sethome");
            case "warps" -> can(player, "warp") || can(player, "warps") || can(player, "setwarp");
            case "teleport" -> can(player, "tpa") || can(player, "tpahere");
            case "powertools" -> can(player, "powertool");
            case "sell" -> can(player, "sell");
            case "shop", "messages" -> true;
            default -> false;
        };
    }

    boolean openSource(Player player, String source, String title, boolean inventory) {
        return openSource(player, source, title, inventory, player::closeInventory);
    }

    boolean openSource(Player player, String source, String title, boolean inventory, Runnable back) {
        if (!allowedView(player, source)) return false;
        return switch (source) {
            case "homes", "warps" -> travel(player, source.equals("warps"), title, inventory, 0, false, back);
            case "teleport" -> teleport(player, title, inventory, 0, back);
            case "staff" -> staff(player, back);
            case "powertools" -> tools(player, 0, back);
            case "sell" -> sell(player, 0, back);
            case "shop" -> shop(player, back);
            case "messages" -> plugin.messages().open(plugin, player, back);
            default -> false;
        };
    }

    private boolean travel(Player viewer, boolean warps, String title, boolean inventory, int page, boolean expanded, Runnable back) {
        if (!allowedView(viewer, warps ? "warps" : "homes")) return false;
        var data = plugin.data().travel(viewer, warps);
        List<Choice> choices = new ArrayList<>();
        if (!data.ready()) {
            choices.add(choice("&7Destinations are loading", Material.CLOCK, () -> travel(viewer, warps, title, inventory, page, expanded, back)));
        } else {
            for (String name : data.names()) {
                choices.add(new Choice("&a" + name, warps ? plugin.warpIcon(viewer, name, Material.ENDER_PEARL) : new ItemStack(Material.WHITE_BED),
                        () -> destination(viewer, warps, name, true, title, inventory, page, expanded, back)));
            }
            int available = Math.max(0, data.limit() - data.names().size());
            int free = data.canCreate() ? (warps ? 1 : data.unlimited() ? Math.min(1, available) : available) : 0;
            if (!warps && can(viewer, "bed")) choices.add(choice("&eBed", Material.RED_BED, () -> run(viewer, "bed")));
            if (free > 0) choices.addAll(java.util.Collections.nCopies(free, choice(
                    warps ? "&eNew Warp" : "&eNew Home", Material.LIME_STAINED_GLASS_PANE,
                    () -> createDestination(viewer, warps))));
            if (expanded) {
                int count = warps ? plugin.config().lockedWarps() : plugin.config().lockedHomes();
                if (warps) {
                    for (String name : data.locked().stream().limit(count).toList()) {
                        choices.add(new Choice("&c" + name + " &7(Restricted)", plugin.warpIcon(viewer, name, Material.IRON_BARS),
                                () -> destination(viewer, true, name, false, title, inventory, page, true, back)));
                    }
                }
                if (warps) count = Math.max(0, count - data.locked().size());
                if (count > 0) choices.addAll(java.util.Collections.nCopies(count, choice("&cLocked", Material.RED_STAINED_GLASS_PANE, null)));
            }
        }
        var extra = data.ready() && (warps ? data.canCreate() && plugin.config().lockedWarps() > 0
                : !data.unlimited() && plugin.config().lockedHomes() > 0) ? choice(expanded ? "Show Less" : "Show More", Material.SPYGLASS,
                () -> travel(viewer, warps, title, inventory, 0, !expanded, back)) : null;
        return paged(viewer, title, inventory, choices, page, next -> travel(viewer, warps, title, inventory, next, expanded, back),
                back, extra);
    }

    private void createDestination(Player player, boolean warp) {
        String command = warp ? "setwarp" : "sethome";
        if (!can(player, command)) return;
        var snapshot = plugin.data().travel(player, warp);
        if (!snapshot.ready() || !snapshot.canCreate() || !warp && snapshot.names().size() >= snapshot.limit()) return;
        plugin.forms().prompt(player, warp ? "New Warp" : "New Home", "Name", "", name -> {
            if (!destinationName(name)) { plugin.reply(player, "&cEnter a valid destination name."); return; }
            var current = plugin.data().travel(player, warp);
            if (current.names().contains(name.toLowerCase(java.util.Locale.ROOT)) || current.locked().contains(name.toLowerCase(java.util.Locale.ROOT))) {
                plugin.reply(player, "&cThat destination already exists.");
                return;
            }
            run(player, command + " " + name);
        });
    }

    private void destination(Player viewer, boolean warp, String name, boolean accessible, String title,
                             boolean inventory, int page, boolean expanded, Runnable back) {
        String type = warp ? "warp" : "home";
        List<Choice> options = new ArrayList<>();
        if (accessible && can(viewer, type)) options.add(choice("&aTeleport", Material.ENDER_PEARL, () -> run(viewer, type + " " + name)));
        if (warp && can(viewer, "setwarp")) options.add(choice("Set Icon from Hand", Material.ITEM_FRAME,
                () -> plugin.setWarpIcon(viewer, name, "")));
        if (warp && can(viewer, "warpinfo")) options.add(choice("View details", Material.PAPER, () -> run(viewer, "warpinfo " + name)));
        if (!warp && can(viewer, "renamehome")) options.add(choice("Rename", Material.NAME_TAG,
                () -> plugin.forms().prompt(viewer, "Rename Home", "Name", name,
                        value -> { if (destinationName(value)) run(viewer, "renamehome " + name + " " + value); })));
        if (can(viewer, "del" + type)) options.add(choice("&cDelete", Material.LAVA_BUCKET,
                () -> confirm(viewer, "Delete " + name + "?", inventory, () -> run(viewer, "del" + type + " " + name),
                        () -> destination(viewer, warp, name, accessible, title, inventory, page, expanded, back))));
        paged(viewer, name, inventory, options, 0, ignored -> {}, () -> travel(viewer, warp, title, inventory, page, expanded, back), null);
    }

    private boolean teleport(Player viewer, String title, boolean inventory, int page, Runnable back) {
        if (!allowedView(viewer, "teleport")) return false;
        List<MenuData.Target> online = plugin.data().onlinePlayers(viewer);
        List<UUID> favorites = favorites(viewer);
        List<Choice> choices = online.stream().sorted(java.util.Comparator.comparingInt(target -> {
            int index = favorites.indexOf(target.id());
            return index < 0 ? Integer.MAX_VALUE : index;
        })).map(target -> new Choice(target.name(), head(target.name()),
                () -> teleportActions(viewer, target, title, inventory, page, back))).toList();
        return paged(viewer, title, inventory, choices, page, next -> teleport(viewer, title, inventory, next, back),
                back, choice("Add Player to List", Material.NAME_TAG, () -> plugin.forms().prompt(viewer,
                        "Add a Player", "Player Name", "", name -> {
                            var target = target(viewer, name);
                            if (target == null) { plugin.reply(viewer, "&cThat player is unavailable."); return; }
                            List<UUID> ids = new ArrayList<>(favorites(viewer));
                            ids.remove(target.id());
                            ids.add(0, target.id());
                            saveFavorites(viewer, ids.stream().limit(50).toList());
                            teleport(viewer, title, inventory, 0, back);
                        })));
    }

    private void teleportActions(Player viewer, MenuData.Target target, String title, boolean inventory, int page, Runnable back) {
        List<Choice> buttons = new ArrayList<>();
        if (can(viewer, "tpa")) buttons.add(choice("Teleport to", Material.ENDER_PEARL, () -> request(viewer, target, "tpa")));
        if (can(viewer, "tpahere")) buttons.add(choice("Teleport here", Material.ENDER_EYE, () -> request(viewer, target, "tpahere")));
        if (favorites(viewer).contains(target.id())) buttons.add(choice("Remove from List", Material.SHEARS, () -> {
            saveFavorites(viewer, favorites(viewer).stream().filter(id -> !id.equals(target.id())).toList());
            teleport(viewer, title, inventory, page, back);
        }));
        paged(viewer, "Teleport with " + target.name() + "?", inventory, buttons, 0, ignored -> {},
                () -> teleport(viewer, title, inventory, page, back), null);
    }

    private void request(Player requester, MenuData.Target target, String command) {
        var current = target(requester, target.name());
        if (current == null || !current.id().equals(target.id())) { plugin.reply(requester, "&cThat player is unavailable."); return; }
        run(requester, command + " " + current.name());
    }

    private MenuData.Target target(Player viewer, String name) {
        return plugin.data().onlinePlayers(viewer).stream().filter(target -> target.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private static List<UUID> favorites(Player player) {
        String saved = player.getPersistentDataContainer().get(TELEPORT_FAVORITES, PersistentDataType.STRING);
        if (saved == null || saved.isBlank()) return List.of();
        var ids = new ArrayList<UUID>();
        for (String token : saved.split(",", 51)) {
            try { ids.add(UUID.fromString(token)); } catch (IllegalArgumentException ignored) {}
        }
        return ids;
    }

    private static void saveFavorites(Player player, List<UUID> ids) {
        player.getPersistentDataContainer().set(TELEPORT_FAVORITES, PersistentDataType.STRING,
                String.join(",", ids.stream().map(UUID::toString).toList()));
    }

    private boolean canPunish(Player player) {
        return player.hasPermission("allium.gui.punish") && PUNISHMENTS.stream().anyMatch(command -> can(player, command));
    }

    private boolean staff(Player viewer, Runnable back) {
        var choices = new ArrayList<Choice>();
        if (canPunish(viewer)) choices.add(choice("Punish Player", Material.IRON_AXE, () -> staffPlayers(viewer, 0, () -> staff(viewer, back))));
        if (allowedView(viewer, "powertools")) choices.add(choice("Powertools", Material.NETHER_STAR, () -> tools(viewer, 0, () -> staff(viewer, back))));
        return paged(viewer, "&8Staff Tools", true, choices, 0, ignored -> {}, back, null);
    }

    void staffPlayers(Player viewer, int page, Runnable back) {
        if (!canPunish(viewer)) return;
        List<Choice> choices = plugin.data().onlinePlayers(viewer).stream().map(target -> new Choice(target.name(), head(target.name()),
                () -> punish(viewer, target.name(), () -> staffPlayers(viewer, page, back)))).toList();
        paged(viewer, "&8Choose a Player", true, choices, page, next -> staffPlayers(viewer, next, back), back,
                choice("Search by Name", Material.NAME_TAG, () -> plugin.forms().prompt(viewer, "Player Lookup", "Player name", "",
                        name -> punish(viewer, name, () -> staffPlayers(viewer, page, back)))));
    }

    void punish(Player viewer, String target, Runnable back) {
        if (!canPunish(viewer) || !target.matches("[A-Za-z0-9_]{1,16}")) return;
        var online = plugin.getServer().getPlayerExact(target);
        if (online != null && online != viewer && target(viewer, target) == null) {
            plugin.reply(viewer, "&cThat player is unavailable.");
            return;
        }
        List<Choice> actions = new ArrayList<>();
        if (can(viewer, "notes")) {
            actions.add(choice("View Notes", Material.BOOK, () -> run(viewer, "notes " + target + " list")));
            actions.add(choice("Add Note", Material.WRITABLE_BOOK, () -> reason(viewer, target, "notes", back)));
        }
        addPunishment(viewer, target, actions, "warn", "Warn", Material.PAPER, back);
        addPunishment(viewer, target, actions, "kick", "Kick", Material.LEATHER_BOOTS, back);
        addPunishment(viewer, target, actions, "tempban", "Temporary Ban", Material.CLOCK, back);
        addPunishment(viewer, target, actions, "ban", "Permanent Ban", Material.IRON_BARS, back);
        addPunishment(viewer, target, actions, "escalate", "Escalate to Senior Staff", Material.BELL, back);
        if (can(viewer, "unnote")) actions.add(choice("Remove Note", Material.SHEARS, () ->
                plugin.forms().prompt(viewer, "Remove Staff Note", "Note ID", "", id -> {
                    if (id.matches("[1-9][0-9]{0,18}")) confirmPunishment(viewer, target, "unnote", id, back);
                })));
        if (can(viewer, "unban")) actions.add(choice("Unban", Material.MILK_BUCKET, () ->
                confirm(viewer, "Unban " + target + "?", true, () -> run(viewer, "unban " + target), () -> punish(viewer, target, back))));
        if (can(viewer, "warn")) actions.add(choice("Warning History", Material.BOOK, () -> run(viewer, "warn " + target + " history")));
        paged(viewer, "&4Punish " + target, true, actions, 0, ignored -> {}, back, null);
    }

    private void addPunishment(Player viewer, String name, List<Choice> choices, String command, String label, Material icon, Runnable back) {
        if (can(viewer, command)) choices.add(choice(label, icon, () -> reason(viewer, name, command, back)));
    }

    private void reason(Player viewer, String target, String command, Runnable back) {
        if (!canPunish(viewer) || !can(viewer, command)) return;
        if (command.equals("notes")) {
            plugin.forms().prompt(viewer, "Staff Note", "Note", "", text -> submitPunishment(viewer, target, command, text, back));
            return;
        }
        var reasons = List.of("Harassment", "Cheating", "Griefing", "Spam").stream()
                .map(text -> choice(text, Material.PAPER, () -> submitPunishment(viewer, target, command, text, back))).toList();
        paged(viewer, "&8Select a Reason", true, reasons, 0, ignored -> {}, () -> punish(viewer, target, back),
                choice("Custom Reason", Material.WRITABLE_BOOK, () -> plugin.forms().prompt(viewer, "Custom Reason", "Reason", "",
                        text -> submitPunishment(viewer, target, command, text, back))));
    }

    private void submitPunishment(Player viewer, String target, String command, String reason, Runnable back) {
        if (reason.isBlank() || reason.length() > 200 || !canPunish(viewer) || !can(viewer, command)) return;
        if (command.equals("tempban")) {
            var durations = List.of("1h", "1d", "7d", "30d").stream().map(duration -> choice(duration, Material.CLOCK,
                    () -> confirmPunishment(viewer, target, command, duration + " " + reason, back))).toList();
            paged(viewer, "&8Temporary Ban Duration", true, durations, 0, ignored -> {}, () -> punish(viewer, target, back),
                    choice("Custom Duration", Material.WRITABLE_BOOK, () -> plugin.forms().prompt(viewer, "Ban Duration", "Duration",
                            "", duration -> { if (duration.matches("[1-9][0-9]{0,3}[smhdw]")) confirmPunishment(viewer, target, command, duration + " " + reason, back); })));
        } else confirmPunishment(viewer, target, command, command.equals("notes") ? "add " + reason : reason, back);
    }

    private void confirmPunishment(Player viewer, String target, String command, String args, Runnable back) {
        confirm(viewer, command + " " + target + "?", true, () -> {
            if (canPunish(viewer)) run(viewer, command + " " + target + " " + args);
        }, () -> punish(viewer, target, back));
    }

    private boolean tools(Player player, int page, Runnable back) {
        if (!allowedView(player, "powertools")) return false;
        List<Choice> choices = plugin.data().powerTools(player).stream()
                .map(tool -> choice((tool.enabled() ? "&a" : "&7") + tool.id(), Material.BLAZE_ROD,
                        () -> tool(player, tool, page, back))).toList();
        return paged(player, "&8Powertools", true, choices, page, next -> tools(player, next, back), back,
                choice("Edit Held Tool", Material.ANVIL, () -> editTool(player, back)));
    }

    private void tool(Player player, MenuData.Tool tool, int page, Runnable back) {
        if (!allowedView(player, "powertools") || !tool.id().matches("[a-z0-9_./-]+")) return;
        List<Choice> choices = new ArrayList<>();
        if (player.hasPermission("allium.powertool.give") && tool.enabled()) choices.add(choice("Get Tool", Material.CHEST, () -> run(player, "powertool get " + tool.id())));
        if (player.hasPermission("allium.powertool.bind") && tool.enabled()) choices.add(choice("Bind to Held Item", Material.ANVIL, () -> run(player, "powertool bind " + tool.id())));
        paged(player, tool.id(), true, choices, 0, ignored -> {}, () -> tools(player, page, back), null);
    }

    private void editTool(Player player, Runnable back) {
        var choices = new ArrayList<Choice>();
        choices.add(choice("Inspect Held Tool", Material.BOOK, () -> run(player, "powertool info")));
        if (player.hasPermission("allium.powertool.edit")) {
            choices.add(choice("Add Player Command", Material.PAPER, () -> toolPrompt(player, "Command", value ->
                    run(player, "powertool /" + value.replaceFirst("^/", "")))));
            choices.add(choice("Remove Right-Click Action", Material.SHEARS, () -> toolPrompt(player, "Action Number", value -> {
                if (value.matches("[1-9][0-9]{0,2}")) run(player, "powertool remove " + value);
            })));
            choices.add(choice("&cClear Held Tool", Material.BARRIER, () -> {
                ItemStack held = player.getInventory().getItemInMainHand().clone();
                confirm(player, "Clear Held Tool?", true, () -> {
                    if (held.equals(player.getInventory().getItemInMainHand())) run(player, "powertool clear");
                }, () -> editTool(player, back));
            }));
        }
        if (player.hasPermission("allium.powertool.save")) choices.add(choice("Save as Named Tool", Material.WRITABLE_BOOK,
                () -> toolPrompt(player, "Tool Name", value -> {
                    if (value.matches("[a-z0-9_-]+")) run(player, "powertool save " + value);
                })));
        paged(player, "&8Edit Held Tool", true, choices, 0, ignored -> {}, () -> tools(player, 0, back), null);
    }

    private void toolPrompt(Player player, String label, java.util.function.Consumer<String> action) {
        var item = player.getInventory().getItemInMainHand().clone();
        plugin.forms().inventoryPrompt(player, "Edit Held Tool", label, value -> {
            if (!player.hasPermission("allium.gui.powertools") || !item.equals(player.getInventory().getItemInMainHand())) return;
            if (!value.isBlank()) action.accept(value);
        });
    }

    private boolean sell(Player player, int page, Runnable back) {
        if (!allowedView(player, "sell")) return false;
        List<ItemStack> stock = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (plugin.data().sellable(item) && stock.stream().noneMatch(sample -> sample.isSimilar(item))) stock.add(item.clone());
        }
        var choices = new ArrayList<Choice>();
        if (player.hasPermission("allium.sell.hand")) choices.add(choice("Sell Held Stack", Material.HOPPER, () -> run(player, "sell hand")));
        for (ItemStack item : stock) {
            String label = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? item.getItemMeta().getDisplayName() : item.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            choices.add(new Choice("&aSell " + label, item, () -> {
                if (!allowedView(player, "sell")) return;
                player.closeInventory();
                plugin.data().sell(player, item);
            }));
        }
        return paged(player, "&8Sell Items", true, choices, page, next -> sell(player, next, back), back,
                player.hasPermission("allium.sell.bulk") ? choice("Sell Inventory", Material.CHEST,
                        () -> run(player, "sell inventory")) : null);
    }

    private boolean shop(Player player, Runnable back) {
        if (!allowedView(player, "shop")) return false;
        ShopCatalog current = shop;
        var slots = new java.util.TreeMap<Integer, MenusPlugin.Entry>();
        int end = (current.rows() - 1) * 9;
        for (ShopCatalog.Category category : current.categories()) {
            if (category.slot() < 0) continue;
            slots.put(category.slot(), categoryEntry(player, category, category.slot(), back));
        }
        int slot = 0;
        for (ShopCatalog.Category category : current.categories()) {
            if (category.slot() >= 0) continue;
            while (slots.containsKey(slot)) slot++;
            if (slot >= end) break;
            slots.put(slot, categoryEntry(player, category, slot, back));
        }
        int footer = end;
        slots.put(footer, new MenusPlugin.Entry(footer, MenuItemText.BACK, Material.ARROW, back));
        if (allowedView(player, "sell")) {
            slots.put(footer + 7, new MenusPlugin.Entry(footer + 7, "<#ebd12a><b>Sell Items</b></#ebd12a>", Material.HOPPER,
                    () -> sell(player, 0, () -> shop(player, back))));
        }
        slots.put(footer + 4, new MenusPlugin.Entry(footer + 4, MenuItemText.SEARCH, Material.OAK_SIGN,
                () -> plugin.forms().inventoryPrompt(player, "Search the Shop", "Item name",
                        term -> searchShop(player, term, 0, back))));
        if (!current.auctionCommand().isEmpty() && plugin.getServer().getPluginManager().isPluginEnabled("ExcellentShop")) {
            slots.put(footer + 8, new MenusPlugin.Entry(footer + 8, "Auction House", Material.GOLD_INGOT, () -> {
                if (allowedView(player, "shop") && current == shop) {
                    player.closeInventory();
                    player.performCommand(current.auctionCommand());
                }
            }));
        }
        return plugin.show(player, current.title(), current.rows() * 9, true, List.copyOf(slots.values()));
    }

    private MenusPlugin.Entry categoryEntry(Player player, ShopCatalog.Category category, int slot, Runnable back) {
        ItemStack icon = !category.texture().isEmpty() ? MenuIcons.texture(category.texture())
                : MenuIcons.resolve(plugin, player, category.icon(), Material.CHEST);
        MenuIcons.lore(icon, category.lore().stream().map(plugin.core().text()::legacy).toList());
        return new MenusPlugin.Entry(slot, category.displayName(), icon, () -> shopCategory(player, category, 0, back));
    }

    private boolean shopCategory(Player player, ShopCatalog.Category category, int page, Runnable back) {
        if (!allowedView(player, "shop") || !shop.categories().contains(category)) return false;
        Runnable parent = () -> shop(player, back);
        if (category.pages() == 0) {
            var choices = category.products().stream().map(product -> productChoice(player, category, product,
                    () -> shopCategory(player, category, page, back))).toList();
            return paged(player, category.title(), true, choices, page,
                    next -> shopCategory(player, category, next, back), parent, null);
        }
        var entries = new ArrayList<MenusPlugin.Entry>();
        for (var item : category.products()) {
            if (item.page() != page + 1) continue;
            entries.add(productChoice(player, category, item, () -> shopCategory(player, category, page, back)).at(item.slot()));
        }
        entries.add(new MenusPlugin.Entry(45, MenuItemText.CLOSE, Material.SPRUCE_DOOR, player::closeInventory));
        entries.add(new MenusPlugin.Entry(47, MenuItemText.SEARCH, Material.OAK_SIGN, () -> plugin.forms().inventoryPrompt(player,
                "Search the Shop", "Item name", term -> searchShop(player, term, 0, back))));
        entries.add(new MenusPlugin.Entry(49, MenuItemText.BACK, Material.ARROW, parent));
        if (page > 0) entries.add(new MenusPlugin.Entry(48, MenuItemText.PREVIOUS, Material.ARROW,
                () -> shopCategory(player, category, page - 1, back)));
        if (page + 1 < category.pages()) entries.add(new MenusPlugin.Entry(50, MenuItemText.NEXT, Material.ARROW,
                () -> shopCategory(player, category, page + 1, back)));
        if (allowedView(player, "sell")) entries.add(new MenusPlugin.Entry(52, "<#ebd12a><b>Sell Items</b></#ebd12a>", Material.HOPPER,
                () -> sell(player, 0, () -> shopCategory(player, category, page, back))));
        return plugin.show(player, "&8Shop → " + category.title() + " (" + (page + 1) + "/" + category.pages() + ")",
                54, true, entries);
    }

    private void searchShop(Player player, String raw, int page, Runnable back) {
        if (!allowedView(player, "shop")) return;
        String term = raw.strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        var found = new ArrayList<Choice>();
        if (!term.isEmpty()) for (var category : shop.categories()) {
            for (var product : category.products()) {
                if (product.id().contains(term) || product.reference().contains(term)) {
                    found.add(productChoice(player, category, product, () -> searchShop(player, raw, page, back)));
                    if (found.size() >= 108) break;
                }
            }
            if (found.size() >= 108) break;
        }
        paged(player, "&8Shop Search", true, found, page, next -> searchShop(player, raw, next, back),
                () -> shop(player, back), null);
    }

    private Choice productChoice(Player player, ShopCatalog.Category category, ShopCatalog.Product product, Runnable back) {
        var display = productItem(product);
        String name = product.displayName();
        String label = "&f&l" + name;
        if (display == null) return choice("&7" + name + " &8(Unavailable)", Material.BARRIER, null);
        var meta = display.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName() && !org.bukkit.ChatColor.stripColor(meta.getDisplayName()).isBlank()) label = null;
            var prices = new ArrayList<String>();
            var economy = plugin.core().economy();
            if (product.buy() > 0) prices.add("&aBuy &f" + economy.format(product.buy()) + " &7each");
            if (product.sell() && can(player, "sell")) {
                double sell = product.sellPrice() == null ? plugin.data().sellPrice(display).orElse(0.0) : price(product);
                if (sell > 0) prices.add("&cSell &f" + economy.format(sell) + " &7each");
            }
            MenuItemText.lore(meta, prices.stream().map(plugin.core().text()::legacy).toList(), true);
            display.setItemMeta(meta);
        }
        return new Choice(label, display, () -> product(player, category, product, back));
    }

    private ItemStack productItem(ShopCatalog.Product product) {
        if (product.itemArgument().isEmpty()) return plugin.core().items().create(product.reference(), 1).stack().orElse(null);
        try {
            return plugin.getServer().getItemFactory().createItemStack(product.itemArgument());
        } catch (RuntimeException unavailable) {
            return LegacyShopItems.create(product);
        }
    }

    private void product(Player player, ShopCatalog.Category category, ShopCatalog.Product product, Runnable back) {
        if (!allowedView(player, "shop") || !shop.categories().contains(category)) return;
        var choices = new ArrayList<Choice>();
        if (product.buy() > 0) choices.add(choice("&a&lBuy 1 &7· &f" + plugin.core().economy().format(product.buy()), Material.EMERALD,
                () -> purchase(player, category, product, back)));
        if (product.sell() && can(player, "sell")) {
            double quote = price(product);
            choices.add(choice(product.sellPrice() == null ? "&c&lSell" : "&c&lSell &7· &f" + plugin.core().economy().format(quote) + " &7each",
                    Material.HOPPER, () -> {
                        if (!allowedView(player, "shop") || !shop.categories().contains(category) || !can(player, "sell")) return;
                        if (product.sellPrice() == null) { run(player, "sell " + product.reference()); return; }
                        if (quote != price(product)) {
                            plugin.reply(player, "&eThe price changed. Review the new price.");
                            product(player, category, product, back);
                            return;
                        }
                        var template = productItem(product);
                        if (template == null) return;
                        player.closeInventory();
                        plugin.data().sellQuoted(player, template, quote);
                    }));
        }
        paged(player, product.displayName(), true, choices, 0, ignored -> {}, back, null);
    }

    private static double price(ShopCatalog.Product product) {
        return product.sellPrice() == null ? 0 : product.sellPrice().amount(product.id(), System.currentTimeMillis() / 1000);
    }

    private void purchase(Player player, ShopCatalog.Category category, ShopCatalog.Product product, Runnable back) {
        if (!allowedView(player, "shop") || !shop.categories().contains(category)
                || !plugin.core().canEditInventory(player) || product.buy() <= 0) return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        var stack = productItem(product);
        if (stack == null) { plugin.reply(player, "&cThis item is unavailable."); return; }
        if (fittingSlot(player, stack) < 0) { plugin.reply(player, "&cYour inventory is full."); return; }
        var economy = plugin.core().economy();
        economy.reconcilePaidActions(player);
        var result = economy.executePaidAction(player, product.buy(), () -> {
            if (!plugin.core().canEditInventory(player) || !allowedView(player, "shop") || !shop.categories().contains(category)) return false;
            int current = fittingSlot(player, stack);
            if (current < 0) return false;
            ItemStack present = player.getInventory().getItem(current);
            ItemStack delivered = stack.clone();
            if (present != null && !present.getType().isAir()) delivered.setAmount(present.getAmount() + 1);
            player.getInventory().setItem(current, delivered);
            return true;
        });
        plugin.reply(player, result.applied() ? "&aPurchased " + product.reference() + "." : "&cPurchase failed: " + result.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        product(player, category, product, back);
    }

    private static int fittingSlot(Player player, ItemStack stack) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int empty = -1;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                if (empty < 0) empty = slot;
            } else if (item.isSimilar(stack) && item.getAmount() < item.getMaxStackSize()) return slot;
        }
        return empty;
    }

    private boolean paged(Player player, String title, boolean inventory, List<Choice> choices, int page,
                          java.util.function.IntConsumer next, Runnable back, Choice extra) {
        int max = Math.max(0, (choices.size() - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, max));
        List<MenusPlugin.Entry> entries = new ArrayList<>();
        for (int i = current * PAGE_SIZE; i < Math.min(choices.size(), (current + 1) * PAGE_SIZE); i++) {
            entries.add(choices.get(i).at(i % PAGE_SIZE));
        }
        if (entries.isEmpty()) entries.add(choice("&7Nothing to show", Material.GRAY_STAINED_GLASS_PANE, null).at(13));
        if (current > 0) entries.add(choice(MenuItemText.PREVIOUS, Material.ARROW, () -> next.accept(current - 1)).at(45));
        if (extra != null) entries.add(extra.at(47));
        if (back != null) entries.add(choice(MenuItemText.BACK, Material.ARROW, back).at(49));
        if (current < max) entries.add(choice(MenuItemText.NEXT, Material.ARROW, () -> next.accept(current + 1)).at(53));
        return plugin.show(player, title + (max == 0 ? "" : " &7(" + (current + 1) + "/" + (max + 1) + ")"), 54, inventory, entries);
    }

    private void confirm(Player player, String title, boolean inventory, Runnable action, Runnable back) {
        plugin.show(player, title, 27, inventory, List.of(
                new MenusPlugin.Entry(11, "&aConfirm", Material.LIME_CONCRETE, action),
                new MenusPlugin.Entry(15, MenuItemText.CANCEL, Material.RED_CONCRETE, back)));
    }

    private boolean can(Player player, String command) { return plugin.data().canRun(player, command); }
    private void run(Player player, String command) { plugin.runCore(player, command); }

    private static boolean destinationName(String raw) { return raw.matches("[A-Za-z0-9_-]{1,32}"); }

    private static ItemStack head(String name) {
        var item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwner(name);
            item.setItemMeta(meta);
        }
        return item;
    }
    private static Choice choice(String label, Material icon, Runnable action) { return new Choice(label, new ItemStack(icon), action); }
    private record Choice(String label, ItemStack icon, Runnable action) {
        MenusPlugin.Entry at(int slot) { return new MenusPlugin.Entry(slot, label, icon, action); }
    }
}
