package codes.castled.allium.brigadier;

import codes.castled.allium.portable.NativePlayerInfo;
import codes.castled.allium.portable.NativeTextPages;
import codes.castled.allium.portable.PlayerNameSearch;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Shared Brigadier text page commands for native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3):
 * {@code /info}, {@code /rules}, {@code /motd}, {@code /customtext}, {@code /inform}, and
 * {@code /broadcasts}.
 */
public final class NativeTextCommands {
    private NativeTextCommands() {}

    public static <S, P> void register(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                       NativeTextPages.BroadcastState broadcastState,
                                       SuggestionProvider<S> onlinePlayers) {
        registerPageCommand(dispatcher, platform, "info", "info.txt", "info");
        registerPageCommand(dispatcher, platform, "rules", "rules.txt", "rules");
        registerPageCommand(dispatcher, platform, "motd", "motd.txt", "motd");
        registerPageCommand(dispatcher, platform, "customtext", "custom.txt", "customtext", "ctext");

        SuggestionProvider<S> infoSections = (ctx, builder) -> {
            String targetName = null;
            try {
                targetName = StringArgumentType.getString(ctx, "player");
            } catch (IllegalArgumentException ignored) {
            }
            NativeTextPages pages = new NativeTextPages(platform.dataDirectory(ctx.getSource()));
            NativeTextPages.Document doc = pages.read("info.txt", targetName);
            suggestSections(doc, builder.getRemaining(), builder);
            return builder.buildFuture();
        };

        LiteralArgumentBuilder<S> inform = LiteralArgumentBuilder.<S>literal("allium:inform")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .executes(ctx -> executeInform(platform, ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"), null, 1))
                        .then(RequiredArgumentBuilder.<S, String>argument("sectionOrPage", StringArgumentType.word())
                                .suggests(infoSections)
                                .executes(ctx -> executeInform(platform, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "sectionOrPage"), 1))
                                .then(RequiredArgumentBuilder.<S, Integer>argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> executeInform(platform, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "sectionOrPage"),
                                                IntegerArgumentType.getInteger(ctx, "page"))))));
        registerAliases(dispatcher, inform, "inform");

        SuggestionProvider<S> broadcastSections = (ctx, builder) -> {
            NativeTextPages pages = new NativeTextPages(platform.dataDirectory(ctx.getSource()));
            NativeTextPages.Document doc = pages.read("broadcasts.txt", null);
            suggestSections(doc, builder.getRemaining(), builder);
            return builder.buildFuture();
        };

        LiteralArgumentBuilder<S> broadcasts = LiteralArgumentBuilder.<S>literal("allium:broadcasts")
                .requires(platform::isGamemaster)
                .executes(ctx -> listBroadcasts(platform, ctx.getSource()))
                .then(LiteralArgumentBuilder.<S>literal("next")
                        .executes(ctx -> sendNextBroadcast(platform, broadcastState, ctx.getSource())))
                .then(LiteralArgumentBuilder.<S>literal("send")
                        .then(RequiredArgumentBuilder.<S, String>argument("section", StringArgumentType.word())
                                .suggests(broadcastSections)
                                .executes(ctx -> sendNamedBroadcast(platform, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "section")))));
        registerAliases(dispatcher, broadcasts, "broadcasts");
    }

    private static <S, P> void registerPageCommand(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                   String command, String filename, String... labels) {
        SuggestionProvider<S> sections = (ctx, builder) -> {
            P player = platform.playerOrNull(ctx.getSource());
            String viewer = player == null ? null : platform.name(player);
            NativeTextPages pages = new NativeTextPages(platform.dataDirectory(ctx.getSource()));
            suggestSections(pages.read(filename, viewer), builder.getRemaining(), builder);
            return builder.buildFuture();
        };

        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("allium:" + command)
                .executes(ctx -> showPage(platform, ctx.getSource(), platform.playerOrNull(ctx.getSource()),
                        command, filename, null, 1, false))
                .then(RequiredArgumentBuilder.<S, String>argument("sectionOrPage", StringArgumentType.word())
                        .suggests(sections)
                        .executes(ctx -> showPage(platform, ctx.getSource(), platform.playerOrNull(ctx.getSource()),
                                command, filename, StringArgumentType.getString(ctx, "sectionOrPage"), 1, false))
                        .then(RequiredArgumentBuilder.<S, Integer>argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> showPage(platform, ctx.getSource(), platform.playerOrNull(ctx.getSource()),
                                        command, filename, StringArgumentType.getString(ctx, "sectionOrPage"),
                                        IntegerArgumentType.getInteger(ctx, "page"), true))));
        registerAliases(dispatcher, root, labels);
    }

    private static <S, P> int executeInform(NativePlatform<S, P> platform, S source,
                                            String playerQuery, String sectionOrPage, int explicitPage) {
        P target = findOnline(platform, source, playerQuery);
        if (target == null) {
            platform.reply(source, "Player '" + playerQuery + "' is not online.");
            return 0;
        }
        return showPage(platform, source, target, "info", "info.txt", sectionOrPage, explicitPage, explicitPage > 1);
    }

    private static <S, P> int showPage(NativePlatform<S, P> platform, S sender, P viewer,
                                       String command, String filename, String sectionOrPage,
                                       int requestedPage, boolean explicitPageArg) {
        String viewerName = viewer == null ? null : platform.name(viewer);
        NativeTextPages pages = new NativeTextPages(platform.dataDirectory(sender));
        NativeTextPages.Document doc = pages.read(filename, viewerName);
        if (doc == null) {
            platform.reply(sender, filename + " could not be read.");
            return 0;
        }
        String section = sectionOrPage;
        int page = requestedPage;
        if (!explicitPageArg && section != null && section.matches("[0-9]{1,6}") && doc.section(section) == null) {
            page = Math.max(1, Integer.parseInt(section));
            section = null;
        }
        List<String> lines = section == null ? doc.index() : doc.section(section);
        if (lines == null || (section != null && lines.isEmpty())) {
            platform.reply(sender, "No text was found for '" + section + "'.");
            return 0;
        }
        if (lines.isEmpty()) {
            List<String> names = doc.primarySectionNames();
            String msg = names.isEmpty() ? filename + " has nothing to show yet."
                    : "Sections: " + String.join(", ", names);
            deliver(platform, sender, viewer, msg);
            return 1;
        }
        NativeTextPages.Page shown = NativeTextPages.paginate(lines, page);
        String displayName = resolveDisplayName(platform, sender, viewer);
        int online = platform.onlinePlayers(sender).size();
        int max = platform.maxPlayers(sender);
        String serverName = platform.serverName(sender);
        for (String raw : shown.lines) {
            String rendered = NativeTextPages.renderLine(raw, viewerName, displayName, online, max, serverName);
            deliver(platform, sender, viewer, rendered);
        }
        if (shown.pages > 1) {
            String nextHint = shown.page < shown.pages
                    ? " - use /" + command + " " + (section == null ? "" : doc.primaryName(section) + " ") + (shown.page + 1)
                    : "";
            deliver(platform, sender, viewer, "Page " + shown.page + "/" + shown.pages + nextHint);
        }
        return 1;
    }

    private static <S, P> int listBroadcasts(NativePlatform<S, P> platform, S source) {
        NativeTextPages pages = new NativeTextPages(platform.dataDirectory(source));
        NativeTextPages.Document doc = pages.read("broadcasts.txt", null);
        List<String> names = doc == null ? java.util.Collections.<String>emptyList() : doc.nonEmptyPrimarySections();
        platform.reply(source, "Broadcasts: " + (names.isEmpty() ? "(none)" : String.join(", ", names)));
        return 1;
    }

    private static <S, P> int sendNextBroadcast(NativePlatform<S, P> platform,
                                                NativeTextPages.BroadcastState state, S source) {
        NativeTextPages pages = new NativeTextPages(platform.dataDirectory(source));
        NativeTextPages.Document doc = pages.read("broadcasts.txt", null);
        if (doc == null) {
            platform.reply(source, "broadcasts.txt could not be read.");
            return 0;
        }
        String name = state.next(doc.nonEmptyPrimarySections());
        if (name == null) {
            platform.reply(source, "No broadcasts are defined in broadcasts.txt.");
            return 0;
        }
        return announceSection(platform, source, doc, name);
    }

    private static <S, P> int sendNamedBroadcast(NativePlatform<S, P> platform, S source, String section) {
        NativeTextPages pages = new NativeTextPages(platform.dataDirectory(source));
        NativeTextPages.Document doc = pages.read("broadcasts.txt", null);
        if (doc == null) {
            platform.reply(source, "broadcasts.txt could not be read.");
            return 0;
        }
        String primary = doc.primaryName(section);
        if (primary == null) {
            platform.reply(source, "No broadcast named '" + section + "' was found.");
            return 0;
        }
        return announceSection(platform, source, doc, primary);
    }

    private static <S, P> int announceSection(NativePlatform<S, P> platform, S source,
                                              NativeTextPages.Document doc, String name) {
        List<String> lines = doc.section(name);
        if (lines == null || lines.isEmpty()) {
            platform.reply(source, "Broadcast '" + name + "' is empty.");
            return 0;
        }
        int online = platform.onlinePlayers(source).size();
        int max = platform.maxPlayers(source);
        String serverName = platform.serverName(source);
        for (String line : lines) {
            platform.broadcast(source, NativeTextPages.renderLine(line, "Everyone", "Everyone", online, max, serverName));
        }
        platform.reply(source, "Sent broadcast '" + name + "'.");
        return 1;
    }

    private static <S, P> String resolveDisplayName(NativePlatform<S, P> platform, S source, P viewer) {
        if (viewer == null) return "Console";
        String real = platform.name(viewer);
        String nick = new NativePlayerInfo(platform.dataDirectory(source)).nicknameOf(platform.id(viewer));
        return nick != null ? NativePlayerInfo.translateColorCodes(nick) : real;
    }

    private static <S, P> void deliver(NativePlatform<S, P> platform, S sender, P viewer, String line) {
        P senderPlayer = platform.playerOrNull(sender);
        if (viewer != null && (senderPlayer == null || !platform.id(senderPlayer).equals(platform.id(viewer)))) {
            platform.send(viewer, line);
        } else {
            platform.reply(sender, line);
        }
    }

    private static void suggestSections(NativeTextPages.Document doc, String remaining,
                                        com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        if (doc == null) return;
        String lower = remaining == null ? "" : remaining.toLowerCase(Locale.ROOT);
        for (String name : doc.sectionNames()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                builder.suggest(name);
            }
        }
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
