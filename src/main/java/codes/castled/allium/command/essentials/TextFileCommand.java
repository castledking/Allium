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
 * {@code /customtext}, {@code /motd} and {@code /rules}: pages of {@code customtext.txt},
 * {@code motd.txt} and {@code rules.txt} in {@code plugins/Allium} ({@link CustomTextDocument}, the
 * layout of Essentials' text files), with click and hover events ({@link InteractiveText}) and the
 * {@code {player}}, {@code {displayname}}, {@code {world}}, {@code {online}}, {@code {max}} and
 * {@code {server}} placeholders (plus PlaceholderAPI's when it is installed). With no section the
 * lines before the first section are shown, or the list of sections when there are none. A file is
 * read again whenever it changes on disk, so edits show without a reload.
 */
public final class TextFileCommand implements SpecCommandHandler {

    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)\\{(player|displayname|world|online|max|maxplayers|server)}");

    private final PluginStart plugin;
    private final CommandSpec spec;
    private final String fileName;
    private final Messages missing;
    private CustomTextDocument document;
    private long loadedModified = Long.MIN_VALUE;
    private long loadedSize = -1;

    public TextFileCommand(PluginStart plugin, CommandSpec spec, String fileName, Messages missing) {
        this.plugin = plugin;
        this.spec = spec;
        this.fileName = fileName;
        this.missing = missing;
    }

    public static TextFileCommand customText(PluginStart plugin) {
        return new TextFileCommand(plugin, CoreCommandSpecs.CUSTOMTEXT, "customtext.txt", Messages.CUSTOMTEXT_MISSING);
    }

    public static TextFileCommand motd(PluginStart plugin) {
        return new TextFileCommand(plugin, CoreCommandSpecs.MOTD, "motd.txt", Messages.MOTD_MISSING);
    }

    public static TextFileCommand rules(PluginStart plugin) {
        return new TextFileCommand(plugin, CoreCommandSpecs.RULES, "rules.txt", Messages.RULES_MISSING);
    }

    @Override
    public CommandSpec spec() {
        return spec;
    }

    public String fileName() {
        return fileName;
    }

    /** Writes the bundled example when the server has no file yet. */
    public void installDefault() {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException missingResource) {
                plugin.logger().warning("The bundled " + fileName + " is missing from the jar.");
            }
        }
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length > 2) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        CustomTextDocument text = document();
        if (text == null) {
            UtilitySupport.send(plugin, sender, Messages.CUSTOMTEXT_FAILED, "{file}", fileName);
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
                        context.sendInvalidArgs(spec.selfUsage());
                        return;
                    }
                    page = parsePage(args[1]);
                }
            }
        }
        if (section == null && text.index().isEmpty() && !text.sectionNames().isEmpty()) {
            sendSections(sender, text);
            return;
        }
        List<String> lines = section == null ? text.index() : text.section(section).orElse(null);
        if (section == null && lines.isEmpty()) {
            UtilitySupport.send(plugin, sender, Messages.CUSTOMTEXT_EMPTY, "{file}", fileName);
            return;
        }
        if (lines == null || lines.isEmpty()) {
            UtilitySupport.send(plugin, sender, missing, "{section}", section);
            return;
        }
        CustomTextDocument.Page shown = CustomTextDocument.page(lines, page);
        send(sender, shown.lines());
        if (shown.pages() > 1) {
            String name = section == null ? "" : text.primaryName(section).orElse(section) + " ";
            HelpCommand.sendFooter(plugin, sender, shown.page(), shown.pages(), "/" + spec.canonicalName() + " " + name);
        }
    }

    /** The first page of the file's index, as {@code /<command>} shows it; nothing when the file has none. */
    public void sendIndex(Player player) {
        CustomTextDocument text = document();
        if (text == null || text.index().isEmpty()) {
            return;
        }
        CustomTextDocument.Page shown = CustomTextDocument.page(text.index(), 1);
        send(player, shown.lines());
        if (shown.pages() > 1) {
            HelpCommand.sendFooter(plugin, player, 1, shown.pages(), "/" + spec.canonicalName() + " ");
        }
    }

    /** Section names and aliases for {@code /<command> <tab>}. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (spec.basePermission() != null && !sender.hasPermission(spec.basePermission()) || args.length != 1) {
            return List.of();
        }
        CustomTextDocument text = document();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return text == null ? List.of() : text.sectionNames().stream().filter(n -> n.startsWith(prefix)).limit(64).toList();
    }

    /** {@code Sections: rules, griefing}: each name runs {@code /<command> <name>} when clicked. */
    private void sendSections(CommandSender sender, CustomTextDocument text) {
        List<String> names = text.sectionNames().stream()
                .filter(name -> text.primaryName(name).map(name::equals).orElse(false))
                .toList();
        String command = "/" + spec.canonicalName() + " ";
        String links = String.join("&7, ", names.stream()
                .map(name -> "<click:run_command:" + command + name + "><hover:show_text:'&7Click to show &e" + name
                        + "'>&e" + name + "</hover></click>")
                .toList());
        String line = plugin.getMessageManager().resolve(Messages.CUSTOMTEXT_SECTIONS, "{command}", spec.canonicalName(), "{sections}", links);
        if (line != null && !line.isEmpty()) {
            send(sender, List.of(line), false);
        }
    }

    private void send(CommandSender sender, List<String> lines) {
        send(sender, lines, true);
    }

    private void send(CommandSender sender, List<String> lines, boolean placeholders) {
        for (String raw : lines) {
            String line = placeholders ? fill(sender, raw) : raw;
            if (sender instanceof Player player && player.spigot() != null) {
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
        File file = new File(plugin.getDataFolder(), fileName);
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
            plugin.logger().warning("Could not read " + fileName + ": " + failed.getMessage());
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
