package codes.castled.allium.spawnercraft;

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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final NamespacedKey SPAWNERMETA_SPAWNED = new NamespacedKey("spawnermeta", "spawned");

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
        if (head == null) return;
        if (isSpawnerMetaSpawned(entity)) {
            // SpawnerMeta multiplies every drop left in the list by the spawner's
            // looting upgrade. Drop the head directly so it stays a single head.
            entity.getWorld().dropItemNaturally(entity.getLocation(), head);
        } else {
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
        // Always a single head per kill: the spawner's looting upgrade scales a
        // stacked mob's normal loot, but deliberately does not scale heads.
        living.getWorld().dropItemNaturally(living.getLocation(), head);
    }

    private ItemStack rollHead(EntityType entityType, Player killer) {
        SpawnerHeadConfig.MobHead headData = SpawnerHeadConfig.get(entityType);
        if (headData == null || killer == null) return null;
        if (RANDOM.nextDouble() >= calculateDropChance(headData, killer)) return null;
        // Same builder /spawnerhead uses, so dropped and given heads stack together.
        return MobHeadFactory.create(plugin, entityType, headData);
    }

    private static int stackAmount(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (pdc.getOrDefault(SPAWNERMETA_STACK, PersistentDataType.BYTE, (byte) 0) <= 0) return 0;
        return Math.max(1, pdc.getOrDefault(SPAWNERMETA_STACK_AMOUNT, PersistentDataType.INTEGER, 1));
    }

    private static boolean isSpawnerMetaSpawned(LivingEntity entity) {
        return entity.getPersistentDataContainer()
                .getOrDefault(SPAWNERMETA_SPAWNED, PersistentDataType.BYTE, (byte) 0) > 0;
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

    public static boolean handlesMobType(EntityType entityType) {
        return SpawnerHeadConfig.handles(entityType);
    }

}
