package net.survivalfun.core.spawnercraft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles mob head drops for specific mobs. Uses same note_block_sound tag for identification.
 */
public class MobHeadDropListener implements Listener {

    private static final Random RANDOM = new Random();
    private static final Map<EntityType, MobHeadData> MOB_HEAD_DATA = new HashMap<>();

    private final Plugin plugin;

    static {
        registerMobHead(EntityType.ENDERMAN, 0.001, 0.0011, 0.0001, "entity.enderman.scream",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODk3N2E5NGYwMjQ5OGNhZDBjZmRiNjVjYTdjYjcyZTIzMTExYTkxNGQ4YzY3MGFjY2NjN2E2NWIzNDdkNzc3NiJ9fX0=", "Enderman Head");
        registerMobHead(EntityType.IRON_GOLEM, 0.0015, 0.0016, 0.0001, "entity.iron_golem.hurt",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmU3YzA3MTlmYWJlMTE2ZGNlNjA1MTk5YmNhZGM2OWE1Mzg4NjA4NjRlZjE1NzA2OTgzZmY2NjI4MjJkOWZlMyJ9fX0=", "Iron Golem Head");
        registerMobHead(EntityType.BLAZE, 0.002, 0.0021, 0.0001, "entity.blaze.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGVlMjNkYzdhMTBjNmE4N2VmOTM3NDU0YzBlOTRlZDQyYzIzYWE2NDFhOTFlZDg0NzBhMzA0MmQwNWM1MmM1MiJ9fX0=", "Blaze Head");
        registerMobHead(EntityType.MAGMA_CUBE, 0.0025, 0.0026, 0.0001, "entity.magma_cube.squish",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjgxNzE4ZDQ5ODQ4NDdhNGFkM2VjMDgxYTRlYmZmZDE4Mzc0MzIzOWFlY2FiNjAzMjIxMzhhNzI2MDk4MTJjMyJ9fX0=", "Magma Cube Head");
        registerMobHead(EntityType.SLIME, 0.003, 0.0031, 0.0001, "entity.slime.squish_small",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzA2NDI0ZWM3YTE5NmIxNWY5YWQ1NzMzYTM2YTZkMWYyZTZhMGQ0MmZmY2UxZTE1MDhmOTBmMzEyYWM0Y2FlZCJ9fX0=", "Slime Head");
        registerMobHead(EntityType.GLOW_SQUID, 0.0035, 0.0036, 0.0001, "entity.glow_squid.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGIyZTliNjU4MWZlZDQ4YTk5ZTAzMjMwOTFhZDVjM2MzMjZjZGEyMDA3M2UyOGE5MDJhMDM3M2Y3MzgyYjU5ZiJ9fX0=", "Glow Squid Head");
        registerMobHead(EntityType.SQUID, 0.004, 0.0041, 0.0001, "entity.squid.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODM1MWI3ZDlhNGYzNmNmZTMxZmQ1OWQ4YzkwMGU0MTlhMTM1MTQ0MTA1ZTdhOTgxY2FhNWExNjhkY2ZmMzI1YiJ9fX0=", "Squid Head");
        registerMobHead(EntityType.SPIDER, 0.005, 0.0051, 0.0001, "entity.spider.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGUyOGU2NjI5YjZlZDFkYTk0ZDRhODE4NzYxNjEyYzM2ZmIzYTY4MTNjNGI2M2ZiOWZlYTUwNzY0MTVmM2YwYyJ9fX0=", "Spider Head");
        registerMobHead(EntityType.CREEPER, 0.0045, 0.0046, 0.0001, "entity.creeper.hurt",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjQyNTQ4MzhjMzNlYTIyN2ZmY2EyMjNkZGRhYWJmZTBiMDIxNWY3MGRhNjQ5ZTk0NDQ3N2Y0NDM3MGNhNjk1MiJ9fX0=", "Creeper Head");
        registerMobHead(EntityType.SKELETON, 0.0055, 0.0056, 0.0001, "entity.skeleton.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzAxMjY4ZTljNDkyZGExZjBkODgyNzFjYjQ5MmE0YjMwMjM5NWY1MTVhN2JiZjc3ZjRhMjBiOTVmYzAyZWIyIn19fQ==", "Skeleton Head");
        registerMobHead(EntityType.ZOMBIE, 0.006, 0.0061, 0.0001, "entity.zombie.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTZmYzg1NGJiODRjZjRiNzY5NzI5Nzk3M2UwMmI3OWJjMTA2OTg0NjBiNTFhNjM5YzYwZTVlNDE3NzM0ZTExIn19fQ==", "Zombie Head");
        registerMobHead(EntityType.COW, 0.0065, 0.0066, 0.0001, "entity.cow.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjNkNjIxMTAwZmVhNTg4MzkyMmU3OGJiNDQ4MDU2NDQ4Yzk4M2UzZjk3ODQxOTQ4YTJkYTc0N2Q2YjA4YjhhYiJ9fX0=", "Cow Head");
        registerMobHead(EntityType.SHEEP, 0.007, 0.0071, 0.0001, "entity.sheep.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmRmZTdjYzQ2ZDc0OWIxNTMyNjFjMWRjMTFhYmJmMmEzMTA4ZWExYmEwYjI2NTAyODBlZWQxNTkyZGNmYzc1YiJ9fX0=", "White Sheep Head");
        registerMobHead(EntityType.PIG, 0.0075, 0.0076, 0.0001, "entity.pig.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDFlZTc2ODFhZGYwMDA2N2YwNGJmNDI2MTFjOTc2NDEwNzVhNDRhZTJiMWMwMzgxZDVhYzZiMzI0NjIxMWJmZSJ9fX0=", "Pig Head");
        registerMobHead(EntityType.CHICKEN, 0.008, 0.0081, 0.0001, "entity.chicken.ambient",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDJhZjZlNTg0N2VlYTA5OWUxYjBhYjhjMjBhOWU1ZjNjNzE5MDE1OGJkYTU0ZTI4MTMzZDliMjcxZWMwY2I0YiJ9fX0=", "Chicken Head");
    }

    private static void registerMobHead(EntityType type, double unenchantedChance, double lootingBase,
                                        double lootingPerLevel, String sound, String texture, String itemName) {
        MOB_HEAD_DATA.put(type, new MobHeadData(unenchantedChance, lootingBase, lootingPerLevel, sound, texture, itemName));
    }

    public MobHeadDropListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        EntityType entityType = entity.getType();
        MobHeadData headData = MOB_HEAD_DATA.get(entityType);
        if (headData == null) return;
        Player killer = entity.getKiller();
        if (killer == null) return;
        double dropChance = calculateDropChance(headData, killer);
        if (RANDOM.nextDouble() >= dropChance) return;
        ItemStack head = createMobHead(headData);
        if (head != null) {
            event.getDrops().add(head);
        }
    }

    private double calculateDropChance(MobHeadData headData, Player killer) {
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        int lootingLevel = weapon.getEnchantmentLevel(Enchantment.LOOTING);
        if (lootingLevel <= 0) return headData.unenchantedChance;
        return headData.lootingBase + (headData.lootingPerLevel * (lootingLevel - 1));
    }

    private ItemStack createMobHead(MobHeadData headData) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return null;
        EntityType entityType = getEntityTypeFromSound(headData.sound);
        String hexColor = SpawnerCoreManager.getMobColor(entityType);
        String entityName = SpawnerCoreManager.formatEntityName(entityType);
        meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + "§l" + entityName + " Head");
        List<String> lore = new ArrayList<>();
        lore.add("§7Used to craft " + entityName + " Spawners");
        meta.setLore(lore);
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            String textureUrl = decodeTextureUrl(headData.texture);
            if (textureUrl != null) {
                textures.setSkin(new URL(textureUrl));
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
            }
        } catch (MalformedURLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set mob head texture for " + headData.itemName, e);
        }
        setNoteBlockSound(meta, headData.sound);
        head.setItemMeta(meta);
        return head;
    }

    private String decodeTextureUrl(String base64Texture) {
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(base64Texture));
            int urlStart = decoded.indexOf("\"url\":\"") + 7;
            int urlEnd = decoded.indexOf("\"", urlStart);
            if (urlStart > 6 && urlEnd > urlStart) {
                return decoded.substring(urlStart, urlEnd);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to decode texture", e);
        }
        return null;
    }

    private void setNoteBlockSound(SkullMeta meta, String sound) {
        NamespacedKey soundKey = NamespacedKey.minecraft(sound);
        try {
            Method method = SkullMeta.class.getMethod("setNoteBlockSound", NamespacedKey.class);
            method.setAccessible(true);
            method.invoke(meta, soundKey);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set note_block_sound via SkullMeta", e);
        }
        try {
            Method method = meta.getClass().getMethod("setNoteBlockSound", NamespacedKey.class);
            method.setAccessible(true);
            method.invoke(meta, soundKey);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set note_block_sound", e);
        }
    }

    private EntityType getEntityTypeFromSound(String sound) {
        for (Map.Entry<EntityType, MobHeadData> entry : MOB_HEAD_DATA.entrySet()) {
            if (entry.getValue().sound.equals(sound)) return entry.getKey();
        }
        return null;
    }

    public static boolean handlesMobType(EntityType entityType) {
        return MOB_HEAD_DATA.containsKey(entityType);
    }

    private static class MobHeadData {
        final double unenchantedChance;
        final double lootingBase;
        final double lootingPerLevel;
        final String sound;
        final String texture;
        final String itemName;

        MobHeadData(double unenchantedChance, double lootingBase, double lootingPerLevel,
                    String sound, String texture, String itemName) {
            this.unenchantedChance = unenchantedChance;
            this.lootingBase = lootingBase;
            this.lootingPerLevel = lootingPerLevel;
            this.sound = sound;
            this.texture = texture;
            this.itemName = itemName;
        }
    }
}
