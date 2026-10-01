package codes.castled.allium.spawnercraft;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry that detects mob heads from the "More Mob Heads" datapack and Allium's own drops.
 * Datapack heads are identified by their item_name, since several share or borrow note block
 * sounds (mooshroom uses the cow sound, cave spider the spider step). Allium heads have no
 * item_name and fall back to the note_block_sound component, which is hard to fake.
 */
public final class MobHeadRegistry {

    private static final String ITEM_NAMES_RESOURCE = "/spawnercraft/more-mob-heads-names.txt";

    private static final Map<String, EntityType> SOUND_TO_ENTITY = new HashMap<>();
    private static final Map<Material, EntityType> VANILLA_SKULL_TO_ENTITY = new HashMap<>();
    private static final Map<String, String> ITEM_NAME_TO_MOB = loadItemNames();

    private MobHeadRegistry() {}

    static {
        VANILLA_SKULL_TO_ENTITY.put(Material.SKELETON_SKULL, EntityType.SKELETON);
        VANILLA_SKULL_TO_ENTITY.put(Material.WITHER_SKELETON_SKULL, EntityType.WITHER_SKELETON);
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

    /**
     * Registers the mob a head sound belongs to. Called for every mob in
     * spawner_heads.yml so heads for mobs added there are still recognised.
     */
    public static void registerSoundMapping(String entityName, EntityType entityType) {
        if (entityName != null && !entityName.isBlank() && entityType != null) {
            SOUND_TO_ENTITY.put(entityName, entityType);
        }
    }

    private static Map<String, String> loadItemNames() {
        Map<String, String> names = new HashMap<>();
        try (InputStream stream = MobHeadRegistry.class.getResourceAsStream(ITEM_NAMES_RESOURCE)) {
            if (stream == null) return names;
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.lastIndexOf('=');
                if (line.startsWith("#") || split <= 0) continue;
                names.put(line.substring(0, split), line.substring(split + 1).strip());
            }
        } catch (IOException ignored) {
        }
        return names;
    }

    /** Mob id (e.g. "mooshroom") for a datapack head's item_name, or null. */
    static String mobForItemName(String itemName) {
        return ITEM_NAME_TO_MOB.get(itemName);
    }

/**
 * Lower-case mob id the head belongs to (e.g. "cave_spider", "ender_dragon"),
 * or null if the item is not a recognised mob head. Only tagged heads count:
 * Allium's own drops (note_block_sound) and More Mob Heads datapack heads.
 */
    public static String getMobKey(ItemStack item) {
        return getMobKey(item, false);
    }

    /**
     * As {@link #getMobKey(ItemStack)}, but an untagged vanilla skull (a plain creeper
     * head, skeleton skull, zombie head...) counts as its mob too when
     * {@code includeVanillaHeads} is set. Callers decide that per config, so a vanilla
     * head can be a plushie ingredient while never being a spawner ingredient.
     */
    public static String getMobKey(ItemStack item, boolean includeVanillaHeads) {
        if (item == null) return null;
        EntityType vanillaType = VANILLA_SKULL_TO_ENTITY.get(item.getType());
        if (vanillaType != null) {
            // A tagged vanilla skull is a real head only if the tag agrees with it.
            if (!isTaggedFor(item, vanillaType) && !includeVanillaHeads) return null;
            return vanillaType.name().toLowerCase(Locale.ROOT);
        }
        if (item.getType() != Material.PLAYER_HEAD) return null;
        if (!item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        if (meta.hasItemName()) {
            String mob = ITEM_NAME_TO_MOB.get(meta.getItemName());
            if (mob != null) return mob;
        }
        return mobForSound(getNoteBlockSound(meta));
    }

    /**
     * True when the head is tagged as belonging to {@code entityType}, either by a
     * datapack item name or by the mob's note block sound.
     */
    private static boolean isTaggedFor(ItemStack item, EntityType entityType) {
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String mob = entityType.name().toLowerCase(Locale.ROOT);
        if (meta.hasItemName() && mob.equals(ITEM_NAME_TO_MOB.get(meta.getItemName()))) return true;
        NamespacedKey sound = getNoteBlockSound(meta);
        if (sound == null) return false;
        // Several sounds can map to one mob type (allay, villager, zombie villager),
        // so compare the resolved type rather than the sound's mob name.
        EntityType soundType = SOUND_TO_ENTITY.get(mobFromSound(sound));
        return soundType != null && soundType == entityType;
    }

    /**
     * Lower-case mob id the head belongs to (e.g. "cave_spider"), or null if the
     * sound isn't a registered mob's or isn't an entity sound at all.
     */
    private static String mobForSound(NamespacedKey sound) {
        if (sound == null) return null;
        String mob = mobFromSound(sound);
        return SOUND_TO_ENTITY.containsKey(mob) ? mob : null;
    }

    /** "entity.magma_cube.squish" -> "magma_cube"; null when not an entity sound. */
    private static String mobFromSound(NamespacedKey sound) {
        String key = sound.getKey();
        String entityKey = key.startsWith("minecraft:") ? key.substring(10) : key;
        if (!entityKey.startsWith("entity.")) return null;
        String withoutPrefix = entityKey.substring(7);
        int dotIndex = withoutPrefix.indexOf('.');
        return dotIndex > 0 ? withoutPrefix.substring(0, dotIndex) : withoutPrefix;
    }

    public static EntityType getEntityType(ItemStack item) {
        return getEntityType(item, false);
    }

    /** @see #getMobKey(ItemStack, boolean) */
    public static EntityType getEntityType(ItemStack item, boolean includeVanillaHeads) {
        String mob = getMobKey(item, includeVanillaHeads);
        if (mob == null) return null;
        EntityType vanillaType = VANILLA_SKULL_TO_ENTITY.get(item.getType());
        if (vanillaType != null) return vanillaType;
        EntityType type = SOUND_TO_ENTITY.get(mob);
        return type != null ? type : Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(mob));
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

    /** True when this is a datapack head (its item_name names a known mob). */
    public static boolean isDatapackHead(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasItemName() && ITEM_NAME_TO_MOB.containsKey(meta.getItemName());
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
        return isMobHeadForPlacement(item, false);
    }

    /** @see #getMobKey(ItemStack, boolean) */
    public static boolean isMobHeadForPlacement(ItemStack item, boolean includeVanillaHeads) {
        if (item == null) return false;
        if (VANILLA_SKULL_TO_ENTITY.containsKey(item.getType())) return getMobKey(item, includeVanillaHeads) != null;
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
