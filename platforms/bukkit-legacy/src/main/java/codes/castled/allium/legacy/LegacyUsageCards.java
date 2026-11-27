package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The usage card a player sees when a command is typed with missing or wrong arguments, as on the
 * modern platform: the command's header and description, then one clickable line per form the
 * sender may use, written with the label they typed. The lines come from command-usages.tsv, which
 * the build keeps equal to the command specs.
 */
final class LegacyUsageCards {
    static final String RESOURCE = "command-usages.tsv";

    private static volatile LegacyUsageCards installed;

    static final class Line {
        final String syntax;
        final String description;
        final String permission;

        Line(String syntax, String description, String permission) {
            this.syntax = syntax;
            this.description = description;
            this.permission = permission == null || permission.isEmpty() ? null : permission;
        }

        List<String> tokens() {
            return LegacyUsageCards.tokens(syntax);
        }

        List<String> literalPrefix() {
            List<String> out = new ArrayList<>();
            for (String token : tokens()) {
                if (isArgument(token) || token.contains("|")) break;
                out.add(token.toLowerCase(Locale.ROOT));
            }
            return out;
        }
    }

    static final class Family {
        final String name;
        final String description;
        final List<Line> lines = new ArrayList<>();

        Family(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final Map<String, Family> byLabel;

    LegacyUsageCards(JavaPlugin plugin, LegacyMessageManager messages, Map<String, Family> byLabel) {
        this.plugin = plugin;
        this.messages = messages;
        this.byLabel = byLabel;
    }

    static LegacyUsageCards load(JavaPlugin plugin, LegacyMessageManager messages) {
        Map<String, Family> byLabel = Collections.emptyMap();
        try (InputStream in = plugin.getResource(RESOURCE)) {
            if (in != null) byLabel = parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException unreadable) {
            plugin.getLogger().warning("Usage cards are unavailable: " + unreadable.getMessage());
        }
        return new LegacyUsageCards(plugin, messages, byLabel);
    }

    static Map<String, Family> parse(BufferedReader reader) throws IOException {
        Map<String, Family> byLabel = new HashMap<>();
        Family current = null;
        for (String row; (row = reader.readLine()) != null; ) {
            if (row.isEmpty() || row.startsWith("#")) continue;
            String[] cells = row.split("\t", -1);
            if ("C".equals(cells[0]) && cells.length >= 4) {
                current = new Family(cells[1], cells[3]);
                byLabel.put(cells[1], current);
                if (!cells[2].isEmpty()) for (String alias : cells[2].split(",")) byLabel.putIfAbsent(alias, current);
            } else if ("U".equals(cells[0]) && cells.length >= 4 && current != null) {
                current.lines.add(new Line(cells[1], cells[2], cells[3]));
            }
        }
        return byLabel;
    }

    static void install(LegacyUsageCards cards) {
        installed = cards;
    }

    /**
     * Shows the card for the command a usage string names ({@code /mail send <player> <message>}),
     * narrowed to the forms it asked for; without a card, the usage line itself in red.
     */
    static void invalid(CommandSender sender, String requestedUsage) {
        LegacyUsageCards cards = installed;
        String command = commandOf(requestedUsage);
        if (cards == null || command == null || !cards.send(sender, command, command, requestedUsage)) {
            sender.sendMessage(ChatColor.RED + "Usage: " + requestedUsage);
        }
    }

    /** Sends {@code command}'s card as typed with {@code label}; false when there is no card for it. */
    boolean send(CommandSender sender, String command, String label, String requestedUsage) {
        Family family = family(command);
        if (family == null) family = family(label);
        if (family == null || family.lines.isEmpty()) return false;
        String bare = bareLabel(label, family.name);
        List<Line> lines = select(family, requestedUsage);
        messages.send(sender, "help.command-header", "&6&lCommand Help &8─ &e/{command}", "{command}", bare);
        String description = description(family);
        if (!description.isEmpty()) {
            messages.send(sender, "help.command-description", "&6Description: &f{description}", "{description}", description);
        }
        List<Line> visible = new ArrayList<>();
        for (Line line : lines) if (line.permission == null || sender.hasPermission(line.permission)) visible.add(line);
        if (visible.isEmpty()) visible = lines;
        if (visible.size() == 1) {
            Line only = visible.get(0);
            String text = messages.resolveColored("help.command-usage", "&6Usage: &f{usage}", "{usage}", colored(bare, only));
            LegacyCompat.sendInteractive(sender, text, suggestion(bare, only), false, hover(bare, only));
        } else {
            messages.send(sender, "help.command-usages", "&6Usage(s):");
            for (Line line : visible) {
                String rendered = colored(bare, line);
                String text = line.description.isEmpty()
                        ? messages.resolveColored("help.usage-line-plain", "&f{usage}", "{usage}", rendered)
                        : messages.resolveColored("help.usage-line", "{usage} &7- &f{description}", "{usage}", rendered,
                        "{description}", line.description);
                LegacyCompat.sendInteractive(sender, text, suggestion(bare, line), false, hover(bare, line));
            }
        }
        if (sender instanceof Player) messages.playSound((Player) sender, "error.invalid-args");
        return true;
    }

    private Family family(String label) {
        if (label == null) return null;
        String bare = label.substring(label.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        return byLabel.get(bare);
    }

    private String description(Family family) {
        org.bukkit.command.PluginCommand command = plugin.getCommand(family.name);
        String fromYaml = command == null || command.getDescription() == null ? "" : command.getDescription().trim();
        return fromYaml.isEmpty() ? family.description : fromYaml;
    }

    private String colored(String label, Line line) {
        StringBuilder out = new StringBuilder(messages.resolve("help.usage-literal", "&f{token}", "{token}", "/" + label));
        for (String token : line.tokens()) {
            out.append(' ').append(messages.resolve(isArgument(token) ? "help.usage-argument" : "help.usage-literal",
                    isArgument(token) ? "&6{token}" : "&f{token}", "{token}", token));
        }
        return LegacyCompat.color(out.toString());
    }

    static String suggestion(String label, Line line) {
        List<String> literals = line.literalPrefix();
        return "/" + label + " " + (literals.isEmpty() ? "" : String.join(" ", literals) + " ");
    }

    private static String hover(String label, Line line) {
        return LegacyCompat.color("&7Click to insert &e" + suggestion(label, line).trim());
    }

    /** As the modern platform: the exact form a command reported, else the forms sharing its subcommand words, else all. */
    static List<Line> select(Family family, String requestedUsage) {
        List<Line> listed = family.lines;
        if (requestedUsage == null || requestedUsage.trim().isEmpty()) return listed;
        String wanted = afterLabel(firstAlternative(requestedUsage));
        List<Line> exact = new ArrayList<>();
        for (Line line : listed) if (line.syntax.equalsIgnoreCase(wanted)) exact.add(line);
        if (!exact.isEmpty()) return exact;
        List<String> literals = requestedLiterals(requestedUsage);
        if (literals.isEmpty()) return listed;
        List<Line> narrowed = new ArrayList<>();
        for (Line line : listed) {
            List<String> prefix = line.literalPrefix();
            if (prefix.size() >= literals.size() && prefix.subList(0, literals.size()).equals(literals)) narrowed.add(line);
        }
        return narrowed.isEmpty() ? listed : narrowed;
    }

    static List<String> requestedLiterals(String requestedUsage) {
        List<String> tokens = tokens(firstAlternative(requestedUsage));
        List<String> out = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (isArgument(token) || token.contains("|")) break;
            out.add(token.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** The command a usage string names ({@code give} for {@code /give <player>}), or null. */
    static String commandOf(String usage) {
        if (usage == null) return null;
        String trimmed = usage.trim();
        if (!trimmed.startsWith("/") || trimmed.length() < 2) return null;
        int space = trimmed.indexOf(' ');
        String command = (space < 0 ? trimmed.substring(1) : trimmed.substring(1, space)).toLowerCase(Locale.ROOT);
        return command.substring(command.lastIndexOf(':') + 1);
    }

    static boolean isArgument(String token) {
        return !token.isEmpty() && (token.charAt(0) == '<' || token.charAt(0) == '[');
    }

    /** Splits on spaces outside {@code <>}, {@code []} and {@code ()}. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '[' || c == '(') depth++;
            else if ((c == '>' || c == ']' || c == ')') && depth > 0) depth--;
            if (c == ' ' && depth == 0) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private static String firstAlternative(String usage) {
        int bar = usage.indexOf(" | ");
        return bar < 0 ? usage : usage.substring(0, bar);
    }

    static String afterLabel(String usage) {
        String trimmed = usage == null ? "" : usage.trim();
        if (!trimmed.startsWith("/")) return trimmed;
        int space = trimmed.indexOf(' ');
        return space < 0 ? "" : trimmed.substring(space + 1).trim();
    }

    private static String bareLabel(String label, String canonical) {
        if (label == null || label.trim().isEmpty()) return canonical;
        String bare = label.substring(label.lastIndexOf(':') + 1).trim();
        return bare.isEmpty() ? canonical : bare.toLowerCase(Locale.ROOT);
    }
}
