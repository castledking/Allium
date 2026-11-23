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
 * {@code /info}, {@code /customtext}, {@code /motd} and {@code /rules}: pages of {@code info.txt},
 * {@code custom.txt}, {@code motd.txt} and {@code rules.txt} in {@code plugins/Allium}
 * ({@link CustomTextDocument}, the layout of Essentials' text files), with click and hover events
 * and placeholders ({@link TextFile}). A player reads their own or their group's file when there is
 * one ({@code info_steve.txt}, {@code info_vip.txt}). With no section the lines before the first
 * section are shown, or the list of sections when there are none. A file is read again whenever it
 * changes on disk, so edits show without a reload.
 */
public final class TextFileCommand implements SpecCommandHandler.Immediate {

    private final PluginStart plugin;
    private final CommandSpec spec;
    private final TextFile file;
    private final Messages missing;

    public TextFileCommand(PluginStart plugin, CommandSpec spec, String fileName, Messages missing) {
        this(plugin, spec, new TextFile(plugin, fileName), missing);
    }

    private TextFileCommand(PluginStart plugin, CommandSpec spec, TextFile file, Messages missing) {
        this.plugin = plugin;
        this.spec = spec;
        this.file = file;
        this.missing = missing;
    }

    /** {@code /info}: {@code info.txt}. */
    public static TextFileCommand info(PluginStart plugin) {
        return new TextFileCommand(plugin, CoreCommandSpecs.INFO, "info.txt", Messages.INFO_MISSING);
    }

    /** {@code /customtext}: {@code custom.txt}, Essentials' name; a pre-recode {@code customtext.txt} is moved to it. */
    public static TextFileCommand customText(PluginStart plugin) {
        return new TextFileCommand(plugin, CoreCommandSpecs.CUSTOMTEXT, new TextFile(plugin, "custom.txt", "customtext.txt"),
                Messages.CUSTOMTEXT_MISSING);
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
    public boolean executeWithResult(BukkitCommandContext context) {
        if (context.args().length > 2) {
            context.sendInvalidArgs(spec.selfUsage());
            return false;
        }
        return show(context.sender(), context.sender(), context.args(), () -> context.sendInvalidArgs(spec.selfUsage()));
    }

    /**
     * Shows {@code viewer} the page {@code args} ({@code [section] [page]}) names, from the file
     * {@code viewer} reads and with their placeholders. Problems (a missing section, a file that
     * cannot be read) go to {@code reportTo}; {@code usage} runs for a page that is not a number.
     *
     * @return whether anything was shown
     */
    public boolean show(CommandSender viewer, CommandSender reportTo, String[] args, Runnable usage) {
        String fileName = file.fileNameFor(viewer);
        CustomTextDocument text = file.document(viewer);
        if (text == null) {
            UtilitySupport.send(plugin, reportTo, Messages.CUSTOMTEXT_FAILED, "{file}", fileName);
            return false;
        }
        String section = null;
        int page = 1;
        if (args.length >= 1) {
            if (args.length == 1 && isPage(args[0]) && text.section(args[0]).isEmpty()) {
                page = parsePage(args[0]);
            } else {
                section = args[0];
                if (args.length >= 2) {
                    if (!isPage(args[1])) {
                        usage.run();
                        return false;
                    }
                    page = parsePage(args[1]);
                }
            }
        }
        if (section == null && text.index().isEmpty() && !text.sectionNames().isEmpty()) {
            sendSections(viewer, text);
            return true;
        }
        List<String> lines = section == null ? text.index() : text.section(section).orElse(null);
        if (section == null && lines.isEmpty()) {
            UtilitySupport.send(plugin, reportTo, Messages.CUSTOMTEXT_EMPTY, "{file}", fileName);
            return false;
        }
        if (lines == null || lines.isEmpty()) {
            UtilitySupport.send(plugin, reportTo, missing, "{section}", section);
            return false;
        }
        CustomTextDocument.Page shown = CustomTextDocument.page(lines, page);
        String name = section == null ? "" : text.primaryName(section).orElse(section) + " ";
        sendPage(viewer, shown, "/" + spec.canonicalName() + " " + name);
        return true;
    }

    /** The first page of the file's index, as {@code /<command>} shows it; nothing when the file has none. */
    public void sendIndex(Player player) {
        CustomTextDocument text = file.document(player);
        if (text == null || text.index().isEmpty()) {
            return;
        }
        CustomTextDocument.Page shown = CustomTextDocument.page(text.index(), 1);
        sendPage(player, shown, "/" + spec.canonicalName() + " ");
    }

    /** Section names and aliases for {@code /<command> <tab>}. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (spec.basePermission() != null && !sender.hasPermission(spec.basePermission()) || args.length != 1) {
            return List.of();
        }
        CustomTextDocument text = file.document(sender);
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

    private void sendPage(CommandSender sender, CustomTextDocument.Page page, String command) {
        file.send(sender, page.lines(), true, () -> {
            if (page.pages() > 1) HelpCommand.sendFooter(plugin, sender, page.page(), page.pages(), command);
        });
    }

    private void send(CommandSender sender, List<String> lines, boolean placeholders) {
        file.send(sender, lines, placeholders);
    }

    /** The parsed shared file (tests). */
    @Nullable
    CustomTextDocument document() {
        return file.document();
    }

    /** Section names and aliases of the file {@code viewer} reads, for {@code /inform <player> <tab>}. */
    List<String> sections(CommandSender viewer, String prefix) {
        CustomTextDocument text = file.document(viewer);
        String lower = prefix.toLowerCase(Locale.ROOT);
        return text == null ? List.of() : text.sectionNames().stream().filter(n -> n.startsWith(lower)).limit(64).toList();
    }

    private static boolean isPage(String raw) {
        return raw.matches("[0-9]{1,6}");
    }

    private static int parsePage(String raw) {
        return Math.max(1, Integer.parseInt(raw));
    }
}
