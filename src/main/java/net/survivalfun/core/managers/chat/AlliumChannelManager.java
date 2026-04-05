package net.survivalfun.core.managers.chat;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.util.WebhookUtil;
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
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.NicknameManager;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.INFO;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.WARN;

/**
 * Allium-owned chat channels with optional DiscordSRV relay.
 */
public final class AlliumChannelManager implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final UUID DISCORD_SENDER_ID = UUID.nameUUIDFromBytes("allium-discord-channel".getBytes(StandardCharsets.UTF_8));
    private static final long SUPPRESSED_EVENT_WINDOW_MS = 15_000L;
    private static final long HANDLED_DISCORD_MESSAGE_WINDOW_MS = 30_000L;
    private static final long OUTBOUND_CHAT_WINDOW_MS = 10_000L;

    private final PluginStart plugin;
    private final Map<String, ChannelDefinition> channels = new LinkedHashMap<>();
    private final Map<UUID, String> writeChannels = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> readChannels = new ConcurrentHashMap<>();
    private final Map<String, String> aliasToChannel = new ConcurrentHashMap<>();
    private final Map<Integer, Long> suppressedDiscordEventIds = new ConcurrentHashMap<>();
    private final Map<String, Long> handledDiscordMessageIds = new ConcurrentHashMap<>();
    private final Map<UUID, RecentOutboundChat> recentOutboundChats = new ConcurrentHashMap<>();
    private final Set<UUID> staffChatActive = ConcurrentHashMap.newKeySet();

    private volatile String defaultChannelName = "default";
    private volatile String staffChannelName = "staff-chat";
    private volatile boolean enabled;
    private volatile boolean discordAvailable;
    private volatile boolean discordHooked;

    public AlliumChannelManager(PluginStart plugin) {
        this.plugin = plugin;
        reload();
        hookDiscordSrvIfAvailable();
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        if (discordHooked) {
            try {
                DiscordSRV.api.unsubscribe(this);
            } catch (Throwable ignored) {
            }
        }
        discordAvailable = false;
        discordHooked = false;
        writeChannels.clear();
        readChannels.clear();
        suppressedDiscordEventIds.clear();
        handledDiscordMessageIds.clear();
        recentOutboundChats.clear();
    }

    public void reload() {
        channels.clear();
        aliasToChannel.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("chat.channels");
        enabled = root == null || root.getBoolean("enabled", true);
        defaultChannelName = normalize(root != null ? root.getString("default-write-channel", "default") : "default");
        staffChannelName = normalize(root != null ? root.getString("staff-channel", "staff-chat") : "staff-chat");

        ConfigurationSection channelSection = root != null ? root.getConfigurationSection("list") : null;
        if (channelSection == null) {
            createFallbackChannels();
            return;
        }

        for (String rawName : channelSection.getKeys(false)) {
            ConfigurationSection section = channelSection.getConfigurationSection(rawName);
            if (section == null) {
                continue;
            }

            String name = normalize(rawName);
            List<String> aliases = new ArrayList<>();
            for (String alias : section.getStringList("aliases")) {
                String normalized = normalize(alias);
                if (!normalized.isEmpty()) {
                    aliases.add(normalized);
                }
            }

            ChannelDefinition def = new ChannelDefinition(
                    name,
                    section.getString("display-name", rawName),
                    aliases,
                    section.getString("format", "<prefix> &a%allium_nickname%&f: &f<message>"),
                    section.getString("discord-channel-id", ""),
                    section.getString("discord-in-format", "<dark_gray>[<blue>Discord</blue><dark_gray>] <white>{discord_username}<gray>: <white>{message}"),
                    section.getString("discord-out-format", "{player}: {message}"),
                    section.getBoolean("webhook-delivery", false)
            );
            channels.put(name, def);
            aliasToChannel.put(name, name);
            for (String alias : aliases) {
                aliasToChannel.putIfAbsent(alias, name);
            }
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Loaded channel: name=" + name + " display=" + def.displayName() + " aliases=" + aliases);
            }
        }

        if (channels.isEmpty()) {
            createFallbackChannels();
        }
        
        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[Channels] Loaded channels: " + channels.keySet() + " defaultChannelName=" + defaultChannelName + " staffChannelName=" + staffChannelName);
        }
    }

    private void createFallbackChannels() {
        addFallback(new ChannelDefinition(
                "default",
                "Global",
                List.of("local", "l", "g", "global"),
                "<prefix> &a%allium_nickname%&f: &f<message>",
                "",
                "<dark_gray>[<blue>Discord</blue><dark_gray>] <white>{discord_username}<gray>: <white>{message}",
                "{player}: {message}",
                false
        ));
        addFallback(new ChannelDefinition(
                "staff-chat",
                "Staff Chat",
                List.of("a", "staffchat"),
                "&8[&bStaff&8] <prefix> &a%allium_nickname%&7: &f<message>",
                "",
                "<dark_gray>[<dark_red><bold>Staff</bold></dark_red><dark_gray>] <white>{discord_username}<gray>: <white>{message}",
                "[Staff] {player}: {message}",
                false
        ));
    }

    private void addFallback(ChannelDefinition definition) {
        channels.put(definition.name(), definition);
        aliasToChannel.put(definition.name(), definition.name());
        for (String alias : definition.aliases()) {
            aliasToChannel.putIfAbsent(alias, definition.name());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Collection<ChannelDefinition> getChannels() {
        return Collections.unmodifiableCollection(channels.values());
    }

    public String getDefaultChannelName() {
        return defaultChannelName;
    }

    public String getStaffChannelName() {
        return staffChannelName;
    }

    public String resolveChannel(String input) {
        if (input == null) {
            return null;
        }
        return aliasToChannel.get(normalize(input));
    }

    public ChannelDefinition getChannel(String name) {
        if (name == null) {
            return null;
        }
        String normalized = normalize(name);
        ChannelDefinition channel = channels.get(normalized);
        if (channel != null) {
            return channel;
        }
        // Fallback for default and staff channels if not in map
        if (normalized.equals(normalize(defaultChannelName))) {
            return new ChannelDefinition(
                    defaultChannelName,
                    "Global",
                    List.of("local", "l", "g", "global"),
                    "<prefix> &a%allium_nickname%&f: &f<message>",
                    "",
                    "<dark_gray>[<blue>Discord</blue><dark_gray>] <white>{discord_username}<gray>: <white>{message}",
                    "{player}: {message}",
                    false
            );
        }
        if (normalized.equals(normalize(staffChannelName))) {
            return new ChannelDefinition(
                    staffChannelName,
                    "Staff Chat",
                    List.of("a", "staffchat"),
                    "&8[&bStaff&8] <prefix> &a%allium_nickname%&7: &f<message>",
                    "",
                    "<dark_gray>[<dark_red><bold>Staff</bold></dark_red><dark_gray>] <white>{discord_username}<gray>: <white>{message}",
                    "[Staff] {player}: {message}",
                    false
            );
        }
        return null;
    }

    public String getWriteChannel(UUID playerId) {
        return writeChannels.get(playerId);
    }

    public Set<String> getReadChannels(UUID playerId) {
        Set<String> channels = readChannels.get(playerId);
        if (channels == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(channels);
    }

    public void applyAutojoin(Player player) {
        UUID playerId = player.getUniqueId();
        writeChannels.remove(playerId);
        readChannels.remove(playerId);

        String chosenWrite = null;
        for (ChannelDefinition channel : channels.values()) {
            String channelName = channel.name();
            if (player.hasPermission("allium.channel.autojoin." + channelName + ".read")) {
                addReadChannel(playerId, channelName);
            }
            if (player.hasPermission("allium.channel.autojoin." + channelName + ".write")) {
                if (chosenWrite == null) {
                    chosenWrite = channelName;
                }
                addReadChannel(playerId, channelName);
            }
        }

        if (chosenWrite == null && channels.containsKey(defaultChannelName)) {
            chosenWrite = defaultChannelName;
            addReadChannel(playerId, defaultChannelName);
        }

        if (chosenWrite != null) {
            writeChannels.put(playerId, chosenWrite);
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Autojoined " + player.getName() + " write=" + chosenWrite + " reads=" + getReadChannels(playerId));
            }
        }
    }

    public boolean addReadChannel(Player player, String requestedChannel) {
        String channel = resolveChannel(requestedChannel);
        if (channel == null || !canRead(player, channel)) {
            return false;
        }
        addReadChannel(player.getUniqueId(), channel);
        return true;
    }

    private void addReadChannel(UUID playerId, String channel) {
        readChannels.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    public boolean setWriteChannel(Player player, String requestedChannel) {
        String channel = resolveChannel(requestedChannel);
        if (channel == null || !canWrite(player, channel)) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        addReadChannel(playerId, channel);
        writeChannels.put(playerId, channel);
        return true;
    }

    public boolean canRead(Player player, String channelName) {
        if (player == null || channelName == null || !channels.containsKey(channelName)) {
            return false;
        }
        return player.hasPermission("allium.channel.join." + channelName + ".read")
                || player.hasPermission("allium.channel.join." + channelName + ".write")
                || (channelName.equals(staffChannelName) && player.hasPermission("allium.staffchat"));
    }

    public boolean canWrite(Player player, String channelName) {
        if (player == null || channelName == null || !channels.containsKey(channelName)) {
            return false;
        }
        return player.hasPermission("allium.channel.join." + channelName + ".write")
                || (channelName.equals(staffChannelName) && player.hasPermission("allium.staffchat"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }
        applyAutojoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        writeChannels.remove(playerId);
        readChannels.remove(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChatEarly(AsyncChatEvent event) {
        if (!enabled || event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        String currentWrite = writeChannels.computeIfAbsent(player.getUniqueId(), ignored -> defaultChannelName);
        if (!channels.containsKey(currentWrite)) {
            currentWrite = defaultChannelName;
        }

        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (rawMessage.isEmpty()) {
            return;
        }

        String targetChannel = currentWrite;
        String message = rawMessage;

        // Handle one-shot prefixes
        if (currentWrite.equals(staffChannelName) && rawMessage.startsWith(">")) {
            String oneShot = rawMessage.substring(1).trim();
            if (!oneShot.isEmpty()) {
                targetChannel = defaultChannelName;
                message = oneShot;
            }
        } else if (!currentWrite.equals(staffChannelName) && rawMessage.startsWith("#")) {
            String oneShot = rawMessage.substring(1).trim();
            if (!oneShot.isEmpty()) {
                targetChannel = staffChannelName;
                message = oneShot;
            }
        }

        message = normalizeOutgoingMessage(player, message);

        // EARLY TRACKING: Populate these BEFORE DiscordSRV sees the event
        recentOutboundChats.put(player.getUniqueId(), new RecentOutboundChat(
                normalizeMessageForComparison(message),
                System.currentTimeMillis(),
                targetChannel.equals(staffChannelName),
                false // isShortcut - determined later in onPlayerChat
        ));

        if (targetChannel.equals(staffChannelName)) {
            staffChatActive.add(player.getUniqueId());
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Early tracked staff-chat for " + player.getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onPlayerChat(AsyncChatEvent event) {
        if (!enabled || event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        String currentWrite = writeChannels.computeIfAbsent(player.getUniqueId(), ignored -> defaultChannelName);
        if (!channels.containsKey(currentWrite)) {
            currentWrite = defaultChannelName;
            writeChannels.put(player.getUniqueId(), currentWrite);
        }
        addReadChannel(player.getUniqueId(), currentWrite);

        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (rawMessage.isEmpty()) {
            return;
        }

        String targetChannel = currentWrite;
        String message = rawMessage;

        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[Channels] Checking shortcut: currentWrite=" + currentWrite + " staffChannelName=" + staffChannelName + " rawMessage=" + rawMessage);
        }

        // Track if this was a shortcut message to force manual Discord relay
        boolean isShortcutMessage = false;

        if (currentWrite.equals(staffChannelName)) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] currentWrite equals staffChannelName: true");
            }
            if (rawMessage.startsWith(">")) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] rawMessage startsWith >: true");
                }
                String oneShot = rawMessage.substring(1).trim();
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] oneShot='" + oneShot + "' isEmpty=" + oneShot.isEmpty());
                }
                if (!oneShot.isEmpty()) {
                    targetChannel = defaultChannelName;
                    message = oneShot;
                    isShortcutMessage = true;
                    // Remove from staffChatActive since we're sending to global, not staff-chat
                    staffChatActive.remove(player.getUniqueId());
                    // Add target channel to read channels so player sees the message
                    addReadChannel(player.getUniqueId(), targetChannel);
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "[Channels] > shortcut detected: sending '" + message + "' to global, removed from staffChatActive");
                    }
                }
            } else {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] rawMessage startsWith >: false");
                }
            }
        } else {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] currentWrite equals staffChannelName: false");
            }
        }

        if (!currentWrite.equals(staffChannelName) && rawMessage.startsWith("#")) {
            String oneShot = rawMessage.substring(1).trim();
            if (!oneShot.isEmpty()) {
                targetChannel = staffChannelName;
                message = oneShot;
                isShortcutMessage = true;
                // Add target channel to read channels so player sees the message
                addReadChannel(player.getUniqueId(), targetChannel);
            }
        }

        message = normalizeOutgoingMessage(player, message);

        // Track recent outbound chats for suppression logic (including staff-chat)
        // Note: This is already populated in onPlayerChatEarly, but update timestamp
        recentOutboundChats.put(player.getUniqueId(), new RecentOutboundChat(
                normalizeMessageForComparison(message),
                System.currentTimeMillis(),
                targetChannel.equals(staffChannelName),
                isShortcutMessage
        ));

        // staffChatActive is already set in onPlayerChatEarly if this is staff-chat

        event.setCancelled(true);
        sendPlayerMessage(player, targetChannel, message);
    }

    public void sendPlayerMessage(Player sender, String channelName, String message) {
        ChannelDefinition channel = getChannel(channelName);
        if (channel == null) {
            return;
        }

        RenderedPlayerMessage rendered = renderPlayerMessage(sender, channel, message);
        Component formatted = rendered.formatted();
        long messageId = plugin.getChatMessageManager().storeMessage(sender, formatted);
        if (plugin.getDiscordSrvMessageBridge() != null) {
            plugin.getDiscordSrvMessageBridge().noteOutgoingPlayerChat(messageId, sender, Component.text(message), formatted);
        }

        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (!isReading(recipient.getUniqueId(), channel.name())) {
                continue;
            }
            recipient.sendMessage(applyStaffHoverForViewer(recipient, sender, messageId, rendered));
        }
        Bukkit.getConsoleSender().sendMessage(LegacyComponentSerializer.legacySection().serialize(formatted));

        // Track if we're in staff-chat mode so we can suppress DiscordSRV global relay
        boolean isStaffChatMessage = channel.name().equals(staffChannelName);
        
        // Note: staffChatActive is already populated in onPlayerChat before this method is called
        // We only need to clean it up after relay
        relayToDiscord(sender, channel, message);
        
        // Clean up staff-chat tracking after relay (with delay to ensure DiscordSRV's async event sees it)
        if (isStaffChatMessage) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                staffChatActive.remove(sender.getUniqueId());
            }, 40L); // 2 second delay (40 ticks) to ensure DiscordSRV events have fired
        }
    }

    private boolean isReading(UUID playerId, String channel) {
        Set<String> reads = readChannels.get(playerId);
        return reads != null && reads.contains(channel);
    }

    private RenderedPlayerMessage renderPlayerMessage(Player sender, ChannelDefinition channel, String plainMessage) {
        String template = channel.format();
        String prefix = getPrefix(sender);
        String suffix = getSuffix(sender);
        String nickname = getNickname(sender);

        if (prefix == null || prefix.trim().isEmpty()) {
            template = template.replaceAll("(?i)\\s*<prefix>\\s*", "");
            prefix = "";
        }
        if (suffix == null || suffix.trim().isEmpty()) {
            template = template.replaceAll("(?i)\\s*<suffix>\\s*", "");
            suffix = "";
        }

        String processedTemplate = template
                .replace("<prefix>", "{PREFIX_COMPONENT}")
                .replace("<suffix>", "{SUFFIX_COMPONENT}")
                .replace("<player>", "{PLAYER_COMPONENT}")
                .replace("<display_name>", "{PLAYER_COMPONENT}")
                .replace("<name>", "{PLAYER_COMPONENT}")
                .replace("<message>", "{MESSAGE_COMPONENT}");

        processedTemplate = applyPlaceholders(sender, processedTemplate);
        Component templateComponent = Text.colorize(processedTemplate);

        Component prefixComponent = prefix.isBlank() ? Component.empty() : applyConfiguredHoverClick(sender, Text.colorize(prefix), "prefix");
        Component nameComponent = nickname == null || nickname.isBlank()
                ? applyConfiguredHoverClick(sender, Component.text(sender.getName()), "name")
                : applyConfiguredHoverClick(sender, Text.colorize(nickname), "name");
        Component suffixComponent = suffix.isBlank() ? Component.empty() : applyConfiguredHoverClick(sender, Text.colorize(suffix), "suffix");
        Component messageComponent = Component.text(plainMessage);

        Component formatted = applyRenderedComponents(templateComponent, prefixComponent, nameComponent, suffixComponent, messageComponent);
        return new RenderedPlayerMessage(templateComponent, prefixComponent, nameComponent, suffixComponent, messageComponent, formatted);
    }

    private Component formatDiscordInbound(ChannelDefinition channel, String username, String message) {
        // Parse Discord emoji syntax (:emoji:) to Unicode
        String parsedMessage = parseDiscordEmojis(message);
        String formatted = channel.discordInFormat()
                .replace("{discord_username}", username)
                .replace("{player}", username)
                .replace("{message}", MINI_MESSAGE.escapeTags(parsedMessage));
        return Text.colorize(formatted);
    }

    private String normalizeOutgoingMessage(Player player, String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        if (plugin.getSpamBlockerManager() == null) {
            return message;
        }
        return plugin.getSpamBlockerManager().rewriteForDiscordRelay(message);
    }

    private Component applyStaffHoverForViewer(Player viewer, Player sender, long messageId, RenderedPlayerMessage rendered) {
        boolean canDelete = viewer.hasPermission("allium.deletemsg")
                || viewer.hasPermission("allium.delmsg")
                || viewer.hasPermission("allium.staff");
        if (!canDelete) {
            return rendered.formatted();
        }

        String mode = plugin.getConfig().getString("chat-hover.delete-hover-mode", "prefix").toLowerCase(Locale.ROOT);
        String effectiveMode = mode;
        if (("prefix".equals(effectiveMode) || "suffix".equals(effectiveMode))
                && plain("prefix".equals(effectiveMode) ? rendered.prefix() : rendered.suffix()).isBlank()) {
            effectiveMode = "name";
        }
        if ("none".equals(effectiveMode)) {
            return rendered.formatted();
        }

        Function<Component, Component> augment = (comp) -> {
            HoverEvent<?> hv = comp.hoverEvent();
            Component hvText = null;
            if (hv != null && hv.action() == HoverEvent.Action.SHOW_TEXT) {
                Object value = hv.value();
                if (value instanceof Component component) {
                    hvText = component;
                }
            }
            if (hvText == null) {
                hvText = Component.empty();
            }

            String existing = plain(hvText);
            if (!existing.contains("Click to delete this message")) {
                Component staffLine = Component.text("Click to delete this message ", NamedTextColor.RED)
                        .append(Component.text("[", NamedTextColor.DARK_GRAY))
                        .append(Component.text(String.valueOf(messageId), NamedTextColor.WHITE))
                        .append(Component.text("]", NamedTextColor.DARK_GRAY));
                hvText = hvText.append(Component.newline()).append(staffLine);
            }

            return comp.hoverEvent(HoverEvent.showText(hvText))
                    .clickEvent(ClickEvent.suggestCommand("/delmsg " + messageId));
        };

        Component viewerPrefix = "prefix".equals(effectiveMode) ? augment.apply(rendered.prefix()) : rendered.prefix();
        Component viewerName = ("name".equals(effectiveMode) || "player".equals(effectiveMode)) ? augment.apply(rendered.name()) : rendered.name();
        Component viewerSuffix = "suffix".equals(effectiveMode) ? augment.apply(rendered.suffix()) : rendered.suffix();
        return applyRenderedComponents(rendered.template(), viewerPrefix, viewerName, viewerSuffix, rendered.message());
    }

    @SuppressWarnings("unchecked")
    private Component applyConfiguredHoverClick(Player player, Component component, String element) {
        Map<String, Object> hoverClickConfig = getHoverClickConfig(player, element);
        if (hoverClickConfig.isEmpty()) {
            return component;
        }

        String group = getHighestPriorityGroup(player);
        Component updated = component;
        Object hoverValue = hoverClickConfig.get("hover");
        if (hoverValue != null) {
            List<String> hoverLines = hoverValue instanceof List<?>
                    ? (List<String>) hoverValue
                    : List.of(String.valueOf(hoverValue));
            if (!hoverLines.isEmpty()) {
                String hover = String.join("\n", hoverLines)
                        .replace("{player}", player.getName())
                        .replace("{prefix}", getPrefix(player))
                        .replace("{group}", group);
                hover = applyConfigPlaceholders(player, hover);
                updated = updated.hoverEvent(HoverEvent.showText(Text.colorize(hover)));
            }
        }

        Object actionValue = hoverClickConfig.get("action");
        Object clickValue = hoverClickConfig.get("click");
        if (actionValue != null && clickValue != null) {
            String click = String.valueOf(clickValue)
                    .replace("{player}", player.getName())
                    .replace("{prefix}", getPrefix(player))
                    .replace("{group}", group);
            click = applyConfigPlaceholders(player, click);
            String action = String.valueOf(actionValue).toLowerCase(Locale.ROOT);
            switch (action) {
                case "run-command" -> updated = updated.clickEvent(ClickEvent.runCommand(click));
                case "suggest-command" -> updated = updated.clickEvent(ClickEvent.suggestCommand(click));
                case "open-url" -> updated = updated.clickEvent(ClickEvent.openUrl(click));
                case "copy-to-clipboard" -> {
                    String content = String.valueOf(hoverClickConfig.getOrDefault("content", click))
                            .replace("{player}", player.getName())
                            .replace("{prefix}", getPrefix(player))
                            .replace("{group}", group);
                    updated = updated.clickEvent(ClickEvent.copyToClipboard(applyConfigPlaceholders(player, content)));
                }
                default -> {
                }
            }
        }

        return updated;
    }

    private Component applyRenderedComponents(Component template, Component prefix, Component name, Component suffix, Component message) {
        return template.replaceText(builder -> builder.matchLiteral("{PREFIX_COMPONENT}").replacement(prefix))
                .replaceText(builder -> builder.matchLiteral("{PLAYER_COMPONENT}").replacement(name))
                .replaceText(builder -> builder.matchLiteral("{SUFFIX_COMPONENT}").replacement(suffix))
                .replaceText(builder -> builder.matchLiteral("{MESSAGE_COMPONENT}").replacement(message));
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getHoverClickConfig(Player player, String element) {
        String group = getHighestPriorityGroup(player);
        String configPath = "chat-hover.groups." + group + "." + element;
        if (!plugin.getConfig().contains(configPath)) {
            configPath = "chat-hover.groups.default." + element;
            if (!plugin.getConfig().contains(configPath)) {
                return Collections.emptyMap();
            }
        }

        Object value = plugin.getConfig().get(configPath);
        if (value instanceof ConfigurationSection section) {
            return section.getValues(false);
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private String getHighestPriorityGroup(Player player) {
        if (plugin.getVaultPermission() == null) {
            return "default";
        }

        String[] groups;
        try {
            groups = plugin.getVaultPermission().getPlayerGroups(null, player);
        } catch (Exception e) {
            return "default";
        }
        if (groups == null || groups.length == 0) {
            return "default";
        }

        String highestGroup = "default";
        int highestWeight = Integer.MIN_VALUE;
        Chat vaultChat = plugin.getVaultChat();
        for (String group : groups) {
            int weight = 0;
            if (vaultChat != null) {
                try {
                    weight = Integer.parseInt(vaultChat.getGroupInfoString(player.getWorld().getName(), group, "weight", "0"));
                } catch (Exception ignored) {
                    weight = 0;
                }
            }
            if (weight > highestWeight) {
                highestWeight = weight;
                highestGroup = group;
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
        return highestGroup;
    }

    private String applyConfigPlaceholders(Player player, String text) {
        String result = text.replace("%allium_nickname%", getNickname(player));
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                result = PlaceholderAPI.setPlaceholders(player, result);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private record RenderedPlayerMessage(Component template,
                                         Component prefix,
                                         Component name,
                                         Component suffix,
                                         Component message,
                                         Component formatted) {}

    private static String parseDiscordEmojis(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Comprehensive Discord emoji to Unicode mapping
        // Based on common Discord role emojis and frequently used emojis
        String result = text
                // Crown and royalty
                .replace(":crown:", "👑")
                .replace(":diamond:", "💎")
                .replace(":gem:", "💎")
                
                // Stars and sparkle
                .replace(":star:", "⭐")
                .replace(":sparkles:", "✨")
                .replace(":star2:", "🌟")
                .replace(":dizzy:", "💫")
                
                // Fire and energy
                .replace(":fire:", "🔥")
                .replace(":rocket:", "🚀")
                .replace(":zap:", "⚡")
                .replace(":boom:", "�")
                
                // Weapons and shields
                .replace(":shield:", "🛡️")
                .replace(":sword:", "⚔️")
                .replace(":crossed_swords:", "⚔️")
                .replace(":bow_and_arrow:", "🏹")
                .replace(":dagger:", "🗡️")
                
                // Hearts and love
                .replace(":heart:", "❤️")
                .replace(":yellow_heart:", "💛")
                .replace(":green_heart:", "💚")
                .replace(":blue_heart:", "💙")
                .replace(":purple_heart:", "💜")
                .replace(":black_heart:", "🖤")
                .replace(":white_heart:", "🤍")
                .replace(":broken_heart:", "💔")
                .replace(":two_hearts:", "💕")
                .replace(":sparkling_heart:", "💖")
                
                // Awards and trophies
                .replace(":medal:", "🏅")
                .replace(":trophy:", "🏆")
                .replace(":first_place:", "🥇")
                .replace(":second_place:", "🥈")
                .replace(":third_place:", "🥉")
                .replace(":sports_medal:", "🏅")
                
                // Warning and status
                .replace(":warning:", "⚠️")
                .replace(":bangbang:", "‼️")
                .replace(":exclamation:", "❗")
                .replace(":grey_exclamation:", "❕")
                .replace(":question:", "❓")
                .replace(":grey_question:", "❔")
                .replace(":x:", "❌")
                .replace(":heavy_check_mark:", "✔️")
                .replace(":white_check_mark:", "✅")
                .replace(":ballot_box_with_check:", "☑️")
                .replace(":o:", "⭕")
                .replace(":heavy_large_circle:", "⭕")
                .replace(":no_entry:", "⛔")
                .replace(":name_badge:", "📛")
                .replace(":stop_sign:", "🛑")
                .replace(":prohibited:", "🚫")
                
                // Faces and people
                .replace(":smile:", "😄")
                .replace(":grin:", "😁")
                .replace(":joy:", "😂")
                .replace(":sob:", "😭")
                .replace(":angry:", "😠")
                .replace(":rage:", "😡")
                .replace(":skull:", "💀")
                .replace(":ghost:", "👻")
                .replace(":alien:", "👽")
                
                // Animals
                .replace(":dragon:", "🐉")
                .replace(":unicorn:", "🦄")
                .replace(":wolf:", "🐺")
                .replace(":fox:", "🦊")
                .replace(":lion:", "🦁")
                .replace(":tiger:", "🐯")
                .replace(":cat:", "🐱")
                .replace(":dog:", "🐶")
                
                // Food
                .replace(":apple:", "🍎")
                .replace(":pizza:", "🍕")
                .replace(":cake:", "🎂")
                .replace(":candy:", "🍬")
                .replace(":lollipop:", "🍭")
                .replace(":corn:", "🌽")
                .replace(":broccoli:", "🥦")
                .replace(":carrot:", "🥕")
                .replace(":leafy_green:", "🥬")
                .replace(":ear_of_rice:", "🌾")
                
                // Nature
                .replace(":sun:", "☀️")
                .replace(":moon:", "🌙")
                .replace(":cloud:", "☁️")
                .replace(":ocean:", "🌊")
                .replace(":mountain:", "⛰️")
                .replace(":volcano:", "🌋")
                .replace(":evergreen_tree:", "🌲")
                
                // Objects
                .replace(":key:", "🔑")
                .replace(":lock:", "🔒")
                .replace(":unlock:", "🔓")
                .replace(":scroll:", "📜")
                .replace(":book:", "📖")
                .replace(":crystal_ball:", "🔮")
                .replace(":hourglass:", "⏳")
                .replace(":clock:", "🕐")
                
                .replace(":candy:", "🍬")
                .replace(":lollipop:", "🍭")
                
                // Activities
                .replace(":game_die:", "🎲")
                .replace(":dart:", "🎯")
                .replace(":tickets:", "🎟️")
                .replace(":flag:", "🏳️")
                .replace(":triangular_flag_on_post:", "🚩")
                
                // Symbols
                .replace(":infinity:", "♾️")
                .replace(":recycle:", "♻️")
                .replace(":radioactive:", "☢️")
                .replace(":biohazard:", "☣️")
                .replace(":peace:", "☮️")
                .replace(":yin_yang:", "☯️")
                .replace(":fleur_de_lis:", "⚜️")
                .replace(":trident:", "🔱")
                .replace(":anchor:", "⚓")
                .replace(":cross:", "✝️")
                .replace(":star_and_crescent:", "☪️")
                .replace(":star_of_david:", "✡️")
                .replace(":wheel_of_dharma:", "☸️")
                
                // Arrows
                .replace(":arrow_up:", "⬆️")
                .replace(":arrow_down:", "⬇️")
                .replace(":arrow_left:", "⬅️")
                .replace(":arrow_right:", "➡️")
                .replace(":arrow_forward:", "▶️")
                .replace(":arrow_backward:", "◀️")
                .replace(":arrow_double_up:", "⏫")
                .replace(":arrow_double_down:", "⏬")
                
                // Numbers
                .replace(":zero:", "0️⃣")
                .replace(":one:", "1️⃣")
                .replace(":two:", "2️⃣")
                .replace(":three:", "3️⃣")
                .replace(":four:", "4️⃣")
                .replace(":five:", "5️⃣")
                .replace(":six:", "6️⃣")
                .replace(":seven:", "7️⃣")
                .replace(":eight:", "8️⃣")
                .replace(":nine:", "9️⃣")
                .replace(":ten:", "🔟")
                
                // Misc
                .replace(":100:", "💯")
                .replace(":1234:", "🔢")
                .replace(":hash:", "#️⃣")
                .replace(":asterisk:", "*️⃣")
                .replace(":exclamation_mark:", "❗")
                .replace(":question_mark:", "❓")
                .replace(":plus:", "➕")
                .replace(":minus:", "➖")
                .replace(":divide:", "➗")
                .replace(":equals:", "🟰");
        
        return result;
    }

    private String formatDiscordOutMessage(Player player, ChannelDefinition channel, String message) {
        String format = channel.discordOutFormat();
        String nickname = getNickname(player);
        String result = format
                .replace("{player}", player.getName())
                .replace("{display_name}", nickname)
                .replace("{name}", nickname)
                .replace("{message}", message);
        
        // Apply PlaceholderAPI placeholders if available
        result = applyPlaceholders(player, result);
        
        return result;
    }

    private String getPrefix(Player player) {
        Chat vaultChat = plugin.getVaultChat();
        String prefix = "";
        if (vaultChat != null) {
            try {
                prefix = vaultChat.getPlayerPrefix(player);
            } catch (Exception ignored) {
            }
        }
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = applyPlaceholders(player, "%luckperms_prefix%");
            if ("%luckperms_prefix%".equals(prefix)) {
                prefix = "";
            }
        }
        return cleanAffix(prefix);
    }

    private String getSuffix(Player player) {
        Chat vaultChat = plugin.getVaultChat();
        String suffix = "";
        if (vaultChat != null) {
            try {
                suffix = vaultChat.getPlayerSuffix(player);
            } catch (Exception ignored) {
            }
        }
        if (suffix == null || suffix.trim().isEmpty()) {
            suffix = applyPlaceholders(player, "%luckperms_suffix%");
            if ("%luckperms_suffix%".equals(suffix)) {
                suffix = "";
            }
        }
        return cleanAffix(suffix);
    }

    private String getNickname(Player player) {
        NicknameManager manager = plugin.getNicknameManager();
        if (manager == null) {
            return player.getName();
        }
        return manager.getStoredNickname(player);
    }

    private String applyPlaceholders(Player player, String text) {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                return PlaceholderAPI.setPlaceholders(player, text);
            }
        } catch (Throwable ignored) {
        }
        return text;
    }

    private String cleanAffix(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text;
        if (cleaned.contains("<") && cleaned.contains(">")) {
            cleaned = cleaned.replaceAll("<[^>]*>", "").trim();
        }
        return cleaned;
    }

    private void relayToDiscord(Player sender, ChannelDefinition channel, String plainMessage) {
        if (!discordAvailable) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Discord relay skipped for " + channel.name() + " because discordAvailable=false");
            }
            return;
        }

        // Staff chat always uses manual relay to ensure correct channel routing
        // (DiscordSRV would route to global if we let it handle naturally)
        boolean isStaffChat = channel.name().equals(staffChannelName);
        
        // Check if this was a shortcut message that needs manual Discord relay
        RecentOutboundChat recent = recentOutboundChats.get(sender.getUniqueId());
        boolean isShortcut = recent != null && recent.isShortcut() && recent.message().equals(normalizeMessageForComparison(plainMessage));
        
        // If channel has discord-channel-id configured AND it's not staff-chat AND not a shortcut, let DiscordSRV handle relay naturally
        // This prevents double messages (Allium relay + DiscordSRV natural relay) for regular channels
        if (!isStaffChat && !isShortcut && channel.discordChannelId() != null && !channel.discordChannelId().isBlank()) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Skipping manual Discord relay for " + channel.name() 
                        + " - letting DiscordSRV handle it naturally (channel-id configured, not shortcut)");
            }
            return;
        }
        
        if (isShortcut && plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[Channels] Forcing manual Discord relay for shortcut message to " + channel.name());
        }

        try {
            Plugin discordSrvPlugin = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (!(discordSrvPlugin instanceof DiscordSRV discordSrv) || !discordSrvPlugin.isEnabled()) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Discord relay skipped for " + channel.name() + " because DiscordSRV is not enabled");
                }
                return;
            }

            // For staff-chat, use the "staff-chat" game channel name directly
            // For other channels, find the DiscordSRV game channel name that maps to our target channel
            String discordSrvChannelName;
            if (isStaffChat) {
                discordSrvChannelName = "staff-chat";
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Using explicit staff-chat channel for Discord relay");
                }
            } else {
                String discordChannelId = resolveDiscordChannelId(channel);
                discordSrvChannelName = findDiscordSrvChannelName(discordSrv, discordChannelId);
                if (discordSrvChannelName == null) {
                    discordSrvChannelName = channel.name(); // fallback to Allium channel name
                }
            }

            if (discordSrv.getDestinationTextChannelForGameChannelName(discordSrvChannelName) == null) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Discord relay skipped for " + channel.name()
                            + " because DiscordSRV has no linked game channel mapping for: " + discordSrvChannelName);
                }
                return;
            }

            final DiscordSRV target = discordSrv;
            final String finalChannelName = discordSrvChannelName;
            final String finalMessage = plainMessage;
            final Player finalSender = sender;
            
            // For staff-chat, use webhook delivery if configured, otherwise send directly via JDA
            if (isStaffChat) {
                // Check if webhook delivery is enabled for this channel
                if (channel.webhookDelivery()) {
                    try {
                        // Use DiscordSRV's webhook delivery for proper profile/avatar display
                        Object textChannelObj = target.getDestinationTextChannelForGameChannelName(finalChannelName);
                        if (textChannelObj != null) {
                            WebhookUtil.deliverMessage((github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel) textChannelObj, 
                                    finalSender, formatDiscordOutMessage(finalSender, channel, finalMessage));
                        }
                    } catch (Throwable t) {
                        if (plugin.isDebugMode()) {
                            Text.sendDebugLog(WARN, "[Channels] Webhook delivery failed for staff-chat: " + t.getMessage());
                        }
                        // Fallback to direct JDA send
                        fallbackToJdaSend(target, finalSender, finalChannelName, finalMessage, channel);
                    }
                } else {
                    // Direct JDA send (original behavior)
                    fallbackToJdaSend(target, finalSender, finalChannelName, finalMessage, channel);
                }
            } else {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        target.processChatMessage(finalSender, finalMessage, finalChannelName, false);
                        if (plugin.isDebugMode()) {
                            Text.sendDebugLog(INFO, "[Channels] Queued DiscordSRV relay for " + channel.name() + " via channel: " + finalChannelName);
                        }
                    } catch (Throwable t) {
                        if (plugin.isDebugMode()) {
                            Text.sendDebugLog(WARN, "[Channels] Discord relay failed for " + channel.name() + ": " + t.getMessage());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Discord relay failed for " + channel.name() + ": " + t.getMessage());
            }
        }
    }

    private void fallbackToJdaSend(DiscordSRV target, Player sender, String channelName, String message, ChannelDefinition channel) {
        try {
            Object textChannelObj = target.getDestinationTextChannelForGameChannelName(channelName);
            if (textChannelObj != null) {
                // Get the JDA TextChannel and send directly
                Class<?> textChannelClass = textChannelObj.getClass();
                java.lang.reflect.Method sendMessageMethod = textChannelClass.getMethod("sendMessage", CharSequence.class);
                Object messageAction = sendMessageMethod.invoke(textChannelObj, formatDiscordOutMessage(sender, channel, message));
                
                // Call queue() to send async
                java.lang.reflect.Method queueMethod = messageAction.getClass().getMethod("queue");
                queueMethod.invoke(messageAction);
                
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Direct JDA send for staff-chat via channel: " + channelName);
                }
            }
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Direct JDA send failed for staff-chat: " + t.getMessage());
            }
            // Fallback to processChatMessage if direct send fails
            try {
                staffChatActive.add(sender.getUniqueId());
                target.processChatMessage(sender, message, channelName, false);
                // Delay cleanup to ensure DiscordSRV's async event sees it
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    staffChatActive.remove(sender.getUniqueId());
                }, 40L);
            } catch (Throwable t2) {
                staffChatActive.remove(sender.getUniqueId());
            }
        }
    }

    /**
     * Finds the DiscordSRV game channel name that maps to the given Discord channel ID.
     * This allows Allium channels to work with any DiscordSRV channel name mapping.
     */
    private String findDiscordSrvChannelName(DiscordSRV discordSrv, String discordChannelId) {
        if (discordChannelId == null || discordChannelId.isBlank()) {
            return null;
        }
        try {
            // Check all DiscordSRV game channels to find one that maps to this channel ID
            for (String gameChannel : discordSrv.getChannels().keySet()) {
                Object textChannelObj = discordSrv.getDestinationTextChannelForGameChannelName(gameChannel);
                if (textChannelObj != null) {
                    String channelId = (String) textChannelObj.getClass().getMethod("getId").invoke(textChannelObj);
                    if (discordChannelId.equals(channelId)) {
                        return gameChannel;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void hookDiscordSrvIfAvailable() {
        try {
            Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (discordSrv == null || !discordSrv.isEnabled()) {
                discordAvailable = false;
                discordHooked = false;
                return;
            }

            discordAvailable = true;
            if (!discordHooked) {
                DiscordSRV.api.subscribe(this);
                discordHooked = true;
            }
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] DiscordSRV channel bridge enabled");
            }
        } catch (Throwable t) {
            discordAvailable = Bukkit.getPluginManager().getPlugin("DiscordSRV") != null
                    && Bukkit.getPluginManager().getPlugin("DiscordSRV").isEnabled();
            discordHooked = false;
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] DiscordSRV inbound bridge unavailable: " + t.getMessage());
            }
        }
    }

    public void retryDiscordHook() {
        hookDiscordSrvIfAvailable();
    }

    @Subscribe
    public void onDiscordGuildMessage(DiscordGuildMessagePreProcessEvent event) {
        try {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] DiscordGuildMessagePreProcessEvent: channel=" + event.getChannel().getId() 
                        + " author=" + event.getAuthor().getName() + " content=" + event.getMessage().getContentDisplay());
            }
            
            ChannelDefinition channel = findByDiscordChannelId(event.getChannel().getId());
            if (channel == null) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] No Allium channel found for Discord channel " + event.getChannel().getId());
                }
                return;
            }

            if (event.getAuthor().isBot()) {
                return;
            }

            // Get message content
            String displayContent = event.getMessage().getContentDisplay();
            String rawContent = event.getMessage().getContentRaw();
            String content = displayContent != null && !displayContent.isBlank() ? displayContent : rawContent;
            
            // If content is blank, don't cancel DiscordSRV - let InteractiveChat handle attachments
            if (content == null || content.isBlank()) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Skipping Discord inbound - no text content (letting InteractiveChat handle attachments)");
                }
                return;
            }

            if (plugin.getSpamBlockerManager() != null && plugin.getSpamBlockerManager().shouldBlockDiscordInbound(event, content)) {
                return;
            }

            if (plugin.getChatFilterManager() != null && plugin.getChatFilterManager().shouldBlockDiscordInbound(event, content)) {
                return;
            }
            
            // Always handle Discord-to-Minecraft in Allium to apply emoji parsing
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Handling Discord inbound for channel: " + channel.name());
            }
            
            // Check if this is a reply and get reply info
            boolean isReply = false;
            String replySnippet = "";
            try {
                Object referencedMsg = event.getMessage().getClass().getMethod("getReferencedMessage").invoke(event.getMessage());
                if (referencedMsg != null) {
                    isReply = true;
                    // Get author name of replied message
                    Object replyAuthor = referencedMsg.getClass().getMethod("getAuthor").invoke(referencedMsg);
                    String replyAuthorName = (String) replyAuthor.getClass().getMethod("getName").invoke(replyAuthor);
                    // Get DiscordSRV reply format and parse %name%
                    String replyFormat = getDiscordSrvReplyFormat();
                    replySnippet = replyFormat.replace("%name%", replyAuthorName);
                }
            } catch (Throwable ignored) {
            }
            
            event.setCancelled(true);
            relayDiscordMessageToChannel(channel, event.getAuthor().getName(),
                    event.getMember() != null ? event.getMember().getEffectiveName() : "",
                    event.getMember(),
                    event.getMessage().getId(),
                    displayContent,
                    rawContent,
                    event.getChannel().getName(),
                    isReply,
                    replySnippet);
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Discord inbound processing failed: " + t.getMessage());
            }
        }
    }

    @Subscribe
    public void onDiscordGuildMessagePostProcess(DiscordGuildMessagePostProcessEvent event) {
        try {
            ChannelDefinition channel = findByDiscordChannelId(event.getChannel().getId());
            if (channel == null || event.getAuthor().isBot()) {
                return;
            }

            // If content is blank, don't cancel - let InteractiveChat handle attachments
            String content = event.getMessage().getContentDisplay();
            if (content == null || content.isBlank()) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] PostProcess skipping blank message (letting InteractiveChat handle attachments)");
                }
                return;
            }

            // Always handle Discord-to-Minecraft in Allium (PreProcess already handles it)
            // Cancel PostProcess to prevent double handling
            event.setCancelled(true);
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Discord inbound post-process failed: " + t.getMessage());
            }
        }
    }

    @Subscribe
    public void onGameChatMessagePreProcess(GameChatMessagePreProcessEvent event) {
        try {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] onGameChatMessagePreProcess ENTER: player=" 
                    + event.getPlayer().getName() + " channel=" + event.getChannel() 
                    + " staffChatActive=" + staffChatActive.contains(event.getPlayer().getUniqueId()));
            }
            purgeExpiredSuppressedEvents();

            if (plugin.getSpamBlockerManager() != null
                    && plugin.getSpamBlockerManager().shouldSuppressDiscordRelay(event.getPlayer().getUniqueId(), event.getMessage())) {
                event.setCancelled(true);
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV relay for spam-blocked chat from "
                            + event.getPlayer().getName());
                }
                return;
            }

            if (plugin.getChatFilterManager() != null
                    && plugin.getChatFilterManager().shouldSuppressDiscordRelay(event.getPlayer().getUniqueId(), event.getMessage())) {
                event.setCancelled(true);
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV relay for filtered chat from "
                            + event.getPlayer().getName());
                }
                return;
            }

            if (plugin.getSpamBlockerManager() != null) {
                String original = event.getMessage() == null ? "" : event.getMessage().trim();
                String rewritten = plugin.getSpamBlockerManager().rewriteForDiscordRelay(original);
                if (rewritten != null && !rewritten.equals(original)) {
                    event.setMessage(rewritten);
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "[Channels] Rewrote DiscordSRV pre-process message for "
                                + event.getPlayer().getName() + ": '" + original + "' -> '" + rewritten + "'");
                    }
                }
            }
            
            // If player is in staff-chat mode, suppress ALL DiscordSRV events (both staff-chat and global)
            // to prevent the message from leaking to the wrong Discord channel
            if (staffChatActive.contains(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV event for staff-chat player "
                            + event.getPlayer().getName() + " channel=" + event.getChannel());
                }
                return;
            }
            
            // Always suppress staff-chat channel (we handle it manually with explicit channel name)
            if (event.getChannel().equals(staffChannelName)) {
                event.setCancelled(true);
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV default relay for staff-chat channel");
                }
                return;
            }
            
            // Check if we should suppress this message (e.g., it was handled by Allium via shortcut)
            if (shouldSuppressOutboundRelay(event.getPlayer().getUniqueId(), event.getTriggeringBukkitEvent(), normalizeMessageForComparison(event.getMessage()))) {
                event.setCancelled(true);
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV relay for "
                            + event.getPlayer().getName() + " channel=" + event.getChannel() + " (message already handled)");
                }
                return;
            }
            
            // Don't suppress if this channel has discord-channel-id configured (let DiscordSRV/InteractiveChat handle naturally)
            if (channelHasDiscordIdConfigured(event.getChannel())) {
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Not suppressing " + event.getChannel() + " - has discord-channel-id configured");
                }
                return;
            }
            
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] NOT suppressing " + event.getChannel() + " for " + event.getPlayer().getName());
            }
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Exception in onGameChatMessagePreProcess: " + t.getMessage());
                t.printStackTrace();
            }
        }
    }

    @Subscribe
    public void onGameChatMessagePostProcess(GameChatMessagePostProcessEvent event) {
        purgeExpiredSuppressedEvents();

        if (plugin.getSpamBlockerManager() != null
                && plugin.getSpamBlockerManager().shouldSuppressDiscordRelay(event.getPlayer().getUniqueId(), event.getProcessedMessage())) {
            event.setCancelled(true);
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV post-process relay for spam-blocked chat from "
                        + event.getPlayer().getName());
            }
            return;
        }

        if (plugin.getChatFilterManager() != null
                && plugin.getChatFilterManager().shouldSuppressDiscordRelay(event.getPlayer().getUniqueId(), event.getProcessedMessage())) {
            event.setCancelled(true);
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV post-process relay for filtered chat from "
                        + event.getPlayer().getName());
            }
            return;
        }

        if (plugin.getSpamBlockerManager() != null) {
            String original = event.getProcessedMessage();
            String rewritten = plugin.getSpamBlockerManager().rewriteForDiscordRelay(original);
            if (rewritten != null && !rewritten.equals(original)) {
                event.setProcessedMessage(rewritten);
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Channels] Rewrote DiscordSRV post-process message for "
                            + event.getPlayer().getName() + ": '" + original + "' -> '" + rewritten + "'");
                }
            }
        }
        
        // If player is in staff-chat mode, suppress ALL DiscordSRV events
        if (staffChatActive.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV post-process for staff-chat player "
                        + event.getPlayer().getName() + " channel=" + event.getChannel());
            }
            return;
        }
        
        // Always suppress staff-chat channel (we handle it manually)
        if (event.getChannel().equals(staffChannelName)) {
            event.setCancelled(true);
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV post-process relay for staff-chat channel");
            }
            return;
        }
        
        // Don't suppress if this channel has discord-channel-id configured (let DiscordSRV/InteractiveChat handle naturally)
        if (channelHasDiscordIdConfigured(event.getChannel())) {
            return;
        }
        if (shouldSuppressOutboundRelay(event.getPlayer().getUniqueId(), event.getTriggeringBukkitEvent(),
                normalizeMessageForComparison(event.getProcessedMessage()))) {
            event.setCancelled(true);
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Suppressed DiscordSRV post-process relay for "
                        + event.getPlayer().getName() + " channel=" + event.getChannel());
            }
        }
    }

    private boolean channelHasDiscordIdConfigured(String channelName) {
        // First check direct channel name
        ChannelDefinition channel = channels.get(channelName);
        if (channel == null) {
            // Check aliases - DiscordSRV channel name might match an alias
            for (ChannelDefinition ch : channels.values()) {
                if (ch.aliases().stream().anyMatch(alias -> normalize(alias).equals(normalize(channelName)))) {
                    channel = ch;
                    break;
                }
            }
        }
        if (channel == null) {
            return false;
        }
        return channel.discordChannelId() != null && !channel.discordChannelId().isBlank();
    }

    private void purgeExpiredSuppressedEvents() {
        long cutoff = System.currentTimeMillis() - SUPPRESSED_EVENT_WINDOW_MS;
        suppressedDiscordEventIds.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        long handledCutoff = System.currentTimeMillis() - HANDLED_DISCORD_MESSAGE_WINDOW_MS;
        handledDiscordMessageIds.entrySet().removeIf(entry -> entry.getValue() < handledCutoff);
        long outboundCutoff = System.currentTimeMillis() - OUTBOUND_CHAT_WINDOW_MS;
        recentOutboundChats.entrySet().removeIf(entry -> entry.getValue().timestamp() < outboundCutoff);
    }

    private boolean shouldSuppressOutboundRelay(UUID playerId, org.bukkit.event.Event triggeringEvent, String message) {
        if (playerId == null) {
            return false;
        }

        if (triggeringEvent != null && suppressedDiscordEventIds.remove(System.identityHashCode(triggeringEvent)) != null) {
            return true;
        }

        if (triggeringEvent == null) {
            return false;
        }

        RecentOutboundChat recent = recentOutboundChats.get(playerId);
        if (recent == null || recent.timestamp() < System.currentTimeMillis() - OUTBOUND_CHAT_WINDOW_MS) {
            return false;
        }

        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[Channels] shouldSuppress check: isShortcut=" + recent.isShortcut() + " message='" + message + "' recent.message='" + recent.message() + "'");
        }

        // Only suppress if this was a shortcut message (Allium is handling the relay manually)
        // For regular messages, let DiscordSRV handle naturally if channel has discord-channel-id
        if (!recent.isShortcut()) {
            return false;
        }

        // Check if message matches directly
        if (!message.isBlank() && message.equals(recent.message())) {
            return true;
        }
        
        // Check if this was a > shortcut message (original had > prefix which was stripped)
        if (!message.isBlank() && message.startsWith(">") && message.substring(1).trim().equals(recent.message())) {
            return true;
        }
        
        // Check if this was a # shortcut message
        if (!message.isBlank() && message.startsWith("#") && message.substring(1).trim().equals(recent.message())) {
            return true;
        }

        return false;
    }

    private static String normalizeMessageForComparison(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().replace("\r", "").replace("\n", "\n");
    }

    private void relayDiscordMessageToChannel(ChannelDefinition channel, String authorName, String effectiveName,
                                              Object member, String discordMessageId, 
                                              String displayContent, String rawContent, 
                                              String discordChannelName,
                                              boolean isReply, String replySnippet) {
        purgeExpiredSuppressedEvents();
        if (discordMessageId != null && !discordMessageId.isBlank()
                && handledDiscordMessageIds.putIfAbsent(discordMessageId, System.currentTimeMillis()) != null) {
            return;
        }

        String username = (effectiveName != null && !effectiveName.isBlank()) ? effectiveName : authorName;
        String content = displayContent != null ? displayContent : "";
        if (content.isBlank()) {
            content = rawContent != null ? rawContent : "";
        }
        if (content.isBlank()) {
            return;
        }

        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[Channels] relayDiscordMessage: inputChannel=" + channel.name() + " discordChannel=" + discordChannelName + " isReply=" + isReply);
        }

        // Resolve the canonical channel name (handles aliases like "global" -> "default")
        String resolved = resolveChannel(channel.name());
        final String canonicalChannelName = resolved != null ? resolved : channel.name();
        
        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[Channels] relayDiscordMessage: resolved=" + resolved + " canonical=" + canonicalChannelName);
        }

        // Build message using DiscordSRV format with role alias support
        String format;
        if (channel.discordInFormat() != null && !channel.discordInFormat().isBlank()) {
            // Use channel-specific discord-in-format with DiscordSRV placeholder parsing
            format = channel.discordInFormat();
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Using custom discord-in-format: " + format);
            }
        } else {
            // Use DiscordSRV's configured format
            format = getDiscordSrvMessageFormat();
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Using DiscordSRV default format");
            }
        }
        String roleAlias = getDiscordSrvTopRoleAlias(member);
        String roleColor = getDiscordSrvTopRoleColor(member);
        String formattedMessage = formatDiscordSrvMessage(format, username, effectiveName, roleAlias, roleColor, content, discordChannelName, isReply, replySnippet);
        Component formatted = Text.colorize(formattedMessage);
        final String finalUsername = username;
        Bukkit.getScheduler().runTask(plugin, () -> {
            long messageId = plugin.getChatMessageManager().storeMessage(DISCORD_SENDER_ID, finalUsername, formatted);
            int recipientCount = 0;
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                // Use canonical channel name for reading check
                if (!isReading(recipient.getUniqueId(), canonicalChannelName)) {
                    if (plugin.isDebugMode() && recipient.getName().equals("ggpots")) {
                        Text.sendDebugLog(INFO, "[Channels] ggpots not reading channel " + canonicalChannelName 
                                + " reads=" + getReadChannels(recipient.getUniqueId()));
                    }
                    continue;
                }
                recipientCount++;
                recipient.sendMessage(formatted);
            }
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Discord inbound sent to " + recipientCount + " recipients");
            }
        });
    }

    private ChannelDefinition findByDiscordChannelId(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        // First check loaded channels
        for (ChannelDefinition channel : channels.values()) {
            if (channelId.equals(resolveDiscordChannelId(channel))) {
                return channel;
            }
        }
        // Fallback: check if channelId matches default or staff-chat from DiscordSRV config
        String defaultDiscordId = getDiscordSrvChannelId(defaultChannelName);
        String staffDiscordId = getDiscordSrvChannelId(staffChannelName);
        
        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[Channels] findByDiscordChannelId fallback: input=" + channelId 
                    + " defaultId=" + defaultDiscordId + " staffId=" + staffDiscordId);
        }
        
        if (channelId.equals(defaultDiscordId)) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Matched default channel");
            }
            return getChannel(defaultChannelName);
        }
        if (channelId.equals(staffDiscordId)) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Channels] Matched staff-chat channel");
            }
            return getChannel(staffChannelName);
        }
        return null;
    }

    private String resolveDiscordChannelId(ChannelDefinition channel) {
        if (channel == null) {
            return "";
        }
        if (channel.discordChannelId() != null && !channel.discordChannelId().isBlank()) {
            return channel.discordChannelId();
        }
        return getDiscordSrvChannelId(channel.name());
    }

    private String getDiscordSrvChannelId(String channelName) {
        try {
            Plugin discordSrvPlugin = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (!(discordSrvPlugin instanceof DiscordSRV discordSrv) || !discordSrvPlugin.isEnabled()) {
                return "";
            }
            
            // Use DiscordSRV's API to get the channel mapping
            Object textChannel = discordSrv.getDestinationTextChannelForGameChannelName(channelName);
            if (textChannel != null) {
                // Get the channel ID via reflection
                String channelId = (String) textChannel.getClass().getMethod("getId").invoke(textChannel);
                return channelId;
            }
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Failed to get DiscordSRV channel id for " + channelName + ": " + t.getMessage());
            }
        }
        return "";
    }

    private String findDiscordSrvChannelNameFromDiscordId(String discordChannelId) {
        try {
            Plugin discordSrvPlugin = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (!(discordSrvPlugin instanceof DiscordSRV discordSrv) || !discordSrvPlugin.isEnabled()) {
                return null;
            }
            
            // Iterate through DiscordSRV's channels to find one matching this Discord channel ID
            for (String gameChannelName : discordSrv.getChannels().keySet()) {
                Object textChannelObj = discordSrv.getDestinationTextChannelForGameChannelName(gameChannelName);
                if (textChannelObj != null) {
                    String channelId = (String) textChannelObj.getClass().getMethod("getId").invoke(textChannelObj);
                    if (discordChannelId.equals(channelId)) {
                        return gameChannelName;
                    }
                }
            }
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Failed to find DiscordSRV channel name for Discord ID " + discordChannelId + ": " + t.getMessage());
            }
        }
        return null;
    }

    private String getDiscordSrvMessageFormat() {
        try {
            Plugin discordSrvPlugin = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (!(discordSrvPlugin instanceof DiscordSRV discordSrv) || !discordSrvPlugin.isEnabled()) {
                return "[Discord] %name%: %message%";
            }
            
            // DiscordSRV stores message formats in messages.yml, not config.yml
            // Try to get the format from DiscordSRV's messages config
            File messagesFile = new File(discordSrvPlugin.getDataFolder(), "messages.yml");
            if (messagesFile.exists()) {
                YamlConfiguration messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
                String format = messagesConfig.getString("DiscordToMinecraftChatMessageFormat");
                if (format != null && !format.isBlank()) {
                    return format;
                }
            }
            
            // Fallback to main config
            FileConfiguration config = discordSrv.getConfig();
            String format = config.getString("DiscordToMinecraftChatMessageFormat");
            if (format != null && !format.isBlank()) {
                return format;
            }
            
            // Final fallback
            return "[Discord] %name%: %message%";
        } catch (Throwable t) {
            return "[Discord] %name%: %message%";
        }
    }

    private String getDiscordSrvReplyFormat() {
        try {
            Plugin discordSrvPlugin = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (!(discordSrvPlugin instanceof DiscordSRV) || !discordSrvPlugin.isEnabled()) {
                return " (replying to %name%)";
            }
            
            // Try to get the reply format from DiscordSRV's messages config
            File messagesFile = new File(discordSrvPlugin.getDataFolder(), "messages.yml");
            if (messagesFile.exists()) {
                YamlConfiguration messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
                String format = messagesConfig.getString("DiscordToMinecraftMessageReplyFormat");
                if (format != null && !format.isBlank()) {
                    return format;
                }
            }
            
            // Fallback to main config
            FileConfiguration config = ((DiscordSRV) discordSrvPlugin).getConfig();
            String format = config.getString("DiscordToMinecraftMessageReplyFormat");
            if (format != null && !format.isBlank()) {
                return format;
            }
            
            return " (replying to %name%)";
        } catch (Throwable t) {
            return " (replying to %name%)";
        }
    }

    private String formatDiscordSrvMessage(String format, String username, String effectiveName, 
                                           String roleAlias, String roleColor, String message, String channelName,
                                           boolean isReply, String replySnippet) {
        // Parse DiscordSRV placeholders
        String result = format
                .replace("%name%", username)
                .replace("%displayname%", effectiveName != null && !effectiveName.isBlank() ? effectiveName : username)
                .replace("%username%", username)
                .replace("%message%", message)
                .replace("%channelname%", channelName != null ? channelName : "")
                .replace("%toprolealias%", roleAlias != null && !roleAlias.isBlank() ? roleAlias : "")
                .replace("%toprolecolor%", roleColor != null && !roleColor.isBlank() ? roleColor : "");
        
        // %reply% placeholder - if it's a reply, show the reply snippet, otherwise empty
        if (isReply && replySnippet != null && !replySnippet.isBlank()) {
            result = result.replace("%reply%", replySnippet);
        } else {
            result = result.replace("%reply%", "");
        }
        
        // Apply emoji parsing to the entire formatted message
        result = parseDiscordEmojis(result);
        
        return result;
    }
    private String unquote(String value) {
        if (value == null) {
            return "";
        }
        String out = value;
        if (out.startsWith("\"") && out.endsWith("\"") && out.length() >= 2) {
            out = out.substring(1, out.length() - 1);
        }
        return out;
    }

    private String getDiscordSrvTopRoleAlias(Object member) {
        if (member == null) return "";
        try {
            // Get the top role from Discord member
            Object topRole = member.getClass().getMethod("getRoles").invoke(member);
            if (topRole instanceof java.util.List && !((java.util.List<?>) topRole).isEmpty()) {
                Object role = ((java.util.List<?>) topRole).get(0);
                // Get the role name
                String roleName = (String) role.getClass().getMethod("getName").invoke(role);
                return roleName;
            }
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Failed to get top role alias: " + t.getMessage());
            }
        }
        return "";
    }

    private String getDiscordSrvTopRoleColor(Object member) {
        if (member == null) return "";
        try {
            // Get the top role from Discord member
            Object roles = member.getClass().getMethod("getRoles").invoke(member);
            if (roles instanceof java.util.List && !((java.util.List<?>) roles).isEmpty()) {
                Object topRole = ((java.util.List<?>) roles).get(0);
                // Get the role color (returns java.awt.Color)
                Object colorObj = topRole.getClass().getMethod("getColor").invoke(topRole);
                if (colorObj != null) {
                    // colorObj is java.awt.Color, extract RGB values
                    int r = (int) colorObj.getClass().getMethod("getRed").invoke(colorObj);
                    int g = (int) colorObj.getClass().getMethod("getGreen").invoke(colorObj);
                    int b = (int) colorObj.getClass().getMethod("getBlue").invoke(colorObj);
                    // Convert to #RRGGBB format
                    String hexColor = String.format("&#%02X%02X%02X", r, g, b);
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "[Channels] Role color: RGB(" + r + "," + g + "," + b + ") hex=" + hexColor);
                    }
                    return hexColor;
                }
            }
        } catch (Throwable t) {
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(WARN, "[Channels] Failed to get top role color: " + t.getMessage());
            }
        }
        return "";
    }

    public void sendChannelInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Configured channels: " + String.join(", ", channels.keySet()));
            return;
        }

        String write = writeChannels.getOrDefault(player.getUniqueId(), defaultChannelName);
        List<String> readable = new ArrayList<>(getReadChannels(player.getUniqueId()));
        Collections.sort(readable);
        sender.sendMessage(Text.parseColors("&8[&bChannels&8] &7Write: &f" + write));
        sender.sendMessage(Text.parseColors("&8[&bChannels&8] &7Read: &f" + String.join(", ", readable)));
        sender.sendMessage(Text.parseColors("&8[&bChannels&8] &7Use &f/channel join <channel> [read|write]&7."));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record ChannelDefinition(
            String name,
            String displayName,
            List<String> aliases,
            String format,
            String discordChannelId,
            String discordInFormat,
            String discordOutFormat,
            boolean webhookDelivery
    ) {}

    private record RecentOutboundChat(String message, long timestamp, boolean isStaffChat, boolean isShortcut) {}
}
