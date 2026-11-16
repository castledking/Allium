package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.text.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Optional APIs live only in guarded nested classes; cache is refreshed on the server thread. */
public final class ChatDecorations {
    public record Value(String prefix,String suffix,String group,List<Integer> colors){public static final Value EMPTY=new Value("","","default",List.of());}
    private final PluginStart plugin;
    private boolean resolving;
    public ChatDecorations(PluginStart plugin){this.plugin=plugin;}
    public Value query(Player player){
        String[] values={"","","default"};List<Integer> colors=List.of();
        try{if(plugin.getServer().getPluginManager().getPlugin("Vault")!=null)values=Vault.read(plugin,player);}catch(LinkageError|RuntimeException ignored){}
        if(resolving)return new Value(values[0],values[1],values[2],colors);
        try{
            resolving=true;
            if(plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")){
                if(values[0].isEmpty())values[0]=Papi.value(player,"%luckperms_prefix%");
                if(values[1].isEmpty())values[1]=Papi.value(player,"%luckperms_suffix%");
                if(plugin.getConfig().getBoolean("nickname.gradientplus-enabled",true))colors=palette(Papi.value(player,"%gradient_displayname%"));
            }
        }catch(LinkageError|RuntimeException ignored){}finally{resolving=false;}
        return new Value(values[0],values[1],values[2],colors);
    }
    public static List<Integer> palette(String rendered){
        if(rendered==null||rendered.length()>4096)return List.of();
        List<Integer> out=new ArrayList<>();
        for(var glyph:TextFormatter.parse(rendered).glyphs()){
            int rgb=glyph.rgb()!=-1?glyph.rgb():glyph.legacyColorChar()!='\0'?TextFormatter.legacyColorRgb(glyph.legacyColorChar()):-1;
            if(rgb!=-1&&(out.isEmpty()||out.get(out.size()-1)!=rgb))out.add(rgb);
        }
        if(out.isEmpty()||out.stream().allMatch(v->v==0xffffff))return List.of();
        return List.of(out.get(0),out.get(out.size()-1));
    }
    private static final class Papi {
        static String value(Player player,String key){String value=me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player,key);return value==null||value.equals(key)?"":value;}
    }
    private static final class Vault {
        static String[] read(PluginStart plugin,Player player){
            var chat=plugin.getServer().getServicesManager().load(net.milkbowl.vault.chat.Chat.class);
            var permission=plugin.getServer().getServicesManager().load(net.milkbowl.vault.permission.Permission.class);
            return new String[]{chat==null?"":Objects.toString(chat.getPlayerPrefix(player),""),chat==null?"":Objects.toString(chat.getPlayerSuffix(player),""),permission==null?"default":Objects.toString(permission.getPrimaryGroup(player),"default")};
        }
    }
}
