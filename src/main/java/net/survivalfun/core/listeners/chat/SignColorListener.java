package net.survivalfun.core.listeners.chat;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SignColorListener implements Listener {

    private final boolean placeholderAPIEnabled;
    private final LegacyComponentSerializer legacySerializer;
    
    // Pattern for MiniMessage tags
    private final Pattern miniMessagePattern = Pattern.compile("<([^>]+)>");

    public SignColorListener() {
        this.placeholderAPIEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        this.legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        
        // Process each line of the sign (avoid deprecated getLines())
        for (int i = 0; i < 4; i++) {
            String line = PlainTextComponentSerializer.plainText().serialize(event.line(i));
            if (line != null && !line.isEmpty()) {
                String filteredLine = filterSignColors(player, line);
                
                // Debug logging
                if (!line.equals(filteredLine)) {
                    Text.sendDebugLog(INFO, player.getName() + " - Line " + i + ": '" + line + "' -> '" + filteredLine + "'");
                }
                
                // Use legacy serializer to properly parse color codes
                event.line(i, legacySerializer.deserialize(filteredLine));
            }
        }
    }

    /**
     * Filters sign colors and formatting based on player's permissions
     * @param player The player creating/editing the sign
     * @param message The original sign line text
     * @return The filtered message
     */
    private String filterSignColors(Player player, String message) {
        String filteredMessage = message;
        
        // Process PlaceholderAPI first (if player has permission)
        filteredMessage = processPlaceholderAPI(player, filteredMessage);
        
        // Filter legacy color codes
        filteredMessage = stripUnauthorizedFormatting(filteredMessage, player);
        
        // Filter MiniMessage tags
        filteredMessage = stripUnauthorizedMiniMessage(filteredMessage, player);
        
        return filteredMessage;
    }

    /**
     * Strips unauthorized formatting codes based on granular permissions
     * @param message The message to filter
     * @param player The player creating the sign
     * @return The filtered message
     */
    private String stripUnauthorizedFormatting(String message, Player player) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        StringBuilder filteredMessage = new StringBuilder();
        int i = 0;

        while (i < message.length()) {
            if (i + 1 < message.length() && (message.charAt(i) == '&' || message.charAt(i) == '\u00a7')) {
                char colorChar = Character.toLowerCase(message.charAt(i + 1));
                boolean keepCode = false;

                switch (colorChar) {
                    case '0': keepCode = player.hasPermission("allium.sign.color.black"); break;
                    case '1': keepCode = player.hasPermission("allium.sign.color.dark_blue"); break;
                    case '2': keepCode = player.hasPermission("allium.sign.color.dark_green"); break;
                    case '3': keepCode = player.hasPermission("allium.sign.color.dark_aqua"); break;
                    case '4': keepCode = player.hasPermission("allium.sign.color.dark_red"); break;
                    case '5': keepCode = player.hasPermission("allium.sign.color.dark_purple"); break;
                    case '6': keepCode = player.hasPermission("allium.sign.color.gold"); break;
                    case '7': keepCode = player.hasPermission("allium.sign.color.gray"); break;
                    case '8': keepCode = player.hasPermission("allium.sign.color.dark_gray"); break;
                    case '9': keepCode = player.hasPermission("allium.sign.color.blue"); break;
                    case 'a': keepCode = player.hasPermission("allium.sign.color.green"); break;
                    case 'b': keepCode = player.hasPermission("allium.sign.color.aqua"); break;
                    case 'c': keepCode = player.hasPermission("allium.sign.color.red"); break;
                    case 'd': keepCode = player.hasPermission("allium.sign.color.light_purple"); break;
                    case 'e': keepCode = player.hasPermission("allium.sign.color.yellow"); break;
                    case 'f': keepCode = player.hasPermission("allium.sign.color.white"); break;
                    case 'l': keepCode = player.hasPermission("allium.sign.format.bold"); break;
                    case 'o': keepCode = player.hasPermission("allium.sign.format.italic"); break;
                    case 'n': keepCode = player.hasPermission("allium.sign.format.underline"); break;
                    case 'm': keepCode = player.hasPermission("allium.sign.format.strikethrough"); break;
                    case 'k': keepCode = player.hasPermission("allium.sign.format.magic"); break;
                    case 'r': keepCode = player.hasPermission("allium.sign.format.reset"); break;
                    default: keepCode = false; break;
                }

                if (keepCode) {
                    filteredMessage.append(message.charAt(i)).append(message.charAt(i + 1));
                    i += 2;
                } else {
                    // Skip the color code, leaving raw text
                    i += 2;
                }
            } else if (i + 7 < message.length() && message.charAt(i) == '&' && message.charAt(i + 1) == '#' &&
                    message.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                // Handle hex colors (&#RRGGBB format)
                if (player.hasPermission("allium.sign.color.hex")) {
                    filteredMessage.append(message, i, i + 8);
                    i += 8;
                } else {
                    // Skip the hex color code
                    i += 8;
                }
            } else {
                filteredMessage.append(message.charAt(i));
                i++;
            }
        }

        return filteredMessage.toString();
    }

    /**
     * Strips unauthorized MiniMessage tags based on permissions
     * @param message The message to filter
     * @param player The player creating the sign
     * @return The filtered message
     */
    private String stripUnauthorizedMiniMessage(String message, Player player) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        StringBuilder result = new StringBuilder();
        Matcher matcher = miniMessagePattern.matcher(message);
        int lastEnd = 0;

        while (matcher.find()) {
            // Add text before the tag
            result.append(message, lastEnd, matcher.start());
            
            String tag = matcher.group(1).toLowerCase();
            boolean keepTag = false;

            // Check permissions for different MiniMessage features
            if (tag.matches("color:[a-z_]+")) {
                String colorName = tag.substring(6); // Remove "color:" prefix
                keepTag = player.hasPermission("allium.sign.minimessage.color." + colorName);
            } else if (tag.matches("#[0-9a-f]{6}")) {
                keepTag = player.hasPermission("allium.sign.minimessage.color.hex");
            } else if (tag.equals("bold") || tag.equals("/bold")) {
                keepTag = player.hasPermission("allium.sign.minimessage.format.bold");
            } else if (tag.equals("italic") || tag.equals("/italic")) {
                keepTag = player.hasPermission("allium.sign.minimessage.format.italic");
            } else if (tag.equals("underlined") || tag.equals("/underlined")) {
                keepTag = player.hasPermission("allium.sign.minimessage.format.underlined");
            } else if (tag.equals("strikethrough") || tag.equals("/strikethrough")) {
                keepTag = player.hasPermission("allium.sign.minimessage.format.strikethrough");
            } else if (tag.equals("obfuscated") || tag.equals("/obfuscated")) {
                keepTag = player.hasPermission("allium.sign.minimessage.format.obfuscated");
            } else if (tag.startsWith("hover:")) {
                keepTag = player.hasPermission("allium.sign.minimessage.hover");
            } else if (tag.startsWith("click:")) {
                keepTag = player.hasPermission("allium.sign.minimessage.click");
            } else if (tag.startsWith("gradient")) {
                keepTag = player.hasPermission("allium.sign.minimessage.gradient");
            } else if (tag.equals("rainbow") || tag.equals("/rainbow")) {
                keepTag = player.hasPermission("allium.sign.minimessage.rainbow");
            }

            if (keepTag) {
                result.append(matcher.group(0)); // Keep the full tag
            }
            // If not keeping the tag, we just skip it (don't add it to result)

            lastEnd = matcher.end();
        }

        // Add remaining text after the last tag
        result.append(message.substring(lastEnd));

        return result.toString();
    }

    /**
     * Processes PlaceholderAPI placeholders in a message if the player has permission
     * @param player The player creating the sign
     * @param message The message to process
     * @return The processed message
     */
    private String processPlaceholderAPI(Player player, String message) {
        if (!placeholderAPIEnabled) {
            return message;
        }
        
        if (player.hasPermission("allium.sign.placeholderapi")) {
            return PlaceholderAPI.setPlaceholders(player, message);
        }
        
        return message;
    }
}
