package codes.castled.allium.sign;
import codes.castled.allium.PluginStart;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.*;
/** The runtime event is newer than the shared 1.20 API; do not link it in shared signatures. */
final class SignEditorGuard implements Listener,AutoCloseable {
 @SuppressWarnings("unchecked") SignEditorGuard(PluginStart plugin,SignService service){
  try{
   Class<?> type=Class.forName("org.bukkit.event.player.PlayerSignOpenEvent");var sign=type.getMethod("getSign");var side=type.getMethod("getSide");var cause=type.getMethod("getCause");
   plugin.getServer().getPluginManager().registerEvent((Class<? extends Event>)type,this,EventPriority.HIGHEST,(listener,event)->{try{if(service.blocksEditor((Sign)sign.invoke(event),(Side)side.invoke(event),cause.invoke(event).toString()))((Cancellable)event).setCancelled(true);}catch(ReflectiveOperationException failure){throw new EventException(failure);}},PluginStart.bukkitPlugin(plugin),true);
  }catch(ReflectiveOperationException|LinkageError absent){plugin.logger().warning("Sign editor guard unavailable on this platform; actionable signs should be waxed.");}
 }
 public void close(){HandlerList.unregisterAll(this);}
}
