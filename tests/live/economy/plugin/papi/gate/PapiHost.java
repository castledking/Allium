package gate;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.UUID;
public final class PapiHost extends JavaPlugin {
    @Override public void onEnable(){if(!getServer().getIp().equals("127.0.0.1")||getServer().getPort()<25800||getServer().getPort()>25802)throw new IllegalStateException("owned fixture ports only");}
    public void palette(UUID id,String value){me.clip.placeholderapi.PlaceholderAPI.palette.put(id,value);}
    public String expand(org.bukkit.entity.Player player,String key){return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player,key);}
}
