package codes.castled.allium.message;

import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

/** A locale entry is inert text plus one optional sound, never executable directives. */
public record LanguageEntry(String text, MessageSoundSpec sound) {
    public static LanguageEntry read(Object value) {
        if(value instanceof String text)return new LanguageEntry(text,null);
        if(value instanceof ConfigurationSection section){
            if(!section.contains("text")&&!section.contains("sound"))return null;
            return new LanguageEntry(section.getString("text",""),sound(section.getString("sound"),
                section.contains("volume")?section.getDouble("volume"):null,
                section.contains("pitch")?section.getDouble("pitch"):null));
        }
        if(!(value instanceof List<?> lines))return null;
        var text=new ArrayList<String>();MessageSoundSpec selected=null;boolean soundSeen=false;
        for(Object raw:lines){
            if(!(raw instanceof String line))continue;
            if(line.startsWith("[sound]")){
                if(soundSeen)continue;soundSeen=true;
                String[] parts=line.substring(7).trim().split("\\s+");
                if(parts.length<1||parts.length>3)continue;
                selected=sound(parts[0],parts.length==3?number(parts[2]):null,parts.length>=2?number(parts[1]):null);
            }else text.add(line);
        }
        return new LanguageEntry(String.join("\n",text),selected);
    }
    private static Double number(String value){try{double n=Double.parseDouble(value);return Double.isFinite(n)?n:null;}catch(NumberFormatException invalid){return null;}}
    private static MessageSoundSpec sound(String id,Double volume,Double pitch){
        if(id==null||id.isBlank()||Set.of("none","off").contains(id.trim().toLowerCase(Locale.ROOT)))return null;
        id=id.trim();if(!id.contains(":")&&id.contains("."))id="minecraft:"+id;
        return new MessageSoundSpec(id,volume,pitch);
    }
}
