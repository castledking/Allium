package net.survivalfun.core.utils;

import org.bukkit.ChatColor;

public class ColorUtils {

    public static String colorize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }


    public static String stripColor(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return ChatColor.stripColor(message);
    }

    public static String colorizeHex(String textToTranslate) {
        java.util.regex.Pattern hexPattern = java.util.regex.Pattern.compile("&#([A-Fa-f0-9]{6})");
        java.util.regex.Matcher matcher = hexPattern.matcher(textToTranslate);
        StringBuilder buffer = new StringBuilder(textToTranslate.length() + 4 * 8);
        while (matcher.find())
        {
            String group = matcher.group(1);
            matcher.appendReplacement(buffer, ChatColor.COLOR_CHAR + "x"
                    + ChatColor.COLOR_CHAR + group.charAt(0) + ChatColor.COLOR_CHAR + group.charAt(1)
                    + ChatColor.COLOR_CHAR + group.charAt(2) + ChatColor.COLOR_CHAR + group.charAt(3)
                    + ChatColor.COLOR_CHAR + group.charAt(4) + ChatColor.COLOR_CHAR + group.charAt(5)
            );

        }
        return matcher.appendTail(buffer).toString();
    }

    public static String colorizeToLegacy(String message) {
        return colorize(message).replace(ChatColor.COLOR_CHAR, '§');
    }
}