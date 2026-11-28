package codes.castled.allium.brigadier;

import codes.castled.allium.portable.DirectMessages;
import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.NativeEconomy;
import codes.castled.allium.portable.NativeSettings;
import codes.castled.allium.portable.PlayerNameSearch;
import codes.castled.allium.portable.Spawns;
import codes.castled.allium.portable.TeleportRequests;
import codes.castled.allium.portable.UtilityMessages;
import codes.castled.allium.portable.Warps;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared Brigadier command suite for all native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3) adapters.
 *
 * <p>Every command is registered under {@code allium:<name>} first and exposed as bare aliases only
 * when the root dispatcher does not already own that literal, preserving vanilla commands.
 */
public final class NativeCommands {

    private NativeCommands() {}

    public static <S, P> void register(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform) {
        register(dispatcher, platform, new TeleportRequests(), new DirectMessages(),
                new NativeEconomy.PendingConfirmations());
    }

    public static <S, P> void register(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                       TeleportRequests teleports, DirectMessages messages) {
        register(dispatcher, platform, teleports, messages, new NativeEconomy.PendingConfirmations());
    }

    public static <S, P> void register(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                       TeleportRequests teleports, DirectMessages messages,
                                       NativeEconomy.PendingConfirmations payConfirmations) {
        SuggestionProvider<S> onlinePlayers = (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<String>();
            for (P online : platform.onlinePlayers(ctx.getSource())) {
                String n = platform.name(online);
                if (n != null && n.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    names.add(n);
                }
            }
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            for (String n : names) builder.suggest(n);
            return builder.buildFuture();
        };

        SuggestionProvider<S> warpNames = (ctx, builder) -> {
            Warps warps = new Warps(platform.dataDirectory(ctx.getSource()));
            for (String w : warps.suggestions(builder.getRemaining())) {
                builder.suggest(w);
            }
            return builder.buildFuture();
        };

        SuggestionProvider<S> homeNames = (ctx, builder) -> {
            Homes homes = homesFor(platform, teleports, ctx.getSource());
            if (homes != null) {
                for (String h : homes.suggestions(builder.getRemaining())) {
                    builder.suggest(h);
                }
            }
            return builder.buildFuture();
        };

        SuggestionProvider<S> onOff = (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase(Locale.ROOT);
            if ("on".startsWith(rem)) builder.suggest("on");
            if ("off".startsWith(rem)) builder.suggest("off");
            return builder.buildFuture();
        };

        registerHomes(dispatcher, platform, teleports, homeNames);
        registerWarps(dispatcher, platform, teleports, warpNames);
        registerSpawn(dispatcher, platform, teleports, onlinePlayers);
        registerTeleports(dispatcher, platform, teleports, onlinePlayers, onOff);
        registerSocial(dispatcher, platform, messages, onlinePlayers, onOff);
        NativeEconomyCommands.register(dispatcher, platform, payConfirmations, onlinePlayers, onOff);
        registerUtilities(dispatcher, platform, onlinePlayers);
    }

    // ==================== Homes ====================

    private static <S, P> void registerHomes(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                             TeleportRequests teleports, SuggestionProvider<S> homeNames) {
        HomeCommands.register(dispatcher, source -> homesFor(platform, teleports, source), platform::reply);

        LiteralArgumentBuilder<S> rename = LiteralArgumentBuilder.<S>literal("allium:renamehome")
                .then(RequiredArgumentBuilder.<S, String>argument("oldName", StringArgumentType.word())
                        .suggests(homeNames)
                        .then(RequiredArgumentBuilder.<S, String>argument("newName", StringArgumentType.word())
                                .executes(ctx -> {
                                    S source = ctx.getSource();
                                    Homes homes = homesFor(platform, teleports, source);
                                    if (homes == null) {
                                        platform.reply(source, Homes.PLAYERS_ONLY);
                                        return 0;
                                    }
                                    return homes.rename(
                                            StringArgumentType.getString(ctx, "oldName"),
                                            StringArgumentType.getString(ctx, "newName"),
                                            msg -> platform.reply(source, msg));
                                })));
        registerAliases(dispatcher, rename, "renamehome");
    }

    private static <S, P> Homes homesFor(NativePlatform<S, P> platform, TeleportRequests teleports, S source) {
        P player = platform.playerOrNull(source);
        if (player == null) return null;
        int limit = platform.isGamemaster(source) ? Homes.MAX_LIMIT : Homes.DEFAULT_LIMIT;
        return new Homes(
                platform.dataDirectory(source).resolve("homes"),
                platform.id(player),
                limit,
                () -> platform.positionOf(player),
                dest -> teleportWithBack(platform, teleports, source, player, dest)
        );
    }

    // ==================== Warps ====================

    private static <S, P> void registerWarps(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                             TeleportRequests teleports, SuggestionProvider<S> warpNames) {
        LiteralArgumentBuilder<S> setWarp = LiteralArgumentBuilder.<S>literal("allium:setwarp")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P player = requirePlayer(platform, source);
                            if (player == null) return 0;
                            Warps warps = new Warps(platform.dataDirectory(source));
                            return warps.set(StringArgumentType.getString(ctx, "name"),
                                    platform.positionOf(player), msg -> platform.reply(source, msg));
                        }));
        registerAliases(dispatcher, setWarp, "setwarp", "createwarp");

        LiteralArgumentBuilder<S> warp = LiteralArgumentBuilder.<S>literal("allium:warp")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    return new Warps(platform.dataDirectory(source)).list(msg -> platform.reply(source, msg));
                })
                .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                        .suggests(warpNames)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P player = requirePlayer(platform, source);
                            if (player == null) return 0;
                            Warps warps = new Warps(platform.dataDirectory(source));
                            return warps.visit(StringArgumentType.getString(ctx, "name"),
                                    dest -> teleportWithBack(platform, teleports, source, player, dest),
                                    msg -> platform.reply(source, msg));
                        }));
        registerAliases(dispatcher, warp, "warp");

        LiteralArgumentBuilder<S> delWarp = LiteralArgumentBuilder.<S>literal("allium:delwarp")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                        .suggests(warpNames)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            Warps warps = new Warps(platform.dataDirectory(source));
                            return warps.delete(StringArgumentType.getString(ctx, "name"),
                                    msg -> platform.reply(source, msg));
                        }));
        registerAliases(dispatcher, delWarp, "delwarp", "remwarp", "rmwarp");

        LiteralArgumentBuilder<S> warpsList = LiteralArgumentBuilder.<S>literal("allium:warps")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    return new Warps(platform.dataDirectory(source)).list(msg -> platform.reply(source, msg));
                });
        registerAliases(dispatcher, warpsList, "warps");
    }

    // ==================== Spawn ====================

    private static <S, P> void registerSpawn(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                             TeleportRequests teleports, SuggestionProvider<S> onlinePlayers) {
        LiteralArgumentBuilder<S> setSpawn = LiteralArgumentBuilder.<S>literal("allium:setspawn")
                .requires(platform::isGamemaster)
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    return new Spawns(platform.dataDirectory(source))
                            .set(platform.positionOf(player), msg -> platform.reply(source, msg));
                });
        registerAliases(dispatcher, setSpawn, "setspawn");

        LiteralArgumentBuilder<S> spawn = LiteralArgumentBuilder.<S>literal("allium:spawn")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    return new Spawns(platform.dataDirectory(source)).visit(
                            () -> platform.defaultSpawn(source),
                            dest -> teleportWithBack(platform, teleports, source, player, dest),
                            msg -> platform.reply(source, msg));
                })
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .requires(platform::isGamemaster)
                        .suggests(onlinePlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P target = resolvePlayer(platform, source, StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            int ok = new Spawns(platform.dataDirectory(source)).visit(
                                    () -> platform.defaultSpawn(source),
                                    dest -> teleportWithBack(platform, teleports, source, target, dest),
                                    msg -> platform.send(target, msg));
                            if (ok == 1) {
                                platform.reply(source, "Sent " + platform.name(target) + " to spawn.");
                            }
                            return ok;
                        }));
        registerAliases(dispatcher, spawn, "spawn");
    }

    // ==================== TPA, Back, TpHere, Settings ====================

    private static <S, P> void registerTeleports(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                 TeleportRequests teleports, SuggestionProvider<S> onlinePlayers,
                                                 SuggestionProvider<S> onOff) {
        LiteralArgumentBuilder<S> tpa = LiteralArgumentBuilder.<S>literal("allium:tpa")
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .executes(ctx -> handleTpa(platform, teleports, ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), false)));
        registerAliases(dispatcher, tpa, "tpa", "tpask");

        LiteralArgumentBuilder<S> tpaHere = LiteralArgumentBuilder.<S>literal("allium:tpahere")
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .executes(ctx -> handleTpa(platform, teleports, ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), true)));
        registerAliases(dispatcher, tpaHere, "tpahere");

        LiteralArgumentBuilder<S> tpAccept = LiteralArgumentBuilder.<S>literal("allium:tpaccept")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P target = requirePlayer(platform, source);
                    if (target == null) return 0;
                    TeleportRequests.Request req = teleports.consumeIncoming(platform.id(target));
                    if (req == null) {
                        platform.reply(source, "You have no pending teleport requests.");
                        return 0;
                    }
                    P from = findById(platform, source, req.sender);
                    if (from == null) {
                        platform.reply(source, "That player is no longer online.");
                        return 0;
                    }
                    if (req.here) {
                        if (!teleportWithBack(platform, teleports, source, target, platform.positionOf(from))) {
                            platform.reply(source, "Could not complete teleport.");
                            return 0;
                        }
                        platform.reply(source, "Teleported to " + platform.name(from) + ".");
                        platform.send(from, platform.name(target) + " accepted your teleport request.");
                    } else {
                        if (!teleportWithBack(platform, teleports, source, from, platform.positionOf(target))) {
                            platform.reply(source, "Could not complete teleport.");
                            return 0;
                        }
                        platform.send(from, "Teleported to " + platform.name(target) + ".");
                        platform.reply(source, "Accepted teleport request from " + platform.name(from) + ".");
                    }
                    return 1;
                });
        registerAliases(dispatcher, tpAccept, "tpaccept", "tpyes");

        LiteralArgumentBuilder<S> tpDeny = LiteralArgumentBuilder.<S>literal("allium:tpdeny")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P target = requirePlayer(platform, source);
                    if (target == null) return 0;
                    TeleportRequests.Request req = teleports.consumeIncoming(platform.id(target));
                    if (req == null) {
                        platform.reply(source, "You have no pending teleport requests.");
                        return 0;
                    }
                    platform.reply(source, "Denied teleport request.");
                    P from = findById(platform, source, req.sender);
                    if (from != null) {
                        platform.send(from, platform.name(target) + " denied your teleport request.");
                    }
                    return 1;
                });
        registerAliases(dispatcher, tpDeny, "tpdeny", "tpno");

        LiteralArgumentBuilder<S> tpCancel = LiteralArgumentBuilder.<S>literal("allium:tpacancel")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P sender = requirePlayer(platform, source);
                    if (sender == null) return 0;
                    UUID cancelledTarget = teleports.cancelOutgoing(platform.id(sender));
                    if (cancelledTarget == null) {
                        platform.reply(source, "You have no pending outgoing teleport requests.");
                        return 0;
                    }
                    platform.reply(source, "Cancelled your pending teleport request.");
                    P target = findById(platform, source, cancelledTarget);
                    if (target != null) {
                        platform.send(target, platform.name(sender) + " cancelled their teleport request.");
                    }
                    return 1;
                });
        registerAliases(dispatcher, tpCancel, "tpacancel", "tpcancel");

        LiteralArgumentBuilder<S> tpHere = LiteralArgumentBuilder.<S>literal("allium:tphere")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P staff = requirePlayer(platform, source);
                            if (staff == null) return 0;
                            P target = resolvePlayer(platform, source, StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            if (!teleportWithBack(platform, teleports, source, target, platform.positionOf(staff))) {
                                platform.reply(source, "Could not teleport " + platform.name(target) + ".");
                                return 0;
                            }
                            platform.reply(source, "Teleported " + platform.name(target) + " to you.");
                            platform.send(target, "You were teleported to " + platform.name(staff) + ".");
                            return 1;
                        }));
        registerAliases(dispatcher, tpHere, "tphere", "s");

        LiteralArgumentBuilder<S> back = LiteralArgumentBuilder.<S>literal("allium:back")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    Homes.Position previous = teleports.backOf(platform.id(player));
                    if (previous == null) {
                        platform.reply(source, "You have no previous location to return to.");
                        return 0;
                    }
                    if (!teleportWithBack(platform, teleports, source, player, previous)) {
                        platform.reply(source, "Could not teleport to your previous location.");
                        return 0;
                    }
                    platform.reply(source, "Returned to your previous location.");
                    return 1;
                });
        registerAliases(dispatcher, back, "back", "return");

        registerToggleCommand(dispatcher, platform, "tptoggle", NativeSettings.Toggle.TPA, onOff, "tptoggle");
        registerToggleCommand(dispatcher, platform, "tpauto", NativeSettings.Toggle.TP_AUTO, onOff, "tpauto");
    }

    private static <S, P> int handleTpa(NativePlatform<S, P> platform, TeleportRequests teleports,
                                        S source, String query, boolean here) {
        P from = requirePlayer(platform, source);
        if (from == null) return 0;
        P target = resolvePlayer(platform, source, query);
        if (target == null) return 0;
        UUID fromId = platform.id(from);
        UUID targetId = platform.id(target);
        if (fromId.equals(targetId)) {
            platform.reply(source, "You cannot send a teleport request to yourself.");
            return 0;
        }
        NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
        if (!platform.isGamemaster(source) && settings.isIgnoring(targetId, fromId)) {
            platform.reply(source, platform.name(target) + " is not accepting teleport requests.");
            return 0;
        }
        if (!platform.isGamemaster(source) && !settings.get(targetId, NativeSettings.Toggle.TPA)) {
            platform.reply(source, platform.name(target) + " is not accepting teleport requests.");
            return 0;
        }
        if (settings.get(targetId, NativeSettings.Toggle.TP_AUTO)) {
            if (here) {
                if (!teleportWithBack(platform, teleports, source, target, platform.positionOf(from))) {
                    platform.reply(source, "Could not complete teleport.");
                    return 0;
                }
                platform.send(target, "Auto-accepted teleport to " + platform.name(from) + ".");
                platform.reply(source, platform.name(target) + " auto-accepted your teleport request.");
            } else {
                if (!teleportWithBack(platform, teleports, source, from, platform.positionOf(target))) {
                    platform.reply(source, "Could not complete teleport.");
                    return 0;
                }
                platform.reply(source, "Teleported to " + platform.name(target) + " (auto-accepted).");
                platform.send(target, "Auto-accepted teleport request from " + platform.name(from) + ".");
            }
            return 1;
        }
        teleports.create(fromId, targetId, here);
        platform.reply(source, "Teleport request sent to " + platform.name(target) + ".");
        platform.send(target, platform.name(from) + (here
                ? " wants you to teleport to them. Use /tpaccept or /tpdeny."
                : " wants to teleport to you. Use /tpaccept or /tpdeny."));
        return 1;
    }

    // ==================== Private Messaging & Ignores ====================

    private static <S, P> void registerSocial(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                              DirectMessages messages, SuggestionProvider<S> onlinePlayers,
                                              SuggestionProvider<S> onOff) {
        LiteralArgumentBuilder<S> msg = LiteralArgumentBuilder.<S>literal("allium:msg")
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> sendPrivateMessage(platform, messages, ctx.getSource(),
                                        resolvePlayer(platform, ctx.getSource(), StringArgumentType.getString(ctx, "player")),
                                        StringArgumentType.getString(ctx, "message")))));
        registerAliases(dispatcher, msg, "msg", "pm", "m", "tell", "w");

        LiteralArgumentBuilder<S> reply = LiteralArgumentBuilder.<S>literal("allium:reply")
                .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P sender = requirePlayer(platform, source);
                            if (sender == null) return 0;
                            NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
                            boolean toLastRecipient = settings.get(platform.id(sender), NativeSettings.Toggle.REPLY_LAST_RECIPIENT);
                            UUID targetId = messages.replyTarget(platform.id(sender), toLastRecipient);
                            if (targetId == null) {
                                platform.reply(source, "You have nobody to reply to.");
                                return 0;
                            }
                            P target = findById(platform, source, targetId);
                            if (target == null) {
                                platform.reply(source, "That player is no longer online.");
                                return 0;
                            }
                            return sendPrivateMessage(platform, messages, source, target,
                                    StringArgumentType.getString(ctx, "message"));
                        }));
        registerAliases(dispatcher, reply, "reply", "r");

        LiteralArgumentBuilder<S> rtoggle = LiteralArgumentBuilder.<S>literal("allium:rtoggle")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
                    boolean next = !settings.get(platform.id(player), NativeSettings.Toggle.REPLY_LAST_RECIPIENT);
                    if (!settings.set(platform.id(player), NativeSettings.Toggle.REPLY_LAST_RECIPIENT, next)) {
                        platform.reply(source, "Your setting could not be saved.");
                        return 0;
                    }
                    platform.reply(source, next
                            ? "/reply now targets the last player you messaged."
                            : "/reply now targets the last player who messaged you.");
                    return 1;
                });
        registerAliases(dispatcher, rtoggle, "rtoggle", "replytoggle");

        registerToggleCommand(dispatcher, platform, "msgtoggle", NativeSettings.Toggle.MSG, onOff, "msgtoggle");

        LiteralArgumentBuilder<S> ignore = LiteralArgumentBuilder.<S>literal("allium:ignore")
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P player = requirePlayer(platform, source);
                            if (player == null) return 0;
                            P target = resolvePlayer(platform, source, StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            if (platform.id(player).equals(platform.id(target))) {
                                platform.reply(source, "You cannot ignore yourself.");
                                return 0;
                            }
                            NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
                            if (!settings.ignore(platform.id(player), platform.id(target), platform.name(target))) {
                                platform.reply(source, "You are already ignoring " + platform.name(target) + ".");
                                return 0;
                            }
                            platform.reply(source, "Now ignoring " + platform.name(target) + ".");
                            return 1;
                        }));
        registerAliases(dispatcher, ignore, "ignore");

        LiteralArgumentBuilder<S> unignore = LiteralArgumentBuilder.<S>literal("allium:unignore")
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P player = requirePlayer(platform, source);
                            if (player == null) return 0;
                            String query = StringArgumentType.getString(ctx, "player");
                            NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
                            UUID targetId = null;
                            String displayName = query;
                            P online = findPlayerSilently(platform, source, query);
                            if (online != null) {
                                targetId = platform.id(online);
                                displayName = platform.name(online);
                            } else {
                                for (Map.Entry<UUID, String> entry : settings.ignoredPlayers(platform.id(player)).entrySet()) {
                                    if (query.equalsIgnoreCase(entry.getValue()) || query.equalsIgnoreCase(entry.getKey().toString())) {
                                        targetId = entry.getKey();
                                        displayName = entry.getValue();
                                        break;
                                    }
                                }
                            }
                            if (targetId == null || !settings.unignore(platform.id(player), targetId)) {
                                platform.reply(source, "You are not ignoring '" + query + "'.");
                                return 0;
                            }
                            platform.reply(source, "Stopped ignoring " + displayName + ".");
                            return 1;
                        }));
        registerAliases(dispatcher, unignore, "unignore");

        LiteralArgumentBuilder<S> ignoreList = LiteralArgumentBuilder.<S>literal("allium:ignorelist")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    Map<UUID, String> ignored = new NativeSettings(platform.dataDirectory(source))
                            .ignoredPlayers(platform.id(player));
                    if (ignored.isEmpty()) {
                        platform.reply(source, "You are not ignoring anyone.");
                    } else {
                        List<String> names = new ArrayList<String>(ignored.values());
                        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                        platform.reply(source, "Ignored players: " + String.join(", ", names));
                    }
                    return 1;
                });
        registerAliases(dispatcher, ignoreList, "ignorelist", "ignored");
    }

    private static <S, P> int sendPrivateMessage(NativePlatform<S, P> platform, DirectMessages messages,
                                                 S source, P target, String body) {
        if (target == null) return 0;
        P sender = platform.playerOrNull(source);
        if (sender != null) {
            UUID senderId = platform.id(sender);
            UUID targetId = platform.id(target);
            if (senderId.equals(targetId)) {
                platform.reply(source, "You cannot message yourself.");
                return 0;
            }
            NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
            if (!platform.isGamemaster(source)) {
                if (settings.isIgnoring(targetId, senderId) || !settings.get(targetId, NativeSettings.Toggle.MSG)) {
                    platform.reply(source, platform.name(target) + " is not accepting private messages.");
                    return 0;
                }
            }
            messages.recordExchange(senderId, targetId);
        }
        String senderName = sender == null ? "Console" : platform.name(sender);
        platform.reply(source, "[You -> " + platform.name(target) + "] " + body);
        platform.send(target, "[" + senderName + " -> You] " + body);
        return 1;
    }

    // ==================== Utilities & Admin Commands ====================

    private static <S, P> void registerUtilities(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                 SuggestionProvider<S> onlinePlayers) {
        // /heal [player]
        registerPlayerAction(dispatcher, platform, onlinePlayers, "heal", true, (source, target, self) -> {
            platform.heal(target);
            if (self) {
                platform.reply(source, "You have been healed.");
            } else {
                platform.reply(source, "Healed " + platform.name(target) + ".");
                platform.send(target, "You have been healed.");
            }
        }, "heal");

        // /feed [player]
        registerPlayerAction(dispatcher, platform, onlinePlayers, "feed", true, (source, target, self) -> {
            platform.feed(target);
            if (self) {
                platform.reply(source, "Your hunger has been satisfied.");
            } else {
                platform.reply(source, "Fed " + platform.name(target) + ".");
                platform.send(target, "Your hunger has been satisfied.");
            }
        }, "feed", "eat");

        // /fly [player]
        registerPlayerAction(dispatcher, platform, onlinePlayers, "fly", true, (source, target, self) -> {
            boolean enabled = platform.toggleFly(target);
            if (self) {
                platform.reply(source, enabled ? "Flight enabled." : "Flight disabled.");
            } else {
                platform.reply(source, (enabled ? "Enabled" : "Disabled") + " flight for " + platform.name(target) + ".");
                platform.send(target, enabled ? "Flight enabled." : "Flight disabled.");
            }
        }, "fly");

        // /clearinventory [player]
        registerPlayerAction(dispatcher, platform, onlinePlayers, "clearinventory", true, (source, target, self) -> {
            platform.clearInventory(target);
            if (self) {
                platform.reply(source, "Your inventory has been cleared.");
            } else {
                platform.reply(source, "Cleared " + platform.name(target) + "'s inventory.");
                platform.send(target, "Your inventory has been cleared.");
            }
        }, "clearinventory", "ci");

        // Gamemode shortcuts: /gmc, /gms, /gma, /gmsp
        registerGamemodeShortcut(dispatcher, platform, onlinePlayers, "gmc", "creative", "gmc", "creativemode");
        registerGamemodeShortcut(dispatcher, platform, onlinePlayers, "gms", "survival", "gms", "survivalmode");
        registerGamemodeShortcut(dispatcher, platform, onlinePlayers, "gma", "adventure", "gma", "adventuremode");
        registerGamemodeShortcut(dispatcher, platform, onlinePlayers, "gmsp", "spectator", "gmsp", "spectatormode");

        // /depth
        LiteralArgumentBuilder<S> depth = LiteralArgumentBuilder.<S>literal("allium:depth")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    int y = (int) Math.floor(platform.positionOf(player).y);
                    platform.reply(source, UtilityMessages.depth(y, platform.seaLevel(player)));
                    return 1;
                });
        registerAliases(dispatcher, depth, "depth", "height");

        // /list (/who, /online)
        LiteralArgumentBuilder<S> list = LiteralArgumentBuilder.<S>literal("allium:list")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    List<String> names = new ArrayList<String>();
                    for (P online : platform.onlinePlayers(source)) {
                        names.add(platform.name(online));
                    }
                    Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                    platform.reply(source, names.isEmpty()
                            ? "Online (0): none"
                            : "Online (" + names.size() + "): " + String.join(", ", names));
                    return 1;
                });
        registerAliases(dispatcher, list, "list", "who", "online");

        // /broadcast <message>
        LiteralArgumentBuilder<S> broadcast = LiteralArgumentBuilder.<S>literal("allium:broadcast")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            platform.broadcast(ctx.getSource(),
                                    "[Broadcast] " + StringArgumentType.getString(ctx, "message"));
                            return 1;
                        }));
        registerAliases(dispatcher, broadcast, "broadcast", "bc", "bcast");

        // Time & weather shortcuts
        registerTimeShortcut(dispatcher, platform, "day", 1000L, "day");
        registerTimeShortcut(dispatcher, platform, "night", 13000L, "night");
        registerWeatherShortcut(dispatcher, platform, "sun", "clear", "sun", "clearweather");
        registerWeatherShortcut(dispatcher, platform, "rain", "rain", "rain");
        registerWeatherShortcut(dispatcher, platform, "storm", "thunder", "storm", "thunder");
    }

    private interface PlayerAction<S, P> {
        void run(S source, P target, boolean self);
    }

    private static <S, P> void registerPlayerAction(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                    SuggestionProvider<S> onlinePlayers, String name,
                                                    boolean requireGamemaster, PlayerAction<S, P> action,
                                                    String... labels) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.literal("allium:" + name);
        if (requireGamemaster) root.requires(platform::isGamemaster);
        root.executes(ctx -> {
            S source = ctx.getSource();
            P player = requirePlayer(platform, source);
            if (player == null) return 0;
            action.run(source, player, true);
            return 1;
        }).then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                .requires(platform::isGamemaster)
                .suggests(onlinePlayers)
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P target = resolvePlayer(platform, source, StringArgumentType.getString(ctx, "player"));
                    if (target == null) return 0;
                    P self = platform.playerOrNull(source);
                    boolean isSelf = self != null && platform.id(self).equals(platform.id(target));
                    action.run(source, target, isSelf);
                    return 1;
                }));
        registerAliases(dispatcher, root, labels);
    }

    private static <S, P> void registerGamemodeShortcut(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                        SuggestionProvider<S> onlinePlayers, String name,
                                                        String mode, String... labels) {
        registerPlayerAction(dispatcher, platform, onlinePlayers, name, true, (source, target, self) -> {
            if (!platform.setGameMode(target, mode)) {
                platform.reply(source, "Could not change game mode to " + mode + ".");
                return;
            }
            if (self) {
                platform.reply(source, "Game mode set to " + mode + ".");
            } else {
                platform.reply(source, "Set " + platform.name(target) + "'s game mode to " + mode + ".");
                platform.send(target, "Game mode set to " + mode + ".");
            }
        }, labels);
    }

    private static <S, P> void registerTimeShortcut(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                    String name, long ticks, String... labels) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("allium:" + name)
                .requires(platform::isGamemaster)
                .executes(ctx -> {
                    S source = ctx.getSource();
                    if (!platform.setTime(source, ticks)) {
                        platform.reply(source, "Could not change world time.");
                        return 0;
                    }
                    platform.reply(source, "Time set to " + name + ".");
                    return 1;
                });
        registerAliases(dispatcher, root, labels);
    }

    private static <S, P> void registerWeatherShortcut(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                       String name, String weatherMode, String... labels) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("allium:" + name)
                .requires(platform::isGamemaster)
                .executes(ctx -> {
                    S source = ctx.getSource();
                    if (!platform.setWeather(source, weatherMode)) {
                        platform.reply(source, "Could not change weather.");
                        return 0;
                    }
                    platform.reply(source, "Weather set to " + name + ".");
                    return 1;
                });
        registerAliases(dispatcher, root, labels);
    }

    private static <S, P> void registerToggleCommand(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                     String name, NativeSettings.Toggle toggle,
                                                     SuggestionProvider<S> onOff, String... labels) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("allium:" + name)
                .executes(ctx -> changeToggle(platform, ctx.getSource(), toggle, null))
                .then(RequiredArgumentBuilder.<S, String>argument("state", StringArgumentType.word())
                        .suggests(onOff)
                        .executes(ctx -> changeToggle(platform, ctx.getSource(), toggle,
                                StringArgumentType.getString(ctx, "state"))));
        registerAliases(dispatcher, root, labels);
    }

    private static <S, P> int changeToggle(NativePlatform<S, P> platform, S source,
                                           NativeSettings.Toggle toggle, String rawState) {
        P player = requirePlayer(platform, source);
        if (player == null) return 0;
        NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
        UUID id = platform.id(player);
        boolean next;
        if (rawState == null) {
            next = !settings.get(id, toggle);
        } else if ("on".equalsIgnoreCase(rawState)) {
            next = true;
        } else if ("off".equalsIgnoreCase(rawState)) {
            next = false;
        } else {
            platform.reply(source, "Use 'on' or 'off'.");
            return 0;
        }
        if (!settings.set(id, toggle, next)) {
            platform.reply(source, "Your setting could not be saved.");
            return 0;
        }
        platform.reply(source, toggle.title + (next ? " enabled." : " disabled."));
        return 1;
    }

    // ==================== Shared Helpers ====================

    private static <S, P> boolean teleportWithBack(NativePlatform<S, P> platform, TeleportRequests teleports,
                                                   S source, P player, Homes.Position dest) {
        Homes.Position previous = platform.positionOf(player);
        if (!platform.teleport(source, player, dest)) {
            return false;
        }
        teleports.recordBack(platform.id(player), previous);
        return true;
    }

    private static <S, P> P requirePlayer(NativePlatform<S, P> platform, S source) {
        P player = platform.playerOrNull(source);
        if (player == null) {
            platform.reply(source, "Only players can use this command.");
        }
        return player;
    }

    private static <S, P> P resolvePlayer(NativePlatform<S, P> platform, S source, String query) {
        P found = findPlayerSilently(platform, source, query);
        if (found == null) {
            platform.reply(source, "Player '" + query + "' is not online.");
        }
        return found;
    }

    private static <S, P> P findPlayerSilently(NativePlatform<S, P> platform, S source, String query) {
        if (query == null || query.isEmpty()) return null;
        Collection<P> online = platform.onlinePlayers(source);
        for (P p : online) {
            if (query.equalsIgnoreCase(platform.name(p))) {
                return p;
            }
        }
        List<P> ranked = PlayerNameSearch.rankedMatches(online, query,
                p -> true, platform::id, platform::name, platform::name);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    private static <S, P> P findById(NativePlatform<S, P> platform, S source, UUID id) {
        if (id == null) return null;
        for (P p : platform.onlinePlayers(source)) {
            if (id.equals(platform.id(p))) return p;
        }
        return null;
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
