package codes.castled.allium.item.library;
import codes.castled.allium.PluginStart;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
/** Immutable alias index. Existing operator files are never replaced by the bundled database. */
public final class LegacyItemDatabase {
 private final Map<String,String> values;
 private final NavigableSet<String> completions;
 public LegacyItemDatabase(Map<String,String> aliases){values=Map.copyOf(aliases);var names=new TreeSet<String>();for(var e:aliases.entrySet()){var material=Material.matchMaterial(e.getValue().split(";",2)[0]);if(material!=null&&material.isItem()&&!material.isAir())names.add(e.getKey());}for(Material m:Material.values())if(m.isItem()&&!m.isAir())names.add(m.name().toLowerCase(Locale.ROOT));completions=Collections.unmodifiableNavigableSet(names);}
 public static LegacyItemDatabase load(PluginStart plugin)throws IOException,org.bukkit.configuration.InvalidConfigurationException{
  Path file=plugin.getDataFolder().toPath().resolve("itemdb.yml");
  if(!Files.exists(file)){byte[] bytes;try(var resource=plugin.getResource("itemdb.yml.gz")){if(resource==null)throw new IOException("Missing itemdb.yml.gz");try(var gzip=new GZIPInputStream(resource)){bytes=gzip.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)throw new IOException("Item database too large");}}Path temp=Files.createTempFile(file.getParent(),".itemdb-",".tmp");try{Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}
  if(Files.size(file)>8*1024*1024)throw new IOException("Item database too large");var yaml=new YamlConfiguration();yaml.load(file.toFile());var aliases=new HashMap<String,String>();for(String group:List.of("aliases","legacy-ids")){var section=yaml.getConfigurationSection(group);if(section==null)continue;for(String name:section.getKeys(false)){Object value=section.get(name);if(!(value instanceof String text)||text.length()>4096)throw new IOException("Invalid item alias: "+name);aliases.put(name.toLowerCase(Locale.ROOT),text);}}if(aliases.size()>100000)throw new IOException("Too many item aliases");return new LegacyItemDatabase(aliases);
 }
 public String lookup(String reference){String id=reference.toLowerCase(Locale.ROOT);if(id.contains(":")&&!id.matches("[0-9]+:[0-9]+"))return null;Material direct=Material.matchMaterial(reference);return direct!=null?null:values.get(id);}
 public List<String> complete(String prefix,Collection<String> allium){var out=new TreeSet<String>();for(String name:allium)if(name.startsWith(prefix))out.add(name);for(String name:completions.tailSet(prefix,true)){if(!name.startsWith(prefix)||out.size()>=64)break;out.add(name);}return out.stream().limit(64).toList();}
}
