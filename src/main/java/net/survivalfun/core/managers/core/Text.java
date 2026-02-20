package net.survivalfun.core.managers.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatColor;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.logging.Level;

public class Text {

    public enum ColorFormat {
        MINI_MESSAGE, // <gradient:>, <#Hex>
        HEX, //&#FFFFFF
        LEGACY_HEX, // &x&R&R&G&G&B&B or §x§R§R§G§G§B§B
        LEGACY_COLOR, // &a, &b, §a, §b
        NONE
    }

    public enum DebugSeverity {
        SUCCESS,
        INFO,
        WARN,
        ERROR
    }

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("[&§]x([&§][A-Fa-f0-9]){6}");
    private static final Pattern SIMPLE_HEX_PATTERN = Pattern.compile("<#([A-Fa-f0-9]{6})>");

    private static final MiniMessage miniMessage = MiniMessage.miniMessage();
    private static PluginStart plugin;
    
    /**
     * Sets the plugin instance for the Text utility class.
     * This should be called during plugin initialization.
     * 
     * @param pluginInstance The PluginStart instance
     */
    public static void setPlugin(PluginStart pluginInstance) {
        plugin = pluginInstance;
    }

    public static void sendDebugLog(DebugSeverity severity, String message) {
        sendDebugLogInternal(severity, message, null, false);
    }

    public static void sendDebugLog(DebugSeverity severity, String message, boolean forceOutput) {
        sendDebugLogInternal(severity, message, null, forceOutput);
    }

    public static void sendDebugLog(DebugSeverity severity, String message, Throwable throwable) {
        // Exceptions should always surface, regardless of debug-mode.
        sendDebugLogInternal(severity, message, throwable, true);
    }

    private static void sendDebugLogInternal(DebugSeverity severity, String message, Throwable throwable, boolean forceOutput) {
        PluginStart plugin = PluginStart.getInstance();
        if (plugin == null || (message == null && throwable == null)) {
            return;
        }

        if (!forceOutput && throwable == null && !plugin.getConfig().getBoolean("debug-mode", false)) {
            return;
        }

        DebugSeverity effectiveSeverity = severity != null ? severity : DebugSeverity.INFO;
        String prefix = "[DEBUG] ";
        String finalMessage = message != null ? message : (throwable != null ? throwable.getMessage() : "");
        String formattedMessage = prefix + finalMessage;

        Level level;
        switch (effectiveSeverity) {
            case SUCCESS:
                level = Level.INFO;
                break;
            case INFO:
                level = Level.INFO;
                break;
            case WARN:
                level = Level.WARNING;
                break;
            case ERROR:
                level = Level.SEVERE;
                break;
            default:
                level = Level.INFO;
                break;
        }

        if (throwable != null) {
            plugin.getLogger().log(level, formattedMessage, throwable);
        } else {
            plugin.getLogger().log(level, formattedMessage);
        }
    }

    /**
     * Broadcasts a message to all online players with optional sound support.
     * If the message starts with 'lang:', it will be treated as a language key.
     * Otherwise, it will be treated as a direct message with optional formatting.
     *
     * @param message The message to broadcast or language key (prefixed with 'lang:') 
     * @param placeholders Optional placeholders for the message
     */
    public static void broadcast(String message, Object... placeholders) {
        if (message == null || message.isEmpty()) {
            return;
        }
        
        // Check if this is a language key (starts with 'lang:')
        if (message.startsWith("lang:")) {
            String langKey = message.substring(5); // Remove 'lang:' prefix
            if (plugin != null && plugin.getLangManager() != null) {
                // Use Lang system which handles sounds internally
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    plugin.getLangManager().sendMessage(player, langKey, placeholders);
                }
                // Also send to console
                plugin.getServer().getConsoleSender().sendMessage(parseColors(plugin.getLangManager().get(langKey)));
            } else {
                // Fallback to simple broadcast if Lang system isn't available
                plugin.getServer().broadcastMessage(parseColors(langKey));
            }
        } else {
            // Direct message with formatting
            Component component = parseMessage(message, placeholders);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.sendMessage(component);
            }
            // Also send to console
            plugin.getServer().getConsoleSender().sendMessage(LegacyComponentSerializer.legacySection().serialize(component));
        }
    }
    
    /**
     * Parses a message with placeholders into a Component.
     * 
     * @param message The message with placeholders
     * @param placeholders Alternating key-value pairs for placeholders (e.g., "{player}", "Steve")
     * @return The parsed Component
     */
    private static Component parseMessage(String message, Object... placeholders) {
        if (placeholders.length > 0 && placeholders.length % 2 == 0) {
            // Process placeholders if they exist and are in pairs
            for (int i = 0; i < placeholders.length; i += 2) {
                if (i + 1 < placeholders.length) {
                    String key = String.valueOf(placeholders[i]);
                    String value = String.valueOf(placeholders[i + 1]);
                    message = message.replace("{" + key + "}", value);
                }
            }
        }
        
        // Parse both MiniMessage and legacy color codes
        return colorize(parseColors(message));
    }
    
    public static String formatTime(int seconds) {
        int days = seconds / 86400;
        seconds %= 86400;

        int hours = seconds / 3600;
        seconds %= 3600;

        int minutes = seconds / 60;
        seconds %= 60;
        boolean sToSeconds = seconds <= 60 && minutes < 1 && hours < 1 && days < 1;
        if (seconds <= 0) {
            if (sToSeconds) {
                return "0 seconds";
            } else {
                return "0s";
            }
        }

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
            if (sToSeconds) {
                timeString.append(seconds).append(" seconds");
            } else {
                timeString.append(seconds).append("s");
            }
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
            SIMPLE_HEX_PATTERN.matcher(input).find()) {
            return ColorFormat.MINI_MESSAGE;
        }

        // Check for MiniMessage tags (more specific than just < and >)
        // MiniMessage tags typically contain colons, equals signs, or specific keywords
        if (input.contains("<") && input.contains(">")) {
            // Look for patterns that indicate actual MiniMessage tags
            // Tags like <color:red>, <hover:...>, <click:...>, etc.
            Pattern miniMessageTagPattern = Pattern.compile("<[^>]*[:=][^>]*>");
            if (miniMessageTagPattern.matcher(input).find()) {
                return ColorFormat.MINI_MESSAGE;
            }

            // Also check for closing tags like </color>
            Pattern closingTagPattern = Pattern.compile("</[^>]+>");
            if (closingTagPattern.matcher(input).find()) {
                return ColorFormat.MINI_MESSAGE;
            }

            // Check for tags with specific MiniMessage keywords
            String[] miniMessageKeywords = {"color:", "hover:", "click:", "font:", "transition:", "insertion:", "keybind:"};
            for (String keyword : miniMessageKeywords) {
                if (input.contains(keyword)) {
                    return ColorFormat.MINI_MESSAGE;
                }
            }
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
     * Checks if a message contains any color codes
     * @param message The message to check
     * @return true if the message contains color codes, false otherwise
     */
    private static boolean hasColorCodes(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }

        // Check for legacy color codes (&0-&9, &a-&f, &k-&r)
        if (message.matches(".*&[0-9a-fk-or].*")) {
            return true;
        }

        // Check for section symbols (same as legacy but with §)
        if (message.matches(".*§[0-9a-fk-or].*")) {
            return true;
        }

        // Check for hex colors (&#RRGGBB)
        if (HEX_PATTERN.matcher(message).find()) {
            return true;
        }

        // Check for legacy hex format
        if (LEGACY_HEX_PATTERN.matcher(message).find()) {
            return true;
        }

        // Check for MiniMessage tags
        if (SIMPLE_HEX_PATTERN.matcher(message).find()) {
            return true;
        }

        // Check for other MiniMessage patterns
        if (message.contains("<gradient:") || message.contains("</gradient>") ||
            message.contains("<color:") || message.contains("</color>") ||
            message.contains("<hover:") || message.contains("</hover>") ||
            message.contains("<click:") || message.contains("</click>") ||
            message.contains("<font:") || message.contains("</font>") ||
            message.contains("<transition:") || message.contains("</transition>") ||
            message.contains("<insertion:") || message.contains("</insertion>") ||
            message.contains("<keybind:") || message.contains("</keybind>")) {
            return true;
        }

        return false;
    }

    /**
     * Checks if there's a color code immediately before a placeholder in the raw message.
     * This helps determine if we should bypass the placeholder-color styling.
     *
     * @param rawMessage The raw message template
     * @param placeholder The placeholder to check (e.g., "{modify}")
     * @return true if there's a color code immediately before the placeholder
     */
    private static boolean hasColorBeforePlaceholder(String rawMessage, String placeholder) {
        int placeholderIndex = rawMessage.indexOf(placeholder);
        if (placeholderIndex < 2) {
            return false;
        }

        // Check for color codes immediately before the placeholder
        // Pattern: &color{placeholder} or §color{placeholder}
        char prevChar = rawMessage.charAt(placeholderIndex - 1);
        char prevPrevChar = rawMessage.charAt(placeholderIndex - 2);

        // Check for &color format (e.g., &c{modify})
        if (prevPrevChar == '&' && isColorChar(prevChar)) {
            return true;
        }

        // Check for §color format (e.g., §c{modify})
        if (prevPrevChar == '§' && isColorChar(prevChar)) {
            return true;
        }

        return false;
    }

    /**
     * Applies error styling to a message with placeholders.
     *
     * @param rawMessage The raw message template.
     * @param lang The language manager to get the styles from.
     * @param replacements A map of placeholder keys (without braces) to their values.
     * @param literalReplacements A map of literal replacements.
     * @param key The language key being processed (can be null).
     * @return The styled message.
     */
    private static String applyErrorStyling(String rawMessage, Lang lang,
                                           Map<String, String> replacements,
                                           Map<String, String> literalReplacements,
                                           String key,
                                           boolean useMessageColorForReplacements) {
        // Get error styles
        String messageColor = lang.getStyle("styles.error.message-color", "&c");
        String placeholderColor = lang.getStyle("styles.error.placeholder-color", "&7");
        String parenthesesColor = lang.getStyle("styles.error.parentheses-color", "&8");

        // Use message color for replacements if requested
        String replacementColor = useMessageColorForReplacements ? messageColor : placeholderColor;

        // Check if this is a special key that should skip placeholder-color when no colors are present
        boolean skipPlaceholderColor = ("creative-manager.blacklist".equals(key) || "creative-manager.restrict".equals(key) || "hold-item".equals(key) || "cannot-self".equals(key)) && !hasColorCodes(rawMessage);

        if (literalReplacements != null && !literalReplacements.isEmpty()) {
            for (Map.Entry<String, String> entry : literalReplacements.entrySet()) {
                String target = entry.getKey();
                String value = entry.getValue() != null ? entry.getValue() : "";
                String styledReplacement = (skipPlaceholderColor || useMessageColorForReplacements ? "" : replacementColor) + value + messageColor;
                rawMessage = rawMessage.replace(target, styledReplacement);
            }
        }

        // Process each replacement with proper styling
        if (replacements != null && !replacements.isEmpty()) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                String placeholderKey = entry.getKey();
                String placeholder = "{" + placeholderKey + "}";
                String replacementValue = entry.getValue() != null ? entry.getValue() : "";

                // Special case for {cmd} - also style the '/' before it with placeholder color
                if ("cmd".equals(placeholderKey) || "suggestion".equals(placeholderKey) && rawMessage.contains("/" + placeholder)) {
                    // Check if there's a color code before the '/' + placeholder
                    String slashPlaceholder = "/" + placeholder;
                    boolean hasColorBefore = hasColorBeforePlaceholder(rawMessage, slashPlaceholder);

                    // Apply styling to both the '/' and the cmd placeholder
                    String styledReplacement;
                    if (hasColorBefore || useMessageColorForReplacements) {
                        // Bypass placeholder-color, just use replacement value + message-color
                        styledReplacement = replacementValue + messageColor;
                    } else {
                        // Normal styling: placeholder-color + "/" + value + message-color
                        styledReplacement = (skipPlaceholderColor ? "" : replacementColor) + "/" + replacementValue + messageColor;
                    }
                    rawMessage = rawMessage.replace(slashPlaceholder, styledReplacement);
                } else {
                    // Check if there's a color code immediately before this placeholder
                    boolean hasColorBefore = hasColorBeforePlaceholder(rawMessage, placeholder);

                    // Normal case: Apply styling based on whether there's a color before
                    String styledReplacement;
                    if (hasColorBefore || useMessageColorForReplacements) {
                        // Bypass placeholder-color, just use replacement value + message-color
                        styledReplacement = replacementValue + messageColor;
                    } else {
                        // Normal styling: placeholder-color + value + message-color
                        // But skip placeholder-color for special keys when no colors are present
                        styledReplacement = (skipPlaceholderColor ? "" : replacementColor) + replacementValue + messageColor;
                    }
                    rawMessage = rawMessage.replace(placeholder, styledReplacement);
                }
            }
        }

        // Apply message color to the beginning if there are no initial placeholders
        if (!rawMessage.startsWith(placeholderColor) && !rawMessage.startsWith(messageColor)) {
            rawMessage = messageColor + rawMessage;
        }

        // Handle parentheses around placeholders (if the message has them)
        // Note: This is a basic implementation - more complex parentheses handling may be needed
        rawMessage = rawMessage.replace("(", parenthesesColor + "(" + messageColor);
        rawMessage = rawMessage.replace(")", messageColor + ")" + messageColor);

        return rawMessage;
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
        sendErrorMessage(sender, key, lang, replacements, null, false);
    }

    private static void sendErrorMessage(CommandSender sender, String key, Lang lang,
                                         Map<String, String> replacements,
                                         Map<String, String> literalReplacements,
                                         boolean useMessageColorForReplacements) {
        // Play error sound if sender is a player
        playErrorSound(sender, key, lang);

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

        // Apply error styling with placeholders
        String styledMessage = applyErrorStyling(rawMessage, lang, replacements, literalReplacements, key, useMessageColorForReplacements);

        // Process color codes and send the message
        sender.sendMessage(parsedErrorPrefix + parseColors(styledMessage));
    }

    /**
     * Overload for sendErrorMessage that accepts varargs replacements.
     */
    public static void sendErrorMessage(CommandSender sender, String key, Lang lang, Object... replacements) {
        // Play error sound if sender is a player
        playErrorSound(sender, key, lang);

        Map<String, String> replacementsMap = new HashMap<>();
        Map<String, String> literalReplacements = new LinkedHashMap<>();
        boolean useMessageColorForReplacements = false;

        int length = replacements == null ? 0 : replacements.length;

        // Check for true flag at the end to enable message color for replacements
        if (replacements != null && length > 0
                && replacements[length - 1] instanceof Boolean
                && (Boolean) replacements[length - 1]) {
            useMessageColorForReplacements = true;
            length -= 1;
        } else if (replacements != null && length > 0 && replacements[length - 1] instanceof String
                && "true".equalsIgnoreCase(String.valueOf(replacements[length - 1]))) {
            useMessageColorForReplacements = true;
            length -= 1;
        }

        if (replacements != null) {
            for (int i = 0; i < length - 1; i += 2) {
                Object keyObj = replacements[i];
                Object valueObj = replacements[i + 1];

                if (keyObj == null || valueObj == null) {
                    continue;
                }

                String placeholderKey = String.valueOf(keyObj);
                String placeholderValue = String.valueOf(valueObj);

                // Check if this is a placeholder (wrapped in {})
                boolean isPlaceholder = placeholderKey.startsWith("{") && placeholderKey.endsWith("}")
                        && placeholderKey.indexOf('{', 1) == -1;

                if (isPlaceholder) {
                    // Remove braces for placeholder-style keys
                    placeholderKey = placeholderKey.substring(1, placeholderKey.length() - 1);
                    replacementsMap.put(placeholderKey, placeholderValue);
                } else {
                    // Default to literal replacement for non-placeholder keys
                    literalReplacements.put(placeholderKey, placeholderValue);
                }
            }
        }

        sendErrorMessage(sender, key, lang, replacementsMap,
                literalReplacements.isEmpty() ? null : literalReplacements, useMessageColorForReplacements);
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

        // Play error sound if sender is a player and message is a language key
        if (message instanceof String) {
            playErrorSound(sender, (String) message, lang);
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

            // Get the message from the language key
            stringMessage = lang.get("unknown-command-suggestion"); // Specific message key
            if (stringMessage == null || stringMessage.isEmpty()) {
                sender.sendMessage("§c§lError: §rMissing error message for key: unknown-command-suggestion");
                return;
            }

            // Apply error styling with placeholders for map case
            Map<String, String> stringReplacements = new HashMap<>();
            for (Map.Entry<String, Object> entry : replacementMap.entrySet()) {
                String placeholder = entry.getKey();
                Object replacementValue = entry.getValue();

                if (replacementValue instanceof Component) {
                    // Handle Component replacement - serialize and keep as-is (no styling applied to components)
                    Component replacementComponent = (Component) replacementValue;
                    String serializedComponent = LegacyComponentSerializer.legacySection().serialize(replacementComponent);
                    stringMessage = stringMessage.replace(placeholder, serializedComponent);
                } else {
                    // Handle String replacement - collect for styling
                    String value = String.valueOf(replacementValue);
                    // Assume placeholder includes curly braces, remove them for the map key
                    String placeholderKey = placeholder.startsWith("{") && placeholder.endsWith("}")
                                          ? placeholder.substring(1, placeholder.length() - 1)
                                          : placeholder;
                    stringReplacements.put(placeholderKey, value);
                }
            }

            // Apply error styling to the message with string replacements
            String styledMessage = applyErrorStyling(stringMessage, lang, stringReplacements, null, null, false);

            // Send the formatted message with the error prefix
            if (!hasPrefix) {
                // When there's no prefix, parse the colors in the message itself
                if (sender instanceof Player) {
                    Component messageComponent = colorize(parseColors(styledMessage));
                    ((Player) sender).sendMessage(messageComponent);
                } else {
                    sender.sendMessage(parseColors(styledMessage));
                }
            } else {
                // When there's a prefix, add it before the message
                if (sender instanceof Player) {
                    Component prefixComponent = colorize(errorPrefix);
                    Component messageComponent = colorize(parseColors(styledMessage));
                    Component finalMessage = prefixComponent.append(Component.space()).append(messageComponent);
                    ((Player) sender).sendMessage(finalMessage);
                } else {
                    sender.sendMessage(errorPrefix + " " + parseColors(styledMessage));
                }
            }
            return; // Important to return, so it doesn't run other logic
        } else {
            sender.sendMessage("§c§lError: §rInvalid message type. Must be a language key (String) or a Component.");
            return;
        }

        // Get the error prefix and handle empty prefix case
        String errorPrefix = lang.get("error-prefix");
        boolean hasPrefix = !errorPrefix.isEmpty();

        if (componentMessage != null) {
            // Send Component directly using Adventure API for Players, fallback for console
            if (sender instanceof Player) {
                Player player = (Player) sender;
                Component finalMessage = Component.empty();

                if (hasPrefix) {
                    Component prefixComponent = colorize(errorPrefix);
                    finalMessage = prefixComponent.append(Component.space()).append(componentMessage);
                } else {
                    finalMessage = componentMessage;
                }

                player.sendMessage(finalMessage);
            } else {
                // Fallback for console - serialize and send as string
                String serializedComponent = LegacyComponentSerializer.legacySection().serialize(componentMessage);

                if (!hasPrefix) {
                    sender.sendMessage(parseColors(serializedComponent));
                } else {
                    sender.sendMessage(errorPrefix + " " + serializedComponent);
                }
            }
        } else {
            // Handle string messages with potential Component replacements
            Map<String, String> stringReplacements = new HashMap<>();
            Map<String, String> literalReplacements = new LinkedHashMap<>();
            List<Component> componentReplacements = new ArrayList<>();

            boolean allowLiteralKeys = false;
            int replacementsLength = replacements.length;
            if (replacementsLength > 0) {
                Object last = replacements[replacementsLength - 1];
                if (last instanceof Boolean) {
                    allowLiteralKeys = (Boolean) last;
                    replacementsLength -= 1;
                } else if (last instanceof String && "true".equalsIgnoreCase(String.valueOf(last))) {
                    allowLiteralKeys = true;
                    replacementsLength -= 1;
                }
            }

            for (int i = 0; i < replacementsLength - 1; i += 2) {
                String placeholder = String.valueOf(replacements[i]);
                Object replacementValue = replacements[i + 1];

                boolean isPurePlaceholder = placeholder.startsWith("{") && placeholder.endsWith("}")
                        && placeholder.indexOf('{', 1) == -1;

                if (allowLiteralKeys && !isPurePlaceholder && !(replacementValue instanceof Component)) {
                    literalReplacements.put(placeholder, String.valueOf(replacementValue));
                    continue;
                }

                if (replacementValue instanceof Component) {
                    // Handle Component replacement - collect for component building
                    Component replacementComponent = (Component) replacementValue;
                    componentReplacements.add(replacementComponent);
                    // For string replacement, use a placeholder that won't conflict
                    if (stringMessage != null) {
                        stringMessage = stringMessage.replace(placeholder, "{COMPONENT_" + (componentReplacements.size() - 1) + "}");
                    }
                } else {
                    // Handle String replacement - collect for styling
                    String value = String.valueOf(replacementValue);
                    // Remove curly braces if present in the placeholder
                    String placeholderKey = placeholder.startsWith("{") && placeholder.endsWith("}")
                                          ? placeholder.substring(1, placeholder.length() - 1)
                                          : placeholder;
                    stringReplacements.put(placeholderKey, value);
                }
            }

            // Apply error styling to the message with string replacements
            String styledMessage = applyErrorStyling(stringMessage, lang, stringReplacements,
                    literalReplacements.isEmpty() ? null : literalReplacements, message instanceof String ? (String) message : null, false);

            // Send the message - use Adventure API for Players if we have Component replacements
            if (sender instanceof Player && !componentReplacements.isEmpty()) {
                Player player = (Player) sender;
                Component finalMessage = Component.empty();

                // Build the component message by processing the styled message
                String[] parts = styledMessage.split("\\{COMPONENT_\\d+\\}");
                List<String> textParts = new ArrayList<>();
                for (String part : parts) {
                    if (!part.isEmpty()) {
                        textParts.add(part);
                    }
                }

                // Build the final component
                boolean isFirst = true;
                int componentIndex = 0;

                for (String textPart : textParts) {
                    if (!isFirst) {
                        // Add component replacement if available
                        if (componentIndex < componentReplacements.size()) {
                            finalMessage = finalMessage.append(componentReplacements.get(componentIndex++));
                        }
                    }

                    if (!textPart.isEmpty()) {
                        Component textComponent = colorize(textPart);
                        finalMessage = finalMessage.append(textComponent);
                    }

                    isFirst = false;
                }

                // Add any remaining components
                while (componentIndex < componentReplacements.size()) {
                    finalMessage = finalMessage.append(componentReplacements.get(componentIndex++));
                }

                // Add error prefix if present
                if (hasPrefix) {
                    Component prefixComponent = colorize(errorPrefix);
                    finalMessage = prefixComponent.append(Component.space()).append(finalMessage);
                }

                player.sendMessage(finalMessage);
            } else {
                // Fallback for console or when no Component replacements - use string approach
                if (!hasPrefix) {
                    sender.sendMessage(parseColors(styledMessage));
                } else {
                    sender.sendMessage(errorPrefix + " " + parseColors(styledMessage));
                }
            }
        }
    }

    /**
     * Plays the error sound for a message key if the sender is a player and a sound is defined.
     */
    private static void playErrorSound(CommandSender sender, String key, Lang lang) {
        if (!(sender instanceof Player player)) {
            return;
        }
        
        try {
            lang.getSoundInstruction(key).ifPresent(soundInstruction ->
                player.playSound(player.getLocation(), soundInstruction.sound(), soundInstruction.volume(), soundInstruction.pitch())
            );
        } catch (Exception e) {
            sendDebugLog(DebugSeverity.WARN, "Failed to play error sound for key " + key + ": " + e.getMessage());
        }
    }

    public static void sendErrorMessage(CommandSender sender, Component message, Lang lang) {
        if (sender == null || message == null || lang == null) {
            return;
        }

        // Get the error prefix and handle empty prefix case
        String errorPrefix = lang.get("error-prefix");
        boolean hasPrefix = !errorPrefix.isEmpty();

        // Send Component directly using Adventure API for Players, fallback for console
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Component finalMessage = Component.empty();

            if (hasPrefix) {
                Component prefixComponent = colorize(errorPrefix);
                finalMessage = prefixComponent.append(Component.space()).append(message);
            } else {
                finalMessage = message;
            }

            player.sendMessage(finalMessage);
        } else {
            // Fallback for console - serialize and send as string
            String serializedComponent = LegacyComponentSerializer.legacySection().serialize(message);

            if (!hasPrefix) {
                sender.sendMessage(parseColors(serializedComponent));
            } else {
                sender.sendMessage(errorPrefix + " " + serializedComponent);
            }
        }
    }

    public static void sendErrorMessageWithComponent(CommandSender sender, Component message, Lang lang) {
        if (sender == null || message == null || lang == null) {
            return;
        }

        // Get the error prefix and handle empty prefix case
        String errorPrefix = lang.get("error-prefix");
        boolean hasPrefix = !errorPrefix.isEmpty();

        // Send Component directly using Adventure API for Players, fallback for console
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Component finalMessage = Component.empty();

            if (hasPrefix) {
                Component prefixComponent = colorize(errorPrefix);
                finalMessage = prefixComponent.append(Component.space()).append(message);
            } else {
                finalMessage = message;
            }

            player.sendMessage(finalMessage);
        } else {
            // Fallback for console - serialize and send as string
            String serializedComponent = LegacyComponentSerializer.legacySection().serialize(message);

            if (!hasPrefix) {
                sender.sendMessage(parseColors(serializedComponent));
            } else {
                sender.sendMessage(errorPrefix + " " + serializedComponent);
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
