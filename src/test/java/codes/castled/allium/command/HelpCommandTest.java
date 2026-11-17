package codes.castled.allium.command;
import codes.castled.allium.PluginStart;
import codes.castled.allium.hide.HideService;
import codes.castled.allium.message.*;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class HelpCommandTest {
 @Test void pagesClampAndReturnOnlyEightRows() {
  var plugin=mock(PluginStart.class);var messages=mock(MessageManager.class);when(plugin.getMessageManager()).thenReturn(messages);var sender=mock(ConsoleCommandSender.class);when(sender.hasPermission("allium.help")).thenReturn(true);
  var help=spy(new HelpCommand(plugin));doReturn(IntStream.range(0,17).mapToObj(i->new HideService.HelpEntry("cmd"+i,"Owner","description")).toList()).when(help).entries(sender);
  help.execute(new BukkitCommandContext(plugin,help.spec(),sender,"help",new String[]{"2"}));verify(messages).send(sender,Messages.HELP_HEADER,"{page}","2","{pages}","3");verify(messages,times(8)).resolve(eq(Messages.HELP_ENTRY),any(String[].class));
  clearInvocations(messages);help.execute(new BukkitCommandContext(plugin,help.spec(),sender,"help",new String[]{"999"}));verify(messages).send(sender,Messages.HELP_HEADER,"{page}","3","{pages}","3");verify(messages,times(1)).resolve(eq(Messages.HELP_ENTRY),any(String[].class));
 }
}
