package codes.castled.allium.item.library;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code <enchantment>:<level>} tokens ({@code sharpness:5}) through the Bukkit enchantment
 * registry. Enchanted books get stored enchantments. Levels above the vanilla maximum (up to 255)
 * and enchantments that do not normally apply are allowed, with a warning.
 */
public final class EnchantmentAdapter {

    public static final int MAX_LEVEL = 255;

    private EnchantmentAdapter() {
    }

    public static Optional<Enchantment> find(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        Enchantment exact=namespaced==null?null:Registry.ENCHANTMENT.get(namespaced);if(exact!=null)return Optional.of(exact);
        if(key.contains(":"))return Optional.empty();Enchantment found=null;for(var enchant:Registry.ENCHANTMENT)if(enchant.getKey().getKey().equalsIgnoreCase(key)){if(found!=null)return Optional.empty();found=enchant;}return Optional.ofNullable(found);
    }

    public static java.util.List<String> validateRaw(ItemStack stack,boolean unsafe){
        ItemMeta meta=stack.getItemMeta();if(meta==null)return java.util.List.of();var existing=meta instanceof EnchantmentStorageMeta book?book.getStoredEnchants():meta.getEnchants();if(existing.isEmpty())return java.util.List.of();var notices=new java.util.ArrayList<String>();for(var entry:existing.entrySet()){String notice=apply(stack,meta,entry.getKey(),Integer.toString(entry.getValue()),unsafe);if(notice!=null)notices.add(notice);}stack.setItemMeta(meta);return java.util.List.copyOf(notices);
    }
    /** @return a warning, or {@code null} */
    public static String apply(ItemStack stack,ItemMeta meta,Enchantment enchantment,String rawLevel){return apply(stack,meta,enchantment,rawLevel,true);}
    public static java.util.List<String> complete(ItemStack item,String prefix){var out=new java.util.TreeSet<String>();for(var e:Registry.ENCHANTMENT){if(item!=null&&item.getType()!=org.bukkit.Material.ENCHANTED_BOOK&&!e.canEnchantItem(item))continue;String key=e.getKey().getNamespace().equals("minecraft")?e.getKey().getKey():e.getKey().toString();if(key.startsWith(prefix.toLowerCase(Locale.ROOT)))out.add(key);}return out.stream().limit(128).toList();}
    public static String apply(ItemStack stack, ItemMeta meta, Enchantment enchantment, String rawLevel,boolean allowUnsafe) {
        int level;
        try {
            level = Integer.parseInt(rawLevel);
        } catch (NumberFormatException e) {
            throw new ItemBuildException("enchantment level '" + rawLevel + "' is not a number");
        }
        if (level < 1 || level > MAX_LEVEL) {
            throw new ItemBuildException("enchantment level must be 1-" + MAX_LEVEL);
        }
        String id=codes.castled.allium.command.essentials.UtilitySupport.enchantName(enchantment.getKey()),item=codes.castled.allium.command.essentials.UtilitySupport.formatMaterialName(stack.getType());String notice=null;
        if(!allowUnsafe){
            if(!(meta instanceof EnchantmentStorageMeta)&&!enchantment.canEnchantItem(stack))throw new ItemBuildException("@enchant|unsafe-enchant|"+id+"|"+item+"||");
            var existing=meta instanceof EnchantmentStorageMeta book?book.getStoredEnchants():meta.getEnchants();
            for(var other:existing.keySet())if(!other.equals(enchantment)&&(other.conflictsWith(enchantment)||enchantment.conflictsWith(other)))throw new ItemBuildException("@enchant|conflicting-enchants|"+id+"|"+item+"|"+codes.castled.allium.command.essentials.UtilitySupport.enchantName(other.getKey())+"|");
            if(level>enchantment.getMaxLevel()){level=enchantment.getMaxLevel();notice="@enchant|unsafe-level|"+id+"|"+item+"||"+level;}
        }
        if(meta instanceof EnchantmentStorageMeta book)book.addStoredEnchant(enchantment,level,allowUnsafe);else meta.addEnchant(enchantment,level,allowUnsafe);
        return notice;
    }
}
