package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.hide.HideService;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HelpCommandTest {

    private static MessageManager stubMessages() {
        MessageManager messages = mock(MessageManager.class);
        when(messages.resolve(any(Messages.class), any(String[].class))).thenAnswer(inv -> {
            Messages m = inv.getArgument(0);
            String out = m.getDefault();
            Object[] raw = inv.getArguments();
            for (int i = 1; i + 1 < raw.length; i += 2) {
                out = out.replace(String.valueOf(raw[i]), String.valueOf(raw[i + 1]));
            }
            return out;
        });
        when(messages.resolve(any(Messages.class))).thenAnswer(inv -> ((Messages) inv.getArgument(0)).getDefault());
        return messages;
    }

    @Test
    void pagesClampAndReturnOnlyEightRows() {
        var plugin = mock(PluginStart.class);
        var messages = stubMessages();
        when(plugin.getMessageManager()).thenReturn(messages);
        var sender = mock(ConsoleCommandSender.class);
        when(sender.hasPermission("allium.help")).thenReturn(true);

        var help = spy(new HelpCommand(plugin));
        doReturn(IntStream.range(0, 17)
                .mapToObj(i -> new HideService.HelpEntry("cmd" + i, "Owner", "description"))
                .toList()).when(help).entries(sender);

        help.execute(new BukkitCommandContext(plugin, help.spec(), sender, "help", new String[]{"2"}));
        verify(messages).send(sender, Messages.HELP_HEADER, "{page}", "2", "{pages}", "3");
        verify(messages, times(8)).resolve(eq(Messages.HELP_ENTRY), any(String[].class));
        verify(messages, never()).resolve(eq(Messages.HELP_PLUGIN_ENTRY), any(String[].class));

        clearInvocations(messages);
        help.execute(new BukkitCommandContext(plugin, help.spec(), sender, "help", new String[]{"999"}));
        verify(messages).send(sender, Messages.HELP_HEADER, "{page}", "3", "{pages}", "3");
        verify(messages, times(1)).resolve(eq(Messages.HELP_ENTRY), any(String[].class));
    }

    @Test
    void pluginsPermissionListsRedPluginsBeforeCommandsAndGatesPluginViews() {
        var plugin = mock(PluginStart.class);
        var messages = stubMessages();
        when(plugin.getMessageManager()).thenReturn(messages);

        var entries = List.of(
                new HideService.HelpEntry("bed", "Allium", "Teleport to bed", "/bed", "allium.bed"),
                new HideService.HelpEntry("heal", "Allium", "Heal players", "/heal [player]", "allium.heal"),
                new HideService.HelpEntry("skills", "AuraSkills", "Open skills", "/skills", "auraskills.use")
        );

        // With allium.help.plugins: 2 unique plugins + 3 commands = 5 rows on page 1, plugins first.
        var staff = mock(ConsoleCommandSender.class);
        when(staff.hasPermission("allium.help")).thenReturn(true);
        when(staff.hasPermission("allium.help.plugins")).thenReturn(true);

        var help = spy(new HelpCommand(plugin));
        doReturn(entries).when(help).entries(staff);

        help.execute(new BukkitCommandContext(plugin, help.spec(), staff, "help", new String[]{}));
        verify(messages).send(staff, Messages.HELP_HEADER, "{page}", "1", "{pages}", "1");
        verify(messages).resolve(Messages.HELP_PLUGIN_ENTRY, "{plugin}", "Allium");
        verify(messages).resolve(Messages.HELP_PLUGIN_ENTRY, "{plugin}", "AuraSkills");
        verify(messages, times(3)).resolve(eq(Messages.HELP_ENTRY), any(String[].class));

        // Tab completion includes plugins only when allium.help.plugins is granted.
        assertTrue(help.complete(staff, new String[]{""}).contains("AuraSkills"));

        // /help Allium shows Allium's 2 commands.
        clearInvocations(messages);
        help.execute(new BukkitCommandContext(plugin, help.spec(), staff, "help", new String[]{"Allium"}));
        verify(messages).send(staff, Messages.HELP_PLUGIN_HEADER, "{plugin}", "Allium");
        verify(messages, times(2)).resolve(eq(Messages.HELP_ENTRY), any(String[].class));

        // Regular player without allium.help.plugins does not see plugin rows, tab entries, or plugin browsing.
        var regular = mock(ConsoleCommandSender.class);
        when(regular.hasPermission("allium.help")).thenReturn(true);
        doReturn(entries).when(help).entries(regular);

        clearInvocations(messages);
        assertFalse(help.complete(regular, new String[]{""}).contains("AuraSkills"));
        help.execute(new BukkitCommandContext(plugin, help.spec(), regular, "help", new String[]{"AuraSkills"}));
        verify(messages, never()).send(eq(regular), eq(Messages.HELP_PLUGIN_HEADER), any(String[].class));
        verify(messages).send(regular, Messages.HELP_UNKNOWN, "{query}", "AuraSkills");
    }

    @Test
    void infoPermissionShowsFullMetadataWhileRegularPlayersGetUsageAndDescriptionOnly() {
        var plugin = mock(PluginStart.class);
        var messages = stubMessages();
        when(plugin.getMessageManager()).thenReturn(messages);

        var entries = List.of(
                new HideService.HelpEntry("heal", "Allium", "Heal players", "/heal [player]", "allium.heal")
        );

        // With allium.help.info: Plugin, Description, Usage, Permission.
        var staff = mock(ConsoleCommandSender.class);
        when(staff.hasPermission("allium.help")).thenReturn(true);
        when(staff.hasPermission("allium.help.info")).thenReturn(true);
        var help = spy(new HelpCommand(plugin));
        doReturn(entries).when(help).entries(staff);

        help.execute(new BukkitCommandContext(plugin, help.spec(), staff, "help", new String[]{"heal"}));
        verify(messages).send(staff, Messages.HELP_COMMAND_HEADER, "{command}", "heal");
        verify(messages).send(staff, Messages.HELP_COMMAND_PLUGIN, "{plugin}", "Allium");
        verify(messages).send(staff, Messages.HELP_COMMAND_DESCRIPTION, "{description}", "Heal players");
        verify(messages).resolve(Messages.HELP_COMMAND_USAGE, "{usage}", "/heal [player]");
        verify(messages).send(staff, Messages.HELP_COMMAND_PERMISSION, "{permission}", "allium.heal");

        // Regular player (without allium.help.info): Description + Usage only; NO Plugin, NO Permission.
        var regular = mock(ConsoleCommandSender.class);
        when(regular.hasPermission("allium.help")).thenReturn(true);
        doReturn(entries).when(help).entries(regular);
        clearInvocations(messages);

        help.execute(new BukkitCommandContext(plugin, help.spec(), regular, "help", new String[]{"heal"}));
        verify(messages).send(regular, Messages.HELP_COMMAND_HEADER, "{command}", "heal");
        verify(messages).send(regular, Messages.HELP_COMMAND_DESCRIPTION, "{description}", "Heal players");
        verify(messages).resolve(Messages.HELP_COMMAND_USAGE, "{usage}", "/heal [player]");
        verify(messages, never()).send(eq(regular), eq(Messages.HELP_COMMAND_PLUGIN), any(String[].class));
        verify(messages, never()).send(eq(regular), eq(Messages.HELP_COMMAND_PERMISSION), any(String[].class));
    }

    @Test
    void searchPullsFromCommandNameAndDescriptionAndUnknownSuggestsClosestCommands() {
        var plugin = mock(PluginStart.class);
        var messages = stubMessages();
        when(plugin.getMessageManager()).thenReturn(messages);

        var entries = List.of(
                new HideService.HelpEntry("bed", "Allium", "Teleport to your bed", "/bed", "allium.bed"),
                new HideService.HelpEntry("disguisehelp", "LibsDisguises", "Help command for LibsDisguises.", "/disguisehelp", "libs.help"),
                new HideService.HelpEntry("gpflags", "GPFlags", "Reload or get help", "/gpflags", "gpflags.use"),
                new HideService.HelpEntry("votehelp", "Voting", "See access to a list of commands", "/votehelp", "vote.help")
        );

        var regular = mock(ConsoleCommandSender.class);
        when(regular.hasPermission("allium.help")).thenReturn(true);
        var help = spy(new HelpCommand(plugin));
        doReturn(entries).when(help).entries(regular);

        // Search "help" matches both names (disguisehelp, votehelp) and description (gpflags: "Reload or get help").
        help.execute(new BukkitCommandContext(plugin, help.spec(), regular, "help", new String[]{"help"}));
        verify(messages).send(regular, Messages.HELP_SEARCH_HEADER, "{query}", "help");
        verify(messages).resolve(Messages.HELP_ENTRY, "{command}", "disguisehelp", "{description}", "Help command for LibsDisguises.");
        verify(messages).resolve(Messages.HELP_ENTRY, "{command}", "votehelp", "{description}", "See access to a list of commands");
        verify(messages).resolve(Messages.HELP_ENTRY, "{command}", "gpflags", "{description}", "Reload or get help");

        // Unknown command "aed" suggests "/bed".
        clearInvocations(messages);
        help.execute(new BukkitCommandContext(plugin, help.spec(), regular, "help", new String[]{"aed"}));
        verify(messages).send(regular, Messages.HELP_UNKNOWN_HEADER, "{query}", "aed");
        verify(messages).send(regular, Messages.HELP_UNKNOWN, "{query}", "aed");
        verify(messages).send(regular, Messages.HELP_DID_YOU_MEAN);
        verify(messages).resolve(Messages.HELP_SUGGESTION, "{command}", "bed");

        // Usage normalization expands /<command>.
        assertEquals("/heal [player]", HideService.HelpEntry.normalizeUsage("/<command> [player]", "heal"));
    }
}
