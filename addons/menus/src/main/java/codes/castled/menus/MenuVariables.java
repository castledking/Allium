package codes.castled.menus;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MenuVariables {
    private static final String KEY = "[a-z0-9_.-]{1,64}";
    private static final Pattern OUTPUT = Pattern.compile("%(?:data:|data_)(" + KEY + ")%");
    private MenuVariables() {}

    static String get(Player player, String key) {
        return player.getPersistentDataContainer().get(new NamespacedKey("menus", "data." + key), PersistentDataType.STRING);
    }

    static String expand(Player player, String text) {
        return OUTPUT.matcher(text).replaceAll(match -> {
            String value = get(player, match.group(1));
            return Matcher.quoteReplacement(value == null ? "" : value);
        });
    }

    static void set(Player player, String key, String value) {
        var data = player.getPersistentDataContainer();
        var namespaced = new NamespacedKey("menus", "data." + key);
        if (value == null) data.remove(namespaced);
        else data.set(namespaced, PersistentDataType.STRING, value);
    }

    static Predicate<Player> conditions(ConfigurationSection section) {
        List<Predicate<Player>> all = parse(section, "when");
        List<Predicate<Player>> any = parse(section, "when-any");
        return player -> all.stream().allMatch(check -> check.test(player))
                && (any.isEmpty() || any.stream().anyMatch(check -> check.test(player)));
    }

    private static List<Predicate<Player>> parse(ConfigurationSection section, String key) {
        if (!section.contains(key)) return List.of();
        var scalar = section.get(key);
        if (scalar instanceof String text) return List.of(condition(text));
        if (scalar instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).map(MenuVariables::condition).toList();
        }
        throw new IllegalArgumentException(section.getCurrentPath() + "." + key + " must contain condition strings");
    }

    private static Predicate<Player> condition(String raw) {
        String text = raw.strip();
        if (text.startsWith("!")) return condition(text.substring(1)).negate();
        if (text.startsWith("permission:") && text.substring(11).matches("[a-z0-9_.-]+")) {
            return player -> player.hasPermission(text.substring(11));
        }
        if (text.startsWith("world:") && !text.substring(6).isBlank()) {
            return player -> text.substring(6).equals(player.getWorld().getName());
        }
        if (text.startsWith("data:")) {
            String[] parts = text.substring(5).split("=", 2);
            if (parts[0].matches(KEY)) return player -> {
                String actual = get(player, parts[0]);
                return parts.length == 1 ? actual != null : parts[1].equals(actual);
            };
        }
        throw new IllegalArgumentException("Invalid menu condition: " + raw);
    }

    static boolean isAction(String action) {
        if (action.startsWith("clear-data:")) return action.substring(11).matches(KEY);
        if (!action.startsWith("set-data:")) return false;
        String[] parts = action.substring(9).split("=", 2);
        return parts.length == 2 && parts[0].matches(KEY) && parts[1].length() <= 256
                && parts[1].codePoints().noneMatch(Character::isISOControl);
    }

    static void apply(Player player, String action) {
        if (action.startsWith("clear-data:")) set(player, action.substring(11), null);
        else {
            String[] parts = action.substring(9).split("=", 2);
            set(player, parts[0], parts[1]);
        }
    }
}
