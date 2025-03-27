package net.survivalfun.core.utils;

import org.bukkit.ChatColor;

public class ColorUtils {
    /**
     * Translates '&' color codes into Minecraft color codes using Spigot API.
     *
     * For example: "&aHello" becomes "§aHello" (green-colored "Hello" in Minecraft).
     *
     * @param message The input string containing '&' color codes
     * @return The translated string with Minecraft color codes
     */
    public static String colorize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Strips all color codes from a string
     * @param message The colored string
     * @return The string without color codes
     */
    public static String stripColor(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return ChatColor.stripColor(message);
    }

    /**
     * Colorizes a string and then converts it to a legacy text component
     * (useful for titles/scoreboards that require § format)
     * @param message The input string
     * @return The legacy-formatted string
     */
    public static String colorizeToLegacy(String message) {
        return colorize(message).replace(ChatColor.COLOR_CHAR, '§');
    }
}