package codes.castled.allium.item.library;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import java.util.*;
public final class EnchantCompletions {
 private EnchantCompletions(){}
 public static List<String> names(ItemStack item,String prefix,Collection<Enchantment> existing){return names(Registry.ENCHANTMENT,item,prefix,existing);}
 public static List<String> names(Iterable<Enchantment> registry,ItemStack item,String prefix,Collection<Enchantment> existing){var out=new TreeSet<String>();for(var enchant:registry){String key=enchant.getKey().getNamespace().equals("minecraft")?enchant.getKey().getKey():enchant.getKey().toString();if(!key.startsWith(prefix)&&!enchant.getKey().toString().startsWith(prefix))continue;if(item!=null&&item.getType()!=Material.ENCHANTED_BOOK&&!enchant.canEnchantItem(item))continue;if(existing.stream().anyMatch(other->other.equals(enchant)||other.conflictsWith(enchant)||enchant.conflictsWith(other)))continue;out.add(prefix.startsWith("minecraft:")?enchant.getKey().toString():key);}return out.stream().limit(128).toList();}
}
