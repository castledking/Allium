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

    public static void legacyEffect(ItemMeta meta,String raw,org.bukkit.Material carrier){
        if(!(meta instanceof PotionMeta potion))throw new ItemBuildException("legacy effects need a potion or tipped arrow");
        String[] parts=raw.split(":");String id=parts[0].toLowerCase(Locale.ROOT);id=switch(id){case "speed"->"speed";case "jump","jump_boost"->"jump_boost";case "instant_heal","heal"->"instant_health";case "instant_damage","harm"->"instant_damage";case "regen"->"regeneration";case "slow"->"slowness";case "increase_damage"->"strength";default->id;};
        int seconds=number(parts[1],"effect seconds",-1,86400),level=number(parts[2],"effect level",0,255);boolean particles=parts.length<4||bool(parts[3]),icon=parts.length<5||bool(parts[4]);
        // A vanilla potion with these exact values is given as that potion, so it is named, colored
        // and brewed like one ("Potion of Poison", not a Water Bottle carrying poison).
        String vanilla=VanillaPotions.key(id);
        if(vanilla!=null&&particles&&icon){String variant=VanillaPotions.variant(vanilla,seconds,level,factor(carrier));if(variant!=null&&tryBase(meta,variant))return;}
        basePotion(meta,"water");
        if(id.equals("turtle_master")){addLegacy(potion,"slowness",duration(seconds>0?seconds:20,carrier),level>=2?5:3,particles,icon);addLegacy(potion,"resistance",duration(seconds>0?seconds:20,carrier),level>=2?3:2,particles,icon);name(meta,"turtle_master",carrier);return;}
        var type=PotionEffectType.getByKey(NamespacedKey.minecraft(id));if(type==null)throw new ItemBuildException("unsupported potion effect: "+id);
        int ticks=seconds>0?duration(seconds,carrier):type.isInstant()?1:defaults(id)*20;
        potion.addCustomEffect(new PotionEffect(type,ticks,Math.max(0,level-1),false,particles,icon),true);
        nameForEffect(meta, id, carrier);
    }
    private static int factor(org.bukkit.Material carrier){return carrier==org.bukkit.Material.TIPPED_ARROW?8:carrier==org.bukkit.Material.LINGERING_POTION?4:1;}
    /** Sets {@code variant} as the base potion when this server has it (1.20.1 lacks the 1.21 potions). */
    private static boolean tryBase(ItemMeta meta,String variant){try{basePotion(meta,variant);return true;}catch(ItemBuildException|IllegalArgumentException missing){return false;}}

    /**
     * Names a potion that carries custom effects after the vanilla potion its first effect belongs
     * to, in the reader's language: "Potion of Poison", "Splash Potion of Swiftness", "Arrow of
     * Leaping". 1.21.2+ has a potion name field for exactly this; older servers get a translated,
     * non-italic item name. An item that already has a name keeps it.
     */
    static boolean name(ItemMeta meta,String key,org.bukkit.Material carrier){
        if(meta.hasDisplayName())return false;
        try{Method custom=meta.getClass().getMethod("setCustomPotionName",String.class);custom.invoke(meta,key);return true;}
        catch(NoSuchMethodException older){/* below */}
        catch(ReflectiveOperationException|RuntimeException failed){return false;}
        return displayName(meta, "item.minecraft." + carrierKey(carrier) + ".effect." + key, true);
    }

    private static void nameForEffect(ItemMeta meta, String id, org.bukkit.Material carrier) {
        String vanilla = VanillaPotions.key(id);
        if (vanilla != null) {
            name(meta, vanilla, carrier);
        } else if (!meta.hasDisplayName()) {
            String prefix = switch (carrier) {
                case SPLASH_POTION -> "Splash Potion of ";
                case LINGERING_POTION -> "Lingering Potion of ";
                case TIPPED_ARROW -> "Arrow of ";
                default -> "Potion of ";
            };
            String effectName = java.util.Arrays.stream(id.split("_"))
                    .filter(word -> !word.isEmpty())
                    .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                    .collect(java.util.stream.Collectors.joining(" "));
            String label = prefix + effectName;
            if (!displayName(meta, label, false)) meta.setDisplayName(label);
        }
    }

    private static boolean displayName(ItemMeta meta, String label, boolean translated) {
        // Paper (Adventure): a non-italic display name.
        for(Method m:meta.getClass().getMethods()){
            if(!m.getName().equals("displayName")||m.getParameterCount()!=1||!m.getParameterTypes()[0].getName().equals("net.kyori.adventure.text.Component"))continue;
            try{
                Class<?> component=m.getParameterTypes()[0],decoration=Class.forName("net.kyori.adventure.text.format.TextDecoration",false,component.getClassLoader());
                Object text=component.getMethod(translated ? "translatable" : "text",String.class).invoke(null,label);
                Object italic=decoration.getField("ITALIC").get(null);
                text=component.getMethod("decoration",decoration,boolean.class).invoke(text,italic,false);
                m.invoke(meta,text);return true;
            }catch(ReflectiveOperationException|LinkageError|RuntimeException failed){return false;}
        }
        // CraftBukkit before 1.20.5 keeps the display name as component JSON.
        for(Class<?> type=meta.getClass();type!=null;type=type.getSuperclass()){
            try{java.lang.reflect.Field field=type.getDeclaredField("displayName");if(field.getType()!=String.class)return false;field.setAccessible(true);field.set(meta,"{\""+(translated?"translate":"text")+"\":\""+label+"\",\"italic\":false}");return true;}
            catch(NoSuchFieldException next){/* superclass */}
            catch(ReflectiveOperationException|RuntimeException failed){return false;}
        }
        return false;
    }
    private static String carrierKey(org.bukkit.Material carrier){return switch(carrier){case SPLASH_POTION->"splash_potion";case LINGERING_POTION->"lingering_potion";case TIPPED_ARROW->"tipped_arrow";default->"potion";};}
    private static void addLegacy(PotionMeta potion,String id,int ticks,int level,boolean particles,boolean icon){var type=PotionEffectType.getByKey(NamespacedKey.minecraft(id));if(type==null)throw new ItemBuildException("Unsupported effect: "+id);potion.addCustomEffect(new PotionEffect(type,ticks,level,false,particles,icon),true);}
    private static int defaults(String id){return switch(id){case "speed","strength","jump_boost","invisibility","night_vision","fire_resistance","water_breathing"->180;case "slowness","weakness","haste","conduit_power"->90;case "mining_fatigue","resistance","luck","slow_falling"->300;case "regeneration","poison","saturation"->45;case "wither"->7;case "nausea","blindness"->15;case "absorption"->120;case "bad_omen","hero_of_the_village"->60;case "glowing"->30;case "levitation","darkness"->10;default->30;};}
    private static int duration(int seconds,org.bukkit.Material carrier){return seconds*20*(carrier==org.bukkit.Material.TIPPED_ARROW?8:carrier==org.bukkit.Material.LINGERING_POTION?4:1);}
    private static boolean bool(String value){return !java.util.Set.of("false","0","off","no").contains(value.toLowerCase(Locale.ROOT));}

    public static void basePotion(ItemMeta meta, String raw) {
        if (!(meta instanceof PotionMeta potion)) {
            throw new ItemBuildException("potion: applies to potions and tipped arrows");
        }
        try {
            Method modern = PotionMeta.class.getMethod("setBasePotionType", PotionType.class);
            modern.invoke(potion, potionType(raw));
        } catch (NoSuchMethodException e) {
            legacyBase(potion, raw);
        } catch (ReflectiveOperationException e) {
            throw new ItemBuildException("could not set base potion " + raw + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static void legacyBase(PotionMeta potion, String raw) {
        String id = raw.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        boolean extended = id.startsWith("long_"), upgraded = id.startsWith("strong_");
        if (extended || upgraded) id = id.substring(id.indexOf('_') + 1);
        potion.setBasePotionData(new org.bukkit.potion.PotionData(potionType(id), extended, upgraded));
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

    /** After the tokens: a potion whose base is plain water (or none) is named after its first custom effect. */
    public static void nameFromEffects(ItemMeta meta, org.bukkit.Material carrier) {
        if (!(meta instanceof PotionMeta potion) || !potion.hasCustomEffects() || !plainBase(potion)) return;
        nameForEffect(meta, potion.getCustomEffects().get(0).getType().getKey().getKey(), carrier);
    }

    @SuppressWarnings("deprecation")
    private static boolean plainBase(PotionMeta potion) {
        try {
            Object type = PotionMeta.class.getMethod("getBasePotionType").invoke(potion);
            return type == null || java.util.Set.of("WATER", "AWKWARD", "MUNDANE", "THICK", "UNCRAFTABLE").contains(((Enum<?>) type).name());
        } catch (NoSuchMethodException older) {
            var data = potion.getBasePotionData();
            return data == null || java.util.Set.of("WATER", "AWKWARD", "MUNDANE", "THICK", "UNCRAFTABLE").contains(data.getType().name());
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return false;
        }
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
