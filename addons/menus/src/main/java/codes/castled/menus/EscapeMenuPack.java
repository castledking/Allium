package codes.castled.menus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Publishes the vanilla pause-screen dialog tag for the next server start. */
final class EscapeMenuPack {
    private EscapeMenuPack() {}

    static void write(JavaPlugin plugin, MenuConfig config) throws IOException {
        var worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) throw new IOException("No primary world is available for the Menus datapack");
        var primary = worlds.get(0);
        Path dimension = primary.getWorldFolder().toPath();
        Path world = dimension.toAbsolutePath();
        // Newer Paper versions place each dimension below the world's level.dat and datapacks.
        while (world != null && !Files.isRegularFile(world.resolve("level.dat"))) world = world.getParent();
        if (world == null) world = plugin.getServer().getWorldContainer().toPath().resolve(primary.getName());
        Path folder = world.resolve("datapacks");
        Path target = folder.resolve("allium-menus.zip");
        var entries = config.menus().values().stream()
                .filter(menu -> config.escapeMenu() && menu.triggers().contains(MenuConfig.Trigger.ESCAPE)).toList();
        if (entries.isEmpty() && !Files.exists(target)) return;

        var format = versionMetadata(plugin);
        var metadata = new JsonObject();
        metadata.add("pack", format);
        Files.createDirectories(folder);
        Path staging = Files.createTempFile(folder, ".allium-menus-", ".tmp");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(staging))) {
                entry(zip, "pack.mcmeta", metadata);
                var ids = new JsonArray();
                for (MenuConfig.Menu menu : entries) {
                    String name = menu.id().replace(':', '/');
                    ids.add("allium_menus:entry/" + name);
                    entry(zip, "data/allium_menus/dialog/entry/" + name + ".json", portal(menu));
                }
                var tag = new JsonObject();
                tag.add("values", ids);
                entry(zip, "data/minecraft/tags/dialog/pause_screen_additions.json", tag);
            }
            if (Files.exists(target) && Files.mismatch(staging, target) == -1) return;
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
            plugin.getLogger().info("Wrote the Menus escape-menu datapack. Changes apply after restart.");
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static JsonObject versionMetadata(JavaPlugin plugin) throws IOException {
        var loader = plugin.getServer().getClass().getClassLoader();
        try (var input = loader.getResourceAsStream("version.json")) {
            if (input == null) throw new IOException("Could not determine the server's datapack format");
            var version = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("pack_version");
            var pack = new JsonObject();
            pack.addProperty("description", "Allium Menus escape entries");
            if (version.has("data_major")) {
                var format = new JsonArray();
                format.add(version.get("data_major"));
                format.add(version.get("data_minor"));
                pack.add("min_format", format);
                pack.add("max_format", format);
            } else {
                pack.add("pack_format", version.get("data"));
            }
            return pack;
        }
    }

    private static JsonObject portal(MenuConfig.Menu menu) {
        String command = "/menus:menus escape " + menu.id();
        String title = plain(menu.title());
        var dialog = new JsonObject();
        if (menu.id().equals("allium:settings")) {
            dialog.addProperty("type", "minecraft:multi_action");
            dialog.addProperty("columns", 2);
            var actions = new JsonArray();
            for (MenuConfig.Button button : menu.buttons()) {
                if (!publicShortcut(button)) continue;
                actions.add(button(plain(button.label()), command + " " + button.action()));
            }
            actions.add(button("Player Settings", command));
            dialog.add("actions", actions);
            title = "Allium";
        } else {
            dialog.addProperty("type", "minecraft:notice");
            dialog.add("action", button("Open", command));
        }
        dialog.add("title", component(title));
        dialog.add("external_title", component(title));
        dialog.addProperty("pause", false);
        return dialog;
    }

    static boolean publicShortcut(MenuConfig.Button button) {
        if (button.conditional() || !button.permission().isEmpty()) return false;
        return java.util.Set.of("menu:allium:toggles", "menu:allium:teleport", "menu:allium:homes",
                "menu:allium:warps", "menu:allium:rules", "view:shop", "view:messages").contains(button.action());
    }

    private static String plain(String text) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text));
    }

    private static JsonObject component(String text) {
        var component = new JsonObject();
        component.addProperty("text", text);
        return component;
    }

    private static JsonObject button(String label, String command) {
        var action = new JsonObject();
        action.addProperty("type", "minecraft:run_command");
        action.addProperty("command", command);
        var button = new JsonObject();
        button.add("label", component(label));
        button.add("action", action);
        return button;
    }

    private static void entry(ZipOutputStream zip, String name, JsonObject json) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(json.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
