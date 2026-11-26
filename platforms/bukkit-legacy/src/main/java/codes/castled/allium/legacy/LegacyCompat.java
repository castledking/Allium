package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java 8 / Spigot 1.12.2-1.19.4 version-compatibility helpers.
 */
public final class LegacyCompat {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Method PLAYER_GET_PING = findMethod(Player.class, "getPing");
    private static final Method BUNGEE_COLOR_OF = findBungeeColorOf();

    private LegacyCompat() {}

    /** Returns the player's ping in ms, using {@code Player.getPing()} on 1.17+ and NMS handle on 1.12-1.16. */
    public static int ping(Player player) {
        if (PLAYER_GET_PING != null) {
            try {
                Object val = PLAYER_GET_PING.invoke(player);
                if (val instanceof Number) {
                    return Math.max(0, ((Number) val).intValue());
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return Math.max(0, handle.getClass().getField("ping").getInt(handle));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Returns the player's max health safely across 1.12.2-1.19.4. */
    @SuppressWarnings("deprecation")
    public static double maxHealth(Player player) {
        try {
            AttributeInstance inst = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (inst != null && inst.getValue() > 0.0) {
                return inst.getValue();
            }
        } catch (Throwable ignored) {
        }
        return Math.max(1.0, player.getMaxHealth());
    }

    /** Translates {@code &} color codes and {@code &#RRGGBB} hex colors (1.16+, stripped gracefully on 1.12-1.15). */
    public static String color(String raw) {
        if (raw == null) return "";
        String text = raw;
        if (text.indexOf("&#") >= 0) {
            Matcher m = HEX_PATTERN.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String replacement = "";
                if (BUNGEE_COLOR_OF != null) {
                    try {
                        Object color = BUNGEE_COLOR_OF.invoke(null, "#" + m.group(1));
                        if (color != null) replacement = color.toString();
                    } catch (Throwable ignored) {
                    }
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Human-readable server platform label (Paper, Purpur, Spigot, CraftBukkit, Bukkit). */
    public static String platformName(Server server) {
        String name = server == null ? "" : server.getName();
        String version = server == null ? "" : server.getVersion();
        String combined = (name + " " + version).toLowerCase(Locale.ROOT);
        if (combined.contains("folia")) return "Folia";
        if (combined.contains("purpur")) return "Purpur";
        if (combined.contains("paper")) return "Paper";
        if (combined.contains("spigot")) return "Spigot";
        if (combined.contains("craftbukkit")) return "CraftBukkit";
        return name == null || name.isEmpty() ? "Bukkit" : name;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findBungeeColorOf() {
        try {
            Class<?> clazz = Class.forName("net.md_5.bungee.api.ChatColor");
            return clazz.getMethod("of", String.class);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
