package codes.castled.allium.brigadier;

import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.NativeChatChannels;
import codes.castled.allium.portable.NativeConfig;
import codes.castled.allium.portable.NativeDiscordRelay;
import codes.castled.allium.portable.NativeModeration;
import codes.castled.allium.portable.NativePlayerInfo;
import codes.castled.allium.portable.NativeSettings;
import codes.castled.allium.portable.NativeTextPages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared Brigadier chat channel, staff chat, action, and live chat/lifecycle routing for native
 * Fabric (1.14–26.3) and NeoForge (1.20.2–26.3):
 * {@code /channel} ({@code /ch}), {@code /g}, {@code /l}, {@code /a} ({@code /ac}, {@code /amsg},
 * {@code /staffchat}), and {@code /me} ({@code /action}).
 */
public final class NativeChatCommands {
    private NativeChatCommands() {}

    public static <S, P> void register(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                       NativePlayerInfo.SessionState playerSessions,
                                       NativeModeration.SessionState modState,
                                       NativeDiscordRelay discord) {
        SuggestionProvider<S> channelNames = (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase(Locale.ROOT);
            if ("global".startsWith(rem)) builder.suggest("global");
            if ("local".startsWith(rem)) builder.suggest("local");
            if (platform.isGamemaster(ctx.getSource()) && "staff".startsWith(rem)) builder.suggest("staff");
            return builder.buildFuture();
        };

        // /channel [global|local|staff]
        LiteralArgumentBuilder<S> channel = LiteralArgumentBuilder.<S>literal("allium:channel")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    NativeConfig cfg = platform.config(source);
                    NativeChatChannels.Channel active = new NativeChatChannels(platform.dataDirectory(source))
                            .activeChannel(platform.id(player), cfg);
                    platform.reply(source, "Active chat channel: " + active.title
                            + " (switch with /channel global|local"
                            + (platform.isGamemaster(source) ? "|staff" : "") + ").");
                    return 1;
                })
                .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                        .suggests(channelNames)
                        .executes(ctx -> switchChannel(platform, ctx.getSource(),
                                NativeChatChannels.Channel.fromName(StringArgumentType.getString(ctx, "name")))));
        registerAliases(dispatcher, channel, "channel", "ch");

        // /g [message]
        LiteralArgumentBuilder<S> global = LiteralArgumentBuilder.<S>literal("allium:g")
                .executes(ctx -> switchChannel(platform, ctx.getSource(), NativeChatChannels.Channel.GLOBAL))
                .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> sendChannelCommand(platform, playerSessions, discord, ctx.getSource(),
                                NativeChatChannels.Channel.GLOBAL, StringArgumentType.getString(ctx, "message"))));
        registerAliases(dispatcher, global, "g", "global", "shout");

        // /l [message]
        LiteralArgumentBuilder<S> local = LiteralArgumentBuilder.<S>literal("allium:l")
                .executes(ctx -> switchChannel(platform, ctx.getSource(), NativeChatChannels.Channel.LOCAL))
                .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> sendChannelCommand(platform, playerSessions, discord, ctx.getSource(),
                                NativeChatChannels.Channel.LOCAL, StringArgumentType.getString(ctx, "message"))));
        registerAliases(dispatcher, local, "l", "local");

        // /a [message] (staff chat)
        LiteralArgumentBuilder<S> staff = LiteralArgumentBuilder.<S>literal("allium:a")
                .requires(platform::isGamemaster)
                .executes(ctx -> toggleStaffChannel(platform, ctx.getSource()))
                .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> sendChannelCommand(platform, playerSessions, discord, ctx.getSource(),
                                NativeChatChannels.Channel.STAFF, StringArgumentType.getString(ctx, "message"))));
        registerAliases(dispatcher, staff, "a", "ac", "amsg", "staffchat");

        // /me <action>
        LiteralArgumentBuilder<S> me = LiteralArgumentBuilder.<S>literal("allium:me")
                .then(RequiredArgumentBuilder.<S, String>argument("action", StringArgumentType.greedyString())
                        .executes(ctx -> sendAction(platform, playerSessions, discord, ctx.getSource(),
                                StringArgumentType.getString(ctx, "action"))));
        registerAliases(dispatcher, me, "me", "action");
    }

    /**
     * Handles a live chat message from Fabric ({@code ServerMessageEvents.ALLOW_CHAT_MESSAGE}) or
     * NeoForge ({@code ServerChatEvent}).
     *
     * @return {@code true} if Allium handled or blocked the message (caller should cancel vanilla broadcast).
     */
    public static <S, P> boolean handleChat(NativePlatform<S, P> platform, S source, P player,
                                            String rawMessage,
                                            NativePlayerInfo.SessionState playerSessions,
                                            NativeModeration.SessionState modState,
                                            NativeDiscordRelay discord) {
        if (player == null || rawMessage == null) return false;
        Path dir = platform.dataDirectory(source);
        UUID playerId = platform.id(player);
        String playerName = platform.name(player);
        long now = playerSessions != null ? playerSessions.now() : System.currentTimeMillis();

        if (playerSessions != null) {
            playerSessions.touchSession(playerId);
            if (playerSessions.isAfk(playerId)) {
                playerSessions.clearAfk(playerId);
                platform.broadcast(source, playerName + " is no longer AFK.");
            }
        }

        // Mute check
        NativeModeration.MuteRecord mute = new NativeModeration(dir).activeMute(playerId, now);
        if (mute != null) {
            String rem = mute.isPermanent() ? "permanent"
                    : NativePlayerInfo.formatDuration(mute.remainingMillis(now));
            platform.send(player, "You are muted (" + rem + "): " + mute.reason);
            return true;
        }

        NativeConfig cfg = platform.config(source);
        boolean isStaff = platform.isGamemasterPlayer(player);
        boolean channelsEnabled = cfg.raw().getBoolean("chat.channels.enabled", true);
        String displayName = displayNameOf(dir, playerId, playerName);

        if (!channelsEnabled) {
            if (isStaff && rawMessage.startsWith("#") && rawMessage.length() > 1
                    && !rawMessage.substring(1).trim().isEmpty()) {
                NativeChatChannels.RoutedMessage routed = new NativeChatChannels.RoutedMessage(
                        NativeChatChannels.Channel.STAFF, rawMessage.substring(1).trim(), false);
                deliverRouted(platform, source, player, playerId, playerName, displayName, isStaff, cfg, routed, discord);
                return true;
            }
            if (discord != null) {
                discord.postChat(cfg, "global", playerId, playerName, displayName, rawMessage);
            }
            return false;
        }

        NativeChatChannels.Channel active = new NativeChatChannels(dir).activeChannel(playerId, cfg);
        NativeChatChannels.RoutedMessage routed = NativeChatChannels.resolveOutgoing(active, isStaff, cfg, rawMessage);
        deliverRouted(platform, source, player, playerId, playerName, displayName, isStaff, cfg, routed, discord);
        return true;
    }

    /** Lifecycle hook called when a player joins the server. */
    public static <S, P> void handleJoin(NativePlatform<S, P> platform, S source, P player,
                                         NativePlayerInfo.SessionState playerSessions,
                                         NativeModeration.SessionState modState,
                                         NativeDiscordRelay discord) {
        if (player == null) return;
        Path dir = platform.dataDirectory(source);
        UUID playerId = platform.id(player);
        String playerName = platform.name(player);
        long now = playerSessions != null ? playerSessions.now() : System.currentTimeMillis();

        if (playerSessions != null) {
            playerSessions.touchSession(playerId);
        }
        NativePlayerInfo info = new NativePlayerInfo(dir);
        info.recordPresence(playerId, playerName, platform.positionOf(player), now);

        NativeConfig cfg = platform.config(source);
        // Re-enforce active jail on login
        NativeModeration mod = new NativeModeration(dir);
        NativeModeration.JailRecord jail = mod.activeJail(playerId, now);
        if (jail != null) {
            Homes.Position jailPos = mod.jailPosition(jail.jailName);
            if (jailPos != null) {
                platform.teleport(source, player, jailPos);
            }
            String rem = jail.isPermanent() ? "permanent"
                    : NativePlayerInfo.formatDuration(jail.remainingMillis(now));
            platform.send(player, "You are jailed in '" + jail.jailName + "' (" + rem + "): " + jail.reason);
        }

        // Join MOTD
        if (cfg.raw().getBoolean("motd.show-on-join", true)) {
            NativeTextPages.Document doc = new NativeTextPages(dir).read("motd.txt", playerName);
            if (doc != null && !doc.index().isEmpty()) {
                String display = displayNameOf(dir, playerId, playerName);
                int online = platform.onlinePlayers(source).size();
                int max = platform.maxPlayers(source);
                String serverName = platform.serverName(source);
                for (String line : doc.index()) {
                    platform.send(player, NativeTextPages.renderLine(line, playerName, display, online, max, serverName));
                }
            }
        }

        // Unread mail reminder
        int unread = info.readMail(playerId).size();
        if (unread > 0) {
            platform.send(player, "You have " + unread + " mail message(s). Use /mail read.");
        }

        // Discord join notice
        boolean vanished = modState != null && modState.isVanished(playerId);
        if (discord != null && !vanished && cfg.raw().getBoolean("join-and-quit-messages.join.enabled", true)) {
            discord.postLifecycle(cfg, playerName + " joined the game.");
        }
    }

    /** Lifecycle hook called when a player disconnects from the server. */
    public static <S, P> void handleQuit(NativePlatform<S, P> platform, S source, P player,
                                         NativePlayerInfo.SessionState playerSessions,
                                         NativeModeration.SessionState modState,
                                         NativeDiscordRelay discord) {
        if (player == null) return;
        Path dir = platform.dataDirectory(source);
        UUID playerId = platform.id(player);
        String playerName = platform.name(player);
        long now = playerSessions != null ? playerSessions.now() : System.currentTimeMillis();

        NativePlayerInfo info = new NativePlayerInfo(dir);
        info.recordPresence(playerId, playerName, platform.positionOf(player), now);
        if (playerSessions != null) {
            long elapsed = playerSessions.finishSession(playerId);
            if (elapsed > 0L) {
                info.addPlaytime(playerId, elapsed);
            }
        }

        NativeConfig cfg = platform.config(source);
        boolean vanished = modState != null && modState.isVanished(playerId);
        if (discord != null && !vanished && cfg.raw().getBoolean("join-and-quit-messages.quit.enabled", true)) {
            discord.postLifecycle(cfg, playerName + " left the game.");
        }
    }

    // ==================== Internal Helpers ====================

    private static <S, P> int switchChannel(NativePlatform<S, P> platform, S source, NativeChatChannels.Channel target) {
        P player = requirePlayer(platform, source);
        if (player == null) return 0;
        if (target == null) {
            platform.reply(source, "Unknown channel. Use global, local, or staff.");
            return 0;
        }
        if (target == NativeChatChannels.Channel.STAFF && !platform.isGamemaster(source)) {
            platform.reply(source, "You do not have permission to join staff chat.");
            return 0;
        }
        new NativeChatChannels(platform.dataDirectory(source)).setActiveChannel(platform.id(player), target);
        platform.reply(source, "Switched chat channel to " + target.title + ".");
        return 1;
    }

    private static <S, P> int toggleStaffChannel(NativePlatform<S, P> platform, S source) {
        P player = requirePlayer(platform, source);
        if (player == null) return 0;
        NativeConfig cfg = platform.config(source);
        NativeChatChannels channels = new NativeChatChannels(platform.dataDirectory(source));
        NativeChatChannels.Channel current = channels.activeChannel(platform.id(player), cfg);
        NativeChatChannels.Channel next = current == NativeChatChannels.Channel.STAFF
                ? NativeChatChannels.defaultChannel(cfg)
                : NativeChatChannels.Channel.STAFF;
        channels.setActiveChannel(platform.id(player), next);
        platform.reply(source, next == NativeChatChannels.Channel.STAFF
                ? "Staff chat toggled ON."
                : "Staff chat toggled OFF (now in " + next.title + ").");
        return 1;
    }

    private static <S, P> int sendChannelCommand(NativePlatform<S, P> platform,
                                                 NativePlayerInfo.SessionState playerSessions,
                                                 NativeDiscordRelay discord, S source,
                                                 NativeChatChannels.Channel channel, String message) {
        P sender = platform.playerOrNull(source);
        Path dir = platform.dataDirectory(source);
        NativeConfig cfg = platform.config(source);
        long now = playerSessions != null ? playerSessions.now() : System.currentTimeMillis();

        if (sender != null && channel != NativeChatChannels.Channel.STAFF) {
            NativeModeration.MuteRecord mute = new NativeModeration(dir).activeMute(platform.id(sender), now);
            if (mute != null) {
                String rem = mute.isPermanent() ? "permanent"
                        : NativePlayerInfo.formatDuration(mute.remainingMillis(now));
                platform.reply(source, "You are muted (" + rem + "): " + mute.reason);
                return 0;
            }
        }

        UUID senderId = sender == null ? null : platform.id(sender);
        String senderName = sender == null ? "Console" : platform.name(sender);
        String displayName = sender == null ? "Console" : displayNameOf(dir, senderId, senderName);
        boolean isStaff = sender == null || platform.isGamemasterPlayer(sender);
        NativeChatChannels.RoutedMessage routed = new NativeChatChannels.RoutedMessage(channel, message, false);
        deliverRouted(platform, source, sender, senderId, senderName, displayName, isStaff, cfg, routed, discord);
        if (sender == null) {
            platform.reply(source, NativeChatChannels.formatChat(cfg, routed, senderName, displayName, true));
        }
        return 1;
    }

    private static <S, P> int sendAction(NativePlatform<S, P> platform,
                                         NativePlayerInfo.SessionState playerSessions,
                                         NativeDiscordRelay discord, S source, String action) {
        P sender = platform.playerOrNull(source);
        Path dir = platform.dataDirectory(source);
        long now = playerSessions != null ? playerSessions.now() : System.currentTimeMillis();
        if (sender != null) {
            NativeModeration.MuteRecord mute = new NativeModeration(dir).activeMute(platform.id(sender), now);
            if (mute != null) {
                String rem = mute.isPermanent() ? "permanent"
                        : NativePlayerInfo.formatDuration(mute.remainingMillis(now));
                platform.reply(source, "You are muted (" + rem + "): " + mute.reason);
                return 0;
            }
        }
        UUID senderId = sender == null ? null : platform.id(sender);
        String senderName = sender == null ? "Console" : platform.name(sender);
        String displayName = sender == null ? "Console" : displayNameOf(dir, senderId, senderName);
        boolean isStaff = sender == null || platform.isGamemasterPlayer(sender);
        NativeConfig cfg = platform.config(source);
        String formatted = NativeChatChannels.formatAction(cfg, senderName, displayName, action, isStaff);
        NativeSettings settings = new NativeSettings(dir);
        for (P recipient : platform.onlinePlayers(source)) {
            if (senderId != null && !isStaff && settings.isIgnoring(platform.id(recipient), senderId)) {
                continue;
            }
            platform.send(recipient, formatted);
        }
        if (discord != null) {
            discord.postChat(cfg, "global", senderId, senderName, displayName, "* " + action);
        }
        return 1;
    }

    private static <S, P> void deliverRouted(NativePlatform<S, P> platform, S source, P sender,
                                             UUID senderId, String senderName, String displayName,
                                             boolean isStaff, NativeConfig cfg,
                                             NativeChatChannels.RoutedMessage routed,
                                             NativeDiscordRelay discord) {
        String formatted = NativeChatChannels.formatChat(cfg, routed, senderName, displayName, isStaff);
        Path dir = platform.dataDirectory(source);

        if (routed.channel == NativeChatChannels.Channel.STAFF) {
            for (P online : platform.onlinePlayers(source)) {
                if (platform.isGamemasterPlayer(online) || (senderId != null && senderId.equals(platform.id(online)))) {
                    platform.send(online, formatted);
                }
            }
            if (discord != null) {
                discord.postChat(cfg, "staff", senderId, senderName, displayName, routed.message);
            }
            return;
        }

        NativeSettings settings = new NativeSettings(dir);
        if (routed.channel == NativeChatChannels.Channel.LOCAL && sender != null) {
            Homes.Position origin = platform.positionOf(sender);
            int radius = NativeChatChannels.localRadius(cfg);
            double maxSq = (double) radius * (double) radius;
            int heard = 0;
            for (P recipient : platform.onlinePlayers(source)) {
                UUID recId = platform.id(recipient);
                boolean isSelf = senderId != null && senderId.equals(recId);
                if (!isSelf) {
                    if (!isStaff && settings.isIgnoring(recId, senderId)) continue;
                    Homes.Position pos = platform.positionOf(recipient);
                    if (!origin.world.equals(pos.world)) continue;
                    double dx = pos.x - origin.x;
                    double dy = pos.y - origin.y;
                    double dz = pos.z - origin.z;
                    if (dx * dx + dy * dy + dz * dz > maxSq) continue;
                    heard++;
                }
                platform.send(recipient, formatted);
            }
            if (heard == 0) {
                platform.send(sender, "No players are nearby to hear local chat (use /g or prefix with '>').");
            }
            if (discord != null) {
                discord.postChat(cfg, "local", senderId, senderName, displayName, routed.message);
            }
            return;
        }

        for (P recipient : platform.onlinePlayers(source)) {
            UUID recId = platform.id(recipient);
            if (senderId != null && !senderId.equals(recId) && !isStaff && settings.isIgnoring(recId, senderId)) {
                continue;
            }
            platform.send(recipient, formatted);
        }
        if (discord != null) {
            discord.postChat(cfg, "global", senderId, senderName, displayName, routed.message);
        }
    }

    private static String displayNameOf(Path dataDir, UUID playerId, String realName) {
        if (playerId == null) return realName;
        String nick = new NativePlayerInfo(dataDir).nicknameOf(playerId);
        return nick != null ? NativePlayerInfo.translateColorCodes(nick) : realName;
    }

    private static <S, P> P requirePlayer(NativePlatform<S, P> platform, S source) {
        P player = platform.playerOrNull(source);
        if (player == null) {
            platform.reply(source, "Only players can use this command.");
        }
        return player;
    }

    private static <S> void registerAliases(CommandDispatcher<S> dispatcher, LiteralArgumentBuilder<S> root,
                                            String... labels) {
        LiteralCommandNode<S> node = dispatcher.register(root);
        for (String label : labels) {
            for (String alias : new String[]{label, "allium:" + label}) {
                if (dispatcher.getRoot().getChild(alias) == null) {
                    dispatcher.register(LiteralArgumentBuilder.<S>literal(alias)
                            .requires(node.getRequirement())
                            .executes(node.getCommand())
                            .redirect(node));
                }
            }
        }
    }
}
