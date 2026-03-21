package net.survivalfun.core.listeners.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.config.Config;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.chat.ChatMessageManager;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final Pattern rawMessagePattern = Pattern.compile("\\[(&[0-9a-fA-Fk-oK-OrR].*?)\\]|\\[([^&].*?)\\]");
    private final Pattern placeholderBracketPattern = Pattern.compile("\\{([^}]+)\\}");
    private final MiniMessage miniMessage;
    private final boolean blockUnicode;
    private final Map<String, String> rawTextMappings = new HashMap<>();
    private final ChatMessageManager chatMessageManager;

    public FormatChatListener(PluginStart plugin, Chat vaultChat, Config config, ChatMessageManager chatMessageManager) {
        this.plugin = plugin;
        this.config = config;
        this.vaultChat = vaultChat;
        this.chatMessageManager = chatMessageManager;
        this.vaultPermission = plugin.getVaultPermission();
        this.blockUnicode = config.getBoolean("block-unicode");

        legacyComponentSerializer = LegacyComponentSerializer.builder().hexColors().character('&').build();
        miniMessage = MiniMessage.miniMessage();

        this.defaultFormat = plugin.getConfig().getString("chat-format.default", "<prefix> &a%allium_nickname%&f: &f<message>");

        this.placeholderAPIEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!placeholderAPIEnabled) {
            Text.sendDebugLog(INFO, "PlaceholderAPI not found. Placeholders in chat format will not work.");
        }

        groupFormats = new HashMap<>();
        loadGroupFormats();
    }

    private void loadGroupFormats() {
        FileConfiguration fileConfig = plugin.getConfig();
        ConfigurationSection groupsSection = fileConfig.getConfigurationSection("chat-format.groups");

        if (groupsSection != null) {
            Map<String, Object> tempMap = groupsSection.getValues(false);
            for (Map.Entry<String, Object> entry : tempMap.entrySet()) {
                groupFormats.put(entry.getKey(), entry.getValue().toString());
            }
            if (config.getBoolean("debug-mode")) {
                Text.sendDebugLog(INFO, "Loaded " + groupFormats.size() + " group chat formats.");
            }
        }
    }

    private String getHighestPriorityGroup(Player player) {
        if (vaultPermission == null) {
            return "default";
        }

        String[] groups;
        try {
            groups = vaultPermission.getPlayerGroups(null, player);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error getting player groups: " + e.getMessage());
            return "default";
        }
        
        if (groups == null || groups.length == 0) {
            return "default";
        }

        String highestGroup = "default";
        int highestWeight = Integer.MIN_VALUE;
        boolean debugMode = config.getBoolean("debug-mode");

        for (String group : groups) {
            try {
                int weight = 0;
                if (vaultChat != null) {
                    String weightStr = vaultChat.getGroupInfoString(player.getWorld().getName(), group, "weight", "0");
                    try {
                        weight = Integer.parseInt(weightStr);
                    } catch (NumberFormatException e) {
                        weight = 0;
                    }
                } else {
                    weight = Arrays.asList(groups).indexOf(group);
                }

                if (weight > highestWeight) {
                    highestWeight = weight;
                    highestGroup = group;
                }
            } catch (Exception e) {
                if (debugMode) {
                    Text.sendDebugLog(WARN, "Error getting weight for group: " + group + " - " + e.getMessage());
                }
            }
        }

        if (!plugin.getConfig().contains("chat-hover.groups." + highestGroup)) {
            for (String group : groups) {
                if (plugin.getConfig().contains("chat-hover.groups." + group)) {
                    return group;
                }
            }
            return "default";
        }

        if (debugMode) {
            Text.sendDebugLog(INFO, "Selected highest priority group: " + highestGroup + " with weight " + highestWeight);
        }
        return highestGroup;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getHoverClickConfig(Player player, String element) {
        String group = getHighestPriorityGroup(player);
        String configPath = "chat-hover.groups." + group + "." + element;

        if (!plugin.getConfig().contains("chat-hover.groups." + group + "." + element)) {
            configPath = "chat-hover.groups.default." + element;
            if (!plugin.getConfig().contains(configPath)) {
                return new HashMap<>();
            }
        }

        Object value = plugin.getConfig().get(configPath);
        if (value instanceof ConfigurationSection) {
            return ((ConfigurationSection) value).getValues(false);
        } else if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new HashMap<>();
    }

    /**
     * Processes PlaceholderAPI placeholders in configuration values (hover, format, etc.)
     * This does NOT require permission as it's for server-configured content.
     * When PlaceholderAPI is not installed, replaces %allium_*% ourselves.
     * Always runs our own %allium_nickname% replacement as fallback (handles PAPI missing or expansion not registered).
     */
    private String processPlaceholderAPIPlaceholdersInConfig(String text, Player player) {
        // Replace our placeholders FIRST so we control the output (PAPI expansion may return blank)
        text = replaceAlliumPlaceholders(text, player);
        if (!placeholderAPIEnabled) {
            return text;
        }

        text = PlaceholderAPI.setPlaceholders(player, text);

        Matcher matcher = placeholderBracketPattern.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            if (placeholder.equals("player") || placeholder.equals("prefix") || placeholder.equals("group")) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String papiPlaceholder = "%" + placeholder + "%";
            String replacement = PlaceholderAPI.setPlaceholders(player, papiPlaceholder);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);

        return buffer.toString();
    }

    /** Returns the player's display name (nickname if set, otherwise username) with formatting applied. */
    private String getPlayerDisplayName(Player player) {
        if (player == null) return "";
        net.survivalfun.core.managers.NicknameManager nm = plugin.getNicknameManager();
        if (nm == null) return player.getName();
        String stored = nm.getStoredNickname(player);
        String formatted = nm.getFormattedNickname(player, stored != null && !stored.isEmpty() ? stored : player.getName());
        return (formatted != null && !formatted.isEmpty()) ? formatted : player.getName();
    }

    /**
     * Replaces %allium_nickname% and %allium_nickname_raw% with our own logic.
     * Used when PlaceholderAPI is missing or as fallback when our expansion isn't registered.
     * Defaults to player's real in-game name when no nickname is set.
     */
    private String replaceAlliumPlaceholders(String text, Player player) {
        if (text == null || player == null) return text;
        if (!text.contains("%allium_")) return text;

        String defaultName = player.getName();
        if (defaultName == null || defaultName.isEmpty()) defaultName = player.getUniqueId().toString();

        net.survivalfun.core.managers.NicknameManager nm = plugin.getNicknameManager();
        String raw = (nm != null) ? nm.getStoredNickname(player) : defaultName;
        if (raw == null || raw.isEmpty()) raw = defaultName;
        String formatted = (nm != null) ? nm.getFormattedNickname(player, raw) : defaultName;
        if (formatted == null || formatted.isEmpty()) formatted = defaultName;

        return text.replace("%allium_nickname%", formatted)
                   .replace("%allium_nickname_raw%", raw);
    }

    private String getFormattedPrefix(Player player) {
        String rawPrefix = getPrefix(player);
        if (rawPrefix != null && !rawPrefix.trim().isEmpty()) {
            Component prefixComponent = legacyComponentSerializer.deserialize(rawPrefix);
            return legacyComponentSerializer.serialize(prefixComponent);
        }
        return "";
    }

    private Component applyHoverClick(Player player, Component component, String element) {
        Map<String, Object> hoverClickConfig = getHoverClickConfig(player, element);
        if (hoverClickConfig.isEmpty()) {
            return component;
        }

        Component hoverComponent = Component.empty();
        if (hoverClickConfig.containsKey("hover")) {
            List<String> hoverText = hoverClickConfig.get("hover") instanceof List ? (List<String>) hoverClickConfig.get("hover") : Collections.singletonList(hoverClickConfig.get("hover").toString());

            if (hoverText != null && !hoverText.isEmpty()) {
                String hover = String.join("\n", hoverText)
                        .replace("{player}", player.getName())
                        .replace("{prefix}", getFormattedPrefix(player))
                        .replace("{group}", getHighestPriorityGroup(player));

                hover = processPlaceholderAPIPlaceholdersInConfig(hover, player);

                if (hover.contains("&") || hover.contains("§")) {
                    hover = convertLegacyToMiniMessage(hover);
                }

                try {
                    hoverComponent = miniMessage.deserialize(hover);
                } catch (Exception e) {
                    if (config.getBoolean("debug-mode")) {
                        Text.sendDebugLog(WARN, "Error parsing hover text: " + e.getMessage());
                    }
                    hoverComponent = Component.text(LegacyComponentSerializer.legacyAmpersand().deserialize(hover).content());
                }
            }
        }

        ClickEvent clickEvent = null;
        if (hoverClickConfig.containsKey("action") && hoverClickConfig.get("action") != null && hoverClickConfig.containsKey("click") && hoverClickConfig.get("click") != null) {
            String action = hoverClickConfig.get("action").toString();
            String click = hoverClickConfig.get("click").toString()
                    .replace("{player}", player.getName())
                    .replace("{prefix}", getFormattedPrefix(player))
                    .replace("{group}", getHighestPriorityGroup(player));

            click = processPlaceholderAPIPlaceholdersInConfig(click, player);

            switch (action.toLowerCase()) {
                case "run-command":
                    clickEvent = ClickEvent.runCommand(click);
                    break;
                case "suggest-command":
                    clickEvent = ClickEvent.suggestCommand(click);
                    break;
                case "open-url":
                    clickEvent = ClickEvent.openUrl(click);
                    break;
                case "copy-to-clipboard":
                    String content = hoverClickConfig.containsKey("content") ? hoverClickConfig.get("content").toString() : click;
                    content = content
                            .replace("{player}", player.getName())
                            .replace("{prefix}", getFormattedPrefix(player))
                            .replace("{group}", getHighestPriorityGroup(player));
                    content = processPlaceholderAPIPlaceholdersInConfig(content, player);
                    clickEvent = ClickEvent.copyToClipboard(content);
                    break;
            }
        }

        if (hoverComponent != Component.empty()) {
            component = component.hoverEvent(hoverComponent);
        }
        if (clickEvent != null) {
            component = component.clickEvent(clickEvent);
        }

        return component;
    }

    public boolean canEnable() {
        return true;
    }

    private boolean containsUnicode(String message) {
        return message.codePoints().anyMatch(cp -> cp > 127);
    }

    private String convertLegacyToMiniMessage(String message) {
        if (message == null || message.isEmpty()) return message;

        message = message.replaceAll("&#([A-Fa-f0-9]{6})", "<#$1>");
        message = message.replace("&l", "<bold>");
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
        message = message.replace("&k", "<obfuscated>");
        message = message.replace("&m", "<strikethrough>");
        message = message.replace("&n", "<underlined>");
        message = message.replace("&o", "<italic>");
        message = message.replace("&r", "<reset>");
        message = message.replace("§0", "<black>");
        message = message.replace("§1", "<dark_blue>");
        message = message.replace("§2", "<dark_green>");
        message = message.replace("§3", "<dark_aqua>");
        message = message.replace("§4", "<dark_red>");
        message = message.replace("§5", "<dark_purple>");
        message = message.replace("§6", "<gold>");
        message = message.replace("§7", "<gray>");
        message = message.replace("§8", "<dark_gray>");
        message = message.replace("§9", "<blue>");
        message = message.replace("§a", "<green>");
        message = message.replace("§b", "<aqua>");
        message = message.replace("§c", "<red>");
        message = message.replace("§d", "<light_purple>");
        message = message.replace("§e", "<yellow>");
        message = message.replace("§f", "<white>");
        message = message.replace("§k", "<obfuscated>");
        message = message.replace("§l", "<bold>");
        message = message.replace("§m", "<strikethrough>");
        message = message.replace("§n", "<underlined>");
        message = message.replace("§o", "<italic>");
        message = message.replace("§r", "<reset>");

        return message;
    }

    private String editMessageWithRawText(String message, Player player) {
        if (message == null || message.isEmpty()) return message;
        
        // Early exit if message doesn't contain any raw text patterns
        if (!message.matches(".*\\[.*\\].*")) {
            return message;
        }

        StringBuilder result = new StringBuilder();
        Matcher matcher = rawMessagePattern.matcher(message);
        int lastEnd = 0;
        boolean hasRawContent = false;

        while (matcher.find()) {
            String rawText = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String fullMatch = matcher.group(0);

            // Append text before the match
            result.append(message, lastEnd, matcher.start());

            // Check if the text inside brackets contains any color codes or formatting
            boolean hasFormatting = rawText.matches(".*[&§][0-9a-fA-Fk-oK-OrR].*");
            
            // Check if brackets are escaped (preceded by a color code with optional space)
            boolean isEscaped = false;
            if (matcher.start() > 0) {
                String beforeMatch = message.substring(0, matcher.start());
                if (beforeMatch.matches(".*[&§][0-9a-fA-Fk-oK-OrR](?:\\s)?$")) {
                    isEscaped = true;
                }
            }

            if (isEscaped) {
                // If escaped, keep the original text including brackets
                result.append(fullMatch);
            } else if (!hasFormatting) {
                // If no formatting codes, keep the brackets and their content as-is
                result.append('[').append(rawText).append(']');
            } else {
                // Has formatting codes, process as raw text
                hasRawContent = true;
                String protectedMarker = "\uE000RAW_" + System.nanoTime() + "_" + rawText.hashCode() + "\uE000";
                result.append(protectedMarker);
                
                if (!rawTextMappings.containsKey(protectedMarker)) {
                    rawTextMappings.put(protectedMarker, rawText);
                }
            }
            
            lastEnd = matcher.end();
        }
        
        // Append any remaining text after the last match
        if (lastEnd < message.length()) {
            result.append(message.substring(lastEnd));
        }
        
        // If no raw content was processed, return the original message to preserve any formatting
        return hasRawContent ? result.toString() : message;
    }

    private boolean hasMiniMessagePermission(Player player, String tagType) {
        if (player == null) return false;
        String permission = "chat.minimessage." + tagType.toLowerCase();
        return player.hasPermission(permission) || 
               player.hasPermission("chat.minimessage.*") || 
               player.hasPermission(permission + ".*");
    }

    private boolean validateMiniMessage(Player player, String message) {
        if (message.matches("(?i).*<(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white)>.*")) {
            if (!hasMiniMessagePermission(player, "color")) {
                String color = message.replaceAll("(?i).*<(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white)>.*", "$1");
                if (!hasMiniMessagePermission(player, "color." + color.toLowerCase())) {
                    return false;
                }
            }
        }

        if (message.matches("(?i).*<gradient[:#].*?>.*")) {
            if (!hasMiniMessagePermission(player, "gradient")) {
                return false;
            }
        }

        if (message.matches("(?i).*<rainbow[:#].*?>.*")) {
            if (!hasMiniMessagePermission(player, "rainbow")) {
                return false;
            }
        }

        if (message.matches("(?i).*<click:.*?>.*")) {
            if (!hasMiniMessagePermission(player, "click")) {
                return false;
            }
        }

        if (message.matches("(?i).*<hover:.*?>.*")) {
            if (!hasMiniMessagePermission(player, "hover")) {
                return false;
            }
        }

        if (message.matches("(?i).*<(b|bold|i|italic|u|underlined|st|strikethrough|obf|obfuscated|reset)>.*")) {
            if (!hasMiniMessagePermission(player, "format")) {
                return false;
            }
        }

        return true;
    }

    private boolean containsMiniMessageTags(String message) {
        Pattern miniMessagePattern = Pattern.compile("<[^>]+>|</[^>]+>|&#[0-9a-fA-F]{6}");
        return miniMessagePattern.matcher(message).find();
    }
    
    private Component processMessageContent(String content) {
        try {
            return miniMessage.deserialize(content);
        } catch (Exception e) {
            return Component.text(content);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(@NotNull AsyncChatEvent event) {
        if (!config.getBoolean("enable-chat-formatting")) {
            return;
        }

        Player player = event.getPlayer();
        Component messageComponent = event.message();
        String rawMessage = legacyComponentSerializer.serialize(messageComponent);
        String originalMessage = PlainTextComponentSerializer.plainText().serialize(event.message());

        boolean debugMode = config.getBoolean("debug-mode");

        // Unicode check and cleanup
        if (blockUnicode && !player.hasPermission("chat.unicode")) {
            if (containsUnicode(originalMessage)) {
                String cleanedMessage = originalMessage.replaceAll("[^-\u007F]+", "");
                if (debugMode) {
                    Text.sendDebugLog(INFO, "Stripped unicode from message by " + player.getName() + ": " + originalMessage + " -> " + cleanedMessage);
                }
                rawMessage = legacyComponentSerializer.serialize(Component.text(cleanedMessage));
                originalMessage = cleanedMessage;
            }
        }

        // Process raw text in brackets FIRST, before any formatting processing
        String messageContent = editMessageWithRawText(rawMessage, player);
        
        // Strip unauthorized formatting and track if we have legacy codes
        boolean hasLegacyCodes = false;
        if (vaultPermission != null) {
            hasLegacyCodes = rawMessage.matches(".*[&§][0-9a-fA-Fk-oK-OrR].*");
            messageContent = stripUnauthorizedFormatting(messageContent, player);
            messageContent = convertLegacyToMiniMessage(messageContent);
        } else {
            messageContent = Text.stripColor(messageContent, null);
        }

        // Check MiniMessage permissions
        boolean useMiniMessage = false;
        boolean hasMiniMessageTags = containsMiniMessageTags(messageContent);
        boolean hasOriginalMiniMessageTags = containsMiniMessageTags(rawMessage);

        if (hasMiniMessageTags) {
            if (hasLegacyCodes && !hasOriginalMiniMessageTags) {
                useMiniMessage = true;
                if (debugMode) {
                    Text.sendDebugLog(INFO, "Allowing legacy-converted MiniMessage tags for " + player.getName());
                }
            } else if (player.hasPermission("chat.minimessage")) {
                if (!validateMiniMessage(player, messageContent)) {
                    messageContent = miniMessage.stripTags(messageContent);
                    if (debugMode) {
                        Text.sendDebugLog(INFO, "Stripped unauthorized MiniMessage tags from message by " + player.getName());
                    }
                } else {
                    useMiniMessage = true;
                }
            } else {
                messageContent = miniMessage.stripTags(messageContent);
                if (debugMode) {
                    Text.sendDebugLog(INFO, "Stripped MiniMessage tags from message by " + player.getName() + " (no permission)");
                }
            }
        }

        // Process PlaceholderAPI placeholders if player has permission
        if (vaultPermission != null && vaultPermission.playerHas(player, "chat.placeholderapi")) {
            messageContent = processPlaceholderAPIPlaceholdersInChat(messageContent, player);
        }

        // Restore protected raw text as the final step
        messageContent = restoreRawText(messageContent);

        onPlayerChat(event, player, rawMessage, messageContent);
    }

    private void onPlayerChat(AsyncChatEvent event, Player player, String message, String messageContent) {
        // Get chat format and prefix
        String chatFormat = getChatFormat(player);
        
        // Process placeholders in the chat format string
        chatFormat = processPlaceholderAPIPlaceholdersInConfig(chatFormat, player);
        
        String prefix = getPrefix(player);
        String suffix = getSuffix(player);
        
        // Process the message content with MiniMessage if needed
        final Component messageComponent = processMessageContent(messageContent);

        // Create the basic chat components
        final boolean hasPrefix = (prefix != null && !prefix.trim().isEmpty());
        final Component prefixComponent = hasPrefix ? (containsMiniMessageTags(prefix) ? miniMessage.deserialize(prefix) : legacyComponentSerializer.deserialize(prefix)) : Component.empty();
        final Component basePrefixComponent = hasPrefix ? applyHoverClick(player, prefixComponent, "prefix") : Component.empty();
        final boolean hasSuffix = (suffix != null && !suffix.trim().isEmpty());
        final Component suffixComponent = hasSuffix ? (containsMiniMessageTags(suffix) ? miniMessage.deserialize(suffix) : legacyComponentSerializer.deserialize(suffix)) : Component.empty();
        final Component baseSuffixComponent = hasSuffix ? applyHoverClick(player, suffixComponent, "suffix") : Component.empty();
        String playerDisplayName = getPlayerDisplayName(player);
        final Component basePlayerComponent = applyHoverClick(player,
            legacyComponentSerializer.deserialize(playerDisplayName), "name");
         
        // If no prefix, remove the <prefix> token and surrounding whitespace to avoid stray spaces
        String formatBase = chatFormat;
        if (!hasPrefix) {
            formatBase = formatBase.replaceAll("(?i)\\s*<prefix>\\s*", "");
        }
        // If no suffix, remove the <suffix> token and surrounding whitespace as well
        if (!hasSuffix) {
            formatBase = formatBase.replaceAll("(?i)\\s*<suffix>\\s*", "");
        }

        // Replace placeholders in the format string with temporary markers
        String processedFormat = formatBase
                .replace("<prefix>", "{PREFIX_COMPONENT}")
                .replace("<player>", "{PLAYER_COMPONENT}")
                .replace("<message>", "{MESSAGE_COMPONENT}")
                .replace("<suffix>", "{SUFFIX_COMPONENT}");
         
        // Build the final message using the processed format
        Component baseMessage = legacyComponentSerializer.deserialize(processedFormat);
         
        // Store the message for potential deletion by staff (using temporary message)
        Component tempMessage = baseMessage.replaceText(builder -> builder
            .matchLiteral("{PREFIX_COMPONENT}")
            .replacement(basePrefixComponent))
            .replaceText(builder -> builder
                .matchLiteral("{PLAYER_COMPONENT}")
                .replacement(basePlayerComponent))
            .replaceText(builder -> builder
                .matchLiteral("{MESSAGE_COMPONENT}")
                .replacement(messageComponent))
            .replaceText(builder -> builder
                .matchLiteral("{SUFFIX_COMPONENT}")
                .replacement(baseSuffixComponent));
         
        long messageId = chatMessageManager.storeMessage(player, tempMessage);
         
        // Track this message for per-player chat history (for packet-based deletion)
        ChatMessageManager.ChatMessage chatMessage = new ChatMessageManager.ChatMessage(
            messageId, player.getUniqueId(), player.getName(), tempMessage
        );
         
        // Track this message for all online players (since chat is broadcast to everyone)
        for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
            chatMessageManager.trackMessageForPlayer(onlinePlayer.getUniqueId(), chatMessage);
        }
         
        // Create the final message with base player component (no extra hover/suggest delete text)
        Component finalMessage = baseMessage.replaceText(builder -> builder
            .matchLiteral("{PREFIX_COMPONENT}")
            .replacement(basePrefixComponent))
            .replaceText(builder -> builder
                .matchLiteral("{PLAYER_COMPONENT}")
                .replacement(basePlayerComponent))
            .replaceText(builder -> builder
                .matchLiteral("{MESSAGE_COMPONENT}")
                .replacement(messageComponent))
            .replaceText(builder -> builder
                .matchLiteral("{SUFFIX_COMPONENT}")
                .replacement(baseSuffixComponent));
 
        // Use a per-viewer renderer to append staff-only hover/click without altering the visible message
        final Component baseBroadcast = finalMessage; // capture for lambda
        final Component basePrefixForAugment = basePrefixComponent;
        final Component baseNameForAugment = basePlayerComponent;
        final Component baseSuffixForAugment = baseSuffixComponent;
        final Component baseMsgForAugment = messageComponent;
        final Component baseMessageTemplate = baseMessage; // for reconstructing per-viewer messages
        event.renderer((source, sourceDisplayName, msg, viewer) -> {
            if (viewer instanceof Player) {
                Player v = (Player) viewer;
                if (v.hasPermission("allium.deletemsg") || v.hasPermission("allium.delmsg") || v.hasPermission("allium.staff")) {
                    String mode = config.getString("chat-hover.delete-hover-mode", "prefix").toLowerCase();
                    String effectiveMode = mode;
                    if (!hasPrefix && !"name".equals(effectiveMode) && !"player".equals(effectiveMode) && !"none".equals(effectiveMode)) {
                        effectiveMode = "name";
                    }
                    if ("none".equals(effectiveMode)) {
                        return baseBroadcast;
                    }

 
                    // Helper to extend hover text and set suggest-command on a single component
                    java.util.function.Function<Component, Component> augment = (comp) -> {
                        HoverEvent<?> hv = comp.hoverEvent();
                        Component hvText = null;
                        if (hv != null && hv.action() == HoverEvent.Action.SHOW_TEXT) {
                            Object value = hv.value();
                            if (value instanceof Component) hvText = (Component) value;
                        }
                        if (hvText == null) hvText = Component.empty();

                        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(hvText);
                        if (!plain.contains("Click to delete this message")) {
                            Component staffLine = Component.text("Click to delete this message ", NamedTextColor.RED)
                                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                                .append(Component.text(String.valueOf(messageId), NamedTextColor.WHITE))
                                .append(Component.text("]", NamedTextColor.DARK_GRAY));
                            hvText = hvText.append(Component.newline()).append(staffLine);
                        }
                        return comp.hoverEvent(HoverEvent.showText(hvText))
                                .clickEvent(ClickEvent.suggestCommand("/delmsg " + messageId));
                    };

                    // Build per-viewer message: augment only the selected segment
                    final Component viewerPrefixFinal = "prefix".equals(effectiveMode)
                            ? augment.apply(basePrefixForAugment)
                            : basePrefixForAugment;
                    final Component viewerNameFinal = ("name".equals(effectiveMode) || "player".equals(effectiveMode))
                            ? augment.apply(baseNameForAugment)
                            : baseNameForAugment;
                    final Component viewerSuffixFinal = "suffix".equals(effectiveMode)
                            ? augment.apply(baseSuffixForAugment)
                            : baseSuffixForAugment;

                    Component viewerMessage = baseMessageTemplate
                        .replaceText(builder -> builder.matchLiteral("{PREFIX_COMPONENT}").replacement(viewerPrefixFinal))
                        .replaceText(builder -> builder.matchLiteral("{PLAYER_COMPONENT}").replacement(viewerNameFinal))
                        .replaceText(builder -> builder.matchLiteral("{MESSAGE_COMPONENT}").replacement(baseMsgForAugment))
                        .replaceText(builder -> builder.matchLiteral("{SUFFIX_COMPONENT}").replacement(viewerSuffixFinal));
                    return viewerMessage;
                }
            }
            return baseBroadcast;
        });
    }

    private String getChatFormat(Player player) {
        String groupName = "default";   
        boolean debugMode = config.getBoolean("debug-mode");

        if (vaultChat != null) {
            groupName = vaultChat.getPrimaryGroup(player);
            if (groupName == null) {
                groupName = "default";
            }
        } else {
            if (debugMode) {
                Text.sendDebugLog(INFO, "Vault Chat unavailable, using default group for " + player.getName());
            }
        }

        if (groupFormats.containsKey(groupName)) {
            return groupFormats.get(groupName);
        } else {
            return defaultFormat;
        }
    }

    /**
     * Processes PlaceholderAPI placeholders in user-generated chat messages.
     * This REQUIRES the chat.placeholderapi permission.
     * When PlaceholderAPI is missing, we still replace our own %allium_nickname% placeholders.
     */
    private String processPlaceholderAPIPlaceholdersInChat(String text, Player player) {
        if (vaultPermission == null) {
            return text;
        }
        // Always replace our own placeholders (works with or without PlaceholderAPI)
        if (text.contains("%allium_")) {
            text = replaceAlliumPlaceholders(text, player);
        }
        if (!placeholderAPIEnabled) {
            return text;
        }
        
        // Check if player has the placeholderapi permission
        boolean hasPermission = false;
        try {
            // Try modern Vault method first (1.7.3+)
            try {
                java.lang.reflect.Method hasMethod = vaultPermission.getClass().getMethod("playerHas", String.class, org.bukkit.OfflinePlayer.class, String.class);
                hasPermission = (boolean) hasMethod.invoke(vaultPermission, player.getWorld().getName(), player, "chat.placeholderapi");
            } catch (NoSuchMethodException e) {
                // Fall back to older methods
                hasPermission = vaultPermission.has(player, "chat.placeholderapi") || 
                               vaultPermission.playerHas((String)null, player, "chat.placeholderapi");
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error checking permission: " + e.getMessage());
            return text;
        }
        
        if (!hasPermission) {
            return text;
        }
        
        // Handle TPS placeholders specially for Folia
        boolean isFolia = false;
        try {
            isFolia = Bukkit.getServer().getClass().getName().contains("folia");
            if (isFolia) {
                text = text.replaceAll("%server_tps_\\d+%", "N/A")
                          .replaceAll("%server_tps%", "N/A");
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error checking for Folia: " + e.getMessage());
        }
        
        // Process %{placeholder}% format first
        Matcher matcher = placeholderPattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        boolean hasPlaceholders = false;
    
        while (matcher.find()) {
            hasPlaceholders = true;
            String placeholder = matcher.group(1);
            // Convert %{placeholder}% to %placeholder% for PAPI
            String papiPlaceholder = "%" + placeholder + "%";
            String replacement;
            try {
                replacement = PlaceholderAPI.setPlaceholders(player, papiPlaceholder);
                // If placeholder wasn't processed, keep the original %{placeholder}%
                if (replacement.equals(papiPlaceholder)) {
                    replacement = matcher.group(0);
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Error processing placeholder " + papiPlaceholder + ": " + e.getMessage());
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        
        if (hasPlaceholders) {
            matcher.appendTail(buffer);
            text = buffer.toString();
        }
        
        // Process regular %placeholder% format if any placeholders remain
        if (text.contains("%")) {
            try {
                text = PlaceholderAPI.setPlaceholders(player, text);
            } catch (UnsupportedOperationException e) {
                if (e.getMessage() != null && e.getMessage().contains("Not on any region")) {
                    Text.sendDebugLog(WARN, "PlaceholderAPI placeholder not supported in this context: " + text);
                    text = text.replaceAll("%[^%]+%", "[Placeholder Error]");
                } else {
                    throw e;
                }
            }
        }
        
        return text;
    }

    private String getPrefix(Player player) {
        String prefix = "";
        boolean debugMode = config.getBoolean("debug-mode");

        try {
            if (vaultChat != null) {
                prefix = vaultChat.getPlayerPrefix(player);
                if (prefix != null && prefix.trim().isEmpty()) {
                    if (debugMode) {
                        Text.sendDebugLog(INFO, "Empty prefix detected for " + player.getName() + ", returning null");
                    }
                    return null;
                }
                prefix = Objects.requireNonNullElse(prefix, "");
                
                // Clean up unwanted group names or formatting that might appear before the actual prefix
                if (prefix.contains("<") && prefix.contains(">")) {
                    // Remove patterns like <groupname> that appear before the actual prefix
                    prefix = prefix.replaceAll("<[^>]*>", "").trim();
                    if (debugMode) {
                        Text.sendDebugLog(INFO, "Cleaned prefix for " + player.getName() + ": removed group tags, result: '" + prefix + "'");
                    }
                }
                
            } else {
                if (debugMode) {
                    Text.sendDebugLog(INFO, "Vault Chat unavailable, using empty prefix for " + player.getName());
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error getting prefix: " + e.getMessage());
        }

        return prefix;
    }

    private String getSuffix(Player player) {
        String suffix = "";
        boolean debugMode = config.getBoolean("debug-mode");

        try {
            if (vaultChat != null) {
                suffix = vaultChat.getPlayerSuffix(player);
                if (suffix != null && suffix.trim().isEmpty()) {
                    if (debugMode) {
                        Text.sendDebugLog(INFO, "Empty suffix detected for " + player.getName() + ", returning null");
                    }
                    return null;
                }
                suffix = Objects.requireNonNullElse(suffix, "");
                
                // Clean up unwanted group names or formatting that might appear before the actual suffix
                if (suffix.contains("<") && suffix.contains(">")) {
                    suffix = suffix.replaceAll("<[^>]*>", "").trim();
                    if (debugMode) {
                        Text.sendDebugLog(INFO, "Cleaned suffix for " + player.getName() + ": removed group tags, result: '" + suffix + "'");
                    }
                }
                
            } else {
                if (debugMode) {
                    Text.sendDebugLog(INFO, "Vault Chat unavailable, using empty suffix for " + player.getName());
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error getting suffix: " + e.getMessage());
        }

        return suffix;
    }

    private String stripUnauthorizedFormatting(String message, Player player) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        boolean allowAnyColor = player.hasPermission("chat.color") || player.hasPermission("chat.color.*");
        boolean allowAnyFormat = player.hasPermission("chat.format") || player.hasPermission("chat.format.*");
        boolean allowHex = allowAnyColor || player.hasPermission("chat.color.hex");

        StringBuilder filteredMessage = new StringBuilder();
        int i = 0;

        while (i < message.length()) {
            if (i + 1 < message.length() && (message.charAt(i) == '&' || message.charAt(i) == '\u00a7')) {
                char colorChar = Character.toLowerCase(message.charAt(i + 1));
                boolean keepCode = false;

                switch (colorChar) {
                    case '0': keepCode = allowAnyColor || player.hasPermission("chat.color.black"); break;
                    case '1': keepCode = allowAnyColor || player.hasPermission("chat.color.dark_blue"); break;
                    case '2': keepCode = allowAnyColor || player.hasPermission("chat.color.dark_green"); break;
                    case '3': keepCode = allowAnyColor || player.hasPermission("chat.color.dark_aqua"); break;
                    case '4': keepCode = allowAnyColor || player.hasPermission("chat.color.dark_red"); break;
                    case '5': keepCode = allowAnyColor || player.hasPermission("chat.color.dark_purple"); break;
                    case '6': keepCode = allowAnyColor || player.hasPermission("chat.color.gold"); break;
                    case '7': keepCode = allowAnyColor || player.hasPermission("chat.color.gray"); break;
                    case '8': keepCode = allowAnyColor || player.hasPermission("chat.color.dark_gray"); break;
                    case '9': keepCode = allowAnyColor || player.hasPermission("chat.color.blue"); break;
                    case 'a': keepCode = allowAnyColor || player.hasPermission("chat.color.green"); break;
                    case 'b': keepCode = allowAnyColor || player.hasPermission("chat.color.aqua"); break;
                    case 'c': keepCode = allowAnyColor || player.hasPermission("chat.color.red"); break;
                    case 'd': keepCode = allowAnyColor || player.hasPermission("chat.color.light_purple"); break;
                    case 'e': keepCode = allowAnyColor || player.hasPermission("chat.color.yellow"); break;
                    case 'f': keepCode = allowAnyColor || player.hasPermission("chat.color.white"); break;
                    case 'l': keepCode = allowAnyFormat || player.hasPermission("chat.format.bold"); break;
                    case 'o': keepCode = allowAnyFormat || player.hasPermission("chat.format.italic"); break;
                    case 'n': keepCode = allowAnyFormat || player.hasPermission("chat.format.underline"); break;
                    case 'm': keepCode = allowAnyFormat || player.hasPermission("chat.format.strikethrough"); break;
                    case 'k': keepCode = allowAnyFormat || player.hasPermission("chat.format.magic"); break;
                    case 'r': keepCode = allowAnyFormat || player.hasPermission("chat.format.reset"); break;
                    default: keepCode = false; break;
                }

                if (keepCode) {
                    filteredMessage.append(message.charAt(i)).append(message.charAt(i + 1));
                    i += 2;
                } else {
                    i += 2;
                }
            } else if (i + 7 < message.length() && message.charAt(i) == '&' && message.charAt(i + 1) == '#' &&
                    message.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                if (allowHex) {
                    filteredMessage.append(message, i, i + 8);
                    i += 8;
                } else {
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
     * Restore protected raw text markers with their literal content
     * @param message The message containing protection markers
     * @return The message with markers replaced by literal raw text
     */
    private String restoreRawText(String message) {
        if (message == null || rawTextMappings.isEmpty()) {
            return message;
        }
        
        String result = message;
        for (Map.Entry<String, String> entry : rawTextMappings.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        
        // Clear mappings after use to prevent memory leaks
        rawTextMappings.clear();
        
        return result;
    }

    public void reload() {
        groupFormats.clear();
        loadGroupFormats();
    }
}
