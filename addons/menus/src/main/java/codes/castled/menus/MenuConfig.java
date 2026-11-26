package codes.castled.menus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

record MenuConfig(Map<String, Menu> menus, boolean inventoryOnly, boolean escapeMenu, long joinDelayMillis, int lockedHomes, int lockedWarps, boolean textEditors) {
    enum Trigger { FIRST_JOIN, JOIN, COMMAND, ESCAPE }
    record Menu(String id, String title, String permission, boolean inventoryOnly, Set<Trigger> triggers, String source,
                int size, List<Button> buttons, Predicate<org.bukkit.entity.Player> when) {}
    record Button(String label, String permission, String action, int slot, String icon, boolean conditional, Predicate<org.bukkit.entity.Player> when) {}

    static final Set<String> SOURCES = Set.of("homes", "warps", "teleport", "staff", "powertools", "shop", "sell", "messages");

    private static final Set<String> ACTIONS = Set.of("homes", "home", "warps", "rules", "close", "back",
            "toggle:tpa", "toggle:msg", "toggle:pay");

    static MenuConfig load(Path directory) throws IOException, InvalidConfigurationException {
        var config = loadYaml(directory.resolve("config.yml"));
        int delay = config.getInt("join-delay-ticks", 20);
        if (delay < 1 || delay > 1200) throw new IllegalArgumentException("config.yml: join-delay-ticks must be 1..1200");
        Map<String, Menu> menus = new LinkedHashMap<>();
        try (var files = Files.walk(directory.resolve("dialogs"))) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".yml")).sorted().toList()) {
                Path layout = directory.resolve("guis").resolve(directory.resolve("dialogs").relativize(file));
                Menu menu = read(file, layout);
                if (menus.putIfAbsent(menu.id(), menu) != null) throw error(file, "duplicate id " + menu.id());
            }
        }
        for (Menu menu : menus.values()) {
            for (Button button : menu.buttons()) {
                if (button.action().startsWith("menu:") && !menus.containsKey(button.action().substring(5))) {
                    throw new IllegalArgumentException(menu.id() + ": missing destination " + button.action().substring(5));
                }
            }
        }
        return new MenuConfig(Collections.unmodifiableMap(menus), config.getBoolean("force-inventory"),
                config.getBoolean("escape-menu", true), delay * 50L, locked(config, "locked-homes", 6),
                locked(config, "locked-warps", 6), config.getBoolean("text-editors", true));
    }

    private static int locked(YamlConfiguration config, String key, int fallback) {
        int value = config.getInt(key, fallback);
        if (value < 0 || value > 1000) throw new IllegalArgumentException("config.yml: " + key + " must be 0..1000");
        return value;
    }

    private static Menu read(Path file, Path layout) throws IOException, InvalidConfigurationException {
        YamlConfiguration source = loadYaml(file);
        var inventory = Files.isRegularFile(layout) ? loadYaml(layout) : new YamlConfiguration();
        String id = source.getString("id", "");
        if (source.getInt("schema") != 1 || !id.matches("[a-z0-9_-]+:[a-z0-9_/-]+") || id.length() > 64) {
            throw error(file, "schema must be 1 and id must be a namespaced menu id up to 64 characters");
        }
        String title = source.getString("title", "").strip();
        if (title.isEmpty() || title.length() > 100) throw error(file, "title must be 1..100 characters");
        String permission = permission(source, "permission", file);
        String presentation = source.getString("presentation", "auto");
        if (!Set.of("auto", "dialog", "inventory").contains(presentation)) throw error(file, "invalid presentation");
        EnumSet<Trigger> triggers = EnumSet.noneOf(Trigger.class);
        for (String value : source.getStringList("open-on")) {
            try {
                triggers.add(Trigger.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException invalid) {
                throw error(file, "unknown open-on trigger " + value);
            }
        }
        int rows = inventory.getInt("rows", 3);
        if (rows < 1 || rows > 6) throw error(layout, "rows must be 1..6");
        String panelSource = source.getString("source", "");
        if (!panelSource.isEmpty()) {
            if (!SOURCES.contains(panelSource)) throw error(file, "unknown source " + panelSource);
            return new Menu(id, title, permission, presentation.equals("inventory"), Set.copyOf(triggers), panelSource,
                    rows * 9, List.of(), MenuVariables.conditions(source));
        }
        ConfigurationSection entries = source.getConfigurationSection("buttons");
        if (entries == null || entries.getKeys(false).isEmpty()) throw error(file, "buttons are required");
        Set<Integer> used = new HashSet<>();
        Map<String, Integer> slots = new LinkedHashMap<>();
        var overrides = inventory.getConfigurationSection("buttons");
        if (overrides != null) for (String key : overrides.getKeys(false)) {
            if (!entries.isConfigurationSection(key)) throw error(layout, "unknown button " + key);
            String path = "buttons." + key + ".slot";
            if (!inventory.contains(path)) continue;
            int slot = inventory.getInt(path, -1);
            if (slot < 0 || slot >= rows * 9 || !used.add(slot)) throw error(layout, "invalid or duplicate slot for " + key);
            slots.put(key, slot);
        }
        List<Button> buttons = new ArrayList<>();
        int next = 0;
        for (String key : entries.getKeys(false)) {
            var entry = entries.getConfigurationSection(key);
            if (entry == null) throw error(file, key + " must be a button section");
            String label = entry.getString("label", "").strip();
            String action = entry.getString("action", "");
            if (label.isEmpty() || label.length() > 100 || !ACTIONS.contains(action) && !MenuVariables.isAction(action) && !action.startsWith("menu:")
                    && !(action.startsWith("view:") && SOURCES.contains(action.substring(5)))) {
                throw error(file, "invalid label or action for " + key);
            }
            Integer slot = slots.get(key);
            if (slot == null) {
                while (used.contains(next)) next++;
                if (next >= rows * 9) throw error(layout, "too many buttons for the inventory");
                slot = next++;
            }
            String icon = inventory.getString("buttons." + key + ".icon", entry.getString("icon", "PAPER"));
            if (icon.isBlank() || icon.length() > 128) throw error(layout, "invalid icon for " + key);
            boolean conditional = entry.contains("when") || entry.contains("when-any");
            buttons.add(new Button(label, permission(entry, "permission", file), action, slot, icon,
                    conditional, MenuVariables.conditions(entry)));

        }
        return new Menu(id, title, permission, presentation.equals("inventory"), Set.copyOf(triggers),
                "", rows * 9, List.copyOf(buttons), MenuVariables.conditions(source));
    }

    private static String permission(ConfigurationSection source, String key, Path file) {
        String permission = source.getString(key, "");
        if (!permission.isEmpty() && !permission.matches("[a-z0-9_.-]+")) throw error(file, "invalid permission");
        return permission;
    }

    private static YamlConfiguration loadYaml(Path file) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (InvalidConfigurationException invalid) {
            throw new InvalidConfigurationException(file + ": " + invalid.getMessage(), invalid);
        }
        return yaml;
    }

    private static IllegalArgumentException error(Path file, String message) {
        return new IllegalArgumentException(file + ": " + message);
    }
}
