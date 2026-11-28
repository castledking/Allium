package codes.castled.allium.brigadier;

import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.NativeConfig;
import codes.castled.allium.portable.NativeDiscordRelay;
import codes.castled.allium.portable.NativeModeration;
import codes.castled.allium.portable.NativePlayerInfo;
import codes.castled.allium.portable.PlayerNameSearch;
import codes.castled.allium.portable.Spawns;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Shared Brigadier moderation commands for native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3):
 * {@code /mute}, {@code /unmute}, {@code /setjail}, {@code /deljail}, {@code /jails},
 * {@code /jail}, {@code /unjail}, {@code /jailedplayers}, {@code /warn}, {@code /warnings},
 * {@code /notes}, {@code /unnote}, {@code /freeze}, {@code /kickall}, {@code /vanish},
 * {@code /report}, and {@code /helpop}.
 */
public final class NativeModerationCommands {
    private static final class ResolvedPlayer<P> {
        final UUID id;
        final String name;
        final P online;

        ResolvedPlayer(UUID id, String name, P online) {
            this.id = id;
            this.name = name;
            this.online = online;
        }
    }

    private NativeModerationCommands() {}

    public static <S, P> void register(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                       NativeModeration.SessionState modState,
                                       NativeDiscordRelay discord,
                                       SuggestionProvider<S> onlinePlayers) {
        SuggestionProvider<S> knownPlayers = (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase(Locale.ROOT);
            Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (P online : platform.onlinePlayers(ctx.getSource())) {
                String n = platform.name(online);
                if (n != null) names.add(n);
            }
            names.addAll(new NativePlayerInfo(platform.dataDirectory(ctx.getSource())).knownNames());
            for (String n : names) {
                if (n.toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(n);
            }
            return builder.buildFuture();
        };

        SuggestionProvider<S> jailNames = (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (String j : new NativeModeration(platform.dataDirectory(ctx.getSource())).jailNames()) {
                if (j.toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(j);
            }
            return builder.buildFuture();
        };

        registerMute(dispatcher, platform, discord, knownPlayers);
        registerJails(dispatcher, platform, discord, knownPlayers, jailNames);
        registerWarningsAndNotes(dispatcher, platform, discord, knownPlayers);
        registerFreezeKickVanish(dispatcher, platform, modState, discord, onlinePlayers);
        registerReportsAndHelpOp(dispatcher, platform, discord, onlinePlayers);
    }

    // ==================== /mute & /unmute ====================

    private static <S, P> void registerMute(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                            NativeDiscordRelay discord, SuggestionProvider<S> knownPlayers) {
        LiteralArgumentBuilder<S> mute = LiteralArgumentBuilder.<S>literal("allium:mute")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .executes(ctx -> executeMute(platform, discord, ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), null))
                        .then(RequiredArgumentBuilder.<S, String>argument("details", StringArgumentType.greedyString())
                                .executes(ctx -> executeMute(platform, discord, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "details")))));
        registerAliases(dispatcher, mute, "mute");

        LiteralArgumentBuilder<S> unmute = LiteralArgumentBuilder.<S>literal("allium:unmute")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            ResolvedPlayer<P> target = resolveKnown(platform, source,
                                    StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            NativeModeration mod = new NativeModeration(platform.dataDirectory(source));
                            if (!mod.unmute(target.id)) {
                                platform.reply(source, target.name + " is not muted.");
                                return 0;
                            }
                            String actor = actorName(platform, source);
                            platform.reply(source, "Unmuted " + target.name + ".");
                            if (target.online != null) {
                                platform.send(target.online, "You have been unmuted.");
                            }
                            if (discord != null) {
                                discord.postModeration(platform.config(source), "unmute", actor, target.name, null, null);
                            }
                            return 1;
                        }));
        registerAliases(dispatcher, unmute, "unmute");
    }

    private static <S, P> int executeMute(NativePlatform<S, P> platform, NativeDiscordRelay discord,
                                          S source, String playerQuery, String rawDetails) {
        ResolvedPlayer<P> target = resolveKnown(platform, source, playerQuery);
        if (target == null) return 0;
        long durationMs = 0L;
        String reason = "Muted by staff.";
        if (rawDetails != null && !rawDetails.trim().isEmpty()) {
            String trimmed = rawDetails.trim();
            int sp = trimmed.indexOf(' ');
            String first = sp < 0 ? trimmed : trimmed.substring(0, sp);
            long parsed = NativeModeration.parseDurationMillis(first);
            if (parsed >= 0L) {
                durationMs = parsed;
                reason = sp < 0 ? "Muted by staff." : trimmed.substring(sp + 1).trim();
                if (reason.isEmpty()) reason = "Muted by staff.";
            } else {
                reason = trimmed;
            }
        }
        String actor = actorName(platform, source);
        NativeModeration mod = new NativeModeration(platform.dataDirectory(source));
        if (!mod.mute(target.id, target.name, actor, durationMs, reason, System.currentTimeMillis())) {
            platform.reply(source, "Could not save mute.");
            return 0;
        }
        String durLabel = NativeModeration.formatDuration(durationMs);
        platform.reply(source, "Muted " + target.name + " (" + durLabel + "): " + reason);
        if (target.online != null) {
            platform.send(target.online, "You have been muted (" + durLabel + "): " + reason);
        }
        if (discord != null) {
            discord.postModeration(platform.config(source), "mute", actor, target.name, durLabel, reason);
        }
        return 1;
    }

    // ==================== /setjail, /deljail, /jails, /jail, /unjail, /jailedplayers ====================

    private static <S, P> void registerJails(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                             NativeDiscordRelay discord, SuggestionProvider<S> knownPlayers,
                                             SuggestionProvider<S> jailNames) {
        LiteralArgumentBuilder<S> setJail = LiteralArgumentBuilder.<S>literal("allium:setjail")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P player = requirePlayer(platform, source);
                            if (player == null) return 0;
                            String name = StringArgumentType.getString(ctx, "name");
                            if (!new NativeModeration(platform.dataDirectory(source)).setJail(name, platform.positionOf(player))) {
                                platform.reply(source, "Jail names may only contain letters, numbers, '_' and '-'.");
                                return 0;
                            }
                            platform.reply(source, "Saved jail '" + name + "'.");
                            return 1;
                        }));
        registerAliases(dispatcher, setJail, "setjail", "createjail");

        LiteralArgumentBuilder<S> delJail = LiteralArgumentBuilder.<S>literal("allium:deljail")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                        .suggests(jailNames)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            String name = StringArgumentType.getString(ctx, "name");
                            if (!new NativeModeration(platform.dataDirectory(source)).deleteJail(name)) {
                                platform.reply(source, "Jail '" + name + "' does not exist.");
                                return 0;
                            }
                            platform.reply(source, "Deleted jail '" + name + "'.");
                            return 1;
                        }));
        registerAliases(dispatcher, delJail, "deljail", "remjail");

        LiteralArgumentBuilder<S> jails = LiteralArgumentBuilder.<S>literal("allium:jails")
                .requires(platform::isGamemaster)
                .executes(ctx -> {
                    S source = ctx.getSource();
                    List<String> list = new NativeModeration(platform.dataDirectory(source)).jailNames();
                    platform.reply(source, list.isEmpty()
                            ? "No jails have been set."
                            : "Jails (" + list.size() + "): " + String.join(", ", list));
                    return 1;
                });
        registerAliases(dispatcher, jails, "jails");

        LiteralArgumentBuilder<S> jail = LiteralArgumentBuilder.<S>literal("allium:jail")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .then(RequiredArgumentBuilder.<S, String>argument("jail", StringArgumentType.word())
                                .suggests(jailNames)
                                .executes(ctx -> executeJail(platform, discord, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "jail"), null))
                                .then(RequiredArgumentBuilder.<S, String>argument("details", StringArgumentType.greedyString())
                                        .executes(ctx -> executeJail(platform, discord, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "jail"),
                                                StringArgumentType.getString(ctx, "details"))))));
        registerAliases(dispatcher, jail, "jail");

        LiteralArgumentBuilder<S> unjail = LiteralArgumentBuilder.<S>literal("allium:unjail")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            ResolvedPlayer<P> target = resolveKnown(platform, source,
                                    StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            NativeModeration mod = new NativeModeration(platform.dataDirectory(source));
                            NativeModeration.JailRecord rec = mod.unjail(target.id);
                            if (rec == null) {
                                platform.reply(source, target.name + " is not jailed.");
                                return 0;
                            }
                            if (target.online != null) {
                                Homes.Position dest = rec.returnPosition != null
                                        ? rec.returnPosition
                                        : new Spawns(platform.dataDirectory(source))
                                                .resolve(() -> platform.defaultSpawn(source));
                                if (dest != null) {
                                    platform.teleport(source, target.online, dest);
                                }
                                platform.send(target.online, "You have been released from jail.");
                            }
                            String actor = actorName(platform, source);
                            platform.reply(source, "Released " + target.name + " from jail.");
                            if (discord != null) {
                                discord.postModeration(platform.config(source), "unjail", actor, target.name, null, null);
                            }
                            return 1;
                        }));
        registerAliases(dispatcher, unjail, "unjail");

        LiteralArgumentBuilder<S> jailedPlayers = LiteralArgumentBuilder.<S>literal("allium:jailedplayers")
                .requires(platform::isGamemaster)
                .executes(ctx -> {
                    S source = ctx.getSource();
                    long now = System.currentTimeMillis();
                    List<NativeModeration.JailRecord> list = new NativeModeration(platform.dataDirectory(source))
                            .jailedPlayers(now);
                    if (list.isEmpty()) {
                        platform.reply(source, "No players are currently jailed.");
                        return 1;
                    }
                    platform.reply(source, "Jailed players (" + list.size() + "):");
                    for (NativeModeration.JailRecord rec : list) {
                        String rem = rec.isPermanent()
                                ? "permanent"
                                : NativePlayerInfo.formatDuration(rec.remainingMillis(now));
                        platform.reply(source, " - " + rec.playerName + " [" + rec.jailName + ", " + rem + "]: " + rec.reason);
                    }
                    return 1;
                });
        registerAliases(dispatcher, jailedPlayers, "jailedplayers");
    }

    private static <S, P> int executeJail(NativePlatform<S, P> platform, NativeDiscordRelay discord,
                                          S source, String playerQuery, String jailName, String rawDetails) {
        ResolvedPlayer<P> target = resolveKnown(platform, source, playerQuery);
        if (target == null) return 0;
        NativeModeration mod = new NativeModeration(platform.dataDirectory(source));
        Homes.Position jailPos = mod.jailPosition(jailName);
        if (jailPos == null) {
            platform.reply(source, "Jail '" + jailName + "' does not exist.");
            return 0;
        }
        long durationMs = 0L;
        String reason = "Jailed by staff.";
        if (rawDetails != null && !rawDetails.trim().isEmpty()) {
            String trimmed = rawDetails.trim();
            int sp = trimmed.indexOf(' ');
            String first = sp < 0 ? trimmed : trimmed.substring(0, sp);
            long parsed = NativeModeration.parseDurationMillis(first);
            if (parsed >= 0L) {
                durationMs = parsed;
                reason = sp < 0 ? "Jailed by staff." : trimmed.substring(sp + 1).trim();
                if (reason.isEmpty()) reason = "Jailed by staff.";
            } else {
                reason = trimmed;
            }
        }
        Homes.Position returnPos = target.online != null ? platform.positionOf(target.online) : null;
        String actor = actorName(platform, source);
        if (!mod.jail(target.id, target.name, jailName, actor, durationMs, reason, returnPos, System.currentTimeMillis())) {
            platform.reply(source, "Could not save jail record.");
            return 0;
        }
        String durLabel = NativeModeration.formatDuration(durationMs);
        if (target.online != null) {
            platform.teleport(source, target.online, jailPos);
            platform.send(target.online, "You have been jailed in '" + jailName + "' (" + durLabel + "): " + reason);
        }
        platform.reply(source, "Jailed " + target.name + " in '" + jailName + "' (" + durLabel + "): " + reason);
        if (discord != null) {
            discord.postModeration(platform.config(source), "jail", actor, target.name, durLabel,
                    "[" + jailName + "] " + reason);
        }
        return 1;
    }

    // ==================== /warn, /warnings, /notes, /unnote ====================

    private static <S, P> void registerWarningsAndNotes(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                        NativeDiscordRelay discord, SuggestionProvider<S> knownPlayers) {
        LiteralArgumentBuilder<S> warn = LiteralArgumentBuilder.<S>literal("allium:warn")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .then(RequiredArgumentBuilder.<S, String>argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    S source = ctx.getSource();
                                    ResolvedPlayer<P> target = resolveKnown(platform, source,
                                            StringArgumentType.getString(ctx, "player"));
                                    if (target == null) return 0;
                                    String reason = StringArgumentType.getString(ctx, "reason").trim();
                                    String actor = actorName(platform, source);
                                    NativeModeration mod = new NativeModeration(platform.dataDirectory(source));
                                    NativeModeration.LogEntry added = mod.addWarning(target.id, actor, reason, System.currentTimeMillis());
                                    if (added == null) {
                                        platform.reply(source, "Could not save warning.");
                                        return 0;
                                    }
                                    platform.reply(source, "Warned " + target.name + " (#" + added.id + "): " + reason);
                                    if (target.online != null) {
                                        platform.send(target.online, "You received a warning from " + actor + ": " + reason);
                                    }
                                    if (discord != null) {
                                        discord.postModeration(platform.config(source), "warn", actor, target.name, null, reason);
                                    }
                                    return 1;
                                })));
        registerAliases(dispatcher, warn, "warn");

        LiteralArgumentBuilder<S> warnings = LiteralArgumentBuilder.<S>literal("allium:warnings")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P self = requirePlayer(platform, source);
                    if (self == null) return 0;
                    return showWarnings(platform, source, platform.id(self), platform.name(self));
                })
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .requires(platform::isGamemaster)
                        .suggests(knownPlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            ResolvedPlayer<P> target = resolveKnown(platform, source,
                                    StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            return showWarnings(platform, source, target.id, target.name);
                        }));
        registerAliases(dispatcher, warnings, "warnings", "warns");

        LiteralArgumentBuilder<S> notes = LiteralArgumentBuilder.<S>literal("allium:notes")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            ResolvedPlayer<P> target = resolveKnown(platform, source,
                                    StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            List<NativeModeration.LogEntry> list = new NativeModeration(platform.dataDirectory(source))
                                    .notes(target.id);
                            if (list.isEmpty()) {
                                platform.reply(source, target.name + " has no staff notes.");
                                return 1;
                            }
                            platform.reply(source, "Staff notes for " + target.name + " (" + list.size() + "):");
                            for (NativeModeration.LogEntry e : list) {
                                platform.reply(source, " #" + e.id + " [" + formatDate(e.createdAtMillis) + "] "
                                        + e.actor + ": " + e.text);
                            }
                            return 1;
                        })
                        .then(RequiredArgumentBuilder.<S, String>argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    S source = ctx.getSource();
                                    ResolvedPlayer<P> target = resolveKnown(platform, source,
                                            StringArgumentType.getString(ctx, "player"));
                                    if (target == null) return 0;
                                    String text = StringArgumentType.getString(ctx, "note").trim();
                                    String actor = actorName(platform, source);
                                    NativeModeration.LogEntry added = new NativeModeration(platform.dataDirectory(source))
                                            .addNote(target.id, actor, text, System.currentTimeMillis());
                                    if (added == null) {
                                        platform.reply(source, "Could not save staff note.");
                                        return 0;
                                    }
                                    platform.reply(source, "Added note #" + added.id + " for " + target.name + ".");
                                    if (discord != null) {
                                        discord.postNote(platform.config(source), actor, target.name, text);
                                    }
                                    return 1;
                                })));
        registerAliases(dispatcher, notes, "notes", "note");

        LiteralArgumentBuilder<S> unnote = LiteralArgumentBuilder.<S>literal("allium:unnote")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .then(RequiredArgumentBuilder.<S, Integer>argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    S source = ctx.getSource();
                                    ResolvedPlayer<P> target = resolveKnown(platform, source,
                                            StringArgumentType.getString(ctx, "player"));
                                    if (target == null) return 0;
                                    int id = IntegerArgumentType.getInteger(ctx, "id");
                                    if (!new NativeModeration(platform.dataDirectory(source)).removeNote(target.id, id)) {
                                        platform.reply(source, "Note #" + id + " was not found for " + target.name + ".");
                                        return 0;
                                    }
                                    platform.reply(source, "Removed note #" + id + " from " + target.name + ".");
                                    return 1;
                                })));
        registerAliases(dispatcher, unnote, "unnote", "delnote");
    }

    private static <S, P> int showWarnings(NativePlatform<S, P> platform, S source, UUID targetId, String targetName) {
        List<NativeModeration.LogEntry> list = new NativeModeration(platform.dataDirectory(source)).warnings(targetId);
        if (list.isEmpty()) {
            platform.reply(source, targetName + " has no warnings.");
            return 1;
        }
        platform.reply(source, "Warnings for " + targetName + " (" + list.size() + "):");
        for (NativeModeration.LogEntry e : list) {
            platform.reply(source, " #" + e.id + " [" + formatDate(e.createdAtMillis) + "] " + e.actor + ": " + e.text);
        }
        return 1;
    }

    // ==================== /freeze, /kickall, /vanish ====================

    private static <S, P> void registerFreezeKickVanish(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                        NativeModeration.SessionState modState,
                                                        NativeDiscordRelay discord,
                                                        SuggestionProvider<S> onlinePlayers) {
        LiteralArgumentBuilder<S> freeze = LiteralArgumentBuilder.<S>literal("allium:freeze")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P target = resolveOnline(platform, source, StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            UUID targetId = platform.id(target);
                            String targetName = platform.name(target);
                            boolean frozen = modState.toggleFreeze(targetId);
                            String actor = actorName(platform, source);
                            platform.reply(source, (frozen ? "Frozen " : "Unfrozen ") + targetName + ".");
                            platform.send(target, frozen
                                    ? "You have been frozen by staff. Do not log out."
                                    : "You have been unfrozen.");
                            if (discord != null) {
                                discord.postModeration(platform.config(source),
                                        frozen ? "freeze" : "unfreeze", actor, targetName, null, null);
                            }
                            return 1;
                        }));
        registerAliases(dispatcher, freeze, "freeze", "unfreeze");

        LiteralArgumentBuilder<S> kickall = LiteralArgumentBuilder.<S>literal("allium:kickall")
                .requires(platform::isGamemaster)
                .executes(ctx -> executeKickAll(platform, discord, ctx.getSource(), "Kicked by staff."))
                .then(RequiredArgumentBuilder.<S, String>argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> executeKickAll(platform, discord, ctx.getSource(),
                                StringArgumentType.getString(ctx, "reason"))));
        registerAliases(dispatcher, kickall, "kickall");

        LiteralArgumentBuilder<S> vanish = LiteralArgumentBuilder.<S>literal("allium:vanish")
                .requires(platform::isGamemaster)
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P staff = requirePlayer(platform, source);
                    if (staff == null) return 0;
                    UUID staffId = platform.id(staff);
                    String staffName = platform.name(staff);
                    boolean vanished = modState.toggleVanish(staffId);
                    platform.reply(source, vanished
                            ? "Vanish enabled. You are now hidden from /list and /near."
                            : "Vanish disabled. You are visible again.");
                    notifyStaff(platform, source, "[Staff] " + staffName + (vanished ? " vanished." : " reappeared."), staffId);
                    return 1;
                });
        registerAliases(dispatcher, vanish, "vanish", "v");
    }

    private static <S, P> int executeKickAll(NativePlatform<S, P> platform, NativeDiscordRelay discord,
                                             S source, String reason) {
        P sender = platform.playerOrNull(source);
        UUID senderId = sender == null ? null : platform.id(sender);
        List<P> toKick = new ArrayList<P>();
        for (P online : platform.onlinePlayers(source)) {
            if (senderId != null && senderId.equals(platform.id(online))) continue;
            if (platform.isGamemasterPlayer(online)) continue;
            toKick.add(online);
        }
        String cleanReason = reason == null || reason.trim().isEmpty() ? "Kicked by staff." : reason.trim();
        for (P target : toKick) {
            platform.kick(target, cleanReason);
        }
        String actor = actorName(platform, source);
        platform.reply(source, "Kicked " + toKick.size() + " player(s): " + cleanReason);
        if (discord != null && !toKick.isEmpty()) {
            discord.postModeration(platform.config(source), "kickall", actor,
                    toKick.size() + " player(s)", null, cleanReason);
        }
        return 1;
    }

    // ==================== /report & /helpop ====================

    private static <S, P> void registerReportsAndHelpOp(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                        NativeDiscordRelay discord, SuggestionProvider<S> onlinePlayers) {
        LiteralArgumentBuilder<S> report = LiteralArgumentBuilder.<S>literal("allium:report")
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .then(RequiredArgumentBuilder.<S, String>argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    S source = ctx.getSource();
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    P onlineTarget = findOnline(platform, source, targetName);
                                    if (onlineTarget != null) targetName = platform.name(onlineTarget);
                                    String reason = StringArgumentType.getString(ctx, "reason").trim();
                                    String reporter = actorName(platform, source);
                                    NativeConfig cfg = platform.config(source);
                                    String alert = cfg.format("moderation.report-staff",
                                            "&8[&cReport&8] &f{reporter} &7reported &c{target}&8: &f{reason}",
                                            "{reporter}", reporter, "{target}", targetName, "{reason}", reason);
                                    notifyStaff(platform, source, alert, null);
                                    platform.reply(source, cfg.format("moderation.report-sent",
                                            "{prefix} &aYour report against &f{target} &ahas been sent to staff.",
                                            "{target}", targetName));
                                    if (discord != null) {
                                        discord.postReport(cfg, reporter, targetName, reason);
                                    }
                                    return 1;
                                })));
        registerAliases(dispatcher, report, "report");

        LiteralArgumentBuilder<S> helpop = LiteralArgumentBuilder.<S>literal("allium:helpop")
                .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            String message = StringArgumentType.getString(ctx, "message").trim();
                            String requester = actorName(platform, source);
                            NativeConfig cfg = platform.config(source);
                            String alert = cfg.format("moderation.helpop-staff",
                                    "&8[&eHelpOp&8] &f{player}&8: &f{message}",
                                    "{player}", requester, "{message}", message);
                            notifyStaff(platform, source, alert, null);
                            platform.reply(source, cfg.format("moderation.helpop-sent",
                                    "{prefix} &aYour help request has been sent to staff."));
                            if (discord != null) {
                                discord.postReport(cfg, requester, null, message);
                            }
                            return 1;
                        }));
        registerAliases(dispatcher, helpop, "helpop", "achelp");
    }

    // ==================== Shared Helpers ====================

    private static <S, P> void notifyStaff(NativePlatform<S, P> platform, S source, String message, UUID excludeId) {
        for (P online : platform.onlinePlayers(source)) {
            if (excludeId != null && excludeId.equals(platform.id(online))) continue;
            if (platform.isGamemasterPlayer(online)) {
                platform.send(online, message);
            }
        }
    }

    private static <S, P> String actorName(NativePlatform<S, P> platform, S source) {
        P player = platform.playerOrNull(source);
        return player == null ? "Console" : platform.name(player);
    }

    private static String formatDate(long epochMillis) {
        if (epochMillis <= 0L) return "unknown";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(epochMillis));
    }

    private static <S, P> P requirePlayer(NativePlatform<S, P> platform, S source) {
        P player = platform.playerOrNull(source);
        if (player == null) {
            platform.reply(source, "Only players can use this command.");
        }
        return player;
    }

    private static <S, P> P resolveOnline(NativePlatform<S, P> platform, S source, String query) {
        P found = findOnline(platform, source, query);
        if (found == null) {
            platform.reply(source, "Player '" + query + "' is not online.");
        }
        return found;
    }

    private static <S, P> P findOnline(NativePlatform<S, P> platform, S source, String query) {
        if (query == null || query.isEmpty()) return null;
        Collection<P> online = platform.onlinePlayers(source);
        for (P p : online) {
            if (query.equalsIgnoreCase(platform.name(p))) return p;
        }
        List<P> ranked = PlayerNameSearch.rankedMatches(online, query,
                p -> true, platform::id, platform::name, platform::name);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    private static <S, P> ResolvedPlayer<P> resolveKnown(NativePlatform<S, P> platform, S source, String query) {
        P online = findOnline(platform, source, query);
        if (online != null) {
            return new ResolvedPlayer<P>(platform.id(online), platform.name(online), online);
        }
        NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
        UUID known = info.findKnownId(query);
        if (known != null) {
            return new ResolvedPlayer<P>(known, info.nameOf(known), null);
        }
        platform.reply(source, "Player '" + query + "' was not found.");
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
