package net.survivalfun.core.spawnercraft;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages spawner core items - non-placeable spawners used in crafting recipes.
 */
public class SpawnerCoreManager {

    private final NamespacedKey coreKey;
    private final NamespacedKey entityTypeKey;

    private static final Map<EntityType, String> MOB_COLORS = new HashMap<>();

    static {
        MOB_COLORS.put(EntityType.CHICKEN, "#FFFFFF");
        MOB_COLORS.put(EntityType.PIG, "#FFB6C1");
        MOB_COLORS.put(EntityType.SHEEP, "#F5F5DC");
        MOB_COLORS.put(EntityType.COW, "#8B4513");
        MOB_COLORS.put(EntityType.MOOSHROOM, "#CD5C5C");
        MOB_COLORS.put(EntityType.HORSE, "#D2691E");
        MOB_COLORS.put(EntityType.DONKEY, "#A0522D");
        MOB_COLORS.put(EntityType.MULE, "#8B7355");
        MOB_COLORS.put(EntityType.RABBIT, "#DEB887");
        MOB_COLORS.put(EntityType.BAT, "#4A4A4A");
        MOB_COLORS.put(EntityType.PARROT, "#FF6347");
        MOB_COLORS.put(EntityType.CAT, "#FFA500");
        MOB_COLORS.put(EntityType.OCELOT, "#FFD700");
        MOB_COLORS.put(EntityType.WOLF, "#C0C0C0");
        MOB_COLORS.put(EntityType.FOX, "#FF8C00");
        MOB_COLORS.put(EntityType.PANDA, "#FFFAFA");
        MOB_COLORS.put(EntityType.POLAR_BEAR, "#F0FFFF");
        MOB_COLORS.put(EntityType.TURTLE, "#2E8B57");
        MOB_COLORS.put(EntityType.DOLPHIN, "#87CEEB");
        MOB_COLORS.put(EntityType.SQUID, "#483D8B");
        MOB_COLORS.put(EntityType.GLOW_SQUID, "#00FFFF");
        MOB_COLORS.put(EntityType.COD, "#DAA520");
        MOB_COLORS.put(EntityType.SALMON, "#FA8072");
        MOB_COLORS.put(EntityType.TROPICAL_FISH, "#FF69B4");
        MOB_COLORS.put(EntityType.PUFFERFISH, "#FFD700");
        MOB_COLORS.put(EntityType.BEE, "#FFD700");
        MOB_COLORS.put(EntityType.STRIDER, "#8B0000");
        MOB_COLORS.put(EntityType.GOAT, "#D3D3D3");
        MOB_COLORS.put(EntityType.AXOLOTL, "#FFB6C1");
        MOB_COLORS.put(EntityType.FROG, "#32CD32");
        MOB_COLORS.put(EntityType.TADPOLE, "#2F4F4F");
        MOB_COLORS.put(EntityType.ALLAY, "#89CFF0");
        MOB_COLORS.put(EntityType.CAMEL, "#C4A35A");
        MOB_COLORS.put(EntityType.SNIFFER, "#8B4513");
        MOB_COLORS.put(EntityType.ARMADILLO, "#C19A6B");
        MOB_COLORS.put(EntityType.ZOMBIE, "#228B22");
        MOB_COLORS.put(EntityType.SKELETON, "#FFFFF0");
        MOB_COLORS.put(EntityType.CREEPER, "#00FF00");
        MOB_COLORS.put(EntityType.SPIDER, "#2F2F2F");
        MOB_COLORS.put(EntityType.CAVE_SPIDER, "#1E90FF");
        MOB_COLORS.put(EntityType.ENDERMAN, "#800080");
        MOB_COLORS.put(EntityType.SLIME, "#7FFF00");
        MOB_COLORS.put(EntityType.MAGMA_CUBE, "#FF4500");
        MOB_COLORS.put(EntityType.GHAST, "#F8F8FF");
        MOB_COLORS.put(EntityType.BLAZE, "#FF8C00");
        MOB_COLORS.put(EntityType.WITHER_SKELETON, "#1C1C1C");
        MOB_COLORS.put(EntityType.WITCH, "#9400D3");
        MOB_COLORS.put(EntityType.GUARDIAN, "#5F9EA0");
        MOB_COLORS.put(EntityType.ELDER_GUARDIAN, "#008B8B");
        MOB_COLORS.put(EntityType.SHULKER, "#DA70D6");
        MOB_COLORS.put(EntityType.PHANTOM, "#4169E1");
        MOB_COLORS.put(EntityType.DROWNED, "#20B2AA");
        MOB_COLORS.put(EntityType.HUSK, "#D2B48C");
        MOB_COLORS.put(EntityType.STRAY, "#B0E0E6");
        MOB_COLORS.put(EntityType.PILLAGER, "#696969");
        MOB_COLORS.put(EntityType.VINDICATOR, "#708090");
        MOB_COLORS.put(EntityType.RAVAGER, "#363636");
        MOB_COLORS.put(EntityType.VEX, "#ADD8E6");
        MOB_COLORS.put(EntityType.EVOKER, "#4B0082");
        MOB_COLORS.put(EntityType.HOGLIN, "#BC8F8F");
        MOB_COLORS.put(EntityType.ZOGLIN, "#90EE90");
        MOB_COLORS.put(EntityType.PIGLIN, "#FFB347");
        MOB_COLORS.put(EntityType.PIGLIN_BRUTE, "#CD853F");
        MOB_COLORS.put(EntityType.ZOMBIFIED_PIGLIN, "#90EE90");
        MOB_COLORS.put(EntityType.WARDEN, "#1A1A2E");
        MOB_COLORS.put(EntityType.BREEZE, "#87CEEB");
        MOB_COLORS.put(EntityType.BOGGED, "#556B2F");
        MOB_COLORS.put(EntityType.IRON_GOLEM, "#C0C0C0");
        MOB_COLORS.put(EntityType.SNOW_GOLEM, "#FFFAFA");
        MOB_COLORS.put(EntityType.LLAMA, "#F5DEB3");
        MOB_COLORS.put(EntityType.TRADER_LLAMA, "#1E90FF");
        MOB_COLORS.put(EntityType.WANDERING_TRADER, "#4169E1");
        MOB_COLORS.put(EntityType.VILLAGER, "#8B4513");
        MOB_COLORS.put(EntityType.ZOMBIE_VILLAGER, "#556B2F");
        MOB_COLORS.put(EntityType.ENDERMITE, "#800080");
        MOB_COLORS.put(EntityType.SILVERFISH, "#A9A9A9");
        MOB_COLORS.put(EntityType.ENDER_DRAGON, "#9932CC");
        MOB_COLORS.put(EntityType.WITHER, "#1C1C1C");
    }

    public SpawnerCoreManager(final Plugin plugin) {
        this.coreKey = new NamespacedKey(plugin, "spawner_core");
        this.entityTypeKey = new NamespacedKey(plugin, "spawner_entity_type");
    }

    public static String getMobColor(final EntityType entityType) {
        return MOB_COLORS.getOrDefault(entityType, "#FFAA00");
    }

    /** Convert #RRGGBB to §x§R§R§G§G§B§B format. */
    public static String hexColor(final String hex) {
        if (hex == null || hex.length() != 7 || !hex.startsWith("#")) {
            return "";
        }
        StringBuilder result = new StringBuilder("§x");
        for (int i = 1; i < 7; i++) {
            result.append("§").append(hex.charAt(i));
        }
        return result.toString();
    }

    public ItemStack createCore(final EntityType entityType) {
        final ItemStack core = new ItemStack(Material.TRIAL_SPAWNER, 1);
        final ItemMeta meta = core.getItemMeta();
        if (meta == null) {
            return core;
        }
        final String entityName = formatEntityName(entityType);
        final String hexColor = getMobColor(entityType);
        meta.setDisplayName(hexColor(hexColor) + "§l" + entityName + " Spawner Core");
        final List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("§7Surround with 8 " + entityName + " Heads");
        lore.add("§7in a crafting table to create a");
        lore.add(hexColor(hexColor) + entityName + " Spawner");
        lore.add("");
        lore.add("§c⚠ Cannot be placed directly");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(coreKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(entityTypeKey, PersistentDataType.STRING, entityType.name());
        core.setItemMeta(meta);
        return core;
    }

    public boolean isCore(final ItemStack item) {
        if (item == null || item.getType() != Material.TRIAL_SPAWNER) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(coreKey, PersistentDataType.BYTE);
    }

    public EntityType getCoreEntityType(final ItemStack item) {
        if (!isCore(item)) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String entityName = meta.getPersistentDataContainer().get(entityTypeKey, PersistentDataType.STRING);
        if (entityName == null) {
            return null;
        }
        try {
            return EntityType.valueOf(entityName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String formatEntityName(final EntityType entityType) {
        String name = entityType.name().toLowerCase(Locale.ENGLISH);
        String[] words = name.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) result.append(" ");
            String w = words[i];
            if (w.length() > 0) {
                result.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return result.toString();
    }

    public NamespacedKey getCoreKey() {
        return coreKey;
    }

    public NamespacedKey getEntityTypeKey() {
        return entityTypeKey;
    }
}
