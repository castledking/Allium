package codes.castled.allium.item.library;
import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
/** Locale boundary for typed enchant notices emitted by the item builder. */
public final class EnchantFeedback {
 private EnchantFeedback(){}
 public static boolean send(PluginStart plugin,CommandSender sender,String text){if(text==null||!text.startsWith("@enchant|"))return false;String[] p=text.split(java.util.regex.Pattern.quote("|"),-1);if(p.length!=6)return false;Runnable deliver=()->plugin.getMessageManager().sendByKey(sender,"give."+p[1],"{enchant}",p[2],"{item}",p[3],"{enchant2}",p[4],"{maxLevel}",p[5]);if(BukkitServerThread.isFolia()&&plugin.getScheduler()!=null)BukkitServerThread.onOwner(plugin,plugin.getScheduler(),sender,()->{if(!(sender instanceof Player player)||player.isOnline())deliver.run();});else deliver.run();return true;}
}
