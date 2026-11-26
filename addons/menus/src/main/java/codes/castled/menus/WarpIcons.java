package codes.castled.menus;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

final class WarpIcons {
    record Icon(ItemStack stack, String item, String texture, Predicate<Player> when) {}
    private final Path file;
    private volatile Map<String, Icon> icons = Map.of();

    WarpIcons(Path file) { this.file = file; }

    synchronized Map<String, Icon> read() throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return parse(yaml);
    }

    void install(Map<String, Icon> snapshot) { icons = snapshot; }

    ItemStack get(MenusPlugin plugin, Player player, String warp, Material fallback) {
        Icon icon = icons.get(warp);
        if (icon != null && icon.when().test(player)) {
            if (icon.stack() != null) return icon.stack().clone();
            if (!icon.texture().isEmpty()) return MenuIcons.texture(icon.texture());
            return MenuIcons.resolve(plugin, player, icon.item(), fallback);
        }
        return new ItemStack(fallback);
    }

    synchronized void set(String warp, ItemStack icon) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        String path = "warps." + warp;
        if (icon == null) yaml.set(path, null);
        else {
            var row = yaml.getConfigurationSection(path);
            if (row == null) row = yaml.createSection(path);
            row.set("item", null);
            row.set("texture", null);
            row.set("stack", icon);
        }
        Map<String, Icon> snapshot = parse(yaml);
        Path temporary = Files.createTempFile(file.getParent(), ".warp-icons-", ".yml");
        try {
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            install(snapshot);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Icon> parse(YamlConfiguration yaml) {
        var result = new LinkedHashMap<String, Icon>();
        var rows = yaml.getConfigurationSection("warps");
        if (rows != null) for (String warp : rows.getKeys(false)) {
            var icon = rows.getConfigurationSection(warp);
            if (icon == null || !warp.matches("[a-z0-9_-]{1,32}")) throw invalid(warp);
            var stack = icon.getItemStack("stack");
            String ref = icon.getString("item", "");
            String texture = icon.getString("texture", "");
            int definitions = (stack == null ? 0 : 1) + (ref.isEmpty() ? 0 : 1) + (texture.isEmpty() ? 0 : 1);
            if (definitions != 1 || stack != null && stack.getType().isAir()
                    || !texture.isEmpty() && !texture.matches("[0-9a-f]{32,64}")) throw invalid(warp);
            result.put(warp, new Icon(stack == null ? null : stack.clone(), ref, texture, MenuVariables.conditions(icon)));
        }
        return Map.copyOf(result);
    }

    private static IllegalArgumentException invalid(String warp) {
        return new IllegalArgumentException("warp-icons.yml: invalid icon for " + warp);
    }
}
