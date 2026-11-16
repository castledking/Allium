package gate;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
/** Disposable command metadata fixtures; never installed outside the gate. */
public final class HideGate extends JavaPlugin {
 public void onEnable(){if(!getServer().getIp().equals("127.0.0.1")||getServer().getPort()<25780||getServer().getPort()>25782)throw new IllegalStateException("owned loopback only");}
 public boolean onCommand(CommandSender sender,Command c,String label,String[] args){sender.sendMessage("HIDE-ACTION "+c.getName()+" "+String.join(" ",args));return true;}
 public List<String> onTabComplete(CommandSender sender,Command c,String label,String[] args){return args.length<=1?List.of("invite","kick","hiddenbranch"):List.of("Alice","Bob");}
}
