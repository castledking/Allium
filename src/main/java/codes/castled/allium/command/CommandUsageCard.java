package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CommandUsage;
import codes.castled.allium.command.spec.CommandUsages;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The command help card a player sees when a command is typed with missing or wrong arguments:
 * the {@code /help <command>} header and description, then one clickable line per form the
 * sender may use ({@link CommandUsages}), written with the label the player typed.
 */
public final class CommandUsageCard {

    private CommandUsageCard() {
    }

    /** Card for a command family looked up by name; falls back to the plain usage message when it is unknown. */
    public static void sendInvalid(PluginStart plugin, CommandSender sender, String command, String label, String requestedUsage) {
        Optional<CommandSpec> spec = plugin.getCommandManager() == null
                ? Optional.empty()
                : plugin.getCommandManager().getFamilyPolicy().findSpec(command);
        if (spec.isEmpty()) {
            plugin.getMessageManager().send(sender, Messages.INVALID_ARGS, "{usage}", requestedUsage);
            return;
        }
        sendInvalid(plugin, sender, spec.get(), label, requestedUsage);
    }

    public static void sendInvalid(PluginStart plugin, CommandSender sender, CommandSpec spec, String label, String requestedUsage) {
        String other = CommandUsages.commandOf(requestedUsage);
        if (other != null && !spec.bareLabels().contains(other) && !other.equals(bareLabel(label, spec))) {
            // A usage naming another command (/i from the console points at /give) is shown as written.
            send(plugin, sender, spec, label, other, CommandUsages.derive(requestedUsage));
            return;
        }
        sendInvalid(plugin, sender, spec, label, CommandUsages.select(spec, requestedUsage));
    }

    public static void sendInvalid(PluginStart plugin, CommandSender sender, CommandSpec spec, String label, List<CommandUsage> usages) {
        send(plugin, sender, spec, label, bareLabel(label, spec), usages);
    }

    private static void send(PluginStart plugin, CommandSender sender, CommandSpec spec, String label, String usageLabel,
                             List<CommandUsage> usages) {
        MessageManager messages = plugin.getMessageManager();
        messages.send(sender, Messages.HELP_COMMAND_HEADER, "{command}", bareLabel(label, spec));
        String description = description(plugin, spec);
        if (!description.isBlank()) {
            messages.send(sender, Messages.HELP_COMMAND_DESCRIPTION, "{description}", description);
        }
        sendUsages(plugin, sender, usageLabel, usages);
        if (sender instanceof Player player) {
            messages.playSoundIfPresent(player, Messages.INVALID_ARGS);
        }
    }

    /**
     * The usage section of a card: {@code Usage: ...} for a single visible form, else a
     * {@code Usage(s):} title and one line per form. Forms whose permission the sender lacks are left out.
     */
    static void sendUsages(PluginStart plugin, CommandSender sender, String label, List<CommandUsage> usages) {
        MessageManager messages = plugin.getMessageManager();
        List<CommandUsage> visible = usages.stream()
                .filter(usage -> usage.permission() == null || sender.hasPermission(usage.permission()))
                .toList();
        if (visible.isEmpty()) {
            // Never an empty card: show the forms even when none of them is open to this sender.
            visible = usages.isEmpty() ? List.of(CommandUsage.of("", "")) : usages;
        }
        if (visible.size() == 1) {
            CommandUsage only = visible.get(0);
            String line = resolve(messages, Messages.HELP_COMMAND_USAGE, "{usage}", colored(messages, label, only));
            HelpCommand.interactive(sender, line, suggestion(label, only), false, hover(label, only));
            return;
        }
        messages.send(sender, Messages.HELP_COMMAND_USAGES);
        for (CommandUsage usage : visible) {
            String rendered = colored(messages, label, usage);
            String line = usage.description().isBlank()
                    ? resolve(messages, Messages.HELP_USAGE_LINE_PLAIN, "{usage}", rendered)
                    : resolve(messages, Messages.HELP_USAGE_LINE, "{usage}", rendered, "{description}", usage.description());
            HelpCommand.interactive(sender, line, suggestion(label, usage), false, hover(label, usage));
        }
    }

    /** {@code /label} and each word in the literal or argument colour. */
    static String colored(MessageManager messages, String label, CommandUsage usage) {
        StringBuilder out = new StringBuilder(resolve(messages, Messages.HELP_USAGE_LITERAL, "{token}", "/" + label));
        for (String token : usage.tokens()) {
            out.append(' ').append(resolve(messages,
                    CommandUsage.isArgument(token) ? Messages.HELP_USAGE_ARGUMENT : Messages.HELP_USAGE_LITERAL,
                    "{token}", token));
        }
        return out.toString();
    }

    /** What clicking a line puts in the chat box: the label and the form's leading subcommand words. */
    static String suggestion(String label, CommandUsage usage) {
        List<String> literals = usage.literalPrefix();
        return "/" + label + " " + (literals.isEmpty() ? "" : String.join(" ", literals) + " ");
    }

    private static String hover(String label, CommandUsage usage) {
        return "&7Click to insert &e" + suggestion(label, usage).trim();
    }

    /** The plugin.yml description, which /help shows too, else the spec's. */
    static String description(PluginStart plugin, CommandSpec spec) {
        PluginCommand command = plugin.getCommand(spec.canonicalName());
        String fromYaml = command == null || command.getDescription() == null ? "" : command.getDescription().trim();
        return fromYaml.isEmpty() ? spec.description() : fromYaml;
    }

    /** {@code allium:gm} is shown as {@code gm}; no label means the canonical name. */
    static String bareLabel(String label, CommandSpec spec) {
        if (label == null || label.isBlank()) {
            return spec.canonicalName();
        }
        String bare = label.substring(label.lastIndexOf(':') + 1).trim();
        return bare.isEmpty() ? spec.canonicalName() : bare.toLowerCase(Locale.ROOT);
    }

    private static String resolve(MessageManager messages, Messages message, String... replacements) {
        String text = messages.resolve(message, replacements);
        return text == null ? "" : text;
    }
}
