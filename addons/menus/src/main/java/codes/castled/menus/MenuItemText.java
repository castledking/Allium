package codes.castled.menus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Item text inherits italics from Minecraft unless the component sets an explicit default. */
final class MenuItemText {
    static final String BACK = "<#faf0a0><b>Back</b></#faf0a0>";
    static final String CLOSE = "&c&lClose";
    static final String SEARCH = "<#7cf1de><b>Search</b></#7cf1de>";
    static final String PREVIOUS = "<white><b>← <u>Previous Page</u></b></white>";
    static final String NEXT = "<white><b><u>Next Page</u> →</b></white>";
    static final String CANCEL = "&c&lCancel";

    private static final Paper PAPER = Paper.load();

    private MenuItemText() {}

    static void name(ItemMeta meta, String legacy) {
        if (legacy != null) meta.setDisplayName(legacy);
        if (!meta.hasDisplayName()) return;
        try {
            if (PAPER != null) {
                PAPER.setName.invoke(meta, PAPER.upright(PAPER.name.invoke(meta)));
            } else {
                Field field = spigotField(meta, "displayName");
                if (field.get(meta) instanceof String json) field.set(meta, uprightJson(json));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The public Bukkit string API is the fallback for unknown metadata implementations.
        }
    }

    static void lore(ItemMeta meta, List<String> legacy, boolean append) {
        if (legacy.isEmpty()) return;
        var previous = append ? meta.getLore() : null;
        try {
            if (PAPER != null) {
                List<?> existing = append ? (List<?>) PAPER.lore.invoke(meta) : null;
                meta.setLore(legacy);
                var result = new ArrayList<>();
                if (existing != null) result.addAll(existing);
                for (Object line : (List<?>) PAPER.lore.invoke(meta)) result.add(PAPER.upright(line));
                PAPER.setLore.invoke(meta, result);
                return;
            }
            Field field = spigotField(meta, "lore");
            List<?> existing = append ? (List<?>) field.get(meta) : null;
            meta.setLore(legacy);
            var result = new ArrayList<>();
            if (existing != null) result.addAll(existing);
            for (Object line : (List<?>) field.get(meta)) result.add(uprightJson((String) line));
            field.set(meta, result);
            return;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keep text available if the native component API cannot be reached.
        }
        var result = previous == null ? new ArrayList<String>() : new ArrayList<>(previous);
        result.addAll(legacy);
        meta.setLore(result);
    }

    private static Field spigotField(ItemMeta meta, String name) throws NoSuchFieldException {
        for (Class<?> type = meta.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException next) {
                // Item types such as potions inherit the shared fields.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String uprightJson(String json) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("text", "");
        wrapper.addProperty("italic", false);
        JsonArray extra = new JsonArray();
        extra.add(JsonParser.parseString(json));
        wrapper.add("extra", extra);
        return wrapper.toString();
    }

    private record Paper(Method name, Method setName, Method lore, Method setLore,
                         Method decoration, Object italic, Object off) {
        static Paper load() {
            try {
                Class<?> component = Class.forName("net.kyori.adventure.text.Component");
                Class<?> decoration = Class.forName("net.kyori.adventure.text.format.TextDecoration");
                Class<?> states = Class.forName("net.kyori.adventure.text.format.TextDecoration$State");
                return new Paper(ItemMeta.class.getMethod("displayName"), ItemMeta.class.getMethod("displayName", component),
                        ItemMeta.class.getMethod("lore"), ItemMeta.class.getMethod("lore", List.class),
                        component.getMethod("decorationIfAbsent", decoration, states),
                        decoration.getField("ITALIC").get(null), states.getField("FALSE").get(null));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }

        Object upright(Object component) throws ReflectiveOperationException {
            return decoration.invoke(component, italic, off);
        }
    }
}
