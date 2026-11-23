package codes.castled.spawnercraft;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.persistence.PersistentDataType;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class MobHeadDrops implements Listener {
    private static final NamespacedKey STACK = new NamespacedKey("spawnermeta", "managed_spawned_stack");
    private static final NamespacedKey STACK_AMOUNT = new NamespacedKey("spawnermeta", "managed_spawned_stack_amount");
    private static final NamespacedKey SPAWNED = new NamespacedKey("spawnermeta", "spawned");

    private final Heads heads;
    private final Supplier<ConfigurationSection> config;
    private final ThreadLocal<Map<EntityDamageEvent, Integer>> potentialStackKills = ThreadLocal.withInitial(IdentityHashMap::new);

    MobHeadDrops(Heads heads, Supplier<ConfigurationSection> config) {
        this.heads = heads;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        var mob = event.getEntity();
        var drops = heads.roll(mob, mob.getKiller(), config.get());
        // SpawnerMeta multiplies the normal loot list. Keep the configured head roll per mob.
        if (mob.getPersistentDataContainer().getOrDefault(SPAWNED, PersistentDataType.BYTE, (byte) 0) > 0) {
            drops.forEach(drop -> mob.getWorld().dropItemNaturally(mob.getLocation(), drop));
        } else {
            event.getDrops().addAll(drops);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void beforeStackKill(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || event.getFinalDamage() < mob.getHealth()) return;
        int before = stackAmount(mob);
        if (before > 1) potentialStackKills.get().put(event, before);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void afterStackKill(EntityDamageEvent event) {
        Integer before = potentialStackKills.get().remove(event);
        if (before == null || !event.isCancelled() || !(event.getEntity() instanceof LivingEntity mob)
                || stackAmount(mob) != before - 1) return;
        // A virtual kill cancels damage, decrements the existing stack, and emits no death event.
        Player killer = killer(event);
        heads.roll(mob, killer == null ? mob.getKiller() : killer, config.get()).forEach(
                drop -> mob.getWorld().dropItemNaturally(mob.getLocation(), drop));
    }

    private static int stackAmount(LivingEntity mob) {
        var data = mob.getPersistentDataContainer();
        if (data.getOrDefault(STACK, PersistentDataType.BYTE, (byte) 0) <= 0) return 0;
        return data.getOrDefault(STACK_AMOUNT, PersistentDataType.INTEGER, 1);
    }

    private static Player killer(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent attack)) return null;
        if (attack.getDamager() instanceof Player player) return player;
        if (attack.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (attack.getDamager() instanceof Tameable tameable && tameable.getOwner() instanceof Player player) return player;
        return null;
    }
}
