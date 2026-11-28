package codes.castled.allium.brigadier;

import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.NativeEconomyBackend;
import codes.castled.allium.portable.NativePlayerInfo;
import codes.castled.allium.portable.NativeSettings;
import codes.castled.allium.portable.PlayerNameSearch;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Shared Brigadier player-info, mail, AFK, nickname, playtime, and near commands for native
 * Fabric (1.14–26.3) and NeoForge (1.20.2–26.3):
 * {@code /mail}, {@code /seen}, {@code /whois}, {@code /playtime}, {@code /afk},
 * {@code /nick}, {@code /realname}, and {@code /near}.
 */
public final class NativeInfoCommands {
    private static final int DEFAULT_NEAR_RADIUS = 200;
    private static final int MAX_NEAR_RADIUS = 1000;

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

    private static final class NearEntry {
        final String name;
        final int distance;

        NearEntry(String name, int distance) {
            this.name = name;
            this.distance = distance;
        }
    }

    private NativeInfoCommands() {}

    public static <S, P> void register(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                       NativePlayerInfo.SessionState sessions,
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

        registerMail(dispatcher, platform, sessions, knownPlayers);
        registerSeen(dispatcher, platform, sessions, knownPlayers);
        registerWhois(dispatcher, platform, sessions, knownPlayers);
        registerPlaytime(dispatcher, platform, sessions, knownPlayers);
        registerAfk(dispatcher, platform, sessions);
        registerNickAndRealname(dispatcher, platform, sessions, onlinePlayers);
        registerNear(dispatcher, platform, sessions);
    }

    // ==================== /mail ====================

    private static <S, P> void registerMail(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                            NativePlayerInfo.SessionState sessions,
                                            SuggestionProvider<S> knownPlayers) {
        LiteralArgumentBuilder<S> mail = LiteralArgumentBuilder.<S>literal("allium:mail")
                .executes(ctx -> readMailbox(platform, sessions, ctx.getSource()))
                .then(LiteralArgumentBuilder.<S>literal("read")
                        .executes(ctx -> readMailbox(platform, sessions, ctx.getSource())))
                .then(LiteralArgumentBuilder.<S>literal("clear")
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P player = requirePlayer(platform, source);
                            if (player == null) return 0;
                            touch(platform, sessions, source, player);
                            int cleared = new NativePlayerInfo(platform.dataDirectory(source))
                                    .clearMail(platform.id(player));
                            platform.reply(source, cleared == 0
                                    ? "Your mailbox is already empty."
                                    : "Cleared " + cleared + " mail message(s).");
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<S>literal("send")
                        .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                                .suggests(knownPlayers)
                                .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> sendMailToPlayer(platform, sessions, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "message"))))))
                .then(LiteralArgumentBuilder.<S>literal("sendall")
                        .requires(platform::isGamemaster)
                        .then(RequiredArgumentBuilder.<S, String>argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> sendMailToAll(platform, sessions, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "message")))));
        registerAliases(dispatcher, mail, "mail");
    }

    private static <S, P> int readMailbox(NativePlatform<S, P> platform, NativePlayerInfo.SessionState sessions, S source) {
        P player = requirePlayer(platform, source);
        if (player == null) return 0;
        touch(platform, sessions, source, player);
        List<String> list = new NativePlayerInfo(platform.dataDirectory(source)).readMail(platform.id(player));
        if (list.isEmpty()) {
            platform.reply(source, "You have no mail.");
            return 1;
        }
        platform.reply(source, "Mail (" + list.size() + "):");
        for (int i = 0; i < list.size(); i++) {
            platform.reply(source, (i + 1) + ". " + list.get(i));
        }
        platform.reply(source, "Use /mail clear to empty your mailbox.");
        return 1;
    }

    private static <S, P> int sendMailToPlayer(NativePlatform<S, P> platform, NativePlayerInfo.SessionState sessions,
                                               S source, String targetQuery, String message) {
        P sender = platform.playerOrNull(source);
        if (sender != null) touch(platform, sessions, source, sender);
        ResolvedPlayer<P> target = resolveKnown(platform, source, targetQuery);
        if (target == null) return 0;
        if (sender != null && !platform.isGamemaster(source)) {
            NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
            if (settings.isIgnoring(target.id, platform.id(sender))) {
                platform.reply(source, target.name + " is not accepting mail from you.");
                return 0;
            }
        }
        String fromName = sender == null ? "Console" : platform.name(sender);
        NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
        if (!info.sendMail(target.id, fromName, message)) {
            platform.reply(source, target.name + "'s mailbox is full.");
            return 0;
        }
        platform.reply(source, "Sent mail to " + target.name + ".");
        if (target.online != null) {
            platform.send(target.online, "You received new mail from " + fromName + ". Use /mail read.");
        }
        return 1;
    }

    private static <S, P> int sendMailToAll(NativePlatform<S, P> platform, NativePlayerInfo.SessionState sessions,
                                            S source, String message) {
        P sender = platform.playerOrNull(source);
        String fromName = sender == null ? "Console" : platform.name(sender);
        NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
        Set<UUID> recipients = new java.util.LinkedHashSet<UUID>(info.knownIds());
        for (P online : platform.onlinePlayers(source)) {
            touch(platform, sessions, source, online);
            recipients.add(platform.id(online));
        }
        int delivered = 0;
        for (UUID id : recipients) {
            if (info.sendMail(id, fromName, message)) delivered++;
        }
        for (P online : platform.onlinePlayers(source)) {
            platform.send(online, "You received new mail from " + fromName + ". Use /mail read.");
        }
        platform.reply(source, "Sent mail to " + delivered + " player(s).");
        return 1;
    }

    // ==================== /seen ====================

    private static <S, P> void registerSeen(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                            NativePlayerInfo.SessionState sessions,
                                            SuggestionProvider<S> knownPlayers) {
        LiteralArgumentBuilder<S> seen = LiteralArgumentBuilder.<S>literal("allium:seen")
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            ResolvedPlayer<P> target = resolveKnown(platform, source,
                                    StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
                            if (target.online != null) {
                                touch(platform, sessions, source, target.online);
                                Homes.Position pos = platform.positionOf(target.online);
                                String status = sessions.isAfk(target.id) ? "Online (AFK)" : "Online";
                                platform.reply(source, target.name + " is " + status + " in " + pos.world
                                        + " (" + (int) Math.floor(pos.x) + ", " + (int) Math.floor(pos.y) + ", "
                                        + (int) Math.floor(pos.z) + ").");
                                long total = info.savedPlaytimeMillis(target.id) + sessions.currentSessionMillis(target.id);
                                platform.reply(source, "Playtime: " + NativePlayerInfo.formatDuration(total));
                            } else {
                                long lastSeen = info.lastSeenMillis(target.id);
                                String ago = lastSeen > 0L
                                        ? NativePlayerInfo.formatDuration(Math.max(0L, sessions.now() - lastSeen)) + " ago"
                                        : "unknown";
                                platform.reply(source, target.name + " is Offline (last seen " + ago + ").");
                                Homes.Position loc = info.lastLocation(target.id);
                                if (loc != null) {
                                    platform.reply(source, "Last location: " + loc.world
                                            + " (" + (int) Math.floor(loc.x) + ", " + (int) Math.floor(loc.y) + ", "
                                            + (int) Math.floor(loc.z) + ")");
                                }
                                long total = info.savedPlaytimeMillis(target.id);
                                if (total > 0L) {
                                    platform.reply(source, "Playtime: " + NativePlayerInfo.formatDuration(total));
                                }
                            }
                            return 1;
                        }));
        registerAliases(dispatcher, seen, "seen");
    }

    // ==================== /whois ====================

    private static <S, P> void registerWhois(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                             NativePlayerInfo.SessionState sessions,
                                             SuggestionProvider<S> knownPlayers) {
        LiteralArgumentBuilder<S> whois = LiteralArgumentBuilder.<S>literal("allium:whois")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            ResolvedPlayer<P> target = resolveKnown(platform, source,
                                    StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
                            NativeEconomyBackend eco = platform.economyBackend(source);
                            String nick = info.nicknameOf(target.id);
                            platform.reply(source, "=== Whois: " + target.name + " ===");
                            platform.reply(source, "UUID: " + target.id);
                            if (nick != null) {
                                platform.reply(source, "Nickname: " + NativePlayerInfo.translateColorCodes(nick));
                            }
                            platform.reply(source, "Balance: " + eco.formatMoney(eco.balance(target.id))
                                    + " (" + eco.providerName() + ")");
                            if (target.online != null) {
                                touch(platform, sessions, source, target.online);
                                Homes.Position pos = platform.positionOf(target.online);
                                platform.reply(source, "Status: Online"
                                        + (sessions.isAfk(target.id) ? " (AFK)" : "")
                                        + " | Ping: " + platform.ping(target.online) + " ms");
                                platform.reply(source, "Location: " + pos.world + " ("
                                        + (int) Math.floor(pos.x) + ", " + (int) Math.floor(pos.y) + ", "
                                        + (int) Math.floor(pos.z) + ")");
                            } else {
                                long lastSeen = info.lastSeenMillis(target.id);
                                String ago = lastSeen > 0L
                                        ? NativePlayerInfo.formatDuration(Math.max(0L, sessions.now() - lastSeen)) + " ago"
                                        : "unknown";
                                platform.reply(source, "Status: Offline (last seen " + ago + ")");
                                Homes.Position loc = info.lastLocation(target.id);
                                if (loc != null) {
                                    platform.reply(source, "Last location: " + loc.world + " ("
                                            + (int) Math.floor(loc.x) + ", " + (int) Math.floor(loc.y) + ", "
                                            + (int) Math.floor(loc.z) + ")");
                                }
                            }
                            long playtime = info.savedPlaytimeMillis(target.id)
                                    + (target.online != null ? sessions.currentSessionMillis(target.id) : 0L);
                            platform.reply(source, "Playtime: " + NativePlayerInfo.formatDuration(playtime)
                                    + " | Mail: " + info.readMail(target.id).size());
                            return 1;
                        }));
        registerAliases(dispatcher, whois, "whois");
    }

    // ==================== /playtime ====================

    private static <S, P> void registerPlaytime(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                NativePlayerInfo.SessionState sessions,
                                                SuggestionProvider<S> knownPlayers) {
        LiteralArgumentBuilder<S> playtime = LiteralArgumentBuilder.<S>literal("allium:playtime")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    touch(platform, sessions, source, player);
                    NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
                    long total = info.savedPlaytimeMillis(platform.id(player))
                            + sessions.currentSessionMillis(platform.id(player));
                    platform.reply(source, "Playtime: " + NativePlayerInfo.formatDuration(total));
                    return 1;
                })
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            ResolvedPlayer<P> target = resolveKnown(platform, source,
                                    StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
                            if (target.online != null) touch(platform, sessions, source, target.online);
                            long total = info.savedPlaytimeMillis(target.id)
                                    + (target.online != null ? sessions.currentSessionMillis(target.id) : 0L);
                            platform.reply(source, target.name + "'s playtime: "
                                    + NativePlayerInfo.formatDuration(total));
                            return 1;
                        }));
        registerAliases(dispatcher, playtime, "playtime", "pt");
    }

    // ==================== /afk ====================

    private static <S, P> void registerAfk(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                           NativePlayerInfo.SessionState sessions) {
        LiteralArgumentBuilder<S> afk = LiteralArgumentBuilder.<S>literal("allium:afk")
                .executes(ctx -> toggleAfk(platform, sessions, ctx.getSource(), null))
                .then(RequiredArgumentBuilder.<S, String>argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> toggleAfk(platform, sessions, ctx.getSource(),
                                StringArgumentType.getString(ctx, "reason"))));
        registerAliases(dispatcher, afk, "afk", "away");
    }

    private static <S, P> int toggleAfk(NativePlatform<S, P> platform, NativePlayerInfo.SessionState sessions,
                                        S source, String reason) {
        P player = requirePlayer(platform, source);
        if (player == null) return 0;
        touch(platform, sessions, source, player);
        boolean nowAfk = sessions.toggleAfk(platform.id(player), reason);
        String name = platform.name(player);
        if (nowAfk) {
            String trimmed = reason == null ? "" : reason.trim();
            platform.broadcast(source, trimmed.isEmpty()
                    ? name + " is now AFK."
                    : name + " is now AFK: " + trimmed);
        } else {
            platform.broadcast(source, name + " is no longer AFK.");
        }
        return 1;
    }

    // ==================== /nick & /realname ====================

    private static <S, P> void registerNickAndRealname(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                       NativePlayerInfo.SessionState sessions,
                                                       SuggestionProvider<S> onlinePlayers) {
        LiteralArgumentBuilder<S> nick = LiteralArgumentBuilder.<S>literal("allium:nick")
                .then(RequiredArgumentBuilder.<S, String>argument("nickname", StringArgumentType.word())
                        .executes(ctx -> applyNick(platform, sessions, ctx.getSource(),
                                StringArgumentType.getString(ctx, "nickname"), null))
                        .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                                .requires(platform::isGamemaster)
                                .suggests(onlinePlayers)
                                .executes(ctx -> applyNick(platform, sessions, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "nickname"),
                                        StringArgumentType.getString(ctx, "player")))));
        registerAliases(dispatcher, nick, "nick", "nickname");

        LiteralArgumentBuilder<S> realname = LiteralArgumentBuilder.<S>literal("allium:realname")
                .then(RequiredArgumentBuilder.<S, String>argument("query", StringArgumentType.word())
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            String query = NativePlayerInfo.stripColorCodes(StringArgumentType.getString(ctx, "query"))
                                    .toLowerCase(Locale.ROOT);
                            NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
                            List<String> matches = new ArrayList<String>();
                            for (P online : platform.onlinePlayers(source)) {
                                UUID id = platform.id(online);
                                String real = platform.name(online);
                                String n = info.nicknameOf(id);
                                String plainNick = n == null ? "" : NativePlayerInfo.stripColorCodes(n).toLowerCase(Locale.ROOT);
                                if ((!plainNick.isEmpty() && plainNick.contains(query))
                                        || real.toLowerCase(Locale.ROOT).contains(query)) {
                                    matches.add((n != null ? NativePlayerInfo.translateColorCodes(n) + " -> " : "") + real);
                                }
                            }
                            if (matches.isEmpty()) {
                                platform.reply(source, "No online player matches '" + query + "'.");
                                return 0;
                            }
                            platform.reply(source, "Matches: " + String.join(", ", matches));
                            return 1;
                        }));
        registerAliases(dispatcher, realname, "realname");
    }

    private static <S, P> int applyNick(NativePlatform<S, P> platform, NativePlayerInfo.SessionState sessions,
                                        S source, String rawNick, String targetName) {
        P target;
        if (targetName != null) {
            target = resolveOnline(platform, source, targetName);
            if (target == null) return 0;
        } else {
            target = requirePlayer(platform, source);
            if (target == null) return 0;
        }
        touch(platform, sessions, source, target);
        NativePlayerInfo info = new NativePlayerInfo(platform.dataDirectory(source));
        if ("off".equalsIgnoreCase(rawNick) || "reset".equalsIgnoreCase(rawNick)) {
            info.setNickname(platform.id(target), platform.name(target), null);
            platform.reply(source, "Reset nickname for " + platform.name(target) + ".");
            return 1;
        }
        String plain = NativePlayerInfo.stripColorCodes(rawNick).trim();
        if (plain.isEmpty() || plain.length() > 32) {
            platform.reply(source, "Nickname must be 1-32 visible characters.");
            return 0;
        }
        if (!info.setNickname(platform.id(target), platform.name(target), rawNick)) {
            platform.reply(source, "Could not save nickname.");
            return 0;
        }
        platform.reply(source, "Set nickname for " + platform.name(target) + " to "
                + NativePlayerInfo.translateColorCodes(rawNick) + ".");
        return 1;
    }

    // ==================== /near ====================

    private static <S, P> void registerNear(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                            NativePlayerInfo.SessionState sessions) {
        LiteralArgumentBuilder<S> near = LiteralArgumentBuilder.<S>literal("allium:near")
                .executes(ctx -> findNear(platform, sessions, ctx.getSource(), DEFAULT_NEAR_RADIUS))
                .then(RequiredArgumentBuilder.<S, Integer>argument("radius", IntegerArgumentType.integer(1, MAX_NEAR_RADIUS))
                        .executes(ctx -> findNear(platform, sessions, ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "radius"))));
        registerAliases(dispatcher, near, "near", "nearby");
    }

    private static <S, P> int findNear(NativePlatform<S, P> platform, NativePlayerInfo.SessionState sessions,
                                       S source, int radius) {
        P viewer = requirePlayer(platform, source);
        if (viewer == null) return 0;
        touch(platform, sessions, source, viewer);
        UUID viewerId = platform.id(viewer);
        Homes.Position origin = platform.positionOf(viewer);
        double maxDistSq = (double) radius * (double) radius;
        List<NearEntry> found = new ArrayList<NearEntry>();
        for (P other : platform.onlinePlayers(source)) {
            if (viewerId.equals(platform.id(other))) continue;
            Homes.Position pos = platform.positionOf(other);
            if (!origin.world.equals(pos.world)) continue;
            double dx = pos.x - origin.x;
            double dy = pos.y - origin.y;
            double dz = pos.z - origin.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= maxDistSq) {
                found.add(new NearEntry(platform.name(other), (int) Math.round(Math.sqrt(distSq))));
            }
        }
        Collections.sort(found, new Comparator<NearEntry>() {
            @Override
            public int compare(NearEntry a, NearEntry b) {
                int c = Integer.compare(a.distance, b.distance);
                return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
            }
        });
        if (found.isEmpty()) {
            platform.reply(source, "No players within " + radius + " blocks.");
            return 1;
        }
        StringBuilder sb = new StringBuilder("Nearby players (").append(radius).append("m): ");
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) sb.append(", ");
            NearEntry e = found.get(i);
            sb.append(e.name).append(" (").append(e.distance).append("m)");
        }
        platform.reply(source, sb.toString());
        return 1;
    }

    // ==================== Shared Helpers ====================

    private static <S, P> void touch(NativePlatform<S, P> platform, NativePlayerInfo.SessionState sessions,
                                     S source, P player) {
        UUID id = platform.id(player);
        sessions.touchSession(id);
        new NativePlayerInfo(platform.dataDirectory(source))
                .recordPresence(id, platform.name(player), platform.positionOf(player), sessions.now());
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
