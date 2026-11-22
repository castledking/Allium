package codes.castled.curios;

import org.bukkit.ChatColor;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

final class ItemFrameMarker {
    private static final Pattern MARKER = Pattern.compile(
            "\\s*(?:\\[frame(?::([a-z0-9_-]+))?\\]|<frame(?::([a-z0-9_-]+))?>)\\s*",
            Pattern.CASE_INSENSITIVE);

    private ItemFrameMarker() {}

    static String style(String line) {
        if (line == null) return null;
        var match = MARKER.matcher(line);
        if (!match.matches()) return null;
        String style = match.group(1) == null ? match.group(2) : match.group(1);
        return style == null ? "auto" : style.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    static boolean apply(ItemMeta meta, String frame) {
        try {
            return paper(meta, frame);
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            List<String> original = meta.getLore();
            var lore = edited(original, line -> ChatColor.stripColor(line), frame,
                    style -> "§8[frame:" + style + "]");
            if (lore == null) return false;
            meta.setLore(lore.isEmpty() ? null : lore);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean paper(ItemMeta meta, String frame) throws ReflectiveOperationException {
        Class<?> component = Class.forName("net.kyori.adventure.text.Component");
        Class<?> serializer = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
        Object plain = serializer.getMethod("plainText").invoke(null);
        var read = serializer.getMethod("serialize", component);
        // Dark grey and upright, as legacy wrote it: creative players and clients without the pack
        // see the marker line itself.
        Class<?> color = Class.forName("net.kyori.adventure.text.format.TextColor");
        Class<?> decoration = Class.forName("net.kyori.adventure.text.format.TextDecoration");
        Object darkGray = Class.forName("net.kyori.adventure.text.format.NamedTextColor").getField("DARK_GRAY").get(null);
        Object italic = decoration.getField("ITALIC").get(null);
        var text = component.getMethod("text", String.class, color);
        var upright = component.getMethod("decoration", decoration, boolean.class);
        List<Object> original = (List<Object>) ItemMeta.class.getMethod("lore").invoke(meta);
        var lore = edited(original, line -> (String) invoke(read, plain, line), frame,
                style -> {
                    try {
                        return upright.invoke(text.invoke(null, "[frame:" + style + "]", darkGray), italic, false);
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException("Could not write the frame marker", error);
                    }
                });
        if (lore == null) return false;
        ItemMeta.class.getMethod("lore", List.class).invoke(meta, lore.isEmpty() ? null : lore);
        return true;
    }

    private static Object invoke(java.lang.reflect.Method method, Object receiver, Object argument) {
        try {
            return method.invoke(receiver, argument);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not read item lore", error);
        }
    }

    private static <T> List<T> edited(List<T> original, Function<T, String> plain, String frame, Function<String, T> marker) {
        var lore = original == null ? new ArrayList<T>() : new ArrayList<>(original);
        boolean removed = lore.removeIf(line -> style(plain.apply(line)) != null);
        if (frame.equals("off")) return removed ? lore : null;
        lore.add(0, marker.apply(frame));
        return lore;
    }
}
