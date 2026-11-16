package me.clip.placeholderapi;
import org.bukkit.entity.Player;
import java.util.*;
/** Test-only ABI host for the fixed placeholders queried by Allium. No external service. */
public final class PlaceholderAPI {
    public static final Map<UUID,String> palette=new HashMap<>();
    public static me.clip.placeholderapi.expansion.PlaceholderExpansion allium;
    public static String setPlaceholders(Player player,String text){
        if(text.equals("%gradient_displayname%"))return palette.getOrDefault(player.getUniqueId(),"&f"+player.getName());
        if(allium!=null&&text.startsWith("%allium_")&&text.endsWith("%"))return allium.onPlaceholderRequest(player,text.substring(8,text.length()-1));
        return text;
    }
}
