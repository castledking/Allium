package net.survivalfun.core.listeners.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.query.QueryOptions;
import net.milkbowl.vault2.chat.Chat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

public class FormatChatListener implements Listener {

    private final JavaPlugin plugin;
    private final LegacyComponentSerializer legacyComponentSerializer;
    private final LuckPerms luckPerms;
    private final Chat vaultChat;
    private final Map<String, String> groupFormats;
    private final boolean useVaultGroups;

    public FormatChatListener(JavaPlugin plugin, Chat vaultChat, LuckPerms luckPerms) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        this.vaultChat = vaultChat;
        this.luckPerms = luckPerms;

        legacyComponentSerializer = LegacyComponentSerializer.builder().hexColors().build();

        // Determine Group Mode and Load Formats
        if (vaultChat != null && luckPerms == null) {
            useVaultGroups = true;
            plugin.getLogger().info("Using Vault for group formatting.");
        } else if (luckPerms != null) {
            useVaultGroups = false;
            plugin.getLogger().info("Using LuckPerms for group formatting.");
        } else {
            useVaultGroups = false;
            plugin.getLogger().warning("Neither LuckPerms nor Vault found, disabling group formatting.");
        }

        Map<String, Object> tempMap = config.getConfigurationSection("chat-format.groups").getValues(false);
        groupFormats = new HashMap<>();
        for (Map.Entry<String, Object> entry : tempMap.entrySet()) {
            groupFormats.put(entry.getKey(), entry.getValue().toString());
        }
    }

    public boolean canEnable() {
        return luckPerms != null || vaultChat != null;
    }

    @EventHandler
    public void onPlayerChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        Component messageComponent = event.message();
        String message = legacyComponentSerializer.serialize(messageComponent); // Serialize the message
        String chatFormat = getChatFormat(player); // Get the appropriate chat format
        String formattedMessage = replacePlaceholders(chatFormat, player, message); // Replace placeholders
        Component formattedComponent = legacyComponentSerializer.deserialize(formattedMessage); // Deserialize

        event.message(formattedComponent);
    }

    private String getChatFormat(Player player) {
        String groupName = "default"; // Default group
        if (useVaultGroups && vaultChat != null) {
            groupName = vaultChat.getPrimaryGroup(player);
        } else if (luckPerms != null) {
            User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
            CachedMetaData metaData = user.getCachedData().getMetaData(QueryOptions.nonContextual());
            groupName = metaData.getPrimaryGroup();
            if (groupName == null) {
                groupName = "default";
            }
        }

        // Get format for the group, or the default format if not found
        if (groupFormats.containsKey(groupName)) {
            return groupFormats.get(groupName);
        } else {
            return plugin.getConfig().getString("chat-format.default", "<prefix> &a<player>&f: &f<message>");
        }
    }

    private String replacePlaceholders(String format, Player player, String message) {
        String prefix = getPrefix(player);
        String displayName = player.getDisplayName();
        String playerName = player.getName();

        return format.replace("<prefix>", prefix)
                .replace("<player>", playerName)
                .replace("<display_name>", displayName)
                .replace("<name>", playerName) // In case they use <name>
                .replace("<message>", message);
        // Add PlaceholderAPI support here if needed
    }

    private String getPrefix(Player player) {
        String prefix = ""; // Initialize prefix

        try {
            if (luckPerms != null) {
                User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
                // LuckPerms API guarantees that user will not be null here.
                CachedMetaData metaData = user.getCachedData().getMetaData(QueryOptions.nonContextual());
                // metaData should not be null
                prefix = Objects.requireNonNullElse(metaData.getPrefix(), "");
                plugin.getLogger().info("LuckPerms Prefix: " + prefix);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error getting prefix: " + e.getMessage());
        }

        plugin.getLogger().info("Final Prefix: " + prefix); // Log the final prefix value
        return prefix;
    }
}