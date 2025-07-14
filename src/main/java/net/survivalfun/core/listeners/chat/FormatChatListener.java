package net.survivalfun.core.listeners.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault2.chat.Chat;
import net.milkbowl.vault2.permission.Permission;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.core.Text;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

public class FormatChatListener implements Listener {

    private final PluginStart plugin;
    private final LegacyComponentSerializer legacyComponentSerializer;
    private final Chat vaultChat;
    private final Permission vaultPermission;
    private final Map<String, String> groupFormats;
    private final Config config;
    private final String defaultFormat;
    private final boolean placeholderAPIEnabled;
    private final Pattern placeholderPattern = Pattern.compile("%\\{([^}]+)\\}%");
    private final Pattern rawMessagePattern = Pattern.compile("\\[(.*?)\\]");
    private final MiniMessage miniMessage;
    private final boolean blockUnicode;

    public FormatChatListener(PluginStart plugin, Chat vaultChat, Config config) {
        this.plugin = plugin;
        this.config = config;
        this.vaultChat = vaultChat;
        this.vaultPermission = plugin.getVaultPermission();
        this.blockUnicode = config.getBoolean("block-unicode");

        legacyComponentSerializer = LegacyComponentSerializer.builder().hexColors().character('&').build();
        miniMessage = MiniMessage.miniMessage();

        // Get the default format from config
        this.defaultFormat = config.getString("chat-format.default", "<prefix> &a<player>&f: &f<message>");

        // Check if PlaceholderAPI is available
        this.placeholderAPIEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!placeholderAPIEnabled) {
            plugin.getLogger().info("PlaceholderAPI not found. Placeholders in chat format will not work.");
        }

        // Load group formats safely
        groupFormats = new HashMap<>();
        loadGroupFormats();
    }

    /**
     * Loads group formats from the config
     */
    private void loadGroupFormats() {
        FileConfiguration fileConfig = plugin.getConfig();
        ConfigurationSection groupsSection = fileConfig.getConfigurationSection("chat-format.groups");

        if (groupsSection != null) {
            Map<String, Object> tempMap = groupsSection.getValues(false);
            for (Map.Entry<String, Object> entry : tempMap.entrySet()) {
                groupFormats.put(entry.getKey(), entry.getValue().toString());
            }
            if (config.getBoolean("debug-mode")) {
                plugin.getLogger().info("Loaded " + groupFormats.size() + " group chat formats.");
            }
        }
    }

    public boolean canEnable() {
        // Allow basic formatting if vaultPermission is available, even if vaultChat is null
        return vaultPermission != null;
    }

    /**
     * Checks if a string contains unicode characters
     * @param message The input string
     * @return true if the string contains unicode characters, false otherwise
     */
    private boolean containsUnicode(String message) {
        return message.codePoints().anyMatch(cp -> cp > 127);
    }

    /**
     * Converts legacy color codes like &c to MiniMessage format like <red>
     * This allows players to use both formats in the same message
     *
     * @param message The input message with possible legacy color codes
     * @return The same message with legacy codes converted to MiniMessage format
     */
    private String convertLegacyToMiniMessage(String message) {
        if (message == null || message.isEmpty()) return message;

        // Replace all color codes with their MiniMessage equivalents
        message = message.replace("&0", "<black>");
        message = message.replace("&1", "<dark_blue>");
        message = message.replace("&2", "<dark_green>");
        message = message.replace("&3", "<dark_aqua>");
        message = message.replace("&4", "<dark_red>");
        message = message.replace("&5", "<dark_purple>");
        message = message.replace("&6", "<gold>");
        message = message.replace("&7", "<gray>");
        message = message.replace("&8", "<dark_gray>");
        message = message.replace("&9", "<blue>");
        message = message.replace("&a", "<green>");
        message = message.replace("&b", "<aqua>");
        message = message.replace("&c", "<red>");
        message = message.replace("&d", "<light_purple>");
        message = message.replace("&e", "<yellow>");
        message = message.replace("&f", "<white>");

        // Format codes
        message = message.replace("&l", "<bold>");
        message = message.replace("&m", "<strikethrough>");
        message = message.replace("&n", "<underlined>");
        message = message.replace("&o", "<italic>");
        message = message.replace("&r", "<reset>");

        return message;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(@NotNull AsyncChatEvent event) {
        if (!config.getBoolean("enable-chat-formatting")) {
            return;
        }

        Player player = event.getPlayer();
        Component messageComponent = event.message();
        String rawMessage = legacyComponentSerializer.serialize(messageComponent);
        String originalMessage = PlainTextComponentSerializer.plainText().serialize(event.message());

        boolean debugMode = config.getBoolean("debug-mode");
        if (debugMode) {
            plugin.getLogger().info("Raw message from " + player.getName() + ": " + rawMessage);
        }

        if (blockUnicode) {
            // Skip unicode check if vaultPermission is null or player has the permission
            if (vaultPermission != null && !vaultPermission.playerHas(player, "chat.unicode")) {
                if (containsUnicode(originalMessage)) {
                    event.setCancelled(true);
                    player.sendMessage(Text.parseColors("&cYour message contains unicode characters which are not allowed."));
                    if (debugMode) {
                        plugin.getLogger().info("Blocked message with unicode from " + player.getName() + ": " + originalMessage);
                    }
                    return;
                }
            } else if (vaultPermission == null && containsUnicode(originalMessage)) {
                // Fallback: block unicode if vaultPermission is null and block-unicode is enabled
                event.setCancelled(true);
                player.sendMessage(Text.parseColors("&cYour message contains unicode characters which are not allowed."));
                if (debugMode) {
                    plugin.getLogger().info("Blocked message with unicode from " + player.getName() + ": " + originalMessage);
                }
                return;
            }
        }

        String messageContent = rawMessage;
        if (vaultPermission != null && vaultPermission.playerHas(player, "chat.color")) {
            messageContent = stripUnauthorizedFormatting(messageContent, player);
        } else {
            messageContent = Text.stripColor(messageContent, null);
        }

        // Parse the message based on player permissions
        if (vaultPermission != null && vaultPermission.playerHas(player, "chat.minimessage")) {
            String convertedMessage = convertLegacyToMiniMessage(messageContent);
            messageComponent = miniMessage.deserialize(convertedMessage);
        } else {
            messageComponent = legacyComponentSerializer.deserialize(messageContent);
        }

        String chatFormat = getChatFormat(player);

        // Assemble the final message string with raw color codes
        String finalMessageFormat = chatFormat.replace("<prefix>", getPrefix(player))
                .replace("<player>", player.getName());

        // Process placeholders only if player has permission
        if (vaultPermission != null && vaultPermission.playerHas(player, "chat.placeholderapi")) {
            finalMessageFormat = processPlaceholderAPIPlaceholders(finalMessageFormat, player);
        } else if (vaultPermission == null && placeholderAPIEnabled) {
            // Fallback: process placeholders if PlaceholderAPI is enabled
            finalMessageFormat = processPlaceholderAPIPlaceholders(finalMessageFormat, player);
        }

        // Build the final component, separating raw text from formatted text
        TextComponent.Builder builder = Component.text();
        String[] parts = finalMessageFormat.split("<message>", -1);

        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                builder.append(legacyComponentSerializer.deserialize(parts[i]));
            }
            if (i < parts.length - 1) {
                // This is where the <message> was. Always handle raw tags first.
                Matcher rawMatcher = rawMessagePattern.matcher(messageContent);
                int lastEnd = 0;
                while (rawMatcher.find()) {
                    String before = messageContent.substring(lastEnd, rawMatcher.start());

                    // Check if the raw tag is escaped by a preceding color code (for non-MiniMessage users)
                    boolean isEscaped = false;
                    if (!vaultPermission.playerHas(player, "chat.minimessage")) {
                        if (rawMatcher.start() > 0) {
                            char charBefore = messageContent.charAt(rawMatcher.start() - 1);
                            if (Text.isColorChar(charBefore)) { // e.g., &c[...] or c[...] if using section signs
                                if (rawMatcher.start() > 1) {
                                    char charTwoBefore = messageContent.charAt(rawMatcher.start() - 2);
                                    if (charTwoBefore == '&' || charTwoBefore == '§') {
                                        isEscaped = true;
                                    }
                                }
                            } else if (Character.isWhitespace(charBefore) && rawMatcher.start() > 2) { // e.g.,  &c[...]
                                char charTwoBefore = messageContent.charAt(rawMatcher.start() - 2);
                                char charThreeBefore = messageContent.charAt(rawMatcher.start() - 3);
                                if (Text.isColorChar(charTwoBefore) && (charThreeBefore == '&' || charThreeBefore == '§')) {
                                    isEscaped = true;
                                }
                            }
                        }
                    }

                    if (isEscaped) {
                        // It's escaped. Combine the part before the match with the content inside the brackets.
                        String textToParse = before + rawMatcher.group(1);
                        builder.append(legacyComponentSerializer.deserialize(textToParse));
                    } else {
                        // It's a true raw tag or a MiniMessage user. Process the part before it, then the raw content.
                        if (!before.isEmpty()) {
                            if (vaultPermission.playerHas(player, "chat.minimessage")) {
                                builder.append(miniMessage.deserialize(before));
                            } else {
                                builder.append(legacyComponentSerializer.deserialize(before));
                            }
                        }
                        // The raw part itself (excluding brackets)
                        builder.append(Component.text(rawMatcher.group(1)));
                    }
                    lastEnd = rawMatcher.end();
                }
                // Part after the last raw tag
                String after = messageContent.substring(lastEnd);
                if (!after.isEmpty()) {
                    if (vaultPermission.playerHas(player, "chat.minimessage")) {
                        builder.append(miniMessage.deserialize(after));
                    } else {
                        builder.append(legacyComponentSerializer.deserialize(after));
                    }
                }
            }
        }

        final Component finalFormattedComponent = builder.build();
        event.renderer((source, sourceDisplayName, message, viewer) -> finalFormattedComponent);
    }

    /**
     * Gets the chat format for a player
     * @param player The player to get the format for
     * @return The chat format for the player
     */
    private String getChatFormat(Player player) {
        String groupName = "default"; // Default group
        boolean debugMode = config.getBoolean("debug-mode");

        if (vaultChat != null) {
            groupName = vaultChat.getPrimaryGroup(null, player);
            if (groupName == null) {
                groupName = "default";
            }
            if (debugMode) {
                plugin.getLogger().info("Vault group for " + player.getName() + ": " + groupName);
            }
        } else {
            if (debugMode) {
                plugin.getLogger().info("Vault Chat unavailable, using default group for " + player.getName());
            }
        }

        // Get format for the group, or the default format if not found
        if (groupFormats.containsKey(groupName)) {
            return groupFormats.get(groupName);
        } else {
            if (debugMode) {
                plugin.getLogger().info("No format found for group: " + groupName + ", using default format");
            }
            return defaultFormat;
        }
    }

    /**
     * Process PlaceholderAPI placeholders in the format %{placeholder}%
     * @param text The text containing placeholders
     * @param player The player to process placeholders for
     * @return The text with processed placeholders
     */
    private String processPlaceholderAPIPlaceholders(String text, Player player) {
        if (!placeholderAPIEnabled) return text;

        // First, handle our custom format %{placeholder}%
        Matcher matcher = placeholderPattern.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            // Convert our format to PlaceholderAPI format
            String papiPlaceholder = "%" + placeholder + "%";
            // Process with PlaceholderAPI
            String replacement = PlaceholderAPI.setPlaceholders(player, papiPlaceholder);
            // Replace in the string
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);

        // Also process any standard PlaceholderAPI placeholders that might be in the text
        return PlaceholderAPI.setPlaceholders(player, buffer.toString());
    }

    private String getPrefix(Player player) {
        String prefix = "";
        boolean debugMode = config.getBoolean("debug-mode");

        try {
            if (vaultChat != null) {
                prefix = Objects.requireNonNullElse(vaultChat.getPlayerPrefix(null, player), "");
                if (debugMode) {
                    plugin.getLogger().info("Vault Prefix: " + prefix);
                }
            } else {
                if (debugMode) {
                    plugin.getLogger().info("Vault Chat unavailable, using empty prefix for " + player.getName());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error getting prefix: " + e.getMessage());
        }

        if (debugMode) {
            plugin.getLogger().info("Final Prefix: " + prefix);
        }
        return prefix;
    }

    private String stripUnauthorizedFormatting(String message, Player player) {
        // If the player doesn't have chat.color, remove all color and formatting codes
        if (!vaultPermission.playerHas(player, "chat.color")) {
            message = message.replaceAll("&[0-9a-fk-or]", "");
        } else {
            // Remove specific formats if the player doesn't have the permission for that format
            if (!vaultPermission.playerHas(player, "chat.format.bold")) {
                message = message.replaceAll("&l", "");
            }
            if (!vaultPermission.playerHas(player, "chat.format.italic")) {
                message = message.replaceAll("&o", "");
            }
            if (!vaultPermission.playerHas(player, "chat.format.underline")) {
                message = message.replaceAll("&n", "");
            }
            if (!vaultPermission.playerHas(player, "chat.format.strikethrough")) {
                message = message.replaceAll("&m", "");
            }
            if (!vaultPermission.playerHas(player, "chat.format.magic")) {
                message = message.replaceAll("&k", "");
            }
            if (!vaultPermission.playerHas(player, "chat.format.reset")) {
                message = message.replaceAll("&r", "");
            }
        }
        return message;
    }

    /**
     * Reloads the chat formats from the config
     */
    public void reload() {
        groupFormats.clear();
        loadGroupFormats();
    }
}