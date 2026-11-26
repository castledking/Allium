package codes.castled.menus;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class MenuForms {
    private static final int MAX_LENGTH = 256;
    private final MenusPlugin plugin;
    private final DialogRenderer paper;
    private final ConcurrentHashMap<UUID, Pending> inputs = new ConcurrentHashMap<>();

    MenuForms(MenusPlugin plugin, DialogRenderer paper) {
        this.plugin = plugin;
        this.paper = paper;
    }

    boolean edit(Player player, String editor, ItemStack preview, List<String> initial, Consumer<List<String>> submit) {
        if (plugin.config() == null || !plugin.config().textEditors() || !player.hasPermission("allium.gui.use")) return false;
        return switch (editor) {
            case "rename" -> form(player, "Rename Item", "Name", preview, normalize(initial), 1, false, submit);
            case "lore" -> form(player, "Edit Lore", "Line", preview, normalize(initial), 32, false, submit);
            case "nick" -> form(player, "Edit Nickname", "Nickname", null, normalize(initial), 1, false, submit);
            default -> false;
        };
    }

    void prompt(Player player, String title, String field, String initial, Consumer<String> submit) {
        form(player, title, field, null, List.of(initial), 1, false, values -> submit.accept(values.get(0)));
    }

    void inventoryPrompt(Player player, String title, String field, Consumer<String> submit) {
        form(player, title, field, null, List.of(""), 1, true, values -> submit.accept(values.get(0)));
    }

    private static List<String> normalize(List<String> values) {
        return values.isEmpty() ? List.of("") : values.stream().limit(32).map(value -> value.replace('§', '&')).toList();
    }

    private boolean form(Player player, String title, String field, ItemStack preview, List<String> values,
                         int maxFields, boolean forceInventory, Consumer<List<String>> submit) {
        var config = plugin.config();
        if (config == null || !player.isOnline()) return false;
        plugin.invalidateView(player);
        inputs.remove(player.getUniqueId());
        if (paper != null && !forceInventory && !config.inventoryOnly() && paper.supports(player)) {
            try {
                player.closeInventory();
                UUID token = UUID.randomUUID();
                var pending = new Pending(player, config, token, expires(), text -> {});
                inputs.put(player.getUniqueId(), pending);
                paper.form(player, title, field, preview, values, maxFields, MAX_LENGTH,
                        (submitted, add) -> plugin.core().onOwner(player, () -> {
                            if (!take(player, pending) || !valid(submitted, values.size())) return;
                            if (add && submitted.size() < maxFields) {
                                List<String> next = new ArrayList<>(submitted);
                                next.add("");
                                form(player, title, field, preview, next, maxFields, forceInventory, submit);
                            } else if (!add) submit.accept(submitted);
                        }));
                return true;
            } catch (RuntimeException | LinkageError failure) {
                inputs.remove(player.getUniqueId());
                plugin.getLogger().warning("Native text editor unavailable: " + failure.getMessage());
            }
        }
        var entries = new ArrayList<MenusPlugin.Entry>();
        for (int i = 0; i < values.size(); i++) {
            int index = i;
            String label = field + (maxFields > 1 ? " " + (i + 1) : "");
            String text = values.get(i);
            entries.add(new MenusPlugin.Entry(i, "&f" + label + (text.isBlank() ? "" : " &7· " + text),
                    Material.WRITABLE_BOOK, () -> ask(player, label, text, updated -> {
                        List<String> next = new ArrayList<>(values);
                        next.set(index, updated);
                        form(player, title, field, preview, next, maxFields, forceInventory, submit);
                    })));
        }
        if (preview != null) entries.add(new MenusPlugin.Entry(40, "Item Preview", preview, null));
        if (values.size() < maxFields) entries.add(new MenusPlugin.Entry(45, "Add Line", Material.PAPER, () -> {
            List<String> next = new ArrayList<>(values);
            next.add("");
            form(player, title, field, preview, next, maxFields, forceInventory, submit);
        }));
        entries.add(new MenusPlugin.Entry(49, "&aSave", Material.LIME_CONCRETE, () -> {
            player.closeInventory();
            if (valid(values, values.size())) submit.accept(values);
        }));
        entries.add(new MenusPlugin.Entry(53, MenuItemText.CANCEL, Material.BARRIER, player::closeInventory));
        return plugin.show(player, title, 54, true, entries);
    }

    private void ask(Player player, String label, String initial, Consumer<String> submit) {
        var pending = new Pending(player, plugin.config(), UUID.randomUUID(), expires(), submit);
        inputs.put(player.getUniqueId(), pending);
        player.closeInventory();
        String command = "/menus:menus input " + pending.token() + " ";
        var edit = new TextComponent("§a[Edit " + label + "]");
        edit.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command + initial));
        var reset = new TextComponent(" §7[Clear]");
        reset.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command.stripTrailing()));
        edit.addExtra(reset);
        player.spigot().sendMessage(edit);
    }

    void input(Player player, String token, String value) {
        Pending pending = inputs.get(player.getUniqueId());
        if (pending == null || !pending.token().toString().equals(token) || !valid(List.of(value), 1)) return;
        if (take(player, pending)) pending.submit().accept(value);
    }

    private boolean take(Player player, Pending pending) {
        return pending.player() == player && player.isOnline() && player.hasPermission("allium.gui.use")
                && pending.config() == plugin.config() && pending.expires() > System.currentTimeMillis()
                && inputs.remove(player.getUniqueId(), pending);
    }

    private static boolean valid(List<String> values, int count) {
        return values.size() == count && values.stream().allMatch(value -> value != null && value.length() <= MAX_LENGTH
                && value.codePoints().noneMatch(Character::isISOControl));
    }

    void quit(Player player) {
        inputs.computeIfPresent(player.getUniqueId(), (id, previous) -> previous.player() == player ? null : previous);
    }

    void cleanup(long now) { inputs.entrySet().removeIf(entry -> entry.getValue().expires() <= now); }
    void clear() { inputs.clear(); }
    private static long expires() { return System.currentTimeMillis() + 120_000L; }
    private record Pending(Player player, MenuConfig config, UUID token, long expires, Consumer<String> submit) {}
}
