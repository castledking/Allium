package codes.castled.allium.item.library;

import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

/**
 * Potion tokens for potions, splash and lingering potions and tipped arrows:
 * {@code potion:<type>} sets the base potion, {@code effect:<type>[:<level>[:<seconds>]]} adds a
 * custom effect, and {@code color:#RRGGBB} colors potions and leather armor.
 *
 * <p>The base potion is version-split: {@code setBasePotionType} from 1.20.2 (and 26.x), else the
 * 1.20.1 {@code setBasePotionData}. 1.20.5 renamed several types, so either spelling is accepted.
 */
public final class PotionAdapter {

    /** Vanilla ids whose 1.20.1 enum names differ. */
    private static final Map<String, String> LEGACY_NAMES = Map.of(
            "swiftness", "SPEED", "leaping", "JUMP", "healing", "INSTANT_HEAL", "harming", "INSTANT_DAMAGE",
            "regeneration", "REGEN");

    private PotionAdapter() {
    }

    public static void basePotion(ItemMeta meta, String raw) {
        if (!(meta instanceof PotionMeta potion)) {
            throw new ItemBuildException("potion: applies to potions and tipped arrows");
        }
        PotionType type = potionType(raw);
        try {
            Method modern = PotionMeta.class.getMethod("setBasePotionType", PotionType.class);
            modern.invoke(potion, type);
        } catch (NoSuchMethodException e) {
            legacyBase(potion, type);
        } catch (ReflectiveOperationException e) {
            throw new ItemBuildException("could not set base potion " + raw + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static void legacyBase(PotionMeta potion, PotionType type) {
        potion.setBasePotionData(new org.bukkit.potion.PotionData(type));
    }

    public static void effect(ItemMeta meta, String raw) {
        if (!(meta instanceof PotionMeta potion)) {
            throw new ItemBuildException("effect: applies to potions and tipped arrows");
        }
        String[] parts = raw.split(":");
        if (parts.length > 3) {
            throw new ItemBuildException("effect: is <type>[:<level>[:<seconds>]]");
        }
        PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(parts[0].toLowerCase(Locale.ROOT)));
        if (type == null) {
            throw new ItemBuildException("unknown effect '" + parts[0] + "'");
        }
        int level = parts.length > 1 ? number(parts[1], "effect level", 1, 255) : 1;
        int seconds = parts.length > 2 ? number(parts[2], "effect seconds", 1, 86_400) : 30;
        potion.addCustomEffect(new PotionEffect(type, seconds * 20, level - 1), true);
    }

    public static void color(ItemMeta meta, String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            throw new ItemBuildException("color: is #RRGGBB");
        }
        Color color = Color.fromRGB(Integer.parseInt(hex, 16));
        if (meta instanceof PotionMeta potion) {
            potion.setColor(color);
        } else if (meta instanceof LeatherArmorMeta leather) {
            leather.setColor(color);
        } else {
            throw new ItemBuildException("color: applies to potions and leather armor");
        }
    }

    private static PotionType potionType(String raw) {
        String id = raw.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        for (PotionType type : PotionType.values()) {
            if (type.name().equalsIgnoreCase(id) || type.name().equalsIgnoreCase(LEGACY_NAMES.getOrDefault(id, ""))
                    || keyOf(type).equals(id)) {
                return type;
            }
        }
        throw new ItemBuildException("unknown potion type '" + raw + "'");
    }

    /** {@code PotionType} is {@code Keyed} from 1.20.2; on 1.20.1 there is no key. */
    private static String keyOf(PotionType type) {
        try {
            Object key = PotionType.class.getMethod("getKey").invoke(type);
            return key == null ? "" : ((NamespacedKey) key).getKey();
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private static int number(String raw, String what, int min, int max) {
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new ItemBuildException(what + " must be " + min + "-" + max);
    }
}
