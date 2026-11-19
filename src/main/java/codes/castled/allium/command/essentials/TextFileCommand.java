package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.HelpCommand;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.customtext.CustomTextDocument;
import codes.castled.allium.message.Messages;
import codes.castled.allium.text.TextFile;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

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

    private final PluginStart plugin;
    private final CommandSpec spec;
    private final TextFile file;
    private final Messages missing;

    public TextFileCommand(PluginStart plugin, CommandSpec spec, String fileName, Messages missing) {
        this.plugin = plugin;
        this.spec = spec;
        this.file = new TextFile(plugin, fileName);
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
        return file.fileName();
    }

    /** Writes the bundled example when the server has no file yet. */
    public void installDefault() {
        file.installDefault();
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length > 2) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        CustomTextDocument text = file.document();
        if (text == null) {
            UtilitySupport.send(plugin, sender, Messages.CUSTOMTEXT_FAILED, "{file}", file.fileName());
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
            UtilitySupport.send(plugin, sender, Messages.CUSTOMTEXT_EMPTY, "{file}", file.fileName());
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
        CustomTextDocument text = file.document();
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
        CustomTextDocument text = file.document();
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
        file.send(sender, lines, true);
    }

    private void send(CommandSender sender, List<String> lines, boolean placeholders) {
        file.send(sender, lines, placeholders);
    }

    /** The parsed file (tests). */
    @Nullable
    CustomTextDocument document() {
        return file.document();
    }

    private static boolean isPage(String raw) {
        return raw.matches("[0-9]{1,6}");
    }

    private static int parsePage(String raw) {
        return Math.max(1, Integer.parseInt(raw));
    }
}
