package codes.castled.allium.chat;
import codes.castled.allium.PluginStart;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
public final class AlliumChatExpansion extends PlaceholderExpansion implements AutoCloseable {
    private final ChatService service;private final PluginStart plugin;
    public AlliumChatExpansion(ChatService service,PluginStart plugin){this.service=service;this.plugin=plugin;if(!register())throw new IllegalStateException("allium placeholder identifier already registered");}
    @Override public String getIdentifier(){return "allium";}
    @Override public String getAuthor(){return "castledking";}
    @Override public String getVersion(){return plugin.getDescription().getVersion();}
    @Override public boolean persist(){return true;}
    @Override public String onPlaceholderRequest(Player player,String key){if(player==null)return "";return switch(key){case "nickname"->service.placeholder(player,false);case "gradientdisplayname"->service.placeholder(player,true);default->null;};}
    @Override public void close(){unregister();}
}
