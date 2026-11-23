package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.hide.HideService;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.HideModule;
import codes.castled.allium.text.SpigotComponents;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Fresh permission/policy view for every page, plugin view, command card, search and completion. */
public final class HelpCommand implements SpecCommandHandler {

    public static final String PERM_HELP = "allium.help";
    public static final String PERM_PLUGINS = "allium.help.plugins";
    public static final String PERM_INFO = "allium.help.info";
    private static final int PAGE_SIZE = 8;
    private static final int MAX_RELATED = 6;
    private static final int MAX_SUGGESTIONS = 5;

    private final PluginStart plugin;

    public HelpCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.HELP;
    }

    public List<HideService.HelpEntry> entries(CommandSender sender) {
        if (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("hide") instanceof HideModule hide
                && hide.isEnabled()) {
            return hide.hide().helpEntries(sender);
        }
        var access = CommandMapBridge.inspectPlatformAccess(plugin.getServer(), ignored -> { });
        if (access == null || access.knownCommands() == null) {
            return List.of();
        }
        return access.knownCommands().entrySet().stream()
                .filter(e -> !e.getKey().contains(":") && !isDisabledAlliumCommand(e.getKey(), e.getValue())
                        && e.getValue().testPermissionSilent(sender))
                .map(e -> HideService.HelpEntry.of(plugin, e.getKey(), e.getValue(), null))
                .sorted(Comparator.comparing(HideService.HelpEntry::path, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean isDisabledAlliumCommand(String label, org.bukkit.command.Command command) {
        if (plugin.getCommandManager() == null) return false;
        if (!(command instanceof org.bukkit.command.PluginIdentifiableCommand owned)) return false;
        if (!owned.getPlugin().getName().equalsIgnoreCase(plugin.getName())) return false;
        return plugin.getCommandManager().getFamilyPolicy().isDisabled(label);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_HELP) || args.length > 2) {
            return List.of();
        }
        var values = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        values.addAll(List.of("1", "2", "3"));
        boolean canSeePlugins = sender.hasPermission(PERM_PLUGINS);
        if (args.length <= 1) {
            for (var e : entries(sender)) {
                values.add(e.path());
                if (canSeePlugins && e.owner() != null && !e.owner().isBlank()) {
                    values.add(e.owner());
                }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix))
                .limit(64)
                .toList();
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        MessageManager messages = plugin.getMessageManager();
        if (!sender.hasPermission(PERM_HELP)) {
            messages.send(sender, Messages.HIDE_NO_PERMISSION, "{cmd}", "help");
            return;
        }

        String[] args = context.args();
        int page = 1;
        boolean explicitPage = false;
        String query = "";
        try {
            if (args.length == 1) {
                if (isPageToken(args[0])) {
                    page = parsePage(args[0]);
                    explicitPage = true;
                } else {
                    query = args[0];
                }
            } else if (args.length >= 2) {
                if (args.length == 2 && isPageToken(args[0])) {
                    page = parsePage(args[0]);
                    explicitPage = true;
                    query = args[1];
                } else if (isPageToken(args[args.length - 1])) {
                    page = parsePage(args[args.length - 1]);
                    explicitPage = true;
                    query = String.join(" ", Arrays.copyOf(args, args.length - 1));
                } else {
                    query = String.join(" ", args);
                }
            }
        } catch (NumberFormatException invalid) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        boolean canSeePlugins = sender.hasPermission(PERM_PLUGINS);
        boolean canSeeInfo = sender.hasPermission(PERM_INFO);
        List<HideService.HelpEntry> allEntries = entries(sender);

        // 1. Main menu: /help [page]
        if (query.isBlank()) {
            renderMainMenu(sender, allEntries, page, canSeePlugins);
            return;
        }

        String trimmed = query.trim();

        // 2. Explicit plugin view: /help plugin:<name> [page] (requires allium.help.plugins)
        if (trimmed.regionMatches(true, 0, "plugin:", 0, 7)) {
            String pluginQuery = trimmed.substring(7).trim();
            if (canSeePlugins && !pluginQuery.isEmpty()) {
                List<HideService.HelpEntry> pluginCommands = commandsForPlugin(allEntries, pluginQuery);
                if (!pluginCommands.isEmpty()) {
                    String canonicalPlugin = pluginCommands.get(0).owner();
                    renderPluginMenu(sender, canonicalPlugin, pluginCommands, page);
                    return;
                }
            }
            renderUnknown(sender, pluginQuery.isEmpty() ? trimmed : pluginQuery, allEntries, canSeePlugins);
            return;
        }

        boolean forcedCommand = trimmed.startsWith("/");
        String cleanQuery = forcedCommand ? trimmed.substring(1).trim() : trimmed;
        if (cleanQuery.isEmpty()) {
            renderMainMenu(sender, allEntries, page, canSeePlugins);
            return;
        }

        HideService.HelpEntry exact = findExactCommand(allEntries, cleanQuery);

        // 3. Implicit plugin view: /help <plugin> [page] (requires allium.help.plugins, never when prefixed with '/')
        if (canSeePlugins && !forcedCommand) {
            List<HideService.HelpEntry> pluginCommands = commandsForPlugin(allEntries, cleanQuery);
            if (!pluginCommands.isEmpty() && (exact == null || explicitPage || pluginCommands.size() > 1)) {
                String canonicalPlugin = pluginCommands.get(0).owner();
                renderPluginMenu(sender, canonicalPlugin, pluginCommands, page);
                return;
            }
        }

        // 4. Exact command match: /help <command>
        if (exact != null && !explicitPage) {
            List<HideService.HelpEntry> related = forcedCommand || canSeeInfo
                    ? List.of()
                    : searchCommands(allEntries, cleanQuery).stream()
                            .filter(e -> !e.path().equalsIgnoreCase(exact.path()))
                            .limit(MAX_RELATED)
                            .toList();
            renderCommandInfo(sender, exact, canSeeInfo, cleanQuery, related);
            return;
        }

        // 5. Substring search across command name AND description
        List<HideService.HelpEntry> matches = searchCommands(allEntries, cleanQuery);
        if (!matches.isEmpty()) {
            renderSearchResults(sender, cleanQuery, matches, page);
            return;
        }

        // 6. Unknown command / plugin with fuzzy "Did you mean one of these?" suggestions
        renderUnknown(sender, cleanQuery, allEntries, canSeePlugins);
    }

    private void renderMainMenu(CommandSender sender, List<HideService.HelpEntry> entries, int requestedPage, boolean canSeePlugins) {
        List<Row> rows = new ArrayList<>();
        if (canSeePlugins) {
            TreeMap<String, String> plugins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (var e : entries) {
                if (e.owner() != null && !e.owner().isBlank()) {
                    plugins.putIfAbsent(e.owner(), e.owner());
                }
            }
            for (String pluginName : plugins.values()) {
                rows.add(Row.plugin(pluginName));
            }
        }
        for (var e : entries) {
            rows.add(Row.command(e));
        }

        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, pages));
        MessageManager messages = plugin.getMessageManager();

        sendBorder(sender);
        messages.send(sender, Messages.HELP_HEADER, "{page}", Integer.toString(page), "{pages}", Integer.toString(pages));
        messages.send(sender, Messages.HELP_SUBTITLE);
        blankLine(sender);

        if (rows.isEmpty()) {
            messages.send(sender, Messages.HELP_EMPTY);
        } else {
            int from = Math.min((page - 1) * PAGE_SIZE, rows.size());
            int to = Math.min(page * PAGE_SIZE, rows.size());
            for (Row row : rows.subList(from, to)) {
                if (row.pluginName != null) {
                    sendPluginRow(sender, row.pluginName);
                } else {
                    sendCommandRow(sender, row.entry);
                }
            }
        }

        blankLine(sender);
        sendFooter(sender, page, pages, "/help ");
        sendBorder(sender);
    }

    private void renderPluginMenu(CommandSender sender, String pluginName, List<HideService.HelpEntry> entries, int requestedPage) {
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, pages));
        MessageManager messages = plugin.getMessageManager();

        sendBorder(sender);
        messages.send(sender, Messages.HELP_PLUGIN_HEADER, "{plugin}", pluginName);
        messages.send(sender, Messages.HELP_PLUGIN_SUBTITLE, "{plugin}", pluginName);
        blankLine(sender);

        int from = Math.min((page - 1) * PAGE_SIZE, entries.size());
        int to = Math.min(page * PAGE_SIZE, entries.size());
        for (var entry : entries.subList(from, to)) {
            sendCommandRow(sender, entry);
        }

        blankLine(sender);
        sendFooter(sender, page, pages, "/help plugin:" + pluginName + " ");
        sendBorder(sender);
    }

    private void renderCommandInfo(
            CommandSender sender,
            HideService.HelpEntry entry,
            boolean canSeeInfo,
            String query,
            List<HideService.HelpEntry> related
    ) {
        MessageManager messages = plugin.getMessageManager();
        String description = displayDescription(entry);
        String usage = entry.usage() != null && !entry.usage().isBlank() ? entry.usage() : "/" + entry.path();
        String permission = entry.permission() != null && !entry.permission().isBlank()
                ? entry.permission()
                : safeResolve(Messages.HELP_NONE);

        sendBorder(sender);
        messages.send(sender, Messages.HELP_COMMAND_HEADER, "{command}", entry.path());
        blankLine(sender);
        if (canSeeInfo) {
            messages.send(sender, Messages.HELP_COMMAND_PLUGIN, "{plugin}", entry.owner());
        }
        messages.send(sender, Messages.HELP_COMMAND_DESCRIPTION, "{description}", description);
        CommandSpec spec = alliumSpec(entry);
        if (spec != null) {
            // Allium commands list every form the viewer may use, as on the missing-arguments card.
            String root = entry.path().split(" ", 2)[0];
            CommandUsageCard.sendUsages(plugin, sender, root,
                    codes.castled.allium.command.spec.CommandUsages.select(spec, entry.path().contains(" ") ? "/" + entry.path() : null));
        } else {
            String usageLine = safeResolve(Messages.HELP_COMMAND_USAGE, "{usage}", usage);
            interactive(sender, usageLine, "/" + entry.path() + " ", false, "&7Click to insert &e/" + entry.path());
        }
        if (canSeeInfo) {
            messages.send(sender, Messages.HELP_COMMAND_PERMISSION, "{permission}", permission);
        }
        if (!related.isEmpty()) {
            blankLine(sender);
            messages.send(sender, Messages.HELP_RELATED_HEADER, "{query}", query);
            for (var item : related) {
                sendCommandRow(sender, item);
            }
        }
        sendBorder(sender);
    }

    /** The spec behind an Allium-owned help entry, or null for other plugins' commands. */
    private CommandSpec alliumSpec(HideService.HelpEntry entry) {
        if (plugin.getCommandManager() == null || entry.owner() == null
                || plugin.getName() == null || !entry.owner().equalsIgnoreCase(plugin.getName())) {
            return null;
        }
        return plugin.getCommandManager().getFamilyPolicy().findSpec(entry.path().split(" ", 2)[0]).orElse(null);
    }

    private void renderSearchResults(CommandSender sender, String query, List<HideService.HelpEntry> matches, int requestedPage) {
        int pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, pages));
        MessageManager messages = plugin.getMessageManager();

        sendBorder(sender);
        messages.send(sender, Messages.HELP_SEARCH_HEADER, "{query}", query);
        blankLine(sender);

        int from = Math.min((page - 1) * PAGE_SIZE, matches.size());
        int to = Math.min(page * PAGE_SIZE, matches.size());
        for (var entry : matches.subList(from, to)) {
            sendCommandRow(sender, entry);
        }

        blankLine(sender);
        sendFooter(sender, page, pages, "/help " + query + " ");
        sendBorder(sender);
    }

    private void renderUnknown(CommandSender sender, String query, List<HideService.HelpEntry> entries, boolean canSeePlugins) {
        MessageManager messages = plugin.getMessageManager();
        sendBorder(sender);
        messages.send(sender, Messages.HELP_UNKNOWN_HEADER, "{query}", query);
        blankLine(sender);
        messages.send(sender, canSeePlugins ? Messages.HELP_UNKNOWN_PLUGIN : Messages.HELP_UNKNOWN, "{query}", query);

        List<String> closest = suggestCommands(query, entries.stream().map(HideService.HelpEntry::path).toList(), MAX_SUGGESTIONS);
        if (!closest.isEmpty()) {
            messages.send(sender, Messages.HELP_DID_YOU_MEAN);
            for (String candidate : closest) {
                String line = safeResolve(Messages.HELP_SUGGESTION, "{command}", candidate);
                interactive(sender, line, "/help /" + candidate, true, "&7Click to view help for &e/" + candidate);
            }
        }
        sendBorder(sender);
    }

    private void sendPluginRow(CommandSender sender, String pluginName) {
        String line = safeResolve(Messages.HELP_PLUGIN_ENTRY, "{plugin}", pluginName);
        interactive(sender, line, "/help plugin:" + pluginName, true, "&7Click to view commands for &e" + pluginName);
    }

    private void sendCommandRow(CommandSender sender, HideService.HelpEntry entry) {
        String description = displayDescription(entry);
        String line = safeResolve(Messages.HELP_ENTRY, "{command}", entry.path(), "{description}", description);
        String usage = entry.usage() != null && !entry.usage().isBlank() ? entry.usage() : "/" + entry.path();
        String hover = "&6Usage: &f" + usage + "\n&6Description: &f" + description + "\n&7Click for command help";
        interactive(sender, line, "/help /" + entry.path(), true, hover);
    }

    private void sendFooter(CommandSender sender, int page, int pages, String commandPrefix) {
        sendFooter(plugin, sender, page, pages, commandPrefix);
    }

    /** {@code « Previous  Page 1/3  Next »}, with clickable arrows that run {@code commandPrefix + page}. */
    public static void sendFooter(PluginStart plugin, CommandSender sender, int page, int pages, String commandPrefix) {
        MessageManager messages = plugin.getMessageManager();
        String prevText = Objects.toString(messages.resolve(page > 1 ? Messages.HELP_PREVIOUS : Messages.HELP_PREVIOUS_DISABLED), "");
        String pageText = Objects.toString(messages.resolve(Messages.HELP_PAGE, "{page}", Integer.toString(page), "{pages}", Integer.toString(pages)), "");
        String nextText = Objects.toString(messages.resolve(page < pages ? Messages.HELP_NEXT : Messages.HELP_NEXT_DISABLED), "");

        if (sender instanceof Player player) {
            List<BaseComponent> all = new ArrayList<>();
            BaseComponent[] prevParts = SpigotComponents.of(TextFormatter.parse(prevText));
            if (page > 1) {
                ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, commandPrefix + (page - 1));
                HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        SpigotComponents.of(TextFormatter.parse("&7Go to page &e" + (page - 1))));
                for (BaseComponent part : prevParts) {
                    part.setClickEvent(click);
                    part.setHoverEvent(hover);
                }
            }
            all.addAll(Arrays.asList(prevParts));
            all.add(new TextComponent(" "));
            all.addAll(Arrays.asList(SpigotComponents.of(TextFormatter.parse(pageText))));
            all.add(new TextComponent(" "));
            BaseComponent[] nextParts = SpigotComponents.of(TextFormatter.parse(nextText));
            if (page < pages) {
                ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, commandPrefix + (page + 1));
                HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        SpigotComponents.of(TextFormatter.parse("&7Go to page &e" + (page + 1))));
                for (BaseComponent part : nextParts) {
                    part.setClickEvent(click);
                    part.setHoverEvent(hover);
                }
            }
            all.addAll(Arrays.asList(nextParts));
            player.spigot().sendMessage(all.toArray(BaseComponent[]::new));
        } else {
            TextSender.send(sender, prevText + " " + pageText + " " + nextText);
        }
    }

    private void sendBorder(CommandSender sender) {
        plugin.getMessageManager().send(sender, Messages.HELP_BORDER);
    }

    private static void blankLine(CommandSender sender) {
        sender.sendMessage("");
    }

    private String displayDescription(HideService.HelpEntry entry) {
        String desc = entry.description();
        return desc != null && !desc.isBlank() ? desc : safeResolve(Messages.HELP_NO_DESCRIPTION);
    }

    private String safeResolve(Messages message, String... replacements) {
        String resolved = plugin.getMessageManager().resolve(message, replacements);
        return resolved == null ? "" : resolved;
    }

    private static HideService.HelpEntry findExactCommand(List<HideService.HelpEntry> entries, String query) {
        for (var e : entries) {
            if (e.path().equalsIgnoreCase(query)) {
                return e;
            }
        }
        return null;
    }

    private static List<HideService.HelpEntry> commandsForPlugin(List<HideService.HelpEntry> entries, String pluginName) {
        return entries.stream()
                .filter(e -> e.owner() != null && e.owner().equalsIgnoreCase(pluginName))
                .toList();
    }

    static List<HideService.HelpEntry> searchCommands(List<HideService.HelpEntry> entries, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        record Ranked(HideService.HelpEntry entry, int rank) {}
        List<Ranked> matches = new ArrayList<>();
        for (var e : entries) {
            String path = e.path().toLowerCase(Locale.ROOT);
            String desc = Objects.toString(e.description(), "").toLowerCase(Locale.ROOT);
            if (path.equals(needle)) {
                matches.add(new Ranked(e, 0));
            } else if (path.startsWith(needle)) {
                matches.add(new Ranked(e, 1));
            } else if (path.contains(needle)) {
                matches.add(new Ranked(e, 2));
            } else if (desc.contains(needle)) {
                matches.add(new Ranked(e, 3));
            }
        }
        matches.sort(Comparator.comparingInt(Ranked::rank)
                .thenComparing(r -> r.entry().path(), String.CASE_INSENSITIVE_ORDER));
        return matches.stream().map(Ranked::entry).toList();
    }

    static List<String> suggestCommands(String input, Collection<String> candidates, int limit) {
        String needle = input.toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || needle.length() > 64) {
            return List.of();
        }
        int maxDistance = needle.length() >= 6 ? 3 : 2;
        record Scored(String candidate, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || candidate.contains(" ")) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.equals(needle)) {
                continue;
            }
            if (Math.abs(lower.length() - needle.length()) > maxDistance) {
                continue;
            }
            int dist = levenshtein(needle, lower);
            if (dist <= maxDistance) {
                scored.add(new Scored(candidate, dist));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score)
                .thenComparing(Scored::candidate, String.CASE_INSENSITIVE_ORDER));
        return scored.stream().limit(limit).map(Scored::candidate).toList();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j < prev.length; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] next = new int[prev.length];
            next[0] = i;
            for (int j = 1; j < next.length; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                next[j] = Math.min(Math.min(next[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = next;
        }
        return prev[b.length()];
    }

    private static boolean isPageToken(String token) {
        return token != null && token.matches("[0-9]+");
    }

    private static int parsePage(String token) {
        try {
            int value = Integer.parseInt(token);
            return Math.max(1, value);
        } catch (NumberFormatException overflow) {
            return Integer.MAX_VALUE;
        }
    }

    static void interactive(CommandSender sender, String text, String command, boolean run, String hoverText) {
        if (sender instanceof Player player && player.spigot() != null) {
            BaseComponent[] parts = SpigotComponents.of(TextFormatter.parse(text));
            ClickEvent click = new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command);
            HoverEvent hover = hoverText == null || hoverText.isEmpty()
                    ? null
                    : new HoverEvent(HoverEvent.Action.SHOW_TEXT, SpigotComponents.of(TextFormatter.parse(hoverText)));
            for (BaseComponent part : parts) {
                part.setClickEvent(click);
                if (hover != null) {
                    part.setHoverEvent(hover);
                }
            }
            player.spigot().sendMessage(parts);
        } else {
            TextSender.send(sender, text);
        }
    }

    private record Row(String pluginName, HideService.HelpEntry entry) {
        static Row plugin(String name) {
            return new Row(name, null);
        }

        static Row command(HideService.HelpEntry entry) {
            return new Row(null, entry);
        }
    }
}
