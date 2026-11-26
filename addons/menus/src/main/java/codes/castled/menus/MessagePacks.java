package codes.castled.menus;

import codes.castled.allium.api.JoinQuitMessageSelection;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

record MessagePacks(String title, int rows, Map<String, Pack> packs) implements JoinQuitMessageSelection {
    private static final String SELECTION = "message-pack";
    record Pack(String title, String texture, String permission, String join, String quit, int slot) {}

    static MessagePacks load(Path file) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        int rows = yaml.getInt("rows", 5);
        if (rows < 2 || rows > 6) throw new IllegalArgumentException("message-packs.yml: rows must be 2..6");
        var entries = yaml.getConfigurationSection("packs");
        var packs = new LinkedHashMap<String, Pack>();
        var slots = new HashSet<Integer>();
        if (entries != null) for (String id : entries.getKeys(false)) {
            var entry = entries.getConfigurationSection(id);
            if (entry == null || !id.matches("[a-z0-9_-]{1,64}") || id.equals("none")) throw invalid(id);
            String title = entry.getString("title", id);
            String texture = entry.getString("texture", "");
            String permission = entry.getString("permission", "allium.messagepack." + id);
            String join = entry.getString("join", "");
            String quit = entry.getString("quit", "");
            int slot = entry.getInt("slot", -1);
            if (title.isBlank() || !texture.isEmpty() && !texture.matches("[0-9a-f]{32,64}")
                    || !permission.matches("[a-z0-9_.-]+") || join.isBlank() || quit.isBlank()
                    || join.length() > 512 || quit.length() > 512
                    || slot < 0 || slot >= (rows - 1) * 9 || !slots.add(slot)) throw invalid(id);
            packs.put(id, new Pack(title, texture, permission, join, quit, slot));
        }
        return new MessagePacks(yaml.getString("title", "&8Join and Leave Messages"), rows, java.util.Collections.unmodifiableMap(packs));
    }

    boolean open(MenusPlugin plugin, Player player, Runnable back) {
        if (!player.hasPermission("allium.gui.messages")) return false;
        String selected = MenuVariables.get(player, SELECTION);
        var buttons = new ArrayList<MenusPlugin.Entry>();
        packs.forEach((id, pack) -> {
            boolean unlocked = player.hasPermission(pack.permission());
            var icon = unlocked ? MenuIcons.texture(pack.texture()) : new org.bukkit.inventory.ItemStack(Material.FEATHER);
            var text = plugin.core().text();
            MenuIcons.lore(icon, java.util.List.of(text.legacy("&7Join  " + preview(player, pack.join())),
                    text.legacy("&7Leave  " + preview(player, pack.quit())),
                    text.legacy(!unlocked ? "&cLocked" : id.equals(selected) ? "&aSelected · Click to deselect" : "&7Click to select")));
            buttons.add(new MenusPlugin.Entry(pack.slot(), unlocked ? pack.title() : "&7" + pack.title(), icon, !unlocked ? null : () -> {
                if (!player.hasPermission("allium.gui.messages") || !player.hasPermission(pack.permission())) return;
                MenuVariables.set(player, SELECTION, id.equals(MenuVariables.get(player, SELECTION)) ? "none" : id);
                open(plugin, player, back);
            }));
        });
        int footer = (rows - 1) * 9;
        buttons.add(new MenusPlugin.Entry(footer + 4, "Use Default Messages", Material.PAPER, () -> {
            if (!player.hasPermission("allium.gui.messages")) return;
            MenuVariables.set(player, SELECTION, "none");
            open(plugin, player, back);
        }));
        buttons.add(new MenusPlugin.Entry(footer + 8, MenuItemText.BACK, Material.ARROW, back));
        return plugin.show(player, title, rows * 9, true, buttons);
    }

    @Override public Selection selected(Player player, String kind) {
        String id = MenuVariables.get(player, SELECTION);
        if (id == null) return null;
        Pack pack = packs.get(id);
        if (pack == null || !player.hasPermission(pack.permission())) return new Selection(null);
        return new Selection(kind.equals("join") ? pack.join() : pack.quit());
    }

    private static String preview(Player player, String message) {
        return message.replace("%allium_gradientdisplayname%", player.getDisplayName())
                .replace("%name%", player.getDisplayName()).replace("%player%", player.getName());
    }

    private static IllegalArgumentException invalid(String id) {
        return new IllegalArgumentException("message-packs.yml: invalid or duplicate pack " + id);
    }
}
