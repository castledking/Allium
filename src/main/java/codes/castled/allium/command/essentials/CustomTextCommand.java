package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.HelpCommand;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.customtext.CustomTextDocument;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.text.InteractiveText;
import codes.castled.allium.text.TextSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /customtext [section] [page]}: pages of {@code plugins/Allium/customtext.txt}
 * ({@link CustomTextDocument}), with click and hover events ({@link InteractiveText}) and the
 * {@code {player}}, {@code {displayname}}, {@code {world}}, {@code {online}}, {@code {max}} and
 * {@code {server}} placeholders (plus PlaceholderAPI's when it is installed). The file is read
 * again whenever it changes on disk, so edits show without a reload. As with Essentials, a
 * {@code commands.yml} alias such as {@code vote: [customtext vote $1-]} turns a section into a command.
 */
public final class CustomTextCommand implements SpecCommandHandler {

    public static final String FILE = "customtext.txt";
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)\\{(player|displayname|world|online|max|maxplayers|server)}");

    private final PluginStart plugin;
    private CustomTextDocument document;
    private long loadedModified = Long.MIN_VALUE;
    private long loadedSize = -1;

    public CustomTextCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.CUSTOMTEXT;
    }

    /** Writes the bundled example when the server has no file yet. */
    public void installDefault() {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            try {
                plugin.saveResource(FILE, false);
            } catch (IllegalArgumentException missing) {
                plugin.logger().warning("The bundled " + FILE + " is missing from the jar.");
            }
        }
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length > 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        CustomTextDocument text = document();
        if (text == null) {
            UtilitySupport.send(plugin, sender, Messages.CUSTOMTEXT_FAILED);
            return;
        }
        String section = null;
        int page = 1;
        if (args.length >= 1) {
            if (args.length == 1 && isPage(args[0]) && text.section(args[0]).isEmpty()) {
                page = parsePage(args[0]);
            } else {
                section = args[0];
                if (args.length == 2) {
                    if (!isPage(args[1])) {
                        context.sendInvalidArgs(spec().selfUsage());
                        return;
                    }
                    page = parsePage(args[1]);
                }
            }
        }
        List<String> lines = section == null ? text.index() : text.section(section).orElse(null);
        if (lines == null || lines.isEmpty()) {
            UtilitySupport.send(plugin, sender, Messages.CUSTOMTEXT_MISSING, "{section}", section == null ? "index" : section);
            return;
        }
        CustomTextDocument.Page shown = CustomTextDocument.page(lines, page);
        for (String line : shown.lines()) {
            send(sender, fill(sender, line));
        }
        if (shown.pages() > 1) {
            String name = section == null ? "" : text.primaryName(section).orElse(section) + " ";
            HelpCommand.sendFooter(plugin, sender, shown.page(), shown.pages(), "/customtext " + name);
        }
    }

    /** Section names and aliases for {@code /customtext <tab>}. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.customtext") || args.length != 1) {
            return List.of();
        }
        CustomTextDocument text = document();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return text == null ? List.of() : text.sectionNames().stream().filter(n -> n.startsWith(prefix)).limit(64).toList();
    }

    private static void send(CommandSender sender, String line) {
        if (sender instanceof Player player) {
            // An empty line still takes a row, as in the file.
            player.spigot().sendMessage(line.isEmpty()
                    ? new net.md_5.bungee.api.chat.BaseComponent[]{new net.md_5.bungee.api.chat.TextComponent(" ")}
                    : InteractiveText.components(line));
        } else {
            var formatted = InteractiveText.formatted(line);
            if (formatted.isEmpty()) {
                sender.sendMessage("");
            } else {
                TextSender.send(sender, formatted);
            }
        }
    }

    /** Placeholders; values a player controls lose {@code <} and {@code >} so they cannot open click tags. */
    private String fill(CommandSender sender, String line) {
        Player player = sender instanceof Player p ? p : null;
        String out = line;
        if (player != null && plugin.getServer().getPluginManager() != null
                && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") && out.indexOf('%') >= 0) {
            try {
                out = Papi.apply(player, out);
            } catch (LinkageError | RuntimeException unavailable) {
                // keep the text without PlaceholderAPI values
            }
        }
        Matcher m = PLACEHOLDER.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "player" -> sender.getName();
                case "displayname" -> player == null ? sender.getName() : player.getDisplayName();
                case "world" -> player == null || player.getWorld() == null ? "" : player.getWorld().getName();
                case "online" -> Long.toString(plugin.getServer().getOnlinePlayers().stream()
                        .filter(other -> PlayerNameVisibility.canSee(sender, other)).count());
                case "max", "maxplayers" -> Integer.toString(plugin.getServer().getMaxPlayers());
                default -> plugin.getServer().getName();
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value.replace("<", "").replace(">", "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The parsed file, read again when its size or modification time changes; null when it cannot be read. */
    @Nullable
    synchronized CustomTextDocument document() {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) {
            installDefault();
        }
        if (!file.isFile()) {
            return null;
        }
        long modified = file.lastModified();
        long size = file.length();
        if (document != null && modified == loadedModified && size == loadedSize) {
            return document;
        }
        try {
            document = CustomTextDocument.parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            loadedModified = modified;
            loadedSize = size;
            return document;
        } catch (IOException | RuntimeException failed) {
            plugin.logger().warning("Could not read " + FILE + ": " + failed.getMessage());
            return document;
        }
    }

    private static boolean isPage(String raw) {
        return raw.matches("[0-9]{1,6}");
    }

    private static int parsePage(String raw) {
        return Math.max(1, Integer.parseInt(raw));
    }

    private static final class Papi {
        static String apply(Player player, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
    }
}
