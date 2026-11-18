package codes.castled.allium.config;

import codes.castled.allium.message.LanguageEntry;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;

/** Upgrade only exact former stock text so custom text, sounds and entry shapes survive. */
final class LanguageColors {
    private LanguageColors() {}

    private record Replacement(String key,String before,String after) {}

    private static final List<Replacement> STOCK = List.of(
            color("moderation.discipline.ban-denied","You are banned: {message}"),
            color("moderation.discipline.login-unavailable","Moderation data is unavailable. Please try again shortly."),
            color("moderation.discipline.kick-notice","You were kicked: {message}"),
            color("info.afk.kick","You were kicked for being AFK."),
            new Replacement("item.give.delivered","{prefix} &aGave &f{amount}x {item} &ato &f{player}&a.","{prefix} &aGave &f{amount} {item} &ato &f{player}&a."),
            new Replacement("item.give.degraded","{prefix} &eGave &f{amount}x {item} &eto &f{player}&e, but a head was given without its skin.","{prefix} &eGave &f{amount} {item} &eto &f{player}&e, but a head was given without its skin."),
            new Replacement("item.give.received","{prefix} &aYou received &f{amount}x {item}&a.","{prefix} &aYou received &f{amount} {item}&a."),
            new Replacement("item.give.delivered","{prefix} &aEntregado &f{amount}x {item} &aa &f{player}&a.","{prefix} &aEntregado &f{amount} {item} &aa &f{player}&a."),
            new Replacement("item.give.degraded","{prefix} &eEntregado &f{amount}x {item} &ea &f{player}&e, pero la cabeza se entrego sin su skin.","{prefix} &eEntregado &f{amount} {item} &ea &f{player}&e, pero la cabeza se entrego sin su skin."),
            new Replacement("item.give.received","{prefix} &aRecibiste &f{amount}x {item}&a.","{prefix} &aRecibiste &f{amount} {item}&a."),
            new Replacement("utility.repair-held","{prefix} &aRepaired the held item.","{prefix} &aRepaired &f{items}&a."),
            new Replacement("utility.repair-all","{prefix} &aRepaired &f{count} &adamaged item(s).","{prefix} &aRepaired &f{items}&a."),
            new Replacement("utility.repair-all","{prefix} &aReparado(s) &f{count} &aobjeto(s) danado(s).","{prefix} &aReparaste &f{items}&a."));

    private static Replacement color(String key,String text) {
        return new Replacement(key,text,"&c"+text);
    }

    static int upgrade(YamlConfiguration yaml) {
        int count=0;
        for(var replacement:STOCK){
            String key=replacement.key();
            Object raw=yaml.get(key);
            var parsed=LanguageEntry.read(raw);
            if(parsed==null||!parsed.text().equals(replacement.before()))continue;
            if(raw instanceof List<?> values){
                var copy=new ArrayList<Object>(values);
                for(int i=0;i<copy.size();i++){
                    if(replacement.before().equals(copy.get(i)))copy.set(i,replacement.after());
                }
                yaml.set(key,copy);
            }else if(yaml.isConfigurationSection(key)){
                yaml.set(key+".text",replacement.after());
            }else{
                yaml.set(key,replacement.after());
            }
            count++;
        }
        return count;
    }
}
