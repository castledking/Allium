package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import org.bukkit.ChatColor;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandUsageCardTest {

    private static final codes.castled.allium.command.spec.CommandSpec NICK = CoreCommandSpecs.CHAT_SPECS.stream()
            .filter(spec -> spec.canonicalName().equals("nick")).findFirst().orElseThrow();
    private final List<String> lines = new ArrayList<>();
    private final Set<String> grants = new HashSet<>();

    private PluginStart plugin() {
        PluginStart plugin = mock(PluginStart.class);
        MessageManager messages = mock(MessageManager.class);
        when(messages.resolve(any(Messages.class), any(String[].class))).thenAnswer(inv -> {
            Messages m = inv.getArgument(0);
            String out = m.getDefault();
            Object[] raw = inv.getArguments();
            for (int i = 1; i + 1 < raw.length; i += 2) out = out.replace(String.valueOf(raw[i]), String.valueOf(raw[i + 1]));
            return out;
        });
        when(messages.resolve(any(Messages.class))).thenAnswer(inv -> ((Messages) inv.getArgument(0)).getDefault());
        when(plugin.getMessageManager()).thenReturn(messages);
        return plugin;
    }

    private ConsoleCommandSender console() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenAnswer(inv -> grants.contains(inv.<String>getArgument(0)));
        doAnswer(inv -> lines.add(ChatColor.stripColor(inv.<String>getArgument(0)))).when(console).sendMessage(anyString());
        return console;
    }

    @Test
    void theCardListsEachFormWithTheTypedLabelAndHidesFormsTheSenderCannotUse() {
        PluginStart plugin = plugin();
        ConsoleCommandSender console = console();
        CommandUsageCard.sendInvalid(plugin, console, NICK, "allium:nickname", NICK.selfUsage());
        verify(plugin.getMessageManager()).send(console, Messages.HELP_COMMAND_HEADER, "{command}", "nickname");
        verify(plugin.getMessageManager()).send(console, Messages.HELP_COMMAND_USAGES);
        assertEquals(List.of("/nickname <nickname> - Changes your nickname to the given text",
                "/nickname off - Removes your nickname"), lines);

        lines.clear();
        grants.add("allium.nick.others");
        CommandUsageCard.sendInvalid(plugin, console, NICK, "nick", NICK.selfUsage());
        assertEquals(4, lines.size());
        assertEquals("/nick <player> off - Removes the given player's nickname", lines.get(3));
    }

    @Test
    void aSingleFormIsOneUsageLineAndAUsageNamingAnotherCommandIsShownAsWritten() {
        PluginStart plugin = plugin();
        ConsoleCommandSender console = console();
        CommandUsageCard.sendInvalid(plugin, console, CoreCommandSpecs.HAT, "hat", "/hat");
        assertEquals(List.of("Usage: /hat"), lines);

        lines.clear();
        CommandUsageCard.sendInvalid(plugin, console, CoreCommandSpecs.ITEM, "i", CoreCommandSpecs.ITEM.consoleUsage());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("Usage: /give <player> <item[,item...]>"), lines.get(0));

        lines.clear();
        grants.add("allium.gamemode.others");
        CommandUsageCard.sendInvalid(plugin, console, CoreCommandSpecs.GAMEMODE, "gm", "/gamemode <mode> <player>");
        assertEquals(List.of("Usage: /gm <mode> <player>"), lines, "the console sees only the form it can use");
    }
}
