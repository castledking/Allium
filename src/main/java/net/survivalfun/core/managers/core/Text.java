package net.survivalfun.core.managers.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Text {


    public enum ColorFormat {
        MINI_MESSAGE, // <gradient:>, <#Hex>
        HEX, //&#FFFFFF
        LEGACY_HEX, // &x&R&R&G&G&B&B or §x§R§R§G§G§B§B
        LEGACY_COLOR, // &a, &b, §a, §b
        NONE
    }

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("[&§]x([&§][A-Fa-f0-9]){6}");
    private static final Pattern SIMPLE_HEX_PATTERN = Pattern.compile("<#([A-Fa-f0-9]{6})>");

    private static final MiniMessage miniMessage = MiniMessage.miniMessage();

    public static String formatTime(int seconds) {
        if (seconds <= 0) {
            return "0s";
        }

        int days = seconds / 86400;
        seconds %= 86400;

        int hours = seconds / 3600;
        seconds %= 3600;

        int minutes = seconds / 60;
        seconds %= 60;

        StringBuilder timeString = new StringBuilder();

        if (days > 0) {
            timeString.append(days).append("d ");
        }

        if (hours > 0 || days > 0) {
            timeString.append(hours).append("h ");
        }

        if (minutes > 0 || hours > 0 || days > 0) {
            timeString.append(minutes).append("m ");
        }

        if (seconds > 0 || timeString.length() == 0) {
            timeString.append(seconds).append("s");
        } else {
            // Remove the trailing space if we added other units
            timeString.deleteCharAt(timeString.length() - 1);
        }

        return timeString.toString();
    }


    public static String parseColors(String input) {
        if (input == null || input.isEmpty()) return "";

        // First check if we should use MiniMessage format
        if (detectColorFormat(input) == ColorFormat.MINI_MESSAGE) {
            Component component = miniMessage.deserialize(input);
            return LegacyComponentSerializer.legacySection().serialize(component);
        }

        // Important: Process hex colors FIRST before standard color codes
        if (input.contains("&#")) {
            input = colorizeHex(input);
        }

        // Then handle legacy hex format
        if (input.contains("&x") || input.contains("§x")) {
            input = translateLegacyHexColors(input);
        }

        // Finally handle standard color codes
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public static ColorFormat detectColorFormat(String input) {
        if (input.contains("<gradient:") || input.contains("</gradient>") || SIMPLE_HEX_PATTERN.matcher(input).find()) {
            return ColorFormat.MINI_MESSAGE;
        }

        if (HEX_PATTERN.matcher(input).find()) {
            return ColorFormat.HEX;
        }

        if (LEGACY_HEX_PATTERN.matcher(input).find()) {
            return ColorFormat.LEGACY_HEX;
        }

        if (input.matches(".*[&§][0-9a-fk-or].*")) {
            return ColorFormat.LEGACY_COLOR;
        }

        return ColorFormat.NONE;
    }

    private static String translateHexColors(String message) {
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + matcher.group(1)).toString());
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String translateLegacyHexColors(String message) {
        Matcher matcher = LEGACY_HEX_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String match = matcher.group().replaceAll("[&§]x|[&§]", "");
            matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + match).toString());
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public static String getLastColor(String message, Boolean isErrorPrefix) {
        String parsedMessage = parseColors(message);
        String lastColor = ChatColor.getLastColors(parsedMessage);
        if (isErrorPrefix) {
            return ChatColor.RED.toString();
        }
        return lastColor.isEmpty() ? ChatColor.RESET.toString() : lastColor;
    }

    /**
     * Strips color codes from a message based on player permissions.
     * Players without specific color permissions will have those colors removed.
     *
     * @param message The message to strip colors from
     * @param player The player to check permissions for (can be null for complete stripping)
     * @return The message with unauthorized color codes stripped
     */
    public static String stripColor(String message, Player player) {
        if (message == null || message.isEmpty()) {
            return "";
        }

        // If player is null or doesn't have any color permissions, strip all colors
        if (player == null || !player.hasPermission("chat.color")) {
            // First convert & to § so ChatColor.stripColor can remove them
            String converted = ChatColor.translateAlternateColorCodes('&', message);
            // Then strip all color codes
            String stripped = ChatColor.stripColor(converted);
            return stripped;
        }

        // If we get here, player has chat.color permission but might not have specific color permissions
        // We'll check each color code individually
        StringBuilder result = new StringBuilder();
        char[] chars = message.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            // Check for color code marker (& or §)
            if ((chars[i] == '&' || chars[i] == ChatColor.COLOR_CHAR) && i + 1 < chars.length) {
                char colorChar = Character.toLowerCase(chars[i + 1]);

                // Check if it's a valid color/format code
                if (isColorOrFormatCode(colorChar)) {
                    String permission = getPermissionForColorCode(colorChar);

                    // If player has permission for this color, keep it
                    if (player.hasPermission(permission)) {
                        result.append(chars[i]).append(chars[i + 1]);
                    }
                    // Otherwise skip this color code
                    i++; // Skip the next character (the color code)
                    continue;
                }

                // Handle hex colors in the format &#RRGGBB
                if (colorChar == '#' && i + 7 < chars.length) {
                    boolean isHexColor = true;
                    for (int j = 0; j < 6; j++) {
                        if (!isHexDigit(chars[i + 2 + j])) {
                            isHexColor = false;
                            break;
                        }
                    }

                    if (isHexColor) {
                        // Check hex color permission
                        if (player.hasPermission("chat.color.hex")) {
                            // Include the entire hex color code
                            for (int j = 0; j < 8; j++) {
                                result.append(chars[i + j]);
                            }
                        }
                        i += 7; // Skip the hex color code
                        continue;
                    }
                }
            }

            // If not a color code or has permission, add the character
            result.append(chars[i]);
        }

        return result.toString();
    }

    /**
     * Checks if a character is a valid color or format code.
     */
    private static boolean isColorOrFormatCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == 'r' || c == 'l' || c == 'm' || c == 'n' || c == 'o' || c == 'k';
    }

    /**
     * Checks if a character is a valid hexadecimal digit.
     */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Gets the permission node for a specific color code.
     */
    private static String getPermissionForColorCode(char colorChar) {
        switch (colorChar) {
            case '0': return "chat.color.black";
            case '1': return "chat.color.dark_blue";
            case '2': return "chat.color.dark_green";
            case '3': return "chat.color.dark_aqua";
            case '4': return "chat.color.dark_red";
            case '5': return "chat.color.dark_purple";
            case '6': return "chat.color.gold";
            case '7': return "chat.color.gray";
            case '8': return "chat.color.dark_gray";
            case '9': return "chat.color.blue";
            case 'a': return "chat.color.green";
            case 'b': return "chat.color.aqua";
            case 'c': return "chat.color.red";
            case 'd': return "chat.color.light_purple";
            case 'e': return "chat.color.yellow";
            case 'f': return "chat.color.white";
            case 'k': return "chat.format.obfuscated";
            case 'l': return "chat.format.bold";
            case 'm': return "chat.format.strikethrough";
            case 'n': return "chat.format.underline";
            case 'o': return "chat.format.italic";
            case 'r': return "chat.color.reset";
            default: return "chat.color";
        }
    }

    public static String colorizeHex(String textToTranslate) {
        Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = hexPattern.matcher(textToTranslate);
        StringBuilder buffer = new StringBuilder(textToTranslate.length() + 4 * 8);

        while (matcher.find()) {
            String group = matcher.group(1);
            matcher.appendReplacement(buffer, ChatColor.COLOR_CHAR + "x"
                    + ChatColor.COLOR_CHAR + group.charAt(0) + ChatColor.COLOR_CHAR + group.charAt(1)
                    + ChatColor.COLOR_CHAR + group.charAt(2) + ChatColor.COLOR_CHAR + group.charAt(3)
                    + ChatColor.COLOR_CHAR + group.charAt(4) + ChatColor.COLOR_CHAR + group.charAt(5)
            );
        }

        return matcher.appendTail(buffer).toString();
    }

    /**
     * Gets the last color code from the error prefix message.
     * This is useful for maintaining consistent color formatting in error messages.
     *
     * @param langManager The language manager to get the error prefix from
     * @return The last color code from the error prefix
     */
    public static String getLastColorInErrorPrefix(net.survivalfun.core.managers.lang.Lang langManager) {
        if (langManager == null) {
            return ChatColor.RED.toString(); // Default to red if lang manager is null
        }

        String errorPrefix = langManager.get("error-prefix");
        return getLastColor(errorPrefix, true);
    }

    /**
     * Formats and sends an error message to a command sender with consistent color formatting.
     *
     * @param sender The command sender to receive the message (Player or CommandSender)
     * @param messageKey The language key for the error message
     * @param lang The language manager to get messages from
     * @param replacements An array of key-value pairs for replacements (key1, value1, key2, value2, ...)
     */
    public static void sendErrorMessage(CommandSender sender, String messageKey,
                                        net.survivalfun.core.managers.lang.Lang lang,
                                        Object... replacements) {
        if (sender == null) {
            return;
        }

        if (lang == null) {
            sender.sendMessage("§c§lError: §rMissing language manager");
            return;
        }

        // Get the message from the key
        String message = lang.get(messageKey);
        if (message == null || message.isEmpty()) {
            sender.sendMessage("§c§lError: §rMissing error message for key: " + messageKey);
            return;
        }

        // Get the error prefix and handle empty prefix case
        String errorPrefix = lang.get("error-prefix");
        boolean hasPrefix = !errorPrefix.isEmpty();

        // Get the last color from the error prefix to maintain consistent coloring
        String errorColor = hasPrefix ? getLastColorInErrorPrefix(lang) : "§c";

        // Convert the replacements to strings and apply them in pairs
        String formattedMessage = message;
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = String.valueOf(replacements[i]);
            String value = String.valueOf(replacements[i + 1]);

            // Only add error color after replacements if we have a prefix
            if (hasPrefix) {
                formattedMessage = formattedMessage.replace(placeholder, value + errorColor);
            } else {
                formattedMessage = formattedMessage.replace(placeholder, value);
            }
        }

        // Send the formatted message with the error prefix
        if (!hasPrefix) {
            // When there's no prefix, parse the colors in the message itself
            sender.sendMessage(parseColors(formattedMessage));
        } else {
            // When there's a prefix, use the traditional approach
            sender.sendMessage(errorPrefix + errorColor + " " + formattedMessage);
        }
    }

}