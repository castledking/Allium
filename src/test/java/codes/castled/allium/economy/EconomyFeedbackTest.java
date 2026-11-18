package codes.castled.allium.economy;
import codes.castled.allium.PluginStart;
import codes.castled.allium.message.*;
import codes.castled.allium.scheduler.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class EconomyFeedbackTest {
 @Test void balanceUsesConfiguredLanguageAfterWorkerReadsAvailableFunds(){
  var plugin=mock(PluginStart.class);var messages=mock(MessageManager.class);when(plugin.getMessageManager()).thenReturn(messages);var scheduler=mock(TaskScheduler.class);when(plugin.getScheduler()).thenReturn(scheduler);when(scheduler.runOnServer(any())).thenAnswer(c->{((Runnable)c.getArgument(0)).run();return TaskHandle.NONE;});
  var player=mock(Player.class);var id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);when(player.hasPermission("allium.balance")).thenReturn(true);
  var engine=mock(EconomyEngine.class);when(engine.balance(id)).thenReturn(new EconomyResult.Found(new Money(10000),new Money(2500),0));var jobs=new ArrayDeque<Runnable>();var runtime=new EconomyRuntime(plugin,engine,jobs::add);
  runtime.dispatch(player,"balance",new String[0]);verifyNoInteractions(messages);jobs.remove().run();verify(messages).send(player,Messages.BALANCE,"{amount}","$75");verify(player,never()).sendMessage(anyString());
 }
}
