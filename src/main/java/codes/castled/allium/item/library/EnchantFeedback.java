package codes.castled.allium.item.library;
import codes.castled.allium.PluginStart;
import org.bukkit.command.CommandSender;
/** Locale boundary for typed enchant notices emitted by the item builder. */
public final class EnchantFeedback {
 private EnchantFeedback(){}
 public static boolean send(PluginStart plugin,CommandSender sender,String text){if(text==null||!text.startsWith("@enchant|"))return false;String[] p=text.split(java.util.regex.Pattern.quote("|"),-1);if(p.length!=6)return false;plugin.getMessageManager().sendByKey(sender,"give."+p[1],"{enchant}",p[2],"{item}",p[3],"{enchant2}",p[4],"{maxLevel}",p[5]);return true;}
}
