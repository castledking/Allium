package net.survivalfun.core.spawnercraft;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry that detects mob heads from the "More Mob Heads" datapack.
 * Uses the note_block_sound component which is unique and hard to fake.
 */
public final class MobHeadRegistry {

    private static final Map<String, EntityType> SOUND_TO_ENTITY = new HashMap<>();
    private static final Map<Material, EntityType> VANILLA_SKULL_TO_ENTITY = new HashMap<>();

    private MobHeadRegistry() {}

    static {
        VANILLA_SKULL_TO_ENTITY.put(Material.SKELETON_SKULL, EntityType.SKELETON);
        VANILLA_SKULL_TO_ENTITY.put(Material.ZOMBIE_HEAD, EntityType.ZOMBIE);
        VANILLA_SKULL_TO_ENTITY.put(Material.CREEPER_HEAD, EntityType.CREEPER);
        VANILLA_SKULL_TO_ENTITY.put(Material.DRAGON_HEAD, EntityType.ENDER_DRAGON);
        VANILLA_SKULL_TO_ENTITY.put(Material.PIGLIN_HEAD, EntityType.PIGLIN);
        registerSound("iron_golem", EntityType.IRON_GOLEM);
        registerSound("allay", EntityType.ALLAY);
        registerSound("armadillo", EntityType.ARMADILLO);
        registerSound("axolotl", EntityType.AXOLOTL);
        registerSound("bat", EntityType.BAT);
        registerSound("bee", EntityType.BEE);
        registerSound("blaze", EntityType.BLAZE);
        registerSound("bogged", EntityType.BOGGED);
        registerSound("breeze", EntityType.BREEZE);
        registerSound("camel", EntityType.CAMEL);
        registerSound("cat", EntityType.CAT);
        registerSound("cave_spider", EntityType.CAVE_SPIDER);
        registerSound("chicken", EntityType.CHICKEN);
        registerSound("cod", EntityType.COD);
        registerSound("cow", EntityType.COW);
        registerSound("creeper", EntityType.CREEPER);
        registerSound("dolphin", EntityType.DOLPHIN);
        registerSound("donkey", EntityType.DONKEY);
        registerSound("drowned", EntityType.DROWNED);
        registerSound("elder_guardian", EntityType.ELDER_GUARDIAN);
        registerSound("enderman", EntityType.ENDERMAN);
        registerSound("endermite", EntityType.ENDERMITE);
        registerSound("evoker", EntityType.EVOKER);
        registerSound("fox", EntityType.FOX);
        registerSound("frog", EntityType.FROG);
        registerSound("ghast", EntityType.GHAST);
        registerSound("glow_squid", EntityType.GLOW_SQUID);
        registerSound("goat", EntityType.GOAT);
        registerSound("guardian", EntityType.GUARDIAN);
        registerSound("hoglin", EntityType.HOGLIN);
        registerSound("horse", EntityType.HORSE);
        registerSound("husk", EntityType.HUSK);
        registerSound("illusioner", EntityType.ILLUSIONER);
        registerSound("llama", EntityType.LLAMA);
        registerSound("magma_cube", EntityType.MAGMA_CUBE);
        registerSound("mooshroom", EntityType.MOOSHROOM);
        registerSound("mule", EntityType.MULE);
        registerSound("ocelot", EntityType.OCELOT);
        registerSound("panda", EntityType.PANDA);
        registerSound("parrot", EntityType.PARROT);
        registerSound("phantom", EntityType.PHANTOM);
        registerSound("pig", EntityType.PIG);
        registerSound("piglin", EntityType.PIGLIN);
        registerSound("piglin_brute", EntityType.PIGLIN_BRUTE);
        registerSound("pillager", EntityType.PILLAGER);
        registerSound("polar_bear", EntityType.POLAR_BEAR);
        registerSound("pufferfish", EntityType.PUFFERFISH);
        registerSound("rabbit", EntityType.RABBIT);
        registerSound("ravager", EntityType.RAVAGER);
        registerSound("salmon", EntityType.SALMON);
        registerSound("sheep", EntityType.SHEEP);
        registerSound("shulker", EntityType.SHULKER);
        registerSound("silverfish", EntityType.SILVERFISH);
        registerSound("skeleton", EntityType.SKELETON);
        registerSound("skeleton_horse", EntityType.SKELETON_HORSE);
        registerSound("slime", EntityType.SLIME);
        registerSound("sniffer", EntityType.SNIFFER);
        registerSound("snow_golem", EntityType.SNOW_GOLEM);
        registerSound("spider", EntityType.SPIDER);
        registerSound("squid", EntityType.SQUID);
        registerSound("stray", EntityType.STRAY);
        registerSound("strider", EntityType.STRIDER);
        registerSound("tadpole", EntityType.TADPOLE);
        registerSound("trader_llama", EntityType.TRADER_LLAMA);
        registerSound("tropical_fish", EntityType.TROPICAL_FISH);
        registerSound("turtle", EntityType.TURTLE);
        registerSound("vex", EntityType.VEX);
        registerSound("villager", EntityType.VILLAGER);
        registerSound("vindicator", EntityType.VINDICATOR);
        registerSound("wandering_trader", EntityType.WANDERING_TRADER);
        registerSound("warden", EntityType.WARDEN);
        registerSound("witch", EntityType.WITCH);
        registerSound("wither", EntityType.WITHER);
        registerSound("wolf", EntityType.WOLF);
        registerSound("zoglin", EntityType.ZOGLIN);
        registerSound("zombie", EntityType.ZOMBIE);
        registerSound("zombie_horse", EntityType.ZOMBIE_HORSE);
        registerSound("zombie_villager", EntityType.ZOMBIE_VILLAGER);
        registerSound("zombified_piglin", EntityType.ZOMBIFIED_PIGLIN);
    }

    private static void registerSound(String entityName, EntityType entityType) {
        SOUND_TO_ENTITY.put(entityName, entityType);
    }

    public static EntityType getEntityType(ItemStack item) {
        if (item == null) return null;
        EntityType vanillaType = VANILLA_SKULL_TO_ENTITY.get(item.getType());
        if (vanillaType != null) return vanillaType;
        if (item.getType() != Material.PLAYER_HEAD) return null;
        if (!item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        NamespacedKey sound = getNoteBlockSound(meta);
        if (sound == null) return null;
        String key = sound.getKey();
        String entityKey = key.startsWith("minecraft:") ? key.substring(10) : key;
        if (!entityKey.startsWith("entity.")) return null;
        String withoutPrefix = entityKey.substring(7);
        int dotIndex = withoutPrefix.indexOf('.');
        String entityName = dotIndex > 0 ? withoutPrefix.substring(0, dotIndex) : withoutPrefix;
        return SOUND_TO_ENTITY.get(entityName);
    }

    private static NamespacedKey getNoteBlockSound(ItemMeta meta) {
        if (meta instanceof SkullMeta) {
            try {
                Method method = SkullMeta.class.getMethod("getNoteBlockSound");
                method.setAccessible(true);
                Object result = method.invoke(meta);
                if (result instanceof NamespacedKey) return (NamespacedKey) result;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {}
        }
        try {
            Method method = ItemMeta.class.getMethod("getNoteBlockSound");
            method.setAccessible(true);
            Object result = method.invoke(meta);
            if (result instanceof NamespacedKey) return (NamespacedKey) result;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {}
        try {
            Method method = meta.getClass().getMethod("getNoteBlockSound");
            method.setAccessible(true);
            Object result = method.invoke(meta);
            if (result instanceof NamespacedKey) return (NamespacedKey) result;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {}
        return null;
    }

    public static boolean isMobHead(ItemStack item) {
        return getEntityType(item) != null;
    }

    public static boolean isVanillaSkull(Material material) {
        return VANILLA_SKULL_TO_ENTITY.containsKey(material);
    }

    public static boolean isAnyHeadType(ItemStack item) {
        if (item == null) return false;
        return item.getType() == Material.PLAYER_HEAD || VANILLA_SKULL_TO_ENTITY.containsKey(item.getType());
    }

    public static boolean isMobHeadForPlacement(ItemStack item) {
        if (item == null) return false;
        if (VANILLA_SKULL_TO_ENTITY.containsKey(item.getType())) return true;
        if (item.getType() != Material.PLAYER_HEAD) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return getNoteBlockSound(meta) != null;
    }

    public static EntityType getUniformEntityType(ItemStack[] items) {
        EntityType firstType = null;
        for (ItemStack item : items) {
            if (item == null) continue;
            EntityType type = getEntityType(item);
            if (type == null) return null;
            if (firstType == null) firstType = type;
            else if (firstType != type) return null;
        }
        return firstType;
    }
}
