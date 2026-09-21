package codes.castled.allium.spawnercraft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Handles mob head drops for specific mobs. Uses same note_block_sound tag for identification.
 */
public class MobHeadDropListener implements Listener {

    private static final Random RANDOM = new Random();

    // SpawnerMeta stacked mobs: a killing blow on a stack larger than one is
    // cancelled and the loot dropped by hand ("virtual kill"), so no
    // EntityDeathEvent fires for any mob but the last one in the stack.
    private static final NamespacedKey SPAWNERMETA_STACK = new NamespacedKey("spawnermeta", "managed_spawned_stack");
    private static final NamespacedKey SPAWNERMETA_STACK_AMOUNT = new NamespacedKey("spawnermeta", "managed_spawned_stack_amount");
    private static final NamespacedKey SPAWNERMETA_LOOTING = new NamespacedKey("spawnermeta", "looting_multiplier");

    private final Plugin plugin;
    private final Map<UUID, Integer> pendingStackKills = new ConcurrentHashMap<>();

    public MobHeadDropListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        EntityType type = entity.getType();
        if (SpawnerHeadConfig.handles(type) && SpawnerHeadConfig.overrideDatapackHeads()) {
            // One head source per mob: drop the datapack's head for this mob so only
            // Allium's roll below can produce one.
            String mob = type.name().toLowerCase(Locale.ROOT);
            event.getDrops().removeIf(drop ->
                    MobHeadRegistry.isDatapackHead(drop) && mob.equals(MobHeadRegistry.getMobKey(drop)));
        }
        ItemStack head = rollHead(type, entity.getKiller());
        if (head != null) {
            event.getDrops().add(head);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStackedMobDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living) || !SpawnerHeadConfig.handles(living.getType())) return;
        int amount = stackAmount(living);
        if (amount > 1 && event.getFinalDamage() >= living.getHealth()) {
            pendingStackKills.put(living.getUniqueId(), amount);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStackedMobKilled(EntityDamageEvent event) {
        Integer before = pendingStackKills.remove(event.getEntity().getUniqueId());
        if (before == null || !event.isCancelled() || !(event.getEntity() instanceof LivingEntity living)) return;
        // SpawnerMeta takes exactly one mob off the stack per virtual kill; anything
        // else means the blow was cancelled for another reason (e.g. protection).
        if (stackAmount(living) != before - 1) return;

        Player killer = playerKiller(event);
        if (killer == null) killer = living.getKiller();
        ItemStack head = rollHead(living.getType(), killer);
        if (head == null) return;
        // Match SpawnerMeta, which multiplies a stacked mob's drops by its looting upgrade.
        head.setAmount(Math.min(head.getMaxStackSize(), lootingMultiplier(living)));
        living.getWorld().dropItemNaturally(living.getLocation(), head);
    }

    private ItemStack rollHead(EntityType entityType, Player killer) {
        SpawnerHeadConfig.MobHead headData = SpawnerHeadConfig.get(entityType);
        if (headData == null || killer == null) return null;
        if (RANDOM.nextDouble() >= calculateDropChance(headData, killer)) return null;
        return createMobHead(entityType, headData);
    }

    private static int stackAmount(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (pdc.getOrDefault(SPAWNERMETA_STACK, PersistentDataType.BYTE, (byte) 0) <= 0) return 0;
        return Math.max(1, pdc.getOrDefault(SPAWNERMETA_STACK_AMOUNT, PersistentDataType.INTEGER, 1));
    }

    private static int lootingMultiplier(LivingEntity entity) {
        return Math.max(1, entity.getPersistentDataContainer().getOrDefault(SPAWNERMETA_LOOTING, PersistentDataType.INTEGER, 1));
    }

    // Same killer resolution SpawnerMeta uses for virtual kills.
    private static Player playerKiller(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player player) return player;
        return null;
    }

    private double calculateDropChance(SpawnerHeadConfig.MobHead headData, Player killer) {
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        int lootingLevel = weapon.getEnchantmentLevel(Enchantment.LOOTING);
        if (lootingLevel <= 0) return headData.chance();
        return headData.lootingChance() + (headData.lootingPerLevel() * (lootingLevel - 1));
    }

    private ItemStack createMobHead(EntityType entityType, SpawnerHeadConfig.MobHead headData) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return null;
        String hexColor = SpawnerCoreManager.getMobColor(entityType);
        String entityName = SpawnerCoreManager.formatEntityName(entityType);
        meta.setDisplayName(SpawnerCoreManager.hexColor(hexColor) + "§l" + entityName + " Head");
        List<String> lore = new ArrayList<>();
        lore.add("§7Used to craft " + entityName + " Spawners");
        meta.setLore(lore);
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            String textureUrl = decodeTextureUrl(headData.texture());
            if (textureUrl != null) {
                textures.setSkin(new URL(textureUrl));
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
            }
        } catch (MalformedURLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set mob head texture for " + entityName + " Head", e);
        }
        setNoteBlockSound(meta, headData.sound());
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

    public static boolean handlesMobType(EntityType entityType) {
        return SpawnerHeadConfig.handles(entityType);
    }

}
