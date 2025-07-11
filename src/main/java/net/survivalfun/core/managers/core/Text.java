package net.survivalfun.core.managers.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatColor;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;

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

    /**
     * Converts a string with color codes to a Kyori Adventure Component
     *
     * @param text Text with color codes (using & symbol)
     * @return A formatted Component with applied colors
     */
    public static Component colorize(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        ColorFormat format = detectColorFormat(text);
        if (format == ColorFormat.MINI_MESSAGE) {
            try {
                // Log MiniMessage input for debugging
                System.out.println("Parsing MiniMessage: " + text);
                Component result = miniMessage.deserialize(text);
                System.out.println("Parsed component: " + result);
                return result;
            } catch (Exception e) {
                System.err.println("MiniMessage parsing failed: " + e.getMessage());
                e.printStackTrace();
                String legacy = parseColors(text);
                return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
            }
        } else {
            String legacy = parseColors(text);
            return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        }
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

    /**
     * Parses color codes and MiniMessage formatting
     * @param text The text to parse
     * @param isChatMessage Whether this is a chat message (affects raw [] handling)
     * @return The parsed text
     */
    public static String parseColors(String text, boolean isChatMessage) {
        if (text == null || text.isEmpty()) return text;

        // Handle raw messages in [] for chat - completely bypass all parsing
        if (isChatMessage) {
            // Check if the entire message is enclosed in brackets
            if (text.startsWith("[") && text.endsWith("]")) {
                String rawContent = text.substring(1, text.length() - 1);
                // If content starts with '&', treat it as a raw color code display request.
                // We escape the ampersand by prepending another one, so it's displayed literally.
                if (rawContent.startsWith("&") || rawContent.startsWith("%")) {
                    return "&" + rawContent;
                }
                // For other bracketed content (like items), return as-is.
                return rawContent;
            }

            // Process text that contains bracketed sections
            // If we find §r[...content...]§r pattern, preserve the content as-is
            if (text.contains("§r[") && text.contains("]§r")) {
                StringBuilder result = new StringBuilder();
                int startIndex = 0;
                int openMarkerIndex;
                int closeMarkerIndex;

                while (startIndex < text.length()) {
                    openMarkerIndex = text.indexOf("§r[", startIndex);

                    if (openMarkerIndex == -1) {
                        // No more markers, add the rest and process normally
                        result.append(parseRegularText(text.substring(startIndex)));
                        break;
                    }

                    // Process text before the marker
                    if (openMarkerIndex > startIndex) {
                        result.append(parseRegularText(text.substring(startIndex, openMarkerIndex)));
                    }

                    // Find the closing marker
                    closeMarkerIndex = text.indexOf("]§r", openMarkerIndex);

                    if (closeMarkerIndex == -1) {
                        // No matching closing marker, treat as regular text
                        result.append(parseRegularText(text.substring(openMarkerIndex)));
                        break;
                    }

                    // Extract the raw content (without the §r[ and ]§r markers)
                    String rawContent = text.substring(openMarkerIndex + 3, closeMarkerIndex);

                    // Add the raw content without parsing
                    result.append("[").append(rawContent).append("]");

                    // Move past this marker pair
                    startIndex = closeMarkerIndex + 3;
                }

                return result.toString();
            }
        }

        // Original parsing logic for non-raw messages
        return parseRegularText(text);
    }

    /**
     * Helper method to parse regular text without special raw content handling
     * @param text The text to parse
     * @return The parsed text
     */
    private static String parseRegularText(String text) {
        if (text == null || text.isEmpty()) return text;

        text = miniMessage.stripTags(text);
        return LegacyComponentSerializer.legacySection().serialize(
            LegacyComponentSerializer.legacySection().deserialize(text)
        );
    }

    /**
     * Checks if a character is a valid Minecraft color or formatting code.
     * @param c The character to check.
     * @return true if the character is a color or formatting code, false otherwise.
     */
    public static boolean isColorChar(char c) {
        return "0123456789abcdefklmnor".indexOf(Character.toLowerCase(c)) > -1;
    }

    public static ColorFormat detectColorFormat(String input) {
        if (input.contains("<gradient:") || input.contains("</gradient>") ||
            SIMPLE_HEX_PATTERN.matcher(input).find() ||
            (input.contains("<") && input.contains(">"))) {
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
        String lastColor = org.bukkit.ChatColor.getLastColors(parsedMessage);
        if (isErrorPrefix) {
            return ChatColor.RED.toString();
        }
        return lastColor.isEmpty() ? net.md_5.bungee.api.ChatColor.RESET.toString() : lastColor;
    }

    /**
     * Sends a styled error message to the CommandSender.
     *
     * @param sender The recipient of the message.
     * @param key The language key for the error message.
     * @param lang The Lang manager instance.
     * @param replacements A map of placeholder keys (without braces) to their values.
     */
    public static void sendErrorMessage(CommandSender sender, String key, Lang lang, Map<String, String> replacements) {
        String parsedErrorPrefix = parseColors(lang.getRaw("error-prefix") + " ");
        String rawMessage = lang.getRaw(key);

        // Use key as raw message if not found in lang
        boolean isDirectMessage = rawMessage == null || rawMessage.equals(key);
        if (isDirectMessage) {
            rawMessage = key;
        }

        if (rawMessage == null || rawMessage.isEmpty()) {
            sender.sendMessage(parsedErrorPrefix + parseColors("&cError: Empty message"));
            return;
        }

        // Process placeholders if any replacements are provided
        if (replacements != null && !replacements.isEmpty()) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                rawMessage = rawMessage.replace(placeholder, entry.getValue() != null ? entry.getValue() : "");
            }
        }

        // Process color codes and send the message
        sender.sendMessage(parsedErrorPrefix + parseColors(rawMessage));
    }

    /**
     * Overload for sendErrorMessage that accepts varargs replacements.
     */
    public static void sendErrorMessage(CommandSender sender, String key, Lang lang, String... replacements) {
        Map<String, String> replacementsMap = new HashMap<>();
        if (replacements != null) {
            for (int i = 0; i < replacements.length - 1; i += 2) {
                if (replacements[i] != null && replacements[i+1] != null) {
                     // Remove curly braces if present in the key part of replacements
                    String placeholderKey = replacements[i].startsWith("{") && replacements[i].endsWith("}")
                                          ? replacements[i].substring(1, replacements[i].length() - 1)
                                          : replacements[i];
                    replacementsMap.put(placeholderKey, replacements[i+1]);
                }
            }
        }
        sendErrorMessage(sender, key, lang, replacementsMap);
    }

    /**
     * Formats and sends an error message to a command sender with consistent color formatting.
     *
     * @param sender      The command sender to receive the message (Player or CommandSender)
     * @param message     The language key for the error message, or the message itself as a Component
     * @param lang        The language manager to get messages from
     * @param replacements An array of key-value pairs for replacements (key1, value1, key2, value2, ...) where value can be String or Component
     */
    public static void sendErrorMessage(CommandSender sender, Object message,
                                        net.survivalfun.core.managers.lang.Lang lang,
                                        Object... replacements) {
        if (sender == null) {
            return;
        }

        if (lang == null) {
            sender.sendMessage("§c§lError: §rMissing language manager");
            return;
        }

        Component componentMessage = null;
        String stringMessage = null;

        if (message instanceof String) {
            // Get the message from the key
            String key = (String) message;
            stringMessage = lang.getRaw(key);
            if (stringMessage == null || stringMessage.isEmpty() || stringMessage.equals(key)) {
                // Use the key as the message if not found in lang
                stringMessage = key;
                sender.sendMessage("§c§lError: §rMissing error message for key: " + message);
                return;
            }
        } else if (message instanceof Component) {
            componentMessage = (Component) message;
        } else if (message instanceof Map) {
            // Handle the case where the message parameter is a map
            Map<String, Object> replacementMap = (Map<String, Object>) message;
            // Get the error prefix and handle empty prefix case
            String errorPrefix = lang.get("error-prefix");
            boolean hasPrefix = !errorPrefix.isEmpty();

            // Get the last color from the error prefix to maintain consistent coloring
            String errorColor = hasPrefix ? getLastColorInErrorPrefix(lang) : "§c";

            // Get the message from the language key
            stringMessage = lang.get("unknown-command-suggestion"); // Specific message key
            if (stringMessage == null || stringMessage.isEmpty()) {
                sender.sendMessage("§c§lError: §rMissing error message for key: unknown-command-suggestion");
                return;
            }

            String formattedMessage = stringMessage; // Start with the language string

            for (Map.Entry<String, Object> entry : replacementMap.entrySet()) {
                String placeholder = entry.getKey();
                Object replacementValue = entry.getValue();

                if (replacementValue instanceof Component) {
                    // Handle Component replacement
                    Component replacementComponent = (Component) replacementValue;
                    String serializedComponent = LegacyComponentSerializer.legacySection().serialize(replacementComponent);
                    formattedMessage = formattedMessage.replace(placeholder, serializedComponent);
                } else {
                    // Handle String replacement
                    String value = String.valueOf(replacementValue);
                    if (hasPrefix) {
                        formattedMessage = formattedMessage.replace(placeholder, value + errorColor);
                    } else {
                        formattedMessage = formattedMessage.replace(placeholder, value);
                    }
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
            return; // Important to return, so it doesn't run other logic
        } else {
            sender.sendMessage("§c§lError: §rInvalid message type. Must be a language key (String) or a Component.");
            return;
        }

        // Get the error prefix and handle empty prefix case
        String errorPrefix = lang.get("error-prefix");
        boolean hasPrefix = !errorPrefix.isEmpty();

        // Get the last color from the error prefix to maintain consistent coloring
        String errorColor = hasPrefix ? getLastColorInErrorPrefix(lang) : "§c";

        if (componentMessage != null) {
            // Serialize the component to a string for prefixing
            String serializedComponent = LegacyComponentSerializer.legacySection().serialize(componentMessage);

            // Send the formatted message with the error prefix
            if (!hasPrefix) {
                // When there's no prefix, parse the colors in the message itself
                sender.sendMessage(parseColors(serializedComponent));
            } else {
                // When there's a prefix, use the traditional approach
                sender.sendMessage(errorPrefix + errorColor + " " + serializedComponent);
            }
        } else {
            // Convert the replacements to strings and apply them in pairs
            String formattedMessage = stringMessage;
            for (int i = 0; i < replacements.length - 1; i += 2) {
                String placeholder = String.valueOf(replacements[i]);
                Object replacementValue = replacements[i + 1];

                if (replacementValue instanceof Component) {
                    // Handle Component replacement
                    Component replacementComponent = (Component) replacementValue;
                    String serializedComponent = LegacyComponentSerializer.legacySection().serialize(replacementComponent);
                    formattedMessage = formattedMessage.replace(placeholder, serializedComponent);

                } else {
                    // Handle String replacement
                    String value = String.valueOf(replacementValue);
                    if (hasPrefix) {
                        formattedMessage = formattedMessage.replace(placeholder, value + errorColor);
                    } else {
                        formattedMessage = formattedMessage.replace(placeholder, value);
                    }
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

    public static String stripColor(String message, Player player) {
        if (message == null || message.isEmpty()) {
            return "";
        }

        // Handle raw display requests like [&c...], pass them through without stripping.
        if (message.startsWith("[&") && message.endsWith("]")) {
            return message;
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

    public static Component createDisplayNameComponent(String name) {
        if (name == null || name.isEmpty()) {
            return Component.empty();
        }
        String displayName = name.replace('_', ' ');
        Component component = colorize(displayName);
        if (component.style().decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
            component = component.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }
        return component;
    }
}
