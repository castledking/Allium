package codes.castled.allium.legacy;

import codes.castled.allium.ServerVersion;
import codes.castled.allium.chat.ChatFilterEngine;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 8 / 1.12.2-1.19.4 chat runtime: formatted public &amp; group chat (with optional Vault
 * prefix/suffix/group lookup), chat channels ({@code /channel}, {@code /g}, {@code /a}, {@code /l},
 * {@code #staff}, {@code >global}), local radius, {@code ?} global questions, {@code @player}
 * mentions with sound, URL permission gating, {@code /action} ({@code /me}), join/quit messages,
 * and shared {@link ChatFilterEngine} ({@code chat.filter.*}).
 */
final class LegacyChatModule implements Listener {

    static final String PERM_URL = "allium.chat.url";
    static final String PERM_FILTER_BYPASS = "allium.chat.filter.bypass";
    static final String PERM_FILTER_ALERTS = "allium.chat.filter.alerts";
    static final String PERM_QUESTION = "allium.chat.question";
    static final String PERM_MENTION = "allium.chat.mention";
    static final String PERM_SHOUT = "allium.chat.shout";
    static final String PERM_IGNORE_EXEMPT = "allium.chat.ignoreexempt";
    static final String RECEIVE_LOCAL = "allium.chat.receive.local";
    static final String RECEIVE_SHOUT = "allium.chat.receive.shout";
    static final String RECEIVE_QUESTION = "allium.chat.receive.question";
    static final String RECEIVE_MENTION = "allium.chat.receive.mention";

    private static final Set<String> GLOBAL_LABELS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("g", "global", "shout")));
    private static final Set<String> STAFF_LABELS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("a", "ac", "amsg", "staffchat")));
    private static final Set<String> LOCAL_LABELS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("l", "local")));

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?:https?://\\S+|www\\.\\S+|\\b[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.(?:com|net|org|gg|io|me|co|edu|gov|tv|xyz|dev|app|mc|info|biz|uk|ca|de|fr|au|jp|ru|br|es|it|nl|pl|se|no|fi|ch|at|be|dk|cz|eu|us|live|shop|store|site|online|cloud|fun|pro|club|tech|world|games|server|play)(?:/\\S*)?)");
    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])");
    private static final Pattern RAW_HEX_PATTERN = Pattern.compile("(?i)\u00a7x(?:\u00a7[0-9a-f]){6}");
    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern AMP_COLOR_PATTERN = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Pattern RAW_COLOR_PATTERN = Pattern.compile("(?i)\u00a7[0-9a-fk-orx]");
    private static final Pattern HEX_PREFIX_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}.*");

    static final class Channel {
        final String id;
        final String displayName;
        final Set<String> aliases;
        final String format;
        final double radius;

        Channel(String id, String displayName, Set<String> aliases, String format, double radius) {
            this.id = id;
            this.displayName = displayName;
            this.aliases = Collections.unmodifiableSet(new LinkedHashSet<String>(aliases));
            this.format = format;
            this.radius = radius;
        }

        boolean local() {
            return radius > 0.0;
        }
    }

    private static final class PlayerChannels {
        String write;
        final Set<String> reads = new LinkedHashSet<String>();

        PlayerChannels(String write) {
            this.write = write;
        }
    }

    private static final class VaultDecor {
        static final VaultDecor EMPTY = new VaultDecor("", "", "default");
        final String prefix;
        final String suffix;
        final String group;

        VaultDecor(String prefix, String suffix, String group) {
            this.prefix = prefix == null ? "" : prefix;
            this.suffix = suffix == null ? "" : suffix;
            this.group = group == null || group.isEmpty() ? "default" : group;
        }
    }

    private static final class Settings {
        final boolean enabled;
        final boolean formatting;
        final boolean channelsEnabled;
        final String defaultFormat;
        final Map<String, String> groupFormats;
        final Map<String, Channel> channels;
        final String defaultChannel;
        final String staffChannel;
        final int maxBody;
        final String actionFormat;
        final boolean mentionsEnabled;
        final String mentionColor;
        final String mentionSound;
        final double mentionVolume;
        final double mentionPitch;
        final boolean questionEnabled;
        final String questionPrefix;
        final String questionFormat;
        final boolean urlsRequirePermission;
        final boolean allowVanishedPublicChat;
        final ChatFilterEngine.Config filter;

        Settings(
                boolean enabled, boolean formatting, boolean channelsEnabled, String defaultFormat,
                Map<String, String> groupFormats, Map<String, Channel> channels,
                String defaultChannel, String staffChannel, int maxBody, String actionFormat,
                boolean mentionsEnabled, String mentionColor, String mentionSound,
                double mentionVolume, double mentionPitch, boolean questionEnabled,
                String questionPrefix, String questionFormat, boolean urlsRequirePermission,
                boolean allowVanishedPublicChat, ChatFilterEngine.Config filter
        ) {
            this.enabled = enabled;
            this.formatting = formatting;
            this.channelsEnabled = channelsEnabled;
            this.defaultFormat = defaultFormat;
            this.groupFormats = Collections.unmodifiableMap(groupFormats);
            this.channels = Collections.unmodifiableMap(channels);
            this.defaultChannel = defaultChannel;
            this.staffChannel = staffChannel;
            this.maxBody = maxBody;
            this.actionFormat = actionFormat;
            this.mentionsEnabled = mentionsEnabled;
            this.mentionColor = mentionColor;
            this.mentionSound = mentionSound;
            this.mentionVolume = mentionVolume;
            this.mentionPitch = mentionPitch;
            this.questionEnabled = questionEnabled;
            this.questionPrefix = questionPrefix;
            this.questionFormat = questionFormat;
            this.urlsRequirePermission = urlsRequirePermission;
            this.allowVanishedPublicChat = allowVanishedPublicChat;
            this.filter = filter;
        }

        Channel resolve(String label) {
            if (label == null || label.isEmpty()) return null;
            String normalized = label.toLowerCase(Locale.ROOT);
            if (GLOBAL_LABELS.contains(normalized)) return channels.get(defaultChannel);
            if (STAFF_LABELS.contains(normalized)) return channels.get(staffChannel);
            if (LOCAL_LABELS.contains(normalized)) return channels.get("local");
            Channel direct = channels.get(normalized);
            if (direct != null) return direct;
            for (Channel c : channels.values()) {
                if (c.aliases.contains(normalized)) return c;
            }
            return null;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager configManager;
    private final LegacyMessageManager messages;
    private final LegacyModerationModule moderation;
    private final LegacyPlayerInfoModule playerInfo;
    private final boolean pre113;
    private final BiPredicate<CommandSender, Player> canSeeTarget;
    private final ChatFilterEngine filterEngine;
    private final Map<UUID, PlayerChannels> playerChannels = new HashMap<UUID, PlayerChannels>();
    private volatile Settings settings;

    LegacyChatModule(
            JavaPlugin plugin,
            LegacyConfigManager configManager,
            LegacyMessageManager messages,
            LegacyModerationModule moderation,
            LegacyPlayerInfoModule playerInfo,
            ServerVersion version,
            BiPredicate<CommandSender, Player> canSeeTarget
    ) {
        this(plugin, configManager, messages, moderation, playerInfo, version, canSeeTarget, System::currentTimeMillis);
    }

    LegacyChatModule(
            JavaPlugin plugin,
            LegacyConfigManager configManager,
            LegacyMessageManager messages,
            LegacyModerationModule moderation,
            LegacyPlayerInfoModule playerInfo,
            ServerVersion version,
            BiPredicate<CommandSender, Player> canSeeTarget,
            LongSupplier clock
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = messages;
        this.moderation = moderation;
        this.playerInfo = playerInfo;
        this.pre113 = version != null && !version.isAtLeast(1, 13, 0);
        this.canSeeTarget = canSeeTarget;
        this.filterEngine = new ChatFilterEngine(clock);
        this.settings = readSettings(configManager.raw());
    }

    void enable() {
        settings = readSettings(configManager.raw());
        loadChannelPrefs();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            ensureSession(p);
        }
    }

    void reload() {
        Settings replacement = readSettings(configManager.raw());
        settings = replacement;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            PlayerChannels state = ensureSession(p);
            if (!replacement.channels.containsKey(state.write)) {
                state.write = replacement.defaultChannel;
                state.reads.add(replacement.defaultChannel);
                messages.send(p, "chat.write-channel-removed",
                        "{prefix} &cYour write channel was removed; choose a channel before speaking.");
            }
        }
    }

    void disable() {
        saveChannelPrefs();
        playerChannels.clear();
        filterEngine.clear();
    }

    boolean handles(String name) {
        return "channel".equals(name) || "action".equals(name);
    }

    boolean onCommand(CommandSender sender, String name, String label, String[] args) {
        if ("channel".equals(name)) {
            return handleChannel(sender, label, args);
        }
        if ("action".equals(name)) {
            return handleAction(sender, args);
        }
        return false;
    }

    List<String> onTabComplete(CommandSender sender, String name, String alias, String[] args, String prefix) {
        if (!"channel".equals(name) || !(sender instanceof Player)) {
            return Collections.emptyList();
        }
        Settings s = settings;
        if (!s.enabled) return Collections.emptyList();
        String shortLabel = stripNamespace(alias);
        if (!"channel".equals(shortLabel)) {
            return Collections.emptyList();
        }
        Player p = (Player) sender;
        if (args.length == 1) {
            return filterPrefix(Arrays.asList("join", "leave", "list"), prefix);
        }
        if (args.length == 2 && ("join".equalsIgnoreCase(args[0]) || "leave".equalsIgnoreCase(args[0]))) {
            return readableChannels(p, prefix);
        }
        if (args.length == 3 && "join".equalsIgnoreCase(args[0])) {
            return filterPrefix(Arrays.asList("read", "write"), prefix);
        }
        return Collections.emptyList();
    }

    // ==================== Chat Event Handling ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Settings s = settings;
        if (!s.enabled) return;
        final Player sender = event.getPlayer();
        final String body = event.getMessage();
        final Set<UUID> audience = new HashSet<UUID>();
        for (Player recipient : event.getRecipients()) {
            if (recipient != null) audience.add(recipient.getUniqueId());
        }
        event.setCancelled(true);
        if (!event.isAsynchronous() || isPrimaryThread()) {
            sendChat(sender, null, body, audience);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    sendChat(sender, null, body, audience);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ensureSession(player);
        handleJoinQuitMessage(player, "join", event.getJoinMessage() != null, event::setJoinMessage);
        if (!player.hasPlayedBefore() && !isPlayerVanished(player) && !moderation.isJailed(player.getUniqueId())) {
            ConfigurationSection firstJoin = configManager.raw().getConfigurationSection("join-and-quit-messages.first-join");
            if (firstJoin != null && firstJoin.getBoolean("enabled", false)) {
                String template = firstJoin.getString("format", "&dWelcome &f%name% &dto the server for the first time!");
                if (template != null && !template.trim().isEmpty()) {
                    String formatted = formatJoinQuitTemplate(player, template);
                    broadcastJoinQuit(player, formatted);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        handleJoinQuitMessage(player, "quit", event.getQuitMessage() != null, event::setQuitMessage);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitCleanup(PlayerQuitEvent event) {
        filterEngine.forget(event.getPlayer().getUniqueId());
    }

    void sendChat(Player sender, String explicitChannel, String raw, Set<UUID> originalAudience) {
        Settings s = settings;
        if (!s.enabled || sender == null || !sender.isOnline()) return;
        if (!canSpeak(sender, s)) return;
        PlayerChannels state = ensureSession(sender);
        String body = raw == null ? "" : raw;

        if (!s.channelsEnabled && explicitChannel == null
                && (!state.write.equals(s.defaultChannel) || body.startsWith("#"))) {
            messages.send(sender, "chat.channels-disabled-select-global",
                    "{prefix} &cChannels are disabled; explicitly select global before speaking.");
            return;
        }

        String target = !s.channelsEnabled ? s.defaultChannel : (explicitChannel == null ? state.write : explicitChannel);
        if (s.channelsEnabled && explicitChannel == null && target.equals(s.staffChannel) && body.startsWith(">")) {
            target = s.defaultChannel;
            body = body.substring(1).trim();
        } else if (s.channelsEnabled && explicitChannel == null && body.startsWith("#")
                && !HEX_PREFIX_PATTERN.matcher(body).matches()) {
            target = s.staffChannel;
            body = body.substring(1);
        }

        Channel channel = s.resolve(target);
        if (channel == null || !canWrite(sender, channel, s)) {
            messages.send(sender, "chat.channel-write-denied",
                    "{prefix} &cYou cannot write to that channel.");
            return;
        }

        boolean question = false;
        if (s.channelsEnabled && explicitChannel == null && channel.local() && s.questionEnabled
                && body.startsWith(s.questionPrefix) && body.length() > s.questionPrefix.length()) {
            if (!sender.hasPermission(PERM_QUESTION)) {
                messages.send(sender, "chat.question-denied",
                        "{prefix} &cYou don't have permission to ask global questions in chat.");
                return;
            }
            Channel global = s.resolve(s.defaultChannel);
            if (global != null && canWrite(sender, global, s)) {
                channel = global;
                body = body.substring(s.questionPrefix.length()).trim();
                question = true;
            }
        }

        body = stripControlChars(body);
        if (body.trim().isEmpty() || body.length() > s.maxBody) {
            messages.send(sender, "chat.message-length-invalid",
                    "{prefix} &cInvalid chat message length.");
            return;
        }

        if (s.urlsRequirePermission && !sender.hasPermission(PERM_URL) && containsUrl(body)) {
            messages.send(sender, "chat.url-denied",
                    "{prefix} &cYou don't have permission to send links in chat.");
            return;
        }

        if (!checkFilter(sender, channel.id, body, s)) {
            return;
        }

        boolean shout = !question && explicitChannel != null && channel.id.equals(s.defaultChannel);
        String receivePerm = question ? RECEIVE_QUESTION : (shout ? RECEIVE_SHOUT : (channel.local() ? RECEIVE_LOCAL : null));
        boolean senderIgnoreExempt = sender.hasPermission(PERM_IGNORE_EXEMPT);
        Location origin = sender.getLocation();
        double maxDistSq = channel.local() ? channel.radius * channel.radius : 0.0;

        List<Player> recipients = new ArrayList<Player>();
        for (Player recipient : plugin.getServer().getOnlinePlayers()) {
            if (recipient == null || !recipient.isOnline()) continue;
            boolean own = recipient.getUniqueId().equals(sender.getUniqueId()) && canRead(sender, channel, s);
            if (!(own || isListening(recipient, channel, s))) continue;
            if (originalAudience != null && !originalAudience.contains(recipient.getUniqueId())) continue;
            if (channel.local() && !own) {
                Location recLoc = recipient.getLocation();
                if (origin == null || origin.getWorld() == null || recLoc == null || recLoc.getWorld() == null
                        || !origin.getWorld().equals(recLoc.getWorld())
                        || origin.distanceSquared(recLoc) > maxDistSq) {
                    continue;
                }
            }
            if (!own && receivePerm != null && !recipient.hasPermission(receivePerm)) continue;
            if (!own && !senderIgnoreExempt && playerInfo.isIgnoring(recipient.getUniqueId(), sender.getUniqueId())) {
                continue;
            }
            recipients.add(recipient);
        }

        Set<Player> mentioned = new LinkedHashSet<Player>();
        String formattedBody = formatBodyWithMentions(sender, body, recipients, mentioned, s);
        VaultDecor decor = queryVault(sender);
        String format = chooseFormat(channel, decor.group, question, s);
        String renderedLine = renderChatLine(format, sender, decor, formattedBody);

        for (Player recipient : recipients) {
            recipient.sendMessage(renderedLine);
        }

        if (!mentioned.isEmpty() && !s.mentionSound.trim().isEmpty()) {
            for (Player targetPlayer : mentioned) {
                if (!targetPlayer.getUniqueId().equals(sender.getUniqueId())) {
                    LegacyCompat.playSound(targetPlayer, s.mentionSound,
                            (float) s.mentionVolume, (float) s.mentionPitch, pre113);
                }
            }
        }

        String plainBody = ChatColor.stripColor(formattedBody);
        CommandSender console = plugin.getServer().getConsoleSender();
        if (console != null) {
            console.sendMessage("[" + channel.id + "] " + sender.getName() + ": " + plainBody);
        }
    }

    // ==================== /channel & /action ====================

    private boolean handleChannel(CommandSender sender, String label, String[] args) {
        Settings s = settings;
        if (!s.enabled) {
            messages.send(sender, "chat.module-disabled", "{prefix} &cChat module is disabled.");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "chat.channel-player-only", "{prefix} &cChannels require a player.");
            return true;
        }
        Player player = (Player) sender;
        PlayerChannels state = ensureSession(player);
        String alias = stripNamespace(label);
        Channel shortcut = s.resolve(alias);

        if (!s.channelsEnabled) {
            if (GLOBAL_LABELS.contains(alias)) {
                if (args.length == 0) {
                    selectChannel(player, state, s.defaultChannel, true, s);
                } else if (checkShoutPermission(player, alias)) {
                    sendChat(player, s.defaultChannel, joinArgs(args, 0), null);
                }
                return true;
            }
            messages.send(player, "chat.channels-disabled-use-global",
                    "{prefix} &cChat channels are disabled; use /g to select global explicitly.");
            return true;
        }

        if (!"channel".equals(alias) && shortcut != null) {
            if (args.length > 0) {
                if (!shortcut.id.equals(s.defaultChannel) || checkShoutPermission(player, alias)) {
                    sendChat(player, shortcut.id, joinArgs(args, 0), null);
                }
                return true;
            }
            String next = state.write.equals(shortcut.id) ? s.defaultChannel : shortcut.id;
            selectChannel(player, state, next, true, s);
            return true;
        }

        if (args.length == 0 || (args.length == 1 && "list".equalsIgnoreCase(args[0]))) {
            List<String> list = readableChannels(player, "");
            messages.send(player, "chat.channels-list",
                    "{prefix} &fChannels: &d{channels}",
                    "{channels}", joinStrings(list, ", "));
            return true;
        }

        if (args.length < 2 || args.length > 3) {
            LegacyUsageCards.invalid(player, "/channel <join|leave|list> [channel] [read|write]");
            return true;
        }

        Channel target = s.resolve(args[1]);
        if (target == null) {
            messages.send(player, "chat.channel-unknown", "{prefix} &cUnknown channel.");
            return true;
        }

        if ("join".equalsIgnoreCase(args[0])) {
            if (args.length == 3 && !"read".equalsIgnoreCase(args[2]) && !"write".equalsIgnoreCase(args[2])) {
                messages.send(player, "chat.channel-direction-invalid", "{prefix} &cChoose read or write.");
                return true;
            }
            boolean write = args.length < 3 || !"read".equalsIgnoreCase(args[2]);
            selectChannel(player, state, target.id, write, s);
            return true;
        }

        if ("leave".equalsIgnoreCase(args[0])) {
            state.reads.remove(target.id);
            if (state.write.equals(target.id)) {
                state.write = s.defaultChannel;
            }
            state.reads.add(s.defaultChannel);
            saveChannelPrefs();
            messages.send(player, "chat.channel-saved",
                    "{prefix} &aChannel preference saved: &f{channel}&a.",
                    "{channel}", state.write);
            return true;
        }

        messages.send(player, "chat.channel-action-invalid", "{prefix} &cChoose join or leave.");
        return true;
    }

    private void selectChannel(Player player, PlayerChannels state, String id, boolean write, Settings s) {
        Channel channel = s.channels.get(id);
        if (channel == null || !(write ? canWrite(player, channel, s) : canRead(player, channel, s))) {
            messages.send(player, "chat.channel-join-denied", "{prefix} &cYou cannot join that channel.");
            return;
        }
        state.reads.add(id);
        if (write) {
            state.write = id;
        }
        saveChannelPrefs();
        messages.send(player, "chat.channel-saved",
                "{prefix} &aChannel preference saved: &f{channel}&a.",
                "{channel}", state.write);
    }

    private boolean checkShoutPermission(Player player, String alias) {
        if (player.hasPermission(PERM_SHOUT)) return true;
        messages.send(player, "no-permission",
                "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", alias);
        return false;
    }

    private boolean handleAction(CommandSender sender, String[] args) {
        Settings s = settings;
        if (!s.enabled) {
            messages.send(sender, "chat.module-disabled", "{prefix} &cChat module is disabled.");
            return true;
        }
        if (!sender.hasPermission("allium.me") && !sender.hasPermission("allium.action")) {
            messages.send(sender, "no-permission",
                    "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", "me");
            return true;
        }
        if (args.length == 0) {
            LegacyUsageCards.invalid(sender, "/me <message>");
            return true;
        }
        String body = stripControlChars(joinArgs(args, 0));
        if (body.trim().isEmpty() || body.length() > s.maxBody) {
            messages.send(sender, "chat.message-length-invalid",
                    "{prefix} &cInvalid chat message length.");
            return true;
        }
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (!canSpeak(player, s)) return true;
            if (s.urlsRequirePermission && !player.hasPermission(PERM_URL) && containsUrl(body)) {
                messages.send(player, "chat.url-denied",
                        "{prefix} &cYou don't have permission to send links in chat.");
                return true;
            }
            if (!checkFilter(player, "action", body, s)) {
                return true;
            }
            String formattedBody = sanitizePlayerMessage(player, body);
            VaultDecor decor = queryVault(player);
            String rendered = renderChatLine(s.actionFormat, player, decor, formattedBody);
            boolean ignoreExempt = player.hasPermission(PERM_IGNORE_EXEMPT);
            for (Player recipient : plugin.getServer().getOnlinePlayers()) {
                if (!recipient.getUniqueId().equals(player.getUniqueId())
                        && !ignoreExempt
                        && playerInfo.isIgnoring(recipient.getUniqueId(), player.getUniqueId())) {
                    continue;
                }
                recipient.sendMessage(rendered);
            }
            CommandSender console = plugin.getServer().getConsoleSender();
            if (console != null) {
                console.sendMessage("[action] * " + player.getName() + " " + ChatColor.stripColor(formattedBody));
            }
            return true;
        }
        String rendered = renderConsoleAction(s.actionFormat, sender.getName(), LegacyCompat.color(body));
        for (Player recipient : plugin.getServer().getOnlinePlayers()) {
            recipient.sendMessage(rendered);
        }
        CommandSender console = plugin.getServer().getConsoleSender();
        if (console != null) {
            console.sendMessage("[action] * " + sender.getName() + " " + ChatColor.stripColor(rendered));
        }
        return true;
    }

    // ==================== Chat Filter ====================

    private boolean checkFilter(Player sender, String channelId, String raw, Settings s) {
        ChatFilterEngine.Config fs = s.filter;
        if (!fs.enabled()) return true;
        if (fs.exemptStaffChannel() && channelId.equals(s.staffChannel)) return true;
        if (sender.hasPermission(PERM_FILTER_BYPASS)) return true;

        ChatFilterEngine.Decision decision = filterEngine.evaluate(sender.getUniqueId(), raw, fs);
        if (!decision.alerted()) return true;

        if (decision.blocked()) {
            ChatFilterEngine.Kind kind = decision.kind();
            if (kind == ChatFilterEngine.Kind.PHRASE) {
                messages.send(sender, "chat.filter.phrase-blocked",
                        "{prefix} &cYour message was blocked by the chat filter.");
            } else if (kind == ChatFilterEngine.Kind.REPEAT) {
                messages.send(sender, "chat.filter.repeat-blocked",
                        "{prefix} &cPlease do not repeat similar messages so quickly.");
            } else if (kind == ChatFilterEngine.Kind.RATE_LIMIT) {
                messages.send(sender, "chat.filter.rate-blocked",
                        "{prefix} &cYou are sending messages too quickly. Please wait {time}s.",
                        "{time}", ChatFilterEngine.formatRetrySeconds(decision.retryAfterMillis()));
            }
        }

        notifyFilterAudit(sender, channelId, raw, decision, fs);
        return !decision.blocked();
    }

    private void notifyFilterAudit(Player sender, String channelId, String raw,
                                   ChatFilterEngine.Decision decision, ChatFilterEngine.Config fs) {
        String clean = stripControlChars(ChatFilterEngine.stripFormatting(raw == null ? "" : raw)).trim();
        String preview = clean.length() > 180 ? clean.substring(0, 180) + "..." : clean;
        String action = decision.blocked() ? "BLOCK" : "ALERT_ONLY";
        String rule = (decision.blocked() ? "" : "[alert-only] ")
                + decision.kind().name().toLowerCase(Locale.ROOT) + ":" + decision.ruleDetail();
        plugin.getLogger().info("ChatFilter action=" + action + " player=" + sender.getName()
                + " (" + sender.getUniqueId() + ") channel=" + channelId
                + " rule=" + rule + " message=\"" + preview + "\"");
        if (fs.notifyStaff()) {
            String template = messages.resolve("chat.filter.staff-alert",
                    "&8[ChatFilter] &c{player} &7({rule}) &8\u00bb &f{message}",
                    "{player}", sender.getName(),
                    "{rule}", rule);
            if (template != null && !template.isEmpty()) {
                String renderedAlert = spliceLiteralPlaceholder(template, "{message}", preview);
                for (Player observer : plugin.getServer().getOnlinePlayers()) {
                    if (observer.hasPermission(PERM_FILTER_ALERTS)) {
                        observer.sendMessage(renderedAlert);
                    }
                }
            }
        }
    }

    // ==================== Join / Quit Messages ====================

    private void handleJoinQuitMessage(Player player, String kind, boolean hasVanillaMessage,
                                       java.util.function.Consumer<String> setter) {
        if (!hasVanillaMessage) return;
        if (isPlayerVanished(player)) {
            setter.accept(null);
            return;
        }
        ConfigurationSection section = configManager.raw().getConfigurationSection("join-and-quit-messages." + kind);
        if (section == null || !section.getBoolean("enabled", true)) {
            return;
        }
        String fallback = "&e%name% &7has " + ("join".equals(kind) ? "joined" : "left") + " the server";
        String template = section.getString("format", fallback);
        if (template == null || template.trim().isEmpty()) {
            setter.accept(null);
            return;
        }
        String formatted = formatJoinQuitTemplate(player, template);
        if (isWorldScopedJoinQuit()) {
            setter.accept(null);
            broadcastJoinQuit(player, formatted);
        } else {
            setter.accept(formatted);
        }
    }

    private String formatJoinQuitTemplate(Player player, String template) {
        VaultDecor decor = queryVault(player);
        String display = displayNameOf(player);
        String expanded = template
                .replace("%player%", player.getName())
                .replace("{player}", player.getName())
                .replace("%name%", display)
                .replace("{displayname}", display)
                .replace("<display_name>", display)
                .replace("{display_name}", display)
                .replace("%allium_nickname%", display)
                .replace("%allium_gradientdisplayname%", display)
                .replace("%prefix%", decor.prefix)
                .replace("<prefix>", decor.prefix)
                .replace("%suffix%", decor.suffix)
                .replace("<suffix>", decor.suffix);
        return LegacyCompat.color(expanded);
    }

    private boolean isWorldScopedJoinQuit() {
        String scope = configManager.raw().getString("join-and-quit-messages.broadcast-scope", "server");
        return scope != null && "world".equalsIgnoreCase(scope.trim());
    }

    private void broadcastJoinQuit(Player subject, String formatted) {
        if (isWorldScopedJoinQuit()) {
            World world = subject.getWorld();
            if (world == null) return;
            for (Player viewer : world.getPlayers()) {
                if (viewer.canSee(subject)) {
                    viewer.sendMessage(formatted);
                }
            }
        } else {
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (viewer.canSee(subject)) {
                    viewer.sendMessage(formatted);
                }
            }
        }
    }

    // ==================== Formatting & Helpers ====================

    private boolean canSpeak(Player player, Settings s) {
        if (moderation.isMuted(player.getUniqueId())) {
            messages.send(player, "chat.muted-or-loading",
                    "{prefix} &cYou cannot chat while muted or moderation is loading.");
            return false;
        }
        if (isPlayerVanished(player) && !s.allowVanishedPublicChat) {
            messages.send(player, "chat.vanished-public",
                    "{prefix} &cPublic chat is disabled while vanished.");
            return false;
        }
        return true;
    }

    private boolean isPlayerVanished(Player player) {
        if (moderation.isVanished(player.getUniqueId())) return true;
        try {
            for (MetadataValue mv : player.getMetadata("vanished")) {
                if (mv != null && mv.asBoolean()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean canRead(Player p, Channel channel, Settings s) {
        return channel.local()
                || p.hasPermission("allium.channel.join." + channel.id + ".read")
                || p.hasPermission("allium.channel.join." + channel.id + ".write")
                || (channel.id.equals(s.staffChannel) && p.hasPermission("allium.staffchat"));
    }

    private boolean canWrite(Player p, Channel channel, Settings s) {
        return p.hasPermission("allium.channel.join." + channel.id + ".write")
                || (channel.id.equals(s.staffChannel) && p.hasPermission("allium.staffchat"));
    }

    private boolean isListening(Player viewer, Channel channel, Settings s) {
        if (!canRead(viewer, channel, s)) return false;
        if (channel.local()) return true;
        PlayerChannels state = ensureSession(viewer);
        return state.reads.contains(channel.id);
    }

    private PlayerChannels ensureSession(Player player) {
        Settings s = settings;
        UUID id = player.getUniqueId();
        PlayerChannels state = playerChannels.get(id);
        if (state == null) {
            state = new PlayerChannels(s.defaultChannel);
            state.reads.add(s.defaultChannel);
            for (Channel c : s.channels.values()) {
                if (player.hasPermission("allium.channel.autojoin." + c.id + ".read")) {
                    state.reads.add(c.id);
                }
            }
            playerChannels.put(id, state);
        } else if (state.write == null || state.write.isEmpty()) {
            state.write = s.defaultChannel;
            state.reads.add(s.defaultChannel);
        }
        return state;
    }

    private List<String> readableChannels(Player player, String prefix) {
        Settings s = settings;
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (Channel c : s.channels.values()) {
            if (canRead(player, c, s) && c.id.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(c.id);
            }
        }
        Collections.sort(out);
        return out;
    }

    private String formatBodyWithMentions(Player sender, String raw, List<Player> recipients,
                                          Set<Player> mentioned, Settings s) {
        if (!s.mentionsEnabled || !sender.hasPermission(PERM_MENTION) || raw.indexOf('@') < 0) {
            return sanitizePlayerMessage(sender, raw);
        }
        Map<String, Player> visibleByName = new HashMap<String, Player>();
        for (Player recipient : recipients) {
            if (canSeeTarget.test(sender, recipient) && recipient.hasPermission(RECEIVE_MENTION)) {
                visibleByName.put(recipient.getName().toLowerCase(Locale.ROOT), recipient);
            }
        }
        if (visibleByName.isEmpty()) {
            return sanitizePlayerMessage(sender, raw);
        }
        Matcher matcher = MENTION_PATTERN.matcher(raw);
        StringBuilder out = new StringBuilder(raw.length() + 16);
        int last = 0;
        boolean matchedAny = false;
        String mentionPrefix = LegacyCompat.color(s.mentionColor);
        while (matcher.find()) {
            Player target = visibleByName.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (target == null) continue;
            matchedAny = true;
            mentioned.add(target);
            if (matcher.start() > last) {
                out.append(sanitizePlayerMessage(sender, raw.substring(last, matcher.start())));
            }
            String priorColors = ChatColor.getLastColors(out.toString());
            out.append(mentionPrefix).append('@').append(target.getName());
            out.append(priorColors.isEmpty() ? ChatColor.RESET.toString() : ChatColor.RESET.toString() + priorColors);
            last = matcher.end();
        }
        if (!matchedAny) {
            return sanitizePlayerMessage(sender, raw);
        }
        if (last < raw.length()) {
            out.append(sanitizePlayerMessage(sender, raw.substring(last)));
        }
        return out.toString();
    }

    private String sanitizePlayerMessage(Player sender, String raw) {
        if (raw == null || raw.isEmpty()) return "";
        boolean allowColor = sender.hasPermission("allium.chat.color");
        boolean allowHex = sender.hasPermission("allium.chat.hex");
        String cleaned = raw;
        if (!allowHex) {
            cleaned = RAW_HEX_PATTERN.matcher(cleaned).replaceAll("");
        }
        if (!allowColor) {
            cleaned = RAW_COLOR_PATTERN.matcher(cleaned).replaceAll("");
        }
        if (allowHex && allowColor) {
            return LegacyCompat.color(cleaned);
        }
        if (allowHex) {
            // Translate only &#RRGGBB while preserving & color codes literally.
            return translateHexOnly(cleaned);
        }
        if (allowColor) {
            // Translate only &[0-9a-fk-or] while preserving &#RRGGBB literally.
            return AMP_COLOR_PATTERN.matcher(cleaned).replaceAll("\u00a7$1");
        }
        return cleaned;
    }

    private static String translateHexOnly(String raw) {
        if (raw.indexOf("&#") < 0) return raw;
        Matcher m = AMP_HEX_PATTERN.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String replacement = LegacyCompat.color("&#" + m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String chooseFormat(Channel channel, String group, boolean question, Settings s) {
        if (!s.formatting) return "{name}: {message}";
        if (question) return s.questionFormat;
        if (channel.id.equals(s.defaultChannel)) {
            String groupFormat = s.groupFormats.get(group);
            if (groupFormat == null && group != null) {
                groupFormat = s.groupFormats.get(group.toLowerCase(Locale.ROOT));
            }
            if (groupFormat != null) return groupFormat;
        }
        return channel.format;
    }

    private String renderChatLine(String format, Player sender, VaultDecor decor, String sanitizedBody) {
        String display = displayNameOf(sender);
        String prepared = format
                .replace("<prefix>", decor.prefix)
                .replace("{prefix}", decor.prefix)
                .replace("<suffix>", decor.suffix)
                .replace("{suffix}", decor.suffix)
                .replace("<display_name>", display)
                .replace("{display_name}", display)
                .replace("<gradient_name>", display)
                .replace("{gradient_name}", display)
                .replace("<name>", display)
                .replace("{name}", display)
                .replace("<player>", sender.getName())
                .replace("{player}", sender.getName());
        String token = prepared.contains("<message>") ? "<message>" : "{message}";
        return spliceLiteralPlaceholder(prepared, token, sanitizedBody);
    }

    private static String renderConsoleAction(String format, String name, String coloredBody) {
        String prepared = format
                .replace("<prefix>", "")
                .replace("{prefix}", "")
                .replace("<suffix>", "")
                .replace("{suffix}", "")
                .replace("<display_name>", name)
                .replace("{display_name}", name)
                .replace("<gradient_name>", name)
                .replace("{gradient_name}", name)
                .replace("<name>", name)
                .replace("{name}", name)
                .replace("<player>", name)
                .replace("{player}", name);
        String token = prepared.contains("<message>") ? "<message>" : "{message}";
        return spliceLiteralPlaceholder(prepared, token, coloredBody);
    }

    /** Colors the template around {@code placeholder} and splices {@code literalValue} without re-coloring it. */
    private static String spliceLiteralPlaceholder(String template, String placeholder, String literalValue) {
        int idx = template.indexOf(placeholder);
        if (idx < 0) {
            return LegacyCompat.color(template);
        }
        String before = LegacyCompat.color(template.substring(0, idx));
        String after = LegacyCompat.color(template.substring(idx + placeholder.length()));
        return before + (literalValue == null ? "" : literalValue) + after;
    }

    private String displayNameOf(Player player) {
        String nick = playerInfo.nicknameOf(player.getUniqueId());
        if (nick != null && !nick.isEmpty()) {
            return LegacyCompat.color(nick) + ChatColor.RESET;
        }
        String display = player.getDisplayName();
        return display == null || display.isEmpty() ? player.getName() : display;
    }

    private VaultDecor queryVault(Player player) {
        try {
            PluginManager pm = plugin.getServer().getPluginManager();
            if (pm == null || !pm.isPluginEnabled("Vault")) {
                return VaultDecor.EMPTY;
            }
            ServicesManager sm = plugin.getServer().getServicesManager();
            if (sm == null) return VaultDecor.EMPTY;

            String prefix = "";
            String suffix = "";
            String group = "";

            try {
                Class<?> chatClass = Class.forName("net.milkbowl.vault.chat.Chat");
                Object chat = sm.load(chatClass);
                if (chat != null) {
                    Method getPrefix = chatClass.getMethod("getPlayerPrefix", Player.class);
                    Method getSuffix = chatClass.getMethod("getPlayerSuffix", Player.class);
                    Method getGroup = chatClass.getMethod("getPrimaryGroup", Player.class);
                    Object p = getPrefix.invoke(chat, player);
                    Object su = getSuffix.invoke(chat, player);
                    Object g = getGroup.invoke(chat, player);
                    if (p instanceof String) prefix = (String) p;
                    if (su instanceof String) suffix = (String) su;
                    if (g instanceof String) group = (String) g;
                }
            } catch (Throwable ignored) {
            }

            if (group.isEmpty()) {
                try {
                    Class<?> permClass = Class.forName("net.milkbowl.vault.permission.Permission");
                    Object perm = sm.load(permClass);
                    if (perm != null) {
                        Method getGroup = permClass.getMethod("getPrimaryGroup", Player.class);
                        Object g = getGroup.invoke(perm, player);
                        if (g instanceof String) group = (String) g;
                    }
                } catch (Throwable ignored) {
                }
            }
            return new VaultDecor(prefix, suffix, group);
        } catch (Throwable ignored) {
            return VaultDecor.EMPTY;
        }
    }

    static boolean containsUrl(String text) {
        return text != null && URL_PATTERN.matcher(text).find();
    }

    private static String stripControlChars(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isPrimaryThread() {
        try {
            return plugin.getServer().isPrimaryThread();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String stripNamespace(String label) {
        if (label == null) return "";
        int colon = label.lastIndexOf(':');
        return (colon >= 0 ? label.substring(colon + 1) : label).toLowerCase(Locale.ROOT);
    }

    private static String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String joinStrings(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static List<String> filterPrefix(List<String> candidates, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(c);
        }
        return out;
    }

    // ==================== Configuration Loading ====================

    private Settings readSettings(FileConfiguration config) {
        boolean enabled = config.getBoolean("modules.chat", true);
        boolean formatting = config.getBoolean("enable-chat-formatting", true);
        boolean channelsEnabled = config.getBoolean("chat.channels.enabled", true);
        String defaultFormat = config.getString("chat-format.default", "<prefix><display_name>&f: <message>");
        if (defaultFormat == null || defaultFormat.isEmpty()) {
            defaultFormat = "<prefix><display_name>&f: <message>";
        }

        Map<String, String> groups = new LinkedHashMap<String, String>();
        ConfigurationSection groupSec = config.getConfigurationSection("chat-format.groups");
        if (groupSec != null) {
            for (String key : groupSec.getKeys(false)) {
                String val = groupSec.getString(key, defaultFormat);
                if (val != null) groups.put(key, val);
            }
        }

        ConfigurationSection chanRoot = config.getConfigurationSection("chat.channels");
        String defChannel = chanRoot == null ? "default" : chanRoot.getString("default-write-channel", "default");
        String staffChannel = chanRoot == null ? "staff-chat" : chanRoot.getString("staff-channel", "staff-chat");
        if (defChannel == null || defChannel.trim().isEmpty()) defChannel = "default";
        if (staffChannel == null || staffChannel.trim().isEmpty()) staffChannel = "staff-chat";
        defChannel = defChannel.trim().toLowerCase(Locale.ROOT);
        staffChannel = staffChannel.trim().toLowerCase(Locale.ROOT);

        Map<String, Channel> channels = new LinkedHashMap<String, Channel>();
        ConfigurationSection listSec = chanRoot == null ? null : chanRoot.getConfigurationSection("list");
        if (listSec != null) {
            for (String rawId : listSec.getKeys(false)) {
                ConfigurationSection c = listSec.getConfigurationSection(rawId);
                if (c == null) continue;
                String id = rawId.trim().toLowerCase(Locale.ROOT);
                Set<String> aliases = new LinkedHashSet<String>();
                for (String alias : c.getStringList("aliases")) {
                    if (alias != null && !alias.trim().isEmpty()) {
                        aliases.add(alias.trim().toLowerCase(Locale.ROOT));
                    }
                }
                channels.put(id, new Channel(
                        id,
                        c.getString("display-name", id),
                        aliases,
                        c.getString("format", defaultFormat),
                        0.0));
            }
        }
        if (!channels.containsKey(defChannel)) {
            channels.put(defChannel, new Channel(defChannel, "Global", GLOBAL_LABELS, defaultFormat, 0.0));
        }
        if (!channels.containsKey(staffChannel)) {
            channels.put(staffChannel, new Channel(staffChannel, "Staff", STAFF_LABELS,
                    "&8[Staff] <display_name>&f: <message>", 0.0));
        }

        ConfigurationSection localSec = chanRoot == null ? null : chanRoot.getConfigurationSection("local-channel");
        double radius = localSec == null ? 100.0 : localSec.getDouble("radius", 100.0);
        if (!Double.isFinite(radius) || radius <= 0.0 || radius > 10000.0) radius = 100.0;
        Set<String> localAliases = new LinkedHashSet<String>(LOCAL_LABELS);
        if (localSec != null) {
            for (String alias : localSec.getStringList("aliases")) {
                if (alias != null && !alias.trim().isEmpty()) {
                    localAliases.add(alias.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        String localFormat = localSec == null
                ? "&8[Local] <display_name>&f: <message>"
                : localSec.getString("format", "&8[Local] <display_name>&f: <message>");
        channels.put("local", new Channel("local", "Local", localAliases, localFormat, radius));

        int maxBody = clampInt(config.getInt("chat.max-message-length", 512), 1, 4096);
        String actionFormat = config.getString("chat.action-format", "&5* <display_name> &5<message>");
        if (actionFormat == null || actionFormat.isEmpty()) actionFormat = "&5* <display_name> &5<message>";

        boolean mentionsEnabled = config.getBoolean("chat.mentions.enabled", true);
        String mentionColor = config.getString("chat.mentions.color", "&e");
        String mentionSound = config.getString("chat.mentions.sound", "minecraft:entity.experience_orb.pickup");
        double mentionVolume = config.getDouble("chat.mentions.volume", 0.8);
        if (!Double.isFinite(mentionVolume) || mentionVolume < 0.0) mentionVolume = 0.8;
        double mentionPitch = config.getDouble("chat.mentions.pitch", 1.5);
        if (!Double.isFinite(mentionPitch) || mentionPitch < 0.5 || mentionPitch > 2.0) mentionPitch = 1.5;

        boolean questionEnabled = config.getBoolean("chat.question.enabled", true);
        String questionPrefix = config.getString("chat.question.prefix", "?");
        if (questionPrefix == null || questionPrefix.isEmpty()) questionPrefix = "?";
        String questionFormat = config.getString("chat.question.format", "&e[Question] &r<prefix><display_name>&f: <message>");
        if (questionFormat == null || questionFormat.isEmpty()) {
            questionFormat = "&e[Question] &r<prefix><display_name>&f: <message>";
        }

        boolean urlsRequirePerm = config.getBoolean("chat.urls.enabled", true);
        boolean allowVanishedPublic = config.getBoolean("moderation.vanish.allow-public-chat", false);
        ChatFilterEngine.Config filter = readFilterConfig(config);

        return new Settings(
                enabled, formatting, channelsEnabled, defaultFormat, groups, channels,
                defChannel, staffChannel, maxBody, actionFormat,
                mentionsEnabled, mentionColor == null ? "&e" : mentionColor,
                mentionSound == null ? "" : mentionSound, mentionVolume, mentionPitch,
                questionEnabled, questionPrefix, questionFormat, urlsRequirePerm,
                allowVanishedPublic, filter);
    }

    private ChatFilterEngine.Config readFilterConfig(FileConfiguration root) {
        if (root == null) return ChatFilterEngine.Config.disabled();
        ConfigurationSection section = root.getConfigurationSection("chat.filter");
        if (section == null) return ChatFilterEngine.Config.disabled();

        boolean enabled = section.getBoolean("enabled", false);
        ChatFilterEngine.Mode mode;
        try {
            mode = ChatFilterEngine.Mode.parse(section.getString("mode", "block"));
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("Invalid chat.filter.mode; defaulting to block: " + invalid.getMessage());
            mode = ChatFilterEngine.Mode.BLOCK;
        }
        boolean exemptStaff = section.getBoolean("exempt-staff-channel", true);
        boolean notifyStaff = section.getBoolean("notify-staff", true);
        boolean audit = section.getBoolean("audit-to-moderation", true);

        ConfigurationSection phraseSec = section.getConfigurationSection("phrases");
        ChatFilterEngine.PhraseConfig phrases = phraseSec == null ? ChatFilterEngine.PhraseConfig.defaults()
                : new ChatFilterEngine.PhraseConfig(
                        phraseSec.getBoolean("enabled", true),
                        phraseSec.getBoolean("normalize-substitutions", true),
                        phraseSec.getBoolean("join-split-letters", true),
                        phraseSec.getStringList("tokens"),
                        phraseSec.getStringList("phrases"),
                        phraseSec.getStringList("substrings"),
                        phraseSec.getStringList("exceptions"));

        ConfigurationSection repeatSec = section.getConfigurationSection("repeat");
        ChatFilterEngine.RepeatConfig repeat;
        if (repeatSec == null) {
            repeat = ChatFilterEngine.RepeatConfig.defaults();
        } else {
            int windowSeconds = clampInt(repeatSec.getInt("window-seconds", 15), 1, 3600);
            int maxSimilar = clampInt(repeatSec.getInt("max-similar", 2), 0, 50);
            double threshold = repeatSec.getDouble("similarity-threshold", 0.85d);
            if (!Double.isFinite(threshold) || threshold < 0.0d || threshold > 1.0d) threshold = 0.85d;
            List<String> whitelist = repeatSec.isList("whitelist")
                    ? repeatSec.getStringList("whitelist")
                    : ChatFilterEngine.RepeatConfig.defaultWhitelist();
            repeat = new ChatFilterEngine.RepeatConfig(
                    repeatSec.getBoolean("enabled", true),
                    windowSeconds, maxSimilar, threshold,
                    repeatSec.getBoolean("strip-flood-suffix", true),
                    whitelist);
        }

        ConfigurationSection rateSec = section.getConfigurationSection("rate-limit");
        ChatFilterEngine.RateLimitConfig rateLimit;
        if (rateSec == null) {
            rateLimit = ChatFilterEngine.RateLimitConfig.defaults();
        } else {
            long minInterval = clampLong(rateSec.getLong("min-interval-millis", 1000L), 0L, 60_000L);
            int burstMax = clampInt(rateSec.getInt("burst-max", 5), 0, 100);
            int burstWindow = clampInt(rateSec.getInt("burst-window-seconds", 10), 1, 300);
            rateLimit = new ChatFilterEngine.RateLimitConfig(
                    rateSec.getBoolean("enabled", true),
                    minInterval, burstMax, burstWindow);
        }

        return new ChatFilterEngine.Config(enabled, mode, exemptStaff, notifyStaff, audit, phrases, repeat, rateLimit);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================== Persistence ====================

    private File channelPrefsFile() {
        return new File(plugin.getDataFolder(), "chat-channels-legacy.yml");
    }

    private void loadChannelPrefs() {
        playerChannels.clear();
        File file = channelPrefsFile();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection playersSec = yaml.getConfigurationSection("players");
        if (playersSec == null) return;
        Settings s = settings;
        for (String key : playersSec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection entry = playersSec.getConfigurationSection(key);
            if (entry == null) continue;
            String write = entry.getString("write", s.defaultChannel);
            if (write == null || !s.channels.containsKey(write)) {
                write = s.defaultChannel;
            }
            PlayerChannels state = new PlayerChannels(write);
            state.reads.add(s.defaultChannel);
            state.reads.add(write);
            for (String read : entry.getStringList("reads")) {
                if (read != null && s.channels.containsKey(read)) {
                    state.reads.add(read);
                }
            }
            playerChannels.put(id, state);
        }
    }

    private void saveChannelPrefs() {
        if (playerChannels.isEmpty()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerChannels> entry : playerChannels.entrySet()) {
            String base = "players." + entry.getKey().toString();
            yaml.set(base + ".write", entry.getValue().write);
            yaml.set(base + ".reads", new ArrayList<String>(entry.getValue().reads));
        }
        try {
            LegacyCompat.saveYamlAtomically(yaml, channelPrefsFile());
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save chat-channels-legacy.yml: " + error.getMessage());
        }
    }
}
