package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Java 8 / 1.12.2-1.19.4 port of Allium's paginated, searchable, permission-filtered {@code /help}:
 * supports {@code /help [page]}, {@code /help plugin:<name> [page]}, {@code /help <plugin> [page]},
 * {@code /help <command>}, substring search, fuzzy Levenshtein suggestions, clickable navigation,
 * and localized {@code help.*} messages from {@code lang/<locale>.yml}.
 */
final class LegacyHelpCommand {

    static final String PERM_HELP = "allium.help";
    static final String PERM_PLUGINS = "allium.help.plugins";
    static final String PERM_INFO = "allium.help.info";
    private static final int PAGE_SIZE = 8;
    private static final int MAX_RELATED = 6;
    private static final int MAX_SUGGESTIONS = 5;

    private static final class Row {
        final String pluginName;
        final LegacyCommandManager.HelpEntry entry;

        Row(String pluginName, LegacyCommandManager.HelpEntry entry) {
            this.pluginName = pluginName;
            this.entry = entry;
        }
    }

    private final LegacyCommandManager commandManager;
    private final LegacyMessageManager messages;

    LegacyHelpCommand(LegacyCommandManager commandManager, LegacyMessageManager messages) {
        this.commandManager = commandManager;
        this.messages = messages;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_HELP) && !sender.isOp()) {
            messages.send(sender, "no-permission", "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", "help");
            return true;
        }

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
                    query = join(Arrays.copyOf(args, args.length - 1));
                } else {
                    query = join(args);
                }
            }
        } catch (NumberFormatException invalid) {
            sender.sendMessage(ChatColor.RED + "Usage: /help [command|plugin|query] [page]");
            return true;
        }

        boolean canSeePlugins = sender.hasPermission(PERM_PLUGINS) || sender.isOp();
        boolean canSeeInfo = sender.hasPermission(PERM_INFO) || sender.isOp();
        List<LegacyCommandManager.HelpEntry> allEntries = commandManager.helpEntries(sender);

        // 1. Main paginated menu: /help [page]
        if (query.trim().isEmpty()) {
            renderMainMenu(sender, allEntries, page, canSeePlugins);
            return true;
        }

        String trimmed = query.trim();

        // 2. Explicit plugin view: /help plugin:<name> [page]
        if (trimmed.length() >= 7 && trimmed.regionMatches(true, 0, "plugin:", 0, 7)) {
            String pluginQuery = trimmed.substring(7).trim();
            if (canSeePlugins && !pluginQuery.isEmpty()) {
                List<LegacyCommandManager.HelpEntry> pluginCmds = commandsForPlugin(allEntries, pluginQuery);
                if (!pluginCmds.isEmpty()) {
                    renderPluginMenu(sender, pluginCmds.get(0).owner, pluginCmds, page);
                    return true;
                }
            }
            renderUnknown(sender, pluginQuery.isEmpty() ? trimmed : pluginQuery, allEntries, canSeePlugins);
            return true;
        }

        boolean forcedCommand = trimmed.startsWith("/");
        String cleanQuery = forcedCommand ? trimmed.substring(1).trim() : trimmed;
        if (cleanQuery.isEmpty()) {
            renderMainMenu(sender, allEntries, page, canSeePlugins);
            return true;
        }

        LegacyCommandManager.HelpEntry exact = findExactCommand(allEntries, cleanQuery);

        // 3. Implicit plugin view: /help <plugin> [page]
        if (canSeePlugins && !forcedCommand) {
            List<LegacyCommandManager.HelpEntry> pluginCmds = commandsForPlugin(allEntries, cleanQuery);
            if (!pluginCmds.isEmpty() && (exact == null || explicitPage || pluginCmds.size() > 1)) {
                renderPluginMenu(sender, pluginCmds.get(0).owner, pluginCmds, page);
                return true;
            }
        }

        // 4. Exact command card: /help <command>
        if (exact != null && !explicitPage) {
            List<LegacyCommandManager.HelpEntry> related = new ArrayList<LegacyCommandManager.HelpEntry>();
            if (!forcedCommand && !canSeeInfo) {
                for (LegacyCommandManager.HelpEntry item : searchCommands(allEntries, cleanQuery)) {
                    if (!item.path.equalsIgnoreCase(exact.path)) {
                        related.add(item);
                        if (related.size() >= MAX_RELATED) break;
                    }
                }
            }
            renderCommandInfo(sender, exact, canSeeInfo, cleanQuery, related);
            return true;
        }

        // 5. Substring search across command name + description
        List<LegacyCommandManager.HelpEntry> matches = searchCommands(allEntries, cleanQuery);
        if (!matches.isEmpty()) {
            renderSearchResults(sender, cleanQuery, matches, page);
            return true;
        }

        // 6. Unknown query with fuzzy "Did you mean?" suggestions
        renderUnknown(sender, cleanQuery, allEntries, canSeePlugins);
        return true;
    }

    List<String> complete(CommandSender sender, String[] args) {
        if ((!sender.hasPermission(PERM_HELP) && !sender.isOp()) || args.length > 2) {
            return Collections.emptyList();
        }
        TreeSet<String> values = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        values.add("1");
        values.add("2");
        values.add("3");
        boolean canSeePlugins = sender.hasPermission(PERM_PLUGINS) || sender.isOp();
        if (args.length <= 1) {
            for (LegacyCommandManager.HelpEntry e : commandManager.helpEntries(sender)) {
                values.add(e.path);
                if (canSeePlugins && !e.owner.isEmpty()) {
                    values.add(e.owner);
                }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (String v : values) {
            if (v.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(v);
                if (out.size() >= 64) break;
            }
        }
        return out;
    }

    private void renderMainMenu(CommandSender sender, List<LegacyCommandManager.HelpEntry> entries,
                                int requestedPage, boolean canSeePlugins) {
        List<Row> rows = new ArrayList<Row>();
        if (canSeePlugins) {
            TreeMap<String, String> plugins = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            for (LegacyCommandManager.HelpEntry e : entries) {
                if (!e.owner.isEmpty() && !plugins.containsKey(e.owner)) {
                    plugins.put(e.owner, e.owner);
                }
            }
            for (String pName : plugins.values()) {
                rows.add(new Row(pName, null));
            }
        }
        for (LegacyCommandManager.HelpEntry e : entries) {
            rows.add(new Row(null, e));
        }

        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, pages));

        sendBorder(sender);
        messages.send(sender, "help.header", "&6&lHelp Menu &8─ &ePage {page}/{pages}",
                "{page}", Integer.toString(page), "{pages}", Integer.toString(pages));
        messages.send(sender, "help.subtitle", "&7Commands you have access to:");
        sender.sendMessage("");

        if (rows.isEmpty()) {
            messages.send(sender, "help.empty", "&7No commands available.");
        } else {
            int from = Math.min((page - 1) * PAGE_SIZE, rows.size());
            int to = Math.min(page * PAGE_SIZE, rows.size());
            for (int i = from; i < to; i++) {
                Row row = rows.get(i);
                if (row.pluginName != null) {
                    sendPluginRow(sender, row.pluginName);
                } else {
                    sendCommandRow(sender, row.entry);
                }
            }
        }

        sender.sendMessage("");
        sendFooter(sender, page, pages, "/help ");
        sendBorder(sender);
    }

    private void renderPluginMenu(CommandSender sender, String pluginName,
                                  List<LegacyCommandManager.HelpEntry> entries, int requestedPage) {
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, pages));

        sendBorder(sender);
        messages.send(sender, "help.plugin-header", "&6&lHelp Menu &8─ &e{plugin}", "{plugin}", pluginName);
        messages.send(sender, "help.plugin-subtitle", "&7Commands from &c[Plugin] &e{plugin}&7:", "{plugin}", pluginName);
        sender.sendMessage("");

        int from = Math.min((page - 1) * PAGE_SIZE, entries.size());
        int to = Math.min(page * PAGE_SIZE, entries.size());
        for (int i = from; i < to; i++) {
            sendCommandRow(sender, entries.get(i));
        }

        sender.sendMessage("");
        sendFooter(sender, page, pages, "/help plugin:" + pluginName + " ");
        sendBorder(sender);
    }

    private void renderCommandInfo(CommandSender sender, LegacyCommandManager.HelpEntry entry,
                                   boolean canSeeInfo, String query,
                                   List<LegacyCommandManager.HelpEntry> related) {
        String description = entry.description.isEmpty()
                ? messages.resolve("help.no-description", "&7No description")
                : entry.description;
        String permission = entry.permission.isEmpty()
                ? messages.resolve("help.none", "&7None")
                : entry.permission;

        sendBorder(sender);
        messages.send(sender, "help.command-header", "&6&lCommand Help &8─ &e/{command}", "{command}", entry.path);
        sender.sendMessage("");
        if (canSeeInfo) {
            messages.send(sender, "help.command-plugin", "&6Plugin: &f{plugin}", "{plugin}", entry.owner);
        }
        messages.send(sender, "help.command-description", "&6Description: &f{description}", "{description}", description);
        String usageLine = messages.resolveColored("help.command-usage", "&6Usage: &f{usage}", "{usage}", entry.usage);
        LegacyCompat.sendInteractive(sender, usageLine, "/" + entry.path + " ", false,
                LegacyCompat.color("&7Click to insert &e/" + entry.path));
        if (canSeeInfo) {
            messages.send(sender, "help.command-permission", "&6Permission: &f{permission}", "{permission}", permission);
        }
        if (!related.isEmpty()) {
            sender.sendMessage("");
            messages.send(sender, "help.related-header", "&7Commands matching &e\"{query}\"&7:", "{query}", query);
            for (LegacyCommandManager.HelpEntry item : related) {
                sendCommandRow(sender, item);
            }
        }
        sendBorder(sender);
    }

    private void renderSearchResults(CommandSender sender, String query,
                                     List<LegacyCommandManager.HelpEntry> matches, int requestedPage) {
        int pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, pages));

        sendBorder(sender);
        messages.send(sender, "help.search-header", "&6&lHelp Menu &8─ &eCommands matching &f\"{query}\"", "{query}", query);
        sender.sendMessage("");

        int from = Math.min((page - 1) * PAGE_SIZE, matches.size());
        int to = Math.min(page * PAGE_SIZE, matches.size());
        for (int i = from; i < to; i++) {
            sendCommandRow(sender, matches.get(i));
        }

        sender.sendMessage("");
        sendFooter(sender, page, pages, "/help " + query + " ");
        sendBorder(sender);
    }

    private void renderUnknown(CommandSender sender, String query,
                               List<LegacyCommandManager.HelpEntry> allEntries, boolean canSeePlugins) {
        sendBorder(sender);
        messages.send(sender, "help.unknown-header", "&6&lHelp Menu &8─ &eHelp for &6{query}", "{query}", query);
        sender.sendMessage("");
        if (canSeePlugins) {
            messages.send(sender, "help.unknown-plugin", "&cUnknown command or plugin: &e{query}", "{query}", query);
        } else {
            messages.send(sender, "help.unknown", "&cUnknown command: &e{query}", "{query}", query);
        }
        List<String> suggestions = fuzzySuggestions(allEntries, query);
        if (!suggestions.isEmpty()) {
            sender.sendMessage("");
            messages.send(sender, "help.did-you-mean", "&7Did you mean one of these?");
            for (String sug : suggestions) {
                String line = messages.resolveColored("help.suggestion", "&7─ &e/{command}", "{command}", sug);
                LegacyCompat.sendInteractive(sender, line, "/help /" + sug, true,
                        LegacyCompat.color("&7Click to view help for &e/" + sug));
            }
        }
        sendBorder(sender);
    }

    private void sendBorder(CommandSender sender) {
        messages.send(sender, "help.border", "&8&m───────────────────────────────────");
    }

    private void sendPluginRow(CommandSender sender, String pluginName) {
        String line = messages.resolveColored("help.plugin-entry", "&c▪ [Plugin] &e{plugin}", "{plugin}", pluginName);
        LegacyCompat.sendInteractive(sender, line, "/help plugin:" + pluginName, true,
                LegacyCompat.color("&7Click to browse &e" + pluginName + " &7commands"));
    }

    private void sendCommandRow(CommandSender sender, LegacyCommandManager.HelpEntry entry) {
        String desc = entry.description.isEmpty()
                ? messages.resolve("help.no-description", "&7No description")
                : entry.description;
        String line = messages.resolveColored("help.entry", "&6▪ &e/{command} &7─ &f{description}",
                "{command}", entry.path, "{description}", desc);
        LegacyCompat.sendInteractive(sender, line, "/help /" + entry.path, true,
                LegacyCompat.color("&7Click for details on &e/" + entry.path));
    }

    private void sendFooter(CommandSender sender, int page, int pages, String cmdPrefix) {
        String pageText = messages.resolveColored("help.page", "&7Page &f{page}/{pages}",
                "{page}", Integer.toString(page), "{pages}", Integer.toString(pages));
        if (!(sender instanceof Player) || pages <= 1) {
            sender.sendMessage(pageText);
            return;
        }
        String prevLabel = messages.resolveColored(page > 1 ? "help.previous" : "help.previous-disabled",
                page > 1 ? "&6« Previous" : "&8« Previous");
        String nextLabel = messages.resolveColored(page < pages ? "help.next" : "help.next-disabled",
                page < pages ? "&6Next »" : "&8Next »");
        String prevCmd = page > 1 ? cmdPrefix + (page - 1) : null;
        String nextCmd = page < pages ? cmdPrefix + (page + 1) : null;
        LegacyCompat.sendHelpFooter(sender, prevLabel, prevCmd, pageText, nextLabel, nextCmd);
    }

    private static LegacyCommandManager.HelpEntry findExactCommand(List<LegacyCommandManager.HelpEntry> entries, String query) {
        for (LegacyCommandManager.HelpEntry e : entries) {
            if (e.path.equalsIgnoreCase(query)) return e;
        }
        return null;
    }

    private static List<LegacyCommandManager.HelpEntry> commandsForPlugin(List<LegacyCommandManager.HelpEntry> entries, String pluginQuery) {
        List<LegacyCommandManager.HelpEntry> out = new ArrayList<LegacyCommandManager.HelpEntry>();
        for (LegacyCommandManager.HelpEntry e : entries) {
            if (e.owner.equalsIgnoreCase(pluginQuery)) out.add(e);
        }
        return out;
    }

    private static List<LegacyCommandManager.HelpEntry> searchCommands(List<LegacyCommandManager.HelpEntry> entries, String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<LegacyCommandManager.HelpEntry> out = new ArrayList<LegacyCommandManager.HelpEntry>();
        for (LegacyCommandManager.HelpEntry e : entries) {
            if (e.path.toLowerCase(Locale.ROOT).contains(lower)
                    || e.description.toLowerCase(Locale.ROOT).contains(lower)) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<String> fuzzySuggestions(List<LegacyCommandManager.HelpEntry> entries, String query) {
        final String target = query.toLowerCase(Locale.ROOT);
        int maxDist = Math.max(2, target.length() / 2);
        List<LegacyCommandManager.HelpEntry> scored = new ArrayList<LegacyCommandManager.HelpEntry>();
        for (LegacyCommandManager.HelpEntry e : entries) {
            int d = levenshtein(target, e.path.toLowerCase(Locale.ROOT));
            if (d <= maxDist) scored.add(e);
        }
        Collections.sort(scored, new Comparator<LegacyCommandManager.HelpEntry>() {
            @Override
            public int compare(LegacyCommandManager.HelpEntry a, LegacyCommandManager.HelpEntry b) {
                int da = levenshtein(target, a.path.toLowerCase(Locale.ROOT));
                int db = levenshtein(target, b.path.toLowerCase(Locale.ROOT));
                return da != db ? Integer.compare(da, db) : a.path.compareToIgnoreCase(b.path);
            }
        });
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < scored.size() && i < MAX_SUGGESTIONS; i++) {
            out.add(scored.get(i).path);
        }
        return out;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private static boolean isPageToken(String token) {
        if (token == null || token.isEmpty()) return false;
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) return false;
        }
        return true;
    }

    private static int parsePage(String token) {
        int val = Integer.parseInt(token);
        if (val <= 0) throw new NumberFormatException("page must be positive");
        return val;
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
