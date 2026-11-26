package codes.castled.menus;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Converts the catalog's typed potion and firework components on pre-component servers. */
final class LegacyShopItems {
    private static final Pattern FIREWORK = Pattern.compile("minecraft:fireworks=\\{flight_duration:([1-3])b}");
    private static final Map<String, String> LEGACY_NAMES = Map.of("swiftness", "SPEED", "leaping", "JUMP",
            "healing", "INSTANT_HEAL", "harming", "INSTANT_DAMAGE", "regeneration", "REGENERATION");

    private LegacyShopItems() {}

    static ItemStack create(ShopCatalog.Product product) {
        Material material = Material.matchMaterial(product.reference());
        if (material == null || !material.isItem() || material.isAir()) return null;
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        if (meta instanceof PotionMeta potion && product.reference().startsWith("minecraft:")) {
            var match = ShopCatalog.POTION.matcher(product.itemArgument());
            if (!match.find()) return null;
            String id = match.group(1);
            boolean extended = id.startsWith("long_");
            boolean strong = id.startsWith("strong_");
            String type = extended ? id.substring(5) : strong ? id.substring(7) : id;
            try {
                var kind = PotionType.valueOf(LEGACY_NAMES.getOrDefault(type, type.toUpperCase(Locale.ROOT)));
                potion.setBasePotionData(new PotionData(kind, extended, strong));
            } catch (IllegalArgumentException | LinkageError unavailable) {
                return null;
            }
        } else if (meta instanceof FireworkMeta firework) {
            var match = FIREWORK.matcher(product.itemArgument());
            if (!match.find()) return null;
            firework.setPower(Integer.parseInt(match.group(1)));
        } else return null;
        stack.setItemMeta(meta);
        return stack;
    }
}
