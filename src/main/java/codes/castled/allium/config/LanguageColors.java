package codes.castled.allium.config;

import codes.castled.allium.message.LanguageEntry;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;

/** Upgrade only exact former stock text; custom text, sounds and entry shapes survive. */
final class LanguageColors {
 private LanguageColors(){}
 private static final Map<String,String> OLD=Map.of(
  "moderation.discipline.ban-denied","You are banned: {message}",
  "moderation.discipline.login-unavailable","Moderation data is unavailable. Please try again shortly.",
  "moderation.discipline.kick-notice","You were kicked: {message}",
  "info.afk.kick","You were kicked for being AFK.");
 static int upgrade(YamlConfiguration yaml){int count=0;for(var entry:OLD.entrySet()){
  String key=entry.getKey();Object raw=yaml.get(key);var parsed=LanguageEntry.read(raw);
  if(parsed==null||!parsed.text().equals(entry.getValue()))continue;
  if(raw instanceof List<?> values){var copy=new ArrayList<Object>(values);for(int i=0;i<copy.size();i++)if(entry.getValue().equals(copy.get(i)))copy.set(i,"&c"+entry.getValue());yaml.set(key,copy);}
  else if(yaml.isConfigurationSection(key))yaml.set(key+".text","&c"+entry.getValue());
  else yaml.set(key,"&c"+entry.getValue());
  count++;
 }return count;}
}
